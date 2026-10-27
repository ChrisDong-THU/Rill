package app.rill.core;

import static app.rill.core.RelayHttp.error;
import static app.rill.core.RelayHttp.headers;
import static app.rill.core.RelayHttp.parse;
import static app.rill.core.RelayHttp.send;

import app.rill.core.RelayHttp.RangeException;
import app.rill.core.RelayHttp.Request;
import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Raw HTTP range relay. No decoding, re-encoding, TLS interception or account API. */
public final class RelayCore implements Closeable {
  public interface Opener {
    HttpURLConnection open(URL url) throws IOException;
  }

  public interface Events {
    void event(String message);
  }

  public static final String HOST = "lgf.audio.tidal.com";
  public static final int PORT = 18889, BLOCK = 256 * 1024, WINDOW = 12, MAX_BLOCKS = 48;
  private final Opener opener;
  private final Events events;
  private final ThreadPoolExecutor fetchers =
      new ThreadPoolExecutor(8, 16, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
  private final Object connectionGate = new Object();
  private int reservedConnections;
  private volatile int connectionLimit = 8;
  private volatile Targets targets = Targets.defaults();

  public void setTargets(Targets value) {
    if (value == null) throw new IllegalArgumentException();
    targets = value;
  }

  public Targets getTargets() {
    return targets;
  }

  private final ThreadPoolExecutor clients =
      new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
  private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
  private final Set<HttpURLConnection> upstream =
      Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());
  private final Set<Socket> sockets =
      Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
  private final Map<Socket, Long> activity = new ConcurrentHashMap<Socket, Long>();
  private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<String, Job>(4, .75f, true);
  // Guarded by jobs; reserve capacity before doing I/O, coalescing only identical URLs.
  private final Set<String> creating = new HashSet<>();
  public final AtomicLong downloaded = new AtomicLong(),
      served = new AtomicLong(),
      requests = new AtomicLong(),
      retries = new AtomicLong(),
      errors = new AtomicLong();
  public final AtomicInteger active = new AtomicInteger(), connections = new AtomicInteger();
  public volatile String state = "等待 BubbleUPnP 音频请求", format = "尚未收到音频", lastError = "";
  public volatile boolean running;
  private ServerSocket server;
  private Thread listener;

  int getListeningPort() {
    return server == null ? -1 : server.getLocalPort();
  }

  public boolean isListening() {
    return running
        && server != null
        && !server.isClosed()
        && listener != null
        && listener.isAlive();
  }

  public RelayCore(Opener opener, Events events) {
    this.opener = opener;
    this.events = events;
  }

  public RelayCore(Opener opener, Events events, int limit) {
    this(opener, events);
    setConnectionLimit(limit);
  }

  public int getConnectionLimit() {
    return connectionLimit;
  }

  public void setConnectionLimit(int limit) {
    if (limit < 1 || limit > 16) throw new IllegalArgumentException("连接上限必须在 1–16 之间");
    synchronized (connectionGate) {
      connectionLimit = limit;
      connectionGate.notifyAll();
    }
    fetchers.setCorePoolSize(limit);
  }

  private void reserveConnection() throws IOException {
    synchronized (connectionGate) {
      while (running && reservedConnections >= connectionLimit)
        try {
          connectionGate.wait(1000);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("取流已取消");
        }
      if (!running) throw new IOException("服务已停止");
      reservedConnections++;
    }
  }

  private void releaseConnection() {
    synchronized (connectionGate) {
      reservedConnections--;
      connectionGate.notifyAll();
    }
  }

  private int window() {
    return Math.min(32, Math.max(WINDOW, connectionLimit * 2));
  }

  private static long now() {
    return System.nanoTime() / 1000000;
  }

  private void log(String s) {
    events.event(s);
  }

  public void start(int port) throws IOException {
    server = new ServerSocket();
    server.setReuseAddress(true);
    server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 8);
    running = true;
    listener =
        new Thread(
            new Runnable() {
              public void run() {
                while (running)
                  try {
                    final Socket s = server.accept();
                    s.setSoTimeout(20000);
                    s.setTcpNoDelay(true);
                    sockets.add(s);
                    activity.put(s, now());
                    try {
                      clients.execute(
                          new Runnable() {
                            public void run() {
                              handle(s);
                            }
                          });
                    } catch (RejectedExecutionException e) {
                      quiet(s);
                      sockets.remove(s);
                      activity.remove(s);
                    }
                  } catch (IOException e) {
                    if (running) {
                      running = false;
                      log("监听失败，等待服务恢复");
                    }
                    break;
                  }
              }
            },
            "Rill-listener");
    listener.setDaemon(true);
    listener.start();
    timer.scheduleAtFixedRate(
        new Runnable() {
          public void run() {
            long t = now();
            for (Map.Entry<Socket, Long> e : activity.entrySet())
              if (t - e.getValue() > 60000) quiet(e.getKey());
            synchronized (jobs) {
              Iterator<Job> it = jobs.values().iterator();
              while (it.hasNext()) {
                Job j = it.next();
                if (j.idleFor(t) > 45000) {
                  j.close();
                  it.remove();
                }
              }
            }
          }
        },
        5,
        5,
        TimeUnit.SECONDS);
    log("本地服务已启动 · 127.0.0.1:" + port);
  }

  public URL allowed(String target, String host) throws IOException {
    URL u = new URL(target.startsWith("/") ? "http://" + host + target : target);
    if (!targets.allows(u)) throw new IOException("请求不在已设置的 HTTP 加速链接中");
    return u;
  }

  private void handle(Socket socket) {
    boolean sent = false;
    Job job = null;
    Reader reader = null;
    OutputStream out = null;
    try {
      InputStream in = new BufferedInputStream(socket.getInputStream(), 8192);
      out = socket.getOutputStream();
      Request r = parse(in);
      if (r == null) return;
      if (r.method.equals("CONNECT")) {
        if (!targets.tunnel(r.target)) {
          error(out, 501, "仅支持已设置的 HTTP 取流，不支持 HTTPS 隧道");
          return;
        }
        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes("US-ASCII"));
        out.flush();
        r = parse(in);
        if (r == null) return;
      }
      if (r.target.equals("/health")
          && "GET".equals(r.method)
          && r.headers
              .getOrDefault("host", "")
              .matches("(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?")) {
        error(out, 200, "Rill ready");
        return;
      }
      if (!r.method.equals("GET") && !r.method.equals("HEAD")) {
        error(out, 405, "仅支持 GET / HEAD");
        return;
      }
      if (r.headers.containsKey("transfer-encoding")
          || !r.headers.getOrDefault("content-length", "0").equals("0")) {
        error(out, 400, "不支持请求正文");
        return;
      }
      URL url = allowed(r.target, r.headers.getOrDefault("host", HOST));
      requests.incrementAndGet();
      activity.put(socket, now());
      job = acquire(url);
      format = job.format;
      String requestedRange = r.headers.get("range");
      // Evaluate conditions before parsing Range: a stale validator requires the full body.
      String validator = r.headers.get("if-range");
      if (!r.method.equals("GET")
          || validator != null
              && (validator.startsWith("W/")
                  || !(validator.equals(job.etag) || validator.equals(job.modified)))) {
        requestedRange = null;
      }
      long[] range = range(requestedRange, job.size);
      long start = range[0], end = range[1];
      boolean partial = requestedRange != null;
      Map<String, String> h = headers();
      h.put("Content-Type", job.contentType);
      h.put("Accept-Ranges", "bytes");
      h.put("Content-Length", String.valueOf(end - start + 1));
      h.put("Cache-Control", "private, no-store");
      if (job.etag != null) h.put("ETag", job.etag);
      if (job.modified != null) h.put("Last-Modified", job.modified);
      if (partial) h.put("Content-Range", "bytes " + start + "-" + end + "/" + job.size);
      if (r.method.equals("HEAD")) {
        send(out, partial ? 206 : 200, partial ? "Partial Content" : "OK", h);
        sent = true;
        return;
      }
      reader = job.reader(start, end);
      active.incrementAndGet();
      state = "正在缓冲 · " + job.id;
      log("播放请求 " + job.id + " · 起点 " + start + " · " + job.format);
      // Do not commit successful response headers until upstream supplied validated bytes.
      reader.prefill();
      state = "正在加速 · " + job.id;
      byte[] buffer = new byte[16384];
      int n = reader.read(buffer);
      send(out, partial ? 206 : 200, partial ? "Partial Content" : "OK", h);
      sent = true;
      while (n != -1 && running) {
        activity.put(socket, now());
        out.write(buffer, 0, n);
        served.addAndGet(n);
        activity.put(socket, now());
        n = reader.read(buffer);
      }
      out.flush();
    } catch (RangeException e) {
      try {
        if (!sent && out != null) {
          Map<String, String> h = headers();
          h.put("Content-Range", "bytes */" + e.size);
          h.put("Content-Length", "0");
          send(out, 416, "Range Not Satisfiable", h);
        }
      } catch (IOException ignored) {
      }
    } catch (Exception e) {
      if (running) {
        String msg = safe(e);
        if (!(e instanceof SocketException)) {
          errors.incrementAndGet();
          lastError = msg;
          log("请求结束：" + msg);
        }
        try {
          if (!sent && out != null) error(out, 502, msg);
        } catch (IOException ignored) {
        }
      }
    } finally {
      if (reader != null) {
        reader.close();
        active.decrementAndGet();
      }
      if (job != null) release(job);
      quiet(socket);
      sockets.remove(socket);
      activity.remove(socket);
      if (active.get() == 0 && running) state = "等待播放 / 已缓存片段可复用";
    }
  }

  /** Parses one byte range; multi-range responses are intentionally unsupported. */
  public static long[] range(String value, long size) throws IOException {
    return RelayHttp.range(value, size);
  }

  private Job retainJob(String key) throws IOException {
    synchronized (jobs) {
      if (!running) throw new IOException("服务已停止");
      Job job = jobs.get(key);
      if (job == null) return null;
      if (job.closed || job.failure != null) {
        job.close();
        jobs.remove(key);
        return null;
      }
      job.references++;
      return job;
    }
  }

  private Job acquire(URL url) throws IOException {
    String key = url.toString();
    synchronized (jobs) {
      while (true) {
        Job cached = retainJob(key);
        if (cached != null) return cached;
        if (creating.contains(key)) {
          try {
            jobs.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("取流已取消", e);
          }
          continue;
        }
        if (jobs.size() + creating.size() >= 2) {
          Iterator<Job> it = jobs.values().iterator();
          while (it.hasNext()) {
            Job old = it.next();
            if (old.references == 0) {
              old.close();
              it.remove();
              break;
            }
          }
        }
        if (jobs.size() + creating.size() >= 2) throw new IOException("同时播放的音频过多，请停止旧播放后重试");
        creating.add(key);
        break;
      }
    }
    boolean reserved = true;
    try {
      Job created = createJob(url);
      synchronized (jobs) {
        if (!running) {
          created.close();
          throw new IOException("服务已停止");
        }
        created.references++;
        creating.remove(key);
        reserved = false;
        jobs.put(key, created);
        return created;
      }
    } finally {
      synchronized (jobs) {
        if (reserved) creating.remove(key);
        jobs.notifyAll();
      }
    }
  }

  public int getPendingPreflights() {
    synchronized (jobs) {
      return creating.size();
    }
  }

  public int getOpenClientCount() {
    return sockets.size();
  }

  private Job createJob(URL url) throws IOException {
    for (int attempt = 1; ; attempt++) {
      if (!running || Thread.currentThread().isInterrupted()) throw new IOException("取流已取消");
      try {
        return new Job(url);
      } catch (Fatal e) {
        throw e;
      } catch (IOException e) {
        if (attempt >= 3 || !running) throw e;
        retries.incrementAndGet();
        try {
          Thread.sleep(250L * attempt);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("取流已取消", interrupted);
        }
      }
    }
  }

  private void release(Job j) {
    synchronized (jobs) {
      j.references--;
      j.lastUsed = now();
    }
  }

  private HttpURLConnection open(
      URL url, long first, long last, long size, String tag, String modified) throws IOException {
    reserveConnection();
    HttpURLConnection c;
    try {
      c = opener.open(url);
    } catch (IOException e) {
      releaseConnection();
      throw e;
    } catch (RuntimeException e) {
      releaseConnection();
      throw e;
    }
    boolean accepted;
    synchronized (connectionGate) {
      accepted = running;
      if (accepted) {
        upstream.add(c);
        connections.incrementAndGet();
      }
    }
    if (!accepted) {
      releaseConnection();
      c.disconnect();
      throw new IOException("服务已停止");
    }
    try {
      c.setConnectTimeout(8000);
      c.setReadTimeout(10000);
      c.setInstanceFollowRedirects(false);
      c.setUseCaches(false);
      c.setRequestProperty("Range", "bytes=" + first + "-" + last);
      c.setRequestProperty("Accept-Encoding", "identity");
      c.setRequestProperty("User-Agent", "stagefright/1.2 (Linux;Android 16)");
      if (tag != null && !tag.startsWith("W/")) c.setRequestProperty("If-Range", tag);
      else if (modified != null) c.setRequestProperty("If-Range", modified);
      int status = c.getResponseCode();
      if (status != 206) throw new Fatal("上游 HTTP " + status + "，分段未获授权或未被接受");
      Matcher m =
          Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)")
              .matcher(String.valueOf(c.getHeaderField("Content-Range")));
      if (!m.matches()
          || Long.parseLong(m.group(1)) != first
          || Long.parseLong(m.group(2))
              != (size == 0 ? Math.min(last, Long.parseLong(m.group(3)) - 1) : last)
          || (size > 0 && Long.parseLong(m.group(3)) != size))
        throw new Fatal("上游 Content-Range 不一致，已拒绝转发");
      if (!"identity".equalsIgnoreCase(c.getHeaderField("Content-Encoding"))
          && c.getHeaderField("Content-Encoding") != null) throw new Fatal("上游编码不符合原样转发要求");
      String length = c.getHeaderField("Content-Length");
      if (length != null
          && Long.parseLong(length)
              != (size == 0 ? Math.min(last, Long.parseLong(m.group(3)) - 1) : last) - first + 1)
        throw new Fatal("上游 Content-Length 不一致");
      if (tag != null && !tag.equals(c.getHeaderField("ETag")))
        throw new Fatal("音频 ETag 已改变，已停止拼接");
      if (tag == null && modified != null && !modified.equals(c.getHeaderField("Last-Modified")))
        throw new Fatal("音频版本已改变，已停止拼接");
      return c;
    } catch (IOException e) {
      disconnect(c);
      throw e;
    } catch (RuntimeException e) {
      disconnect(c);
      throw new Fatal("上游响应头无效");
    }
  }

  private void disconnect(HttpURLConnection c) {
    if (c != null) {
      boolean removed = upstream.remove(c);
      try {
        c.disconnect();
      } finally {
        if (removed) {
          connections.decrementAndGet();
          releaseConnection();
        }
      }
    }
  }

  static class Fatal extends IOException {
    Fatal(String s) {
      super(s);
    }
  }

  static class Block {
    byte[] data;
    int valid;
    boolean busy, failed;

    Block(int n) {
      data = new byte[n];
    }
  }

  class Job implements Closeable {
    final URL url;
    final String id, etag, modified, format, contentType;
    final long size;
    final LinkedHashMap<Integer, Block> blocks = new LinkedHashMap<Integer, Block>(64, .75f, true);
    final List<Reader> readers = new ArrayList<Reader>();
    volatile boolean closed;
    volatile String failure;
    int references;
    long lastUsed = now();

    Job(URL u) throws IOException {
      url = u;
      id = hash(u.toString().getBytes("UTF-8")).substring(0, 8);
      HttpURLConnection c = null;
      try {
        c = open(url, 0, 65535, 0, null, null);
        size = Long.parseLong(c.getHeaderField("Content-Range").split("/")[1]);
        if (size < 1 || size > 2147483647L) throw new Fatal("暂不支持此音频大小");
        etag = c.getHeaderField("ETag");
        modified = c.getHeaderField("Last-Modified");
        byte[] prefix = new byte[(int) Math.min(size, 65536)];
        InputStream in = c.getInputStream();
        int off = 0, n;
        while (off < prefix.length && (n = in.read(prefix, off, prefix.length - off)) > 0) {
          off += n;
          downloaded.addAndGet(n);
        }
        if (off != prefix.length) throw new IOException("音频预检未完整返回");
        boolean flac =
            prefix.length >= 26
                && prefix[0] == 'f'
                && prefix[1] == 'L'
                && prefix[2] == 'a'
                && prefix[3] == 'C'
                && (prefix[4] & 127) == 0;
        String type = c.getHeaderField("Content-Type");
        contentType =
            flac
                ? "audio/flac"
                : type != null && type.matches("[A-Za-z0-9!#$&^_.+/-]+(?:;[ -~]*)?")
                    ? type
                    : "application/octet-stream";
        if (flac) {
          long packed = 0;
          for (int k = 18; k < 26; k++) packed = (packed << 8) | (prefix[k] & 255L);
          int rate = (int) (packed >>> 44), bits = (int) ((packed >>> 36) & 31) + 1;
          format = bits + "-bit / " + String.format(Locale.ROOT, "%.1f", rate / 1000.0) + " kHz";
        } else format = contentType.split(";")[0];
        Block b = new Block((int) Math.min(size, BLOCK));
        System.arraycopy(prefix, 0, b.data, 0, prefix.length);
        b.valid = prefix.length;
        blocks.put(0, b);
        log("识别音频 " + id + " · " + format + " · 原样取流");
      } finally {
        disconnect(c);
      }
    }

    synchronized long idleFor(long t) {
      return references == 0 && readers.isEmpty() ? t - lastUsed : 0;
    }

    synchronized Reader reader(long start, long end) {
      Reader r = new Reader(this, start, end);
      readers.add(r);
      schedule();
      return r;
    }

    synchronized boolean needed(int index) {
      for (Reader r : readers)
        if (!r.closed
            && index >= r.position / BLOCK
            && index <= Math.min(r.end / BLOCK, r.position / BLOCK + window() - 1)) return true;
      return false;
    }

    synchronized void trim() {
      Iterator<Map.Entry<Integer, Block>> it = blocks.entrySet().iterator();
      while (blocks.size() >= MAX_BLOCKS && it.hasNext()) {
        Map.Entry<Integer, Block> e = it.next();
        if (!e.getValue().busy && !needed(e.getKey())) it.remove();
      }
    }

    synchronized void schedule() {
      if (closed || failure != null) return;
      for (int step = 0; step < window(); step++)
        for (Reader r : readers) {
          if (r.closed || r.position > r.end) continue;
          final int index = (int) (r.position / BLOCK) + step;
          if (index > r.end / BLOCK) continue;
          Block b = blocks.get(index);
          if (b == null) {
            trim();
            if (blocks.size() >= MAX_BLOCKS) continue;
            b = new Block((int) Math.min(BLOCK, size - (long) index * BLOCK));
            blocks.put(index, b);
          }
          if (b.valid == b.data.length || b.busy || b.failed) continue;
          b.busy = true;
          final Block block = b;
          try {
            fetchers.execute(
                new Runnable() {
                  public void run() {
                    fetch(index, block);
                  }
                });
          } catch (RejectedExecutionException e) {
            b.busy = false;
          }
        }
    }

    private void fetch(int index, Block b) {
      HttpURLConnection c = null;
      try {
        for (int attempt = 0; attempt < 3 && !closed; attempt++) {
          synchronized (this) {
            if (!needed(index)) {
              return;
            }
            if (b.valid == b.data.length) return;
          }
          try {
            int offset;
            synchronized (this) {
              offset = b.valid;
            }
            long first = (long) index * BLOCK + offset,
                last = (long) index * BLOCK + b.data.length - 1;
            c = open(url, first, last, size, etag, modified);
            InputStream in = c.getInputStream();
            byte[] temp = new byte[16384];
            while (!closed) {
              int length;
              synchronized (this) {
                length = b.data.length - b.valid;
              }
              if (length == 0) break;
              int n = in.read(temp, 0, Math.min(temp.length, length));
              if (n < 0) throw new EOFException("early EOF");
              if (n == 0) continue;
              downloaded.addAndGet(n);
              synchronized (this) {
                if (closed) return;
                System.arraycopy(temp, 0, b.data, b.valid, n);
                b.valid += n;
                notifyAll();
              }
            }
            return;
          } catch (Fatal e) {
            synchronized (this) {
              failure = e.getMessage();
              closed = true;
              notifyAll();
            }
            log(failure);
            return;
          } catch (IOException e) {
            if (attempt == 2) {
              synchronized (this) {
                b.failed = true;
                failure = "音频片段重试 3 次失败，请检查 Wi-Fi 或重新播放";
                notifyAll();
              }
              return;
            }
            retries.incrementAndGet();
            try {
              Thread.sleep(250L * (attempt + 1));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              return;
            }
          } finally {
            disconnect(c);
            c = null;
          }
        }
      } finally {
        synchronized (this) {
          b.busy = false;
          notifyAll();
        }
      }
    }

    public synchronized void close() {
      closed = true;
      blocks.clear();
      notifyAll();
    }
  }

  class Reader implements Closeable {
    final Job job;
    long position, end;
    boolean closed;

    Reader(Job j, long s, long e) {
      job = j;
      position = s;
      end = e;
    }

    void prefill() throws IOException {
      synchronized (job) {
        long target = Math.min(512 * 1024, end - position + 1), until = now() + 40000;
        while (true) {
          if (!running || closed || job.closed || job.failure != null)
            throw new IOException(job.failure != null ? job.failure : "播放已取消");
          long p = position, available = 0;
          while (available < target) {
            Block b = job.blocks.get((int) (p / BLOCK));
            int offset = (int) (p % BLOCK);
            if (b == null || b.valid <= offset) break;
            int n = (int) Math.min(target - available, b.valid - offset);
            p += n;
            available += n;
            if (b.valid < b.data.length) break;
          }
          if (available >= target) return;
          job.schedule();
          long left = until - now();
          if (left <= 0) throw new IOException("启动缓冲超时");
          try {
            job.wait(Math.min(left, 1000));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("播放已取消");
          }
        }
      }
    }

    int read(byte[] out) throws IOException {
      synchronized (job) {
        long until = now() + 40000;
        while (true) {
          if (closed || !running) throw new IOException("播放已结束");
          if (job.failure != null) throw new IOException(job.failure);
          if (job.closed) throw new IOException("缓存会话已关闭");
          if (position > end) return -1;
          int index = (int) (position / BLOCK), offset = (int) (position % BLOCK);
          Block b = job.blocks.get(index);
          if (b != null && b.valid > offset) {
            int n = (int) Math.min(Math.min(out.length, b.valid - offset), end - position + 1);
            System.arraycopy(b.data, offset, out, 0, n);
            position += n;
            job.lastUsed = now();
            job.schedule();
            return n;
          }
          job.schedule();
          long left = until - now();
          if (left <= 0) throw new IOException("等待音频片段超时");
          try {
            job.wait(Math.min(left, 1000));
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("播放已取消");
          }
        }
      }
    }

    public void close() {
      synchronized (job) {
        closed = true;
        job.readers.remove(this);
        job.lastUsed = now();
        job.notifyAll();
      }
    }
  }

  public static String hash(byte[] b) throws IOException {
    try {
      StringBuilder s = new StringBuilder();
      for (byte v : MessageDigest.getInstance("SHA-256").digest(b))
        s.append(String.format(Locale.ROOT, "%02x", v & 255));
      return s.toString();
    } catch (Exception e) {
      throw new IOException("校验失败");
    }
  }

  public static String safe(Exception e) {
    String s = e.getMessage();
    if (s != null && !s.contains("http") && !s.contains("token") && !s.contains("/")) return s;
    return e.getClass().getSimpleName() + "，网络读取或客户端连接结束";
  }

  private static void quiet(Closeable c) {
    try {
      c.close();
    } catch (Exception ignored) {
    }
  }

  public void close() {
    running = false;
    synchronized (connectionGate) {
      connectionGate.notifyAll();
    }
    state = "已停止";
    if (server != null) quiet(server);
    for (Socket s : sockets) quiet(s);
    for (HttpURLConnection c : upstream) disconnect(c);
    synchronized (jobs) {
      for (Job j : jobs.values()) j.close();
      jobs.clear();
      jobs.notifyAll();
    }
    fetchers.shutdownNow();
    clients.shutdownNow();
    timer.shutdownNow();
    log("本地服务已停止");
  }
}
