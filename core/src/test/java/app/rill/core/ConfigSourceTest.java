package app.rill.core;

import static org.junit.Assert.*;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ConfigSourceTest {
  @Test
  public void removesBomAndClosesInput() throws Exception {
    final boolean[] closed = {false};
    ByteArrayInputStream input =
        new ByteArrayInputStream("\uFEFFrules: []".getBytes(StandardCharsets.UTF_8)) {
          @Override
          public void close() {
            closed[0] = true;
          }
        };
    assertEquals("rules: []", ConfigSource.read(input));
    assertTrue(closed[0]);
  }

  @Test
  public void rejectsOversizedInput() {
    assertThrows(
        IOException.class,
        () -> ConfigSource.read(new ByteArrayInputStream(new byte[ConfigAdapter.MAX_BYTES + 1])));
  }

  @Test
  public void validatesSubscriptionUrls() throws Exception {
    assertEquals("example.com", ConfigSource.validateUrl(" https://example.com/config ").getHost());
    for (String value :
        new String[] {
          "file:///secret",
          "https://user:pass@example.com",
          "not a URL",
          "http://example.com:0",
          "http://example.com:99999",
          "https://example.com/#fragment"
        }) {
      assertThrows(IOException.class, () -> ConfigSource.validateUrl(value));
    }
  }

  @Test
  public void cancelledFetchDoesNotAttemptNetworkIo() {
    Thread.currentThread().interrupt();
    try {
      IOException error =
          assertThrows(IOException.class, () -> ConfigSource.fetch("http://127.0.0.1:1/config"));
      assertTrue(error.getMessage().contains("已取消"));
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  public void followsRelativeRedirectsAndRejectsNonRedirectStatuses() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/start",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/config");
          exchange.sendResponseHeaders(302, -1);
          exchange.close();
        });
    server.createContext(
        "/config",
        exchange -> {
          byte[] content = "rules: []\n".getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(200, content.length);
          try (java.io.OutputStream out = exchange.getResponseBody()) {
            out.write(content);
          }
        });
    server.createContext(
        "/unchanged",
        exchange -> {
          exchange.sendResponseHeaders(304, -1);
          exchange.close();
        });
    server.createContext(
        "/loop",
        exchange -> {
          exchange.getResponseHeaders().set("Location", "/loop");
          exchange.sendResponseHeaders(307, -1);
          exchange.close();
        });
    server.start();
    try {
      String origin = "http://127.0.0.1:" + server.getAddress().getPort();
      assertEquals("rules: []\n", ConfigSource.fetch(origin + "/start"));
      assertTrue(
          assertThrows(IOException.class, () -> ConfigSource.fetch(origin + "/unchanged"))
              .getMessage()
              .contains("HTTP 304"));
      assertTrue(
          assertThrows(IOException.class, () -> ConfigSource.fetch(origin + "/loop"))
              .getMessage()
              .contains("跳转次数过多"));
    } finally {
      server.stop(0);
    }
  }
}
