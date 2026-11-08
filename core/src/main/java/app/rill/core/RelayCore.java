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
import java.util.Arrays;
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

    default void requestStarted() {}

    default void requestFinished() {}
  }

  public static final String HOST = "lgf.audio.tidal.com";
  public static final int PORT = 18889, BLOCK = 256 * 1024, WINDOW = 12, MAX_BLOCKS = 48;
  private static final int START_BUFFER = 512 * 1024;
  // An unaligned START_BUFFER spans at most three blocks.
  private static final int START_WINDOW = START_BUFFER / BLOCK + 1;
  private static final int RECENT_BLOCKS = 4;
  private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
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
  private final Map<Socket, Client> clientsBySocket = new ConcurrentHashMap<>();
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
    fetchers.allowCoreThreadTimeOut(true);
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
      fetchers.setCorePoolSize(limit);
      connectionGate.notifyAll();
    }
  }

  private void reserveConnection() throws IOException {
    synchronized (connectionGate) {
      while (running && reservedConnections >= connectionLimit)
        try {
          connectionGate.wait();
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
                    Socket s = server.accept();
                    Client client = new Client(s);
                    try {
                      s.setSoTimeout(20000);
                      s.setTcpNoDelay(true);
                      clientsBySocket.put(s, client);
                      if (!running) throw new SocketException();
                      clients.execute(client);
                    } catch (IOException | RejectedExecutionException e) {
                      quiet(s);
                      clientsBySocket.remove(s);
                    }
                  } catch (IOException e) {
                    if (running) {
                      running = false;
                      synchronized (connectionGate) {
                        connectionGate.notifyAll();
                      }
                      log("监听失败，等待服务恢复");
                    }
                    break;
                  }
              }
            },
            "Rill-listener");
    listener.setDaemon(true);
    listener.start();
    timer.scheduleWithFixedDelay(
        new Runnable() {
          public void run() {
            long t = now();
            for (Client client : clientsBySocket.values())
              if (t - client.lastActivity > 60000) quiet(client.socket);
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

  private final class Client implements Runnable {
    final Socket socket;
    volatile long lastActivity = now();

    Client(Socket socket) {
      this.socket = socket;
    }

    @Override
    public void run() {
      handle(this);
    }
  }

  private void handle(Client client) {
    Socket socket = client.socket;
    boolean sent = false, requestStarted = false;
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
      requestStarted = true;
      events.requestStarted();
      requests.incrementAndGet();
      client.lastActivity = now();
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
      byte[] buffer = new byte[(int) Math.min(64 * 1024, end - start + 1)];
      int n = reader.read(buffer, 0);
      send(out, partial ? 206 : 200, partial ? "Partial Content" : "OK", h);
      sent = true;
      while (n != -1 && running) {
        client.lastActivity = now();
        out.write(buffer, 0, n);
        served.addAndGet(n);
        client.lastActivity = now();
        n = reader.read(buffer, n);
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
      clientsBySocket.remove(socket);
      if (active.get() == 0 && running) state = "等待播放 / 已缓存片段可复用";
      if (requestStarted) events.requestFinished();
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
    return clientsBySocket.size();
  }

  public long getCachedBytes() {
    synchronized (jobs) {
      long bytes = 0;
      for (Job job : jobs.values()) bytes += job.cachedBytes();
      return bytes;
    }
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
    int status = -1;
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
      status = c.getResponseCode();
      if (status != 206) throw new Fatal("上游 HTTP " + status + "，分段未获授权或未被接受");
      Matcher m = CONTENT_RANGE.matcher(String.valueOf(c.getHeaderField("Content-Range")));
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
    } catch (IOException | RuntimeException e) {
      try {
        // Android's disconnect cancels the call, while the caller owns the response body.
        if (status >= 400) quiet(c.getErrorStream());
        else if (status >= 0) quiet(c.getInputStream());
      } catch (IOException | RuntimeException ignored) {
        // Preserve the original transport or validation failure.
      } finally {
        disconnect(c);
      }
      if (e instanceof IOException) throw (IOException) e;
      throw new Fatal("上游响应头无效");
    }
  }

  private void disconnect(HttpURLConnection c) {
    if (c != null && upstream.remove(c)) {
      try {
        c.disconnect();
      } finally {
        connections.decrementAndGet();
        releaseConnection();
      }
    }
  }

  static class Fatal extends IOException {
    Fatal(String s) {
      super(s);
    }
  }

  private static final class Block {
    final int length;
    byte[] data;
    int valid;
    boolean used;
    Runnable task;

    Block(int length) {
      this.length = length;
    }
  }

  class Job implements Closeable {
    final URL url;
    final String id, etag, modified, format, contentType;
    final long size;
    final LinkedHashMap<Integer, Block> blocks = new LinkedHashMap<>(16, .75f, true);
    final Map<Integer, Integer> wanted = new LinkedHashMap<>();
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
        int off = 0, n;
        try (InputStream in = c.getInputStream()) {
          while (off < prefix.length && (n = in.read(prefix, off, prefix.length - off)) > 0) {
            off += n;
            downloaded.addAndGet(n);
          }
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
        b.data = prefix;
        b.valid = prefix.length;
        b.used = true;
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
      updateWindow();
      return r;
    }

    synchronized long cachedBytes() {
      long bytes = 0;
      for (Block b : blocks.values()) if (b.data != null) bytes += b.data.length;
      return bytes;
    }

    private void trim() {
      int recent = 0;
      for (Map.Entry<Integer, Block> entry : blocks.entrySet())
        if (!wanted.containsKey(entry.getKey()) && entry.getValue().used) recent++;
      Iterator<Map.Entry<Integer, Block>> it = blocks.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<Integer, Block> entry = it.next();
        if (wanted.containsKey(entry.getKey())) continue;
        Block block = entry.getValue();
        if (block.task != null && fetchers.remove(block.task)) block.task = null;
        if (block.task == null && (!block.used || recent > RECENT_BLOCKS)) {
          if (block.used) recent--;
          it.remove();
        }
      }
    }

    // Advance prefetch only when a reader moves to a different block, starts or leaves.
    private void updateWindow() {
      if (closed || failure != null) return;
      wanted.clear();
      int windowSize = window();
      // Give every reader its nearest blocks before scheduling farther-ahead reads.
      for (int step = 0; step < windowSize; step++)
        for (Reader r : readers) {
          if (r.position > r.end || step >= r.lookahead(windowSize)) continue;
          int index = (int) (r.position / BLOCK) + step;
          long start = (long) index * BLOCK;
          if (start > r.end) continue;
          if (wanted.size() < MAX_BLOCKS - RECENT_BLOCKS || wanted.containsKey(index))
            wanted.merge(index, (int) Math.min(BLOCK, r.end - start + 1), Math::max);
        }
      trim();
      schedule();
    }

    private void schedule() {
      if (!running || closed || failure != null) return;
      for (Map.Entry<Integer, Integer> entry : wanted.entrySet()) {
        int index = entry.getKey();
        Block block = blocks.get(index);
        if (block == null) {
          if (blocks.size() >= MAX_BLOCKS) continue;
          block = new Block((int) Math.min(BLOCK, size - (long) index * BLOCK));
          blocks.put(index, block);
        }
        queueFetch(index, block, entry.getValue());
      }
    }

    private void queueFetch(int index, Block block, int end) {
      if (block.task != null || block.valid >= end) return;
      block.task = () -> fetch(index, block);
      try {
        fetchers.execute(block.task);
      } catch (RejectedExecutionException e) {
        block.task = null;
      }
    }

    private void fetch(int index, Block b) {
      try {
        for (int attempt = 0; attempt < 3; attempt++) {
          int offset, end;
          byte[] data;
          synchronized (this) {
            Integer required = wanted.get(index);
            if (!running || closed || failure != null || required == null || b.valid >= required)
              return;
            offset = b.valid;
            end = required;
            if (b.data == null) b.data = new byte[end];
            else if (b.data.length < end) b.data = Arrays.copyOf(b.data, end);
            data = b.data;
          }
          HttpURLConnection c = null;
          IOException error;
          try {
            long start = (long) index * BLOCK;
            c = open(url, start + offset, start + end - 1, size, etag, modified);
            try (InputStream in = c.getInputStream()) {
              while (offset < end) {
                int n = in.read(data, offset, Math.min(16384, end - offset));
                if (n < 0) throw new EOFException("early EOF");
                if (n == 0) throw new IOException("上游读取没有进展");
                offset += n;
                downloaded.addAndGet(n);
                synchronized (this) {
                  b.valid = offset;
                  Integer required = wanted.get(index);
                  if (!running || closed || failure != null || required == null) return;
                  for (Reader r : readers) {
                    if (r.waitingFor == index) {
                      notifyAll();
                      break;
                    }
                  }
                  if (offset >= required) return;
                }
              }
            }
            return;
          } catch (IOException e) {
            error = e;
          } catch (RuntimeException e) {
            error = new Fatal("上游读取失败：" + safe(e));
          } finally {
            disconnect(c);
          }
          synchronized (this) {
            Integer required = wanted.get(index);
            if (!running || closed || failure != null || required == null || b.valid >= required)
              return;
            if (error instanceof Fatal) {
              fail(error.getMessage());
            } else if (attempt == 2) {
              fail("音频片段重试 3 次失败，请检查 Wi-Fi 或重新播放");
            }
          }
          if (error instanceof Fatal) log(error.getMessage());
          if (failure != null) return;
          retries.incrementAndGet();
          try {
            Thread.sleep(250L * (attempt + 1));
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return;
          }
        }
      } finally {
        synchronized (this) {
          b.task = null;
          if (running && !closed && failure == null) {
            Integer end = wanted.get(index);
            if (end == null) {
              trim();
              schedule();
            } else {
              queueFetch(index, b, end);
            }
          }
        }
      }
    }

    private void fail(String message) {
      failure = message;
      close();
    }

    public synchronized void close() {
      closed = true;
      for (Block b : blocks.values()) if (b.task != null) fetchers.remove(b.task);
      blocks.clear();
      wanted.clear();
      notifyAll();
    }
  }

  class Reader implements Closeable {
    final Job job;
    final long start, end;
    long position;
    boolean closed;
    int waitingFor = -1;

    Reader(Job j, long s, long e) {
      job = j;
      start = position = s;
      end = e;
    }

    int lookahead(int limit) {
      int growth = (int) Math.min(4, (position - start) / BLOCK);
      return Math.min(limit, START_WINDOW << growth);
    }

    void prefill() throws IOException {
      synchronized (job) {
        long target = Math.min(START_BUFFER, end - position + 1), until = now() + 40000;
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
            if (b.valid < b.length) break;
          }
          if (available >= target) return;
          long left = until - now();
          if (left <= 0) throw new IOException("启动缓冲超时");
          awaitBlock((int) (p / BLOCK), left);
        }
      }
    }

    int read(byte[] out, int delivered) throws IOException {
      synchronized (job) {
        // The previous write has succeeded. A blocked or aborted write never grows the window.
        long previous = position;
        position += delivered;
        if (previous / BLOCK != position / BLOCK || previous <= end && position > end)
          job.updateWindow();
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
            b.used = true;
            return n;
          }
          long left = until - now();
          if (left <= 0) throw new IOException("等待音频片段超时");
          awaitBlock(index, left);
        }
      }
    }

    private void awaitBlock(int index, long timeout) throws IOException {
      waitingFor = index;
      try {
        job.wait(timeout);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IOException("播放已取消");
      } finally {
        waitingFor = -1;
      }
    }

    public void close() {
      synchronized (job) {
        closed = true;
        job.readers.remove(this);
        job.updateWindow();
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
    if (c == null) return;
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
    for (Socket s : clientsBySocket.keySet()) quiet(s);
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
