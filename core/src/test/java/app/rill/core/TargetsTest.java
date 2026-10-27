package app.rill.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.URL;
import org.junit.Test;

public class TargetsTest {
  @Test
  public void normalizesAndDeduplicatesWithoutKeepingSecrets() throws Exception {
    Targets targets =
        Targets.parse("HTTP://EXAMPLE.COM/music?token=secret\nexample.com\n127.0.0.2:8080/file");
    assertEquals("http://example.com\nhttp://127.0.0.2:8080", targets.text());
    assertTrue(targets.allows(new URL("http://example.com/another?token=new")));
    assertFalse(targets.allows(new URL("http://example.com:8080/another")));
    assertFalse(targets.allows(new URL("http://other.example/another")));
    assertEquals("IP-CIDR,127.0.0.2/32,Group,no-resolve", targets.origins.get(1).rule("Group"));
  }

  @Test
  public void rejectsUnsupportedOrInvalidOrigins() {
    for (String value :
        new String[] {
          "",
          "https://example.com",
          "http://user:pass@example.com",
          "localhost:18889",
          "127.0.0.1:18889",
          "256.0.0.1",
          "01.2.3.4",
          "a..b",
          "example.com:0"
        }) {
      assertThrows(value, IOException.class, () -> Targets.parse(value));
    }
  }

  @Test
  public void enforcesOriginCountAndTunnelAllowlist() throws Exception {
    StringBuilder input = new StringBuilder();
    for (int i = 0; i < 17; i++) input.append("host").append(i).append(".example\n");
    assertThrows(IOException.class, () -> Targets.parse(input.toString()));
    Targets targets = Targets.parse("example.com:8080");
    assertTrue(targets.tunnel("example.com:8080"));
    assertFalse(targets.tunnel("example.com:443"));
  }
}
