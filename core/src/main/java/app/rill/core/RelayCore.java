package app.rill.core;

import static app.rill.core.RelayHttp.error;
import static app.rill.core.RelayHttp.headers;
import static app.rill.core.RelayHttp.parse;
import static app.rill.core.RelayHttp.range;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
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

  public static final int PORT = 18889, BLOCK = 256 * 1024;
  public static final int DEFAULT_CONNECTION_LIMIT = 8, MAX_CONNECTION_LIMIT = 16;
  private static final int START_WINDOW = 2, READ_WINDOW = 4, MAX_BLOCKS = 48;
  private static final long BOOST_WAIT_MS = 150, BOOST_HOLD_MS = 1_000;
  private static final int RECENT_BLOCKS = 4;
  private static final int MAX_WANTED_BLOCKS = MAX_BLOCKS - MAX_CONNECTION_LIMIT - RECENT_BLOCKS;
  private static final int FIRST_PROBE_TIMEOUT_MS = 3500;
  private static final int CONNECT_TIMEOUT_MS = 8000, READ_TIMEOUT_MS = 10000;
  private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
  private final Opener opener;
  private final Events events;
  private final ThreadPoolExecutor fetchers =
      new ThreadPoolExecutor(
          DEFAULT_CONNECTION_LIMIT,
          MAX_CONNECTION_LIMIT,
          30,
          TimeUnit.SECONDS,
          new LinkedBlockingQueue<Runnable>());
  private final Object connectionGate = new Object();
  private int reservedConnections;
  private volatile int connectionLimit = DEFAULT_CONNECTION_LIMIT;
  private volatile Targets targets = Targets.defaults();

  public void setTargets(Targets value) {
    if (value == null) throw new IllegalArgumentException();
    targets = value;
  }

  public boolean handlesHttpPort(int port) {
    for (Targets.Origin origin : targets.origins) {
      if (origin.port == port) return true;
    }
    return false;
  }

  private final ThreadPoolExecutor clients =
      new ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, new SynchronousQueue<Runnable>());
  private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1);
  private final Object maintenance = new Object();
  private ScheduledFuture<?> cleanup;
  private long cleanupAt = Long.MAX_VALUE;
  private final ExecutorService cancellations = Executors.newSingleThreadExecutor();
  private final Set<HttpURLConnection> upstream =
      Collections.newSetFromMap(new ConcurrentHashMap<HttpURLConnection, Boolean>());
  private final Map<Socket, Client> clientsBySocket = new ConcurrentHashMap<>();
  private final LinkedHashMap<String, Job> jobs = new LinkedHashMap<String, Job>(4, .75f, true);
  // Guarded by jobs; reserve capacity before doing I/O, coalescing only identical URLs.
  private final Map<String, PendingProbe> creating = new HashMap<>();
  public final AtomicLong downloaded = new AtomicLong(),
      served = new AtomicLong(),
      requests = new AtomicLong(),
      upstreamRequests = new AtomicLong(),
      retries = new AtomicLong(),
      errors = new AtomicLong();
  public final AtomicInteger active = new AtomicInteger(), connections = new AtomicInteger();
  public final AtomicInteger peakConnections = new AtomicInteger();
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
    timer.setRemoveOnCancelPolicy(true);
  }

  public RelayCore(Opener opener, Events events, int limit) {
    this(opener, events);
    setConnectionLimit(limit);
  }

  public int getConnectionLimit() {
    return connectionLimit;
  }

  public void setConnectionLimit(int limit) {
    if (limit < 1 || limit > MAX_CONNECTION_LIMIT)
      throw new IllegalArgumentException("连接上限必须在 1–16 之间");
    synchronized (connectionGate) {
      if (connectionLimit == limit) return;
      connectionLimit = limit;
      fetchers.setCorePoolSize(limit);
      connectionGate.notifyAll();
    }
    synchronized (jobs) {
      for (Job job : jobs.values()) job.connectionLimitChanged();
    }
  }

  private void reserveConnection(BooleanSupplier cancelled) throws IOException {
    synchronized (connectionGate) {
      while (running && !cancelled.getAsBoolean() && reservedConnections >= connectionLimit)
        try {
          connectionGate.wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("取流已取消");
        }
      if (!running || cancelled.getAsBoolean()) throw new IOException("取流已取消");
      reservedConnections++;
    }
  }

  private void releaseConnection() {
    synchronized (connectionGate) {
      reservedConnections--;
      connectionGate.notifyAll();
    }
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
    start();
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
                      scheduleCleanup(60_000);
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
    log("本地服务已启动 · 127.0.0.1:" + port);
  }

  public void start() {
    running = true;
  }

  private void scheduleCleanup(long delay) {
    synchronized (maintenance) {
      if (!running) return;
      long deadline = now() + delay;
      if (cleanupAt <= deadline) return;
      if (cleanup != null) cleanup.cancel(false);
      cleanupAt = deadline;
      cleanup = timer.schedule(() -> cleanIdle(deadline), delay, TimeUnit.MILLISECONDS);
    }
  }

  private void cleanIdle(long deadline) {
    synchronized (maintenance) {
      if (cleanupAt != deadline) return;
      cleanup = null;
      cleanupAt = Long.MAX_VALUE;
    }
    long t = now();
    long next = Long.MAX_VALUE;
    for (Client client : clientsBySocket.values()) {
      long remaining = client.lastActivity + 60_000 - t;
      if (remaining <= 0) quiet(client.socket);
      else next = Math.min(next, remaining);
    }
    synchronized (jobs) {
      Iterator<Job> it = jobs.values().iterator();
      while (it.hasNext()) {
        Job job = it.next();
        if (job.references != 0) continue;
        long remaining = job.lastUsed + 45_000 - t;
        if (remaining <= 0) {
          job.close();
          it.remove();
        } else next = Math.min(next, remaining);
      }
    }
    if (next != Long.MAX_VALUE) scheduleCleanup(next);
  }

  URL allowed(String target, String host) throws IOException {
    URL u = new URL(target.startsWith("/") ? "http://" + host + target : target);
    if (!targets.allows(u)) throw new IOException("请求不在已设置的 HTTP 加速链接中");
    return u;
  }

  /** Serves a matched request directly on the tunnel socket. The caller can rewind on mismatch. */
  public boolean serveHttp(Socket socket, InputStream input, int port) throws IOException {
    Request request;
    URL url;
    try {
      request = parse(input);
      if (request == null || !(request.method.equals("GET") || request.method.equals("HEAD")))
        return false;
      url = allowed(request.target, request.headers.getOrDefault("host", ""));
      if ((url.getPort() == -1 ? 80 : url.getPort()) != port) return false;
    } catch (IOException e) {
      return false;
    }
    Client client = new Client(socket);
    socket.setSoTimeout(0);
    clientsBySocket.put(socket, client);
    if (!running) {
      clientsBySocket.remove(socket);
      throw new IOException("服务已停止");
    }
    scheduleCleanup(60_000);
    handle(client, request, url);
    return true;
  }

  private final class Client implements Runnable {
    final Socket socket;
    volatile long lastActivity = now();

    Client(Socket socket) {
      this.socket = socket;
    }

    @Override
    public void run() {
      handle(this, null, null);
    }
  }

  private void handle(Client client, Request request, URL matchedUrl) {
    Socket socket = client.socket;
    boolean sent = false, requestStarted = false, head = false;
    Job job = null;
    Reader reader = null;
    OutputStream out = null;
    try {
      out = socket.getOutputStream();
      Request r =
          request == null ? parse(new BufferedInputStream(socket.getInputStream())) : request;
      if (r == null) return;
      head = r.method.equals("HEAD");
      if (request == null
          && r.target.equals("/health")
          && "GET".equals(r.method)
          && r.headers
              .getOrDefault("host", "")
              .matches("(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?")) {
        error(out, 200, "Rill ready");
        return;
      }
      if (!r.method.equals("GET") && !head) {
        error(out, 405, "仅支持 GET / HEAD");
        return;
      }
      if (r.headers.containsKey("transfer-encoding")
          || !r.headers.getOrDefault("content-length", "0").equals("0")) {
        error(out, 400, "不支持请求正文", head);
        return;
      }
      URL url =
          matchedUrl == null ? allowed(r.target, r.headers.getOrDefault("host", "")) : matchedUrl;
      long requestedAt = now();
      requestStarted = true;
      events.requestStarted();
      requests.incrementAndGet();
      client.lastActivity = now();
      job = acquire(url);
      long metadataAt = now();
      format = job.format;
      String requestedRange = r.headers.get("range");
      // Evaluate conditions before parsing Range: a stale validator requires the full body.
      String validator = r.headers.get("if-range");
      if (head
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
      if (head) {
        send(out, 200, "OK", h);
        sent = true;
        return;
      }
      reader = job.reader(start, end);
      active.incrementAndGet();
      state = "正在缓冲 · " + job.id;
      log("播放请求 " + job.id + " · 起点 " + start + " · " + job.format);
      // Wait only for the first validated bytes of this range. The player buffers the rest.
      int n = reader.read(0);
      state = "正在加速 · " + job.id;
      send(out, partial ? 206 : 200, partial ? "Partial Content" : "OK", h);
      sent = true;
      boolean firstWrite = true;
      while (n != -1 && running) {
        client.lastActivity = now();
        out.write(reader.data, reader.offset, n);
        served.addAndGet(n);
        client.lastActivity = now();
        if (firstWrite) {
          firstWrite = false;
          if (job.firstDelivery.compareAndSet(false, true))
            log(
                "首批音频 "
                    + job.id
                    + " · 请求至交付 "
                    + (client.lastActivity - requestedAt)
                    + " ms · 获取元数据 "
                    + (metadataAt - requestedAt)
                    + " ms");
        }
        n = reader.read(n);
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
          if (!sent && out != null) error(out, 502, msg, head);
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

  // Called with jobs held.
  private Job retainJob(String key) throws IOException {
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

  private static final class PendingProbe {
    IOException failure;
  }

  private Job acquire(URL url) throws IOException {
    String key = url.toString();
    PendingProbe mine;
    synchronized (jobs) {
      while (true) {
        Job cached = retainJob(key);
        if (cached != null) return cached;
        PendingProbe pending = creating.get(key);
        if (pending != null) {
          try {
            while (running && creating.get(key) == pending) jobs.wait();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("取流已取消", e);
          }
          if (pending.failure != null) throw pending.failure;
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
        mine = new PendingProbe();
        creating.put(key, mine);
        break;
      }
    }
    IOException failure = null;
    try {
      Job created = createJob(url);
      synchronized (jobs) {
        if (!running) {
          created.close();
          throw new IOException("服务已停止");
        }
        created.references++;
        creating.remove(key);
        jobs.put(key, created);
        return created;
      }
    } catch (IOException e) {
      failure = e;
      throw e;
    } catch (RuntimeException e) {
      failure = new IOException("音频预检失败：" + e.getClass().getSimpleName(), e);
      throw failure;
    } finally {
      synchronized (jobs) {
        mine.failure = failure;
        creating.remove(key, mine);
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
      long started = now();
      try {
        // A stalled first probe otherwise delays the player's next-track startup by 10 seconds.
        return new Job(url, attempt == 1 ? FIRST_PROBE_TIMEOUT_MS : READ_TIMEOUT_MS);
      } catch (Fatal e) {
        throw e;
      } catch (IOException e) {
        if (attempt >= 3 || !running) throw e;
        retries.incrementAndGet();
        log(
            "音频预检重试 · 第 "
                + attempt
                + " 次耗时 "
                + (now() - started)
                + " ms · "
                + e.getClass().getSimpleName());
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
      if (j.references == 0) scheduleCleanup(45_000);
    }
  }

  private HttpURLConnection open(
      URL url,
      long first,
      long last,
      long size,
      String tag,
      String modified,
      int readTimeout,
      Job.RangeFetch range)
      throws IOException {
    reserveConnection(() -> range != null && range.cancelled);
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
        int count = connections.incrementAndGet();
        if (count > peakConnections.get()) peakConnections.set(count);
        if (range != null) range.connection = c;
      }
    }
    if (!accepted) {
      releaseConnection();
      c.disconnect();
      throw new IOException("服务已停止");
    }
    int status = -1;
    try {
      if (range != null && range.cancelled) throw new IOException("取流已取消");
      c.setConnectTimeout(Math.min(CONNECT_TIMEOUT_MS, readTimeout));
      c.setReadTimeout(readTimeout);
      c.setInstanceFollowRedirects(false);
      c.setUseCaches(false);
      c.setRequestProperty("Range", "bytes=" + first + "-" + last);
      c.setRequestProperty("Accept-Encoding", "identity");
      c.setRequestProperty("User-Agent", "stagefright/1.2 (Linux;Android 16)");
      if (tag != null && !tag.startsWith("W/")) c.setRequestProperty("If-Range", tag);
      else if (modified != null) c.setRequestProperty("If-Range", modified);
      upstreamRequests.incrementAndGet();
      status = c.getResponseCode();
      if ((status == 502 || status == 503 || status == 504)
          && c.getHeaderField("Retry-After") == null)
        throw new IOException("上游暂时不可用，HTTP " + status);
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
    finishConnection(c, true);
  }

  private void finishConnection(HttpURLConnection c, boolean cancel) {
    if (c != null && upstream.remove(c)) {
      try {
        if (cancel) c.disconnect();
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
    byte[] data;
    int valid;
    boolean used;
  }

  class Job implements Closeable {
    final URL url;
    final String id, etag, modified, format, contentType;
    final long size;
    final LinkedHashMap<Integer, Block> blocks = new LinkedHashMap<>(16, .75f, true);
    final Map<Integer, Integer> wanted = new LinkedHashMap<>();
    final List<Reader> readers = new ArrayList<Reader>();
    private final List<RangeFetch> ranges = new ArrayList<>();
    final AtomicBoolean firstDelivery = new AtomicBoolean();
    volatile boolean closed;
    volatile String failure;
    int references;
    long lastUsed = now();

    Job(URL u, int probeTimeout) throws IOException {
      url = u;
      id = hash(u.toString().getBytes("UTF-8")).substring(0, 8);
      HttpURLConnection c = null;
      boolean complete = false;
      try {
        c = open(url, 0, 65535, 0, null, null, probeTimeout, null);
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
        complete = true;
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
        Block b = new Block();
        b.data = prefix;
        b.valid = prefix.length;
        b.used = true;
        blocks.put(0, b);
        log("识别音频 " + id + " · " + format + " · 原样取流");
      } finally {
        finishConnection(c, !complete);
      }
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

    synchronized void connectionLimitChanged() {
      for (Reader reader : readers) {
        reader.parallelism = Math.min(reader.parallelism, connectionLimit);
        reader.nextBoost = 0;
      }
      updateWindow();
      notifyAll();
    }

    private boolean covered(int index) {
      for (RangeFetch range : ranges) if (range.covers(index)) return true;
      return false;
    }

    private void trim() {
      int recent = 0;
      for (Map.Entry<Integer, Block> entry : blocks.entrySet())
        if (!wanted.containsKey(entry.getKey()) && entry.getValue().used) recent++;
      Iterator<Map.Entry<Integer, Block>> it = blocks.entrySet().iterator();
      while (it.hasNext()) {
        Map.Entry<Integer, Block> entry = it.next();
        if (wanted.containsKey(entry.getKey()) || covered(entry.getKey())) continue;
        Block block = entry.getValue();
        if (!block.used || recent > RECENT_BLOCKS) {
          if (block.used) recent--;
          it.remove();
        }
      }
    }

    private void updateWindow() {
      if (closed) return;
      wanted.clear();
      // Reserve space for each reader's next block before queuing any read-ahead.
      for (int step = 0; step < MAX_WANTED_BLOCKS; step++) {
        for (Reader r : readers) {
          if (r.position > r.end || step >= r.lookahead()) continue;
          int index = (int) (r.position / BLOCK) + step;
          long start = (long) index * BLOCK;
          if (start > r.end) continue;
          if (wanted.size() < MAX_WANTED_BLOCKS || wanted.containsKey(index))
            wanted.merge(index, (int) Math.min(BLOCK, r.end - start + 1), Math::max);
        }
      }
      cancelObsoleteRanges();
      trim();
      schedule();
    }

    private boolean needsBytes(int index) {
      Integer end = wanted.get(index);
      Block block = blocks.get(index);
      return end != null && (block == null || block.valid < end);
    }

    private void schedule() {
      if (!running || closed) return;
      int parallelism = 1;
      for (Reader reader : readers) parallelism = Math.max(parallelism, reader.parallelism);
      parallelism = Math.min(parallelism, connectionLimit);
      while (ranges.size() < connectionLimit && scheduleNext(parallelism)) {
        // Fill the remaining slots from the bounded window.
      }
    }

    private boolean scheduleNext(int parallelism) {
      int index = -1;
      for (Reader reader : readers) {
        int waiting = reader.waitingFor;
        if (waiting < 0 || covered(waiting)) continue;
        Block block = blocks.get(waiting);
        if (block == null || block.valid <= reader.position % BLOCK) {
          index = waiting;
          break;
        }
      }
      if (index < 0 && ranges.size() < parallelism) {
        for (Integer wantedIndex : wanted.keySet()) {
          if (needsBytes(wantedIndex) && !covered(wantedIndex)) {
            index = wantedIndex;
            break;
          }
        }
      }
      if (index < 0) return false;

      Block firstBlock = blocks.get(index);
      long first = (long) index * BLOCK + (firstBlock == null ? 0 : firstBlock.valid);
      int tail = index;
      int maxBlocks = Math.max(1, Math.min(READ_WINDOW, (wanted.size() + 1) / parallelism - 1));
      if (parallelism == 1) maxBlocks = READ_WINDOW;
      while (wanted.get(tail) == BLOCK && tail - index + 1 < maxBlocks) {
        int next = tail + 1;
        Block nextBlock = blocks.get(next);
        if (!wanted.containsKey(next)
            || covered(next)
            || (nextBlock != null && nextBlock.valid > 0)) break;
        tail = next;
      }
      // Refill in batches. A single missing block ahead of all readers can wait until
      // more room opens, or until a reader reaches it and needs its bytes.
      if (tail == index && parallelism == 1) {
        boolean atReader = false;
        for (Reader reader : readers)
          if (reader.position <= reader.end && reader.position / BLOCK == index) {
            atReader = true;
            break;
          }
        if (!atReader) return false;
      }
      // 'end' is exclusive. The wire request may span up to four independent cache blocks.
      RangeFetch range = new RangeFetch(first, (long) tail * BLOCK + wanted.get(tail));
      ranges.add(range);
      try {
        fetchers.execute(range);
        return true;
      } catch (RejectedExecutionException e) {
        ranges.remove(range);
        return false;
      }
    }

    private void cancelObsoleteRanges() {
      Iterator<RangeFetch> it = ranges.iterator();
      while (it.hasNext()) {
        RangeFetch range = it.next();
        if (!range.cancelled && range.position < range.end && !range.isNeeded()) {
          range.cancel();
          if (fetchers.remove(range)) it.remove();
        }
      }
    }

    private final class RangeFetch implements Runnable {
      final long end;
      volatile long position;
      volatile boolean cancelled;
      volatile HttpURLConnection connection;

      RangeFetch(long first, long end) {
        position = first;
        this.end = end;
      }

      boolean covers(int index) {
        return index >= Math.min(position, end - 1) / BLOCK && index <= (end - 1) / BLOCK;
      }

      // Called with the job lock. Never perform a socket operation while holding it.
      boolean isNeeded() {
        for (int index = (int) (position / BLOCK); (long) index * BLOCK < end; index++) {
          if (needsBytes(index)) return true;
        }
        return false;
      }

      void cancel() {
        cancelled = true;
        synchronized (connectionGate) {
          connectionGate.notifyAll();
        }
        HttpURLConnection active = connection;
        if (active != null) {
          try {
            cancellations.execute(active::disconnect);
          } catch (RejectedExecutionException ignored) {
            // Core shutdown already closes all tracked upstream connections.
          }
        }
      }

      @Override
      public void run() {
        try {
          for (int attempt = 0; attempt < 3 && !cancelled && running; attempt++) {
            HttpURLConnection c = null;
            boolean complete = false;
            IOException error;
            try {
              c = open(url, position, end - 1, size, etag, modified, READ_TIMEOUT_MS, this);
              try (InputStream in = c.getInputStream()) {
                readRange(in);
                complete = position == end;
              }
              return;
            } catch (IOException e) {
              error = e;
            } catch (RuntimeException e) {
              error = new Fatal("上游读取失败：" + safe(e));
            } finally {
              connection = null;
              finishConnection(c, !complete && !cancelled);
            }
            synchronized (Job.this) {
              if (cancelled || closed || !running || !isNeeded()) return;
              if (error instanceof Fatal || attempt == 2) {
                fail(error instanceof Fatal ? error.getMessage() : "音频片段重试 3 次失败，请检查网络或重新播放");
                return;
              }
            }
            retries.incrementAndGet();
            try {
              Thread.sleep(250L * (attempt + 1));
            } catch (InterruptedException interrupted) {
              Thread.currentThread().interrupt();
              return;
            }
          }
        } finally {
          synchronized (Job.this) {
            ranges.remove(this);
            trim();
            schedule();
            Job.this.notifyAll();
          }
        }
      }

      private void readRange(InputStream in) throws IOException {
        while (position < end && !cancelled && running) {
          int index = (int) (position / BLOCK);
          int offset = (int) (position % BLOCK);
          int last = (int) Math.min(BLOCK, end - (long) index * BLOCK);
          Block block;
          byte[] data;
          synchronized (Job.this) {
            if (cancelled || closed || !wanted.containsKey(index)) return;
            block = blocks.get(index);
            if (block == null) {
              block = new Block();
              blocks.put(index, block);
            }
            if (block.data == null) block.data = new byte[last];
            else if (block.data.length < last) block.data = Arrays.copyOf(block.data, last);
            data = block.data;
          }
          while (offset < last && !cancelled && running) {
            int n = in.read(data, offset, Math.min(64 * 1024, last - offset));
            if (n < 0) throw new EOFException("early EOF");
            if (n == 0) throw new IOException("上游读取没有进展");
            downloaded.addAndGet(n);
            offset += n;
            synchronized (Job.this) {
              if (cancelled || closed) return;
              position += n;
              block.valid = offset;
              for (Reader reader : readers) {
                if (reader.waitingFor == index) {
                  Job.this.notifyAll();
                  break;
                }
              }
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
      wanted.clear();
      for (RangeFetch range : ranges) {
        if (!range.cancelled) range.cancel();
        fetchers.remove(range);
      }
      ranges.clear();
      blocks.clear();
      notifyAll();
    }
  }

  class Reader implements Closeable {
    final Job job;
    final long start, end;
    long position;
    boolean closed;
    int waitingFor = -1;
    int parallelism = 1;
    long nextBoost, lastBoost;
    byte[] data;
    int offset;

    Reader(Job j, long s, long e) {
      job = j;
      start = position = s;
      end = e;
    }

    int lookahead() {
      if (parallelism > 1) return Math.min(MAX_WANTED_BLOCKS, parallelism * 2 + 2);
      return position - start < BLOCK ? START_WINDOW : READ_WINDOW;
    }

    int read(int delivered) throws IOException {
      synchronized (job) {
        // The previous write has succeeded. A blocked or aborted write never grows the window.
        long previous = position;
        position += delivered;
        boolean moved = previous / BLOCK != position / BLOCK;
        if (moved && parallelism > 1 && now() - lastBoost >= BOOST_HOLD_MS) {
          int index = (int) (position / BLOCK);
          Block current = job.blocks.get(index), next = job.blocks.get(index + 1);
          if (current != null
              && current.valid == BLOCK
              && next != null
              && BLOCK - position % BLOCK + next.valid >= 2L * BLOCK) {
            parallelism /= 2;
            nextBoost = 0;
            lastBoost = now();
          }
        }
        if (moved
            || (previous - start < BLOCK && position - start >= BLOCK)
            || (previous <= end && position > end)) job.updateWindow();
        long until = now() + 40000;
        while (true) {
          if (closed || !running) throw new IOException("播放已结束");
          if (job.failure != null) throw new IOException(job.failure);
          if (job.closed) throw new IOException("缓存会话已关闭");
          if (position > end) return -1;
          int index = (int) (position / BLOCK);
          offset = (int) (position % BLOCK);
          Block b = job.blocks.get(index);
          if (b != null && b.valid > offset) {
            // The producer only appends beyond valid. The reader retains this array even if
            // the block is evicted or grown while the socket write is in progress.
            data = b.data;
            b.used = true;
            // A full block of available data gives the current range time to catch up.
            Block next = job.blocks.get(index + 1);
            if (b.valid == BLOCK && next != null && BLOCK - offset + next.valid >= BLOCK)
              nextBoost = 0;
            return (int) Math.min(Math.min(64 * 1024, b.valid - offset), end - position + 1);
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
        job.schedule();
        if (parallelism < connectionLimit) {
          long now = now();
          if (nextBoost == 0) nextBoost = now + BOOST_WAIT_MS;
          if (now >= nextBoost) {
            parallelism = Math.min(connectionLimit, parallelism * 2);
            lastBoost = now;
            nextBoost = now + BOOST_WAIT_MS;
            job.updateWindow();
          }
          timeout = Math.min(timeout, nextBoost - now);
        }
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

  private static String hash(byte[] b) throws IOException {
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
    cancellations.shutdown();
    clients.shutdownNow();
    synchronized (maintenance) {
      timer.shutdownNow();
    }
    log("本地服务已停止");
  }
}
