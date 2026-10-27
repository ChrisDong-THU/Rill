package app.rill.core;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class RelayCoreTest {
  @Test
  public void ignoresRangeForHeadAndStaleOrWeakIfRange() throws Exception {
    try (RelayCore relay = new RelayCore(RelayCoreTest::tinyTrack, message -> {})) {
      relay.setTargets(Targets.parse("example.com"));
      relay.start(0);
      String request = " http://example.com/audio HTTP/1.1\r\n";
      Response stale =
          request(
              relay.getListeningPort(),
              "GET" + request + "Range: bytes=999-\r\nIf-Range: \"old\"\r\n\r\n");
      assertTrue(stale.headers.startsWith("HTTP/1.1 200"));
      assertArrayEquals(new byte[] {1, 2, 3, 4}, stale.body);
      Response head =
          request(relay.getListeningPort(), "HEAD" + request + "Range: bytes=999-\r\n\r\n");
      assertTrue(head.headers.startsWith("HTTP/1.1 200"));
      assertTrue(head.headers.contains("Content-Length: 4"));
      assertFalse(head.headers.contains("Content-Range:"));
      assertEquals(0, head.body.length);
    }
    try (RelayCore relay = new RelayCore(url -> tinyTrack(url, "W/\"weak\""), message -> {})) {
      relay.setTargets(Targets.parse("example.com"));
      relay.start(0);
      Response weak =
          request(
              relay.getListeningPort(),
              "GET http://example.com/audio HTTP/1.1\r\n"
                  + "Range: bytes=1-2\r\n"
                  + "If-Range: W/\"weak\"\r\n\r\n");
      assertTrue(weak.headers.startsWith("HTTP/1.1 200"));
      assertArrayEquals(new byte[] {1, 2, 3, 4}, weak.body);
    }
  }

  @Test
  public void honorsMatchingStrongIfRange() throws Exception {
    try (RelayCore relay = new RelayCore(url -> tinyTrack(url, "\"current\""), message -> {})) {
      relay.setTargets(Targets.parse("example.com"));
      relay.start(0);
      Response result =
          request(
              relay.getListeningPort(),
              "GET http://example.com/audio HTTP/1.1\r\n"
                  + "Range: bytes=1-2\r\n"
                  + "If-Range: \"current\"\r\n\r\n");
      assertTrue(result.headers.startsWith("HTTP/1.1 206"));
      assertTrue(result.headers.contains("Content-Range: bytes 1-2/4"));
      assertArrayEquals(new byte[] {2, 3}, result.body);
    }
  }

  @Test
  public void stalledTrackDoesNotBlockDifferentTrack() throws Exception {
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    ExecutorService clients = Executors.newCachedThreadPool();
    try (RelayCore relay =
        new RelayCore(
            url -> {
              if (url.getPath().equals("/slow")) {
                entered.countDown();
                awaitPreflight(release);
              }
              return tinyTrack(url);
            },
            message -> {})) {
      relay.setTargets(Targets.parse("example.com"));
      relay.start(0);
      Future<Response> slow =
          clients.submit(
              () ->
                  request(
                      relay.getListeningPort(), "GET http://example.com/slow HTTP/1.1\r\n\r\n"));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Future<Response> fast =
          clients.submit(
              () ->
                  request(
                      relay.getListeningPort(), "GET http://example.com/fast HTTP/1.1\r\n\r\n"));
      try {
        assertArrayEquals(new byte[] {1, 2, 3, 4}, fast.get(2, TimeUnit.SECONDS).body);
      } finally {
        release.countDown();
      }
      assertArrayEquals(new byte[] {1, 2, 3, 4}, slow.get(2, TimeUnit.SECONDS).body);
    } finally {
      release.countDown();
      clients.shutdownNow();
    }
  }

  @Test
  public void coalescesSameTrackAndBoundsConcurrentPreflights() throws Exception {
    CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
    AtomicInteger opened = new AtomicInteger();
    ExecutorService clients = Executors.newCachedThreadPool();
    try (RelayCore relay =
        new RelayCore(
            url -> {
              opened.incrementAndGet();
              entered.countDown();
              awaitPreflight(release);
              return tinyTrack(url);
            },
            message -> {})) {
      relay.setTargets(Targets.parse("example.com"));
      relay.start(0);
      Future<Response> one =
          clients.submit(
              () ->
                  request(relay.getListeningPort(), "GET http://example.com/one HTTP/1.1\r\n\r\n"));
      Future<Response> two =
          clients.submit(
              () ->
                  request(relay.getListeningPort(), "GET http://example.com/two HTTP/1.1\r\n\r\n"));
      assertTrue(entered.await(2, TimeUnit.SECONDS));
      Future<Response> duplicate =
          clients.submit(
              () ->
                  request(relay.getListeningPort(), "GET http://example.com/one HTTP/1.1\r\n\r\n"));
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
      while (relay.requests.get() < 3 && System.nanoTime() < deadline) Thread.sleep(10);
      assertEquals(3, relay.requests.get());
      Response third =
          request(relay.getListeningPort(), "GET http://example.com/three HTTP/1.1\r\n\r\n");
      assertTrue(third.headers.startsWith("HTTP/1.1 502"));
      assertEquals(2, opened.get());
      assertEquals(2, relay.getPendingPreflights());
      release.countDown();
      for (Future<Response> response : Arrays.asList(one, two, duplicate))
        assertArrayEquals(new byte[] {1, 2, 3, 4}, response.get(2, TimeUnit.SECONDS).body);
      assertEquals(2, opened.get());
      assertEquals(0, relay.getPendingPreflights());
    } finally {
      release.countDown();
      clients.shutdownNow();
    }
  }

  private static void awaitPreflight(CountDownLatch release) throws IOException {
    try {
      if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test preflight timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException(e);
    }
  }

  private static HttpURLConnection tinyTrack(URL url) {
    return tinyTrack(url, null);
  }

  private static HttpURLConnection tinyTrack(URL url, String etag) {
    return new HttpURLConnection(url) {
      @Override
      public int getResponseCode() {
        return 206;
      }

      @Override
      public String getHeaderField(String name) {
        if (name.equals("Content-Range")) return "bytes 0-3/4";
        if (name.equals("Content-Length")) return "4";
        if (name.equals("ETag")) return etag;
        return null;
      }

      @Override
      public InputStream getInputStream() {
        return new ByteArrayInputStream(new byte[] {1, 2, 3, 4});
      }

      @Override
      public void disconnect() {}

      @Override
      public boolean usingProxy() {
        return false;
      }

      @Override
      public void connect() {}
    };
  }

  @Test
  public void retriesTransientTrackPreflightFailure() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    try (RelayCore relay = preflightFixture(attempts, false)) {
      relay.setTargets(Targets.parse("example.com"));
      relay.start(0);
      Response response =
          request(relay.getListeningPort(), "GET http://example.com/track HTTP/1.1\r\n\r\n");
      assertTrue(response.headers.startsWith("HTTP/1.1 200"));
      assertArrayEquals(new byte[] {1, 2, 3, 4}, response.body);
      assertEquals(2, attempts.get());
      assertEquals(1, relay.retries.get());
      assertEquals(0, relay.connections.get());
    }
  }

  @Test
  public void doesNotRetryRejectedTrackPreflight() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    try (RelayCore relay = preflightFixture(attempts, true)) {
      relay.setTargets(Targets.parse("example.com"));
      relay.start(0);
      Response response =
          request(relay.getListeningPort(), "GET http://example.com/track HTTP/1.1\r\n\r\n");
      assertTrue(response.headers.startsWith("HTTP/1.1 502"));
      assertEquals(1, attempts.get());
      assertEquals(0, relay.retries.get());
      assertEquals(0, relay.connections.get());
    }
  }

  private static RelayCore preflightFixture(AtomicInteger attempts, boolean rejected) {
    return new RelayCore(
        url ->
            new HttpURLConnection(url) {
              @Override
              public int getResponseCode() throws IOException {
                if (attempts.incrementAndGet() == 1) {
                  if (rejected) return 403;
                  throw new EOFException("Transient connection loss");
                }
                return 206;
              }

              @Override
              public String getHeaderField(String name) {
                if (name.equals("Content-Range")) return "bytes 0-3/4";
                if (name.equals("Content-Length")) return "4";
                return null;
              }

              @Override
              public InputStream getInputStream() {
                return new ByteArrayInputStream(new byte[] {1, 2, 3, 4});
              }

              @Override
              public void disconnect() {}

              @Override
              public boolean usingProxy() {
                return false;
              }

              @Override
              public void connect() {}
            },
        message -> {});
  }

  @Test
  public void shutdownDoesNotWaitForBlockedTrackPreflight() throws Exception {
    CountDownLatch preflight = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService tasks = Executors.newCachedThreadPool();
    RelayCore relay =
        new RelayCore(
            url ->
                new HttpURLConnection(url) {
                  @Override
                  public int getResponseCode() throws IOException {
                    preflight.countDown();
                    try {
                      if (!release.await(10, TimeUnit.SECONDS))
                        throw new IOException("Test timeout");
                    } catch (InterruptedException e) {
                      Thread.currentThread().interrupt();
                      throw new IOException(e);
                    }
                    throw new IOException("Upstream disconnected");
                  }

                  @Override
                  public void disconnect() {}

                  @Override
                  public boolean usingProxy() {
                    return false;
                  }

                  @Override
                  public void connect() {}
                },
            message -> {});
    try {
      relay.setTargets(Targets.parse("example.com"));
      relay.start(0);
      Future<?> request =
          tasks.submit(
              () -> {
                try (Socket socket = new Socket("127.0.0.1", relay.getListeningPort())) {
                  socket.setSoTimeout(5000);
                  socket
                      .getOutputStream()
                      .write(
                          "GET http://example.com/track HTTP/1.1\r\n\r\n"
                              .getBytes(StandardCharsets.ISO_8859_1));
                  // Shutdown may close the player connection before sending any headers.
                  while (socket.getInputStream().read() != -1) {}
                } catch (IOException ignored) {
                }
              });
      assertTrue(preflight.await(2, TimeUnit.SECONDS));
      Future<?> shutdown = tasks.submit(relay::close);
      shutdown.get(2, TimeUnit.SECONDS);
      assertFalse(relay.isListening());
      release.countDown();
      request.get(2, TimeUnit.SECONDS);
      assertEquals(0, relay.connections.get());
    } finally {
      release.countDown();
      relay.close();
      tasks.shutdownNow();
    }
  }

  @Test
  public void parsesRangesAndRejectsInvalidRanges() throws Exception {
    assertArrayEquals(new long[] {0, 99}, RelayCore.range(null, 100));
    assertArrayEquals(new long[] {10, 99}, RelayCore.range("bytes=10-", 100));
    assertArrayEquals(new long[] {90, 99}, RelayCore.range("bytes=-10", 100));
    assertArrayEquals(new long[] {0, 99}, RelayCore.range("bytes=0-999", 100));
    for (String value :
        new String[] {
          "bytes=100-", "bytes=9-2", "bytes=-0", "bytes=0-1,3-4", "bytes=999999999999999999999-"
        }) {
      assertThrows(IOException.class, () -> RelayCore.range(value, 100));
    }
  }

  @Test
  public void relaysBytesAcrossBlockBoundariesAndSupportsHead() throws Exception {
    byte[] content = new byte[700000];
    for (int i = 0; i < content.length; i++) content[i] = (byte) (i * 31);
    HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    ExecutorService workers = Executors.newCachedThreadPool();
    upstream.setExecutor(workers);
    upstream.createContext(
        "/audio",
        exchange -> {
          try {
            long[] range =
                RelayCore.range(exchange.getRequestHeaders().getFirst("Range"), content.length);
            int first = (int) range[0], last = (int) range[1];
            exchange
                .getResponseHeaders()
                .add("Content-Range", "bytes " + first + "-" + last + "/" + content.length);
            exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
            exchange.getResponseHeaders().add("ETag", "\"fixture\"");
            exchange.sendResponseHeaders(206, last - first + 1);
            exchange.getResponseBody().write(content, first, last - first + 1);
          } finally {
            exchange.close();
          }
        });
    upstream.start();
    try (RelayCore relay =
        new RelayCore(
            url -> (HttpURLConnection) url.openConnection(Proxy.NO_PROXY), message -> {})) {
      String origin = "http://127.0.0.1:" + upstream.getAddress().getPort();
      relay.setTargets(Targets.parse(origin));
      relay.start(0);
      int port = relay.getListeningPort();
      Response response =
          request(port, "GET " + origin + "/audio HTTP/1.1\r\nRange: bytes=200000-699999\r\n\r\n");
      assertTrue(response.headers.startsWith("HTTP/1.1 206"));
      assertArrayEquals(Arrays.copyOfRange(content, 200000, 700000), response.body);
      Response head = request(port, "HEAD " + origin + "/audio HTTP/1.1\r\n\r\n");
      assertTrue(head.headers.contains("Content-Length: 700000"));
      assertEquals(0, head.body.length);
      Response invalid =
          request(port, "GET " + origin + "/audio HTTP/1.1\r\nRange: bytes=700000-\r\n\r\n");
      assertTrue(invalid.headers.startsWith("HTTP/1.1 416"));
      Response health = request(port, "GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n");
      assertTrue(health.headers.startsWith("HTTP/1.1 200"));
      Response forbidden = request(port, "GET http://outside.example/audio HTTP/1.1\r\n\r\n");
      assertTrue(forbidden.headers.startsWith("HTTP/1.1 502"));
    } finally {
      upstream.stop(0);
      workers.shutdownNow();
    }
  }

  private static Response request(int port, String request) throws IOException {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10000);
      socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
      ByteArrayOutputStream response = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int length;
      while ((length = socket.getInputStream().read(buffer)) != -1)
        response.write(buffer, 0, length);
      byte[] bytes = response.toByteArray();
      String raw = new String(bytes, StandardCharsets.ISO_8859_1);
      int boundary = raw.indexOf("\r\n\r\n");
      assertTrue("HTTP response has a header terminator", boundary >= 0);
      return new Response(
          raw.substring(0, boundary), Arrays.copyOfRange(bytes, boundary + 4, bytes.length));
    }
  }

  private static final class Response {
    final String headers;
    final byte[] body;

    Response(String headers, byte[] body) {
      this.headers = headers;
      this.body = body;
    }
  }
}
