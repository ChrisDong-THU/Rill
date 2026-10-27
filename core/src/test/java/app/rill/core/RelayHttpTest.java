package app.rill.core;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class RelayHttpTest {
  @Test
  public void rejectsAmbiguousAndOversizedRequests() {
    for (String request :
        new String[] {
          "GET / HTTP/2.0\r\n\r\n",
          "GET / HTTP/1.1\r\nHost: one\r\nhost: two\r\n\r\n",
          "GET / HTTP/1.1\r\nBad Name: value\r\n\r\n",
          "GET / HTTP/1.1\r\nHost : value\r\n\r\n",
          "GET / HTTP/1.1\r\nHost: localhost\r\n",
          "GET /" + new String(new char[8192]).replace('\0', 'x') + " HTTP/1.1\r\n\r\n"
        }) {
      assertThrows(
          IOException.class,
          () ->
              RelayHttp.parse(
                  new ByteArrayInputStream(request.getBytes(StandardCharsets.ISO_8859_1))));
    }
  }
}
