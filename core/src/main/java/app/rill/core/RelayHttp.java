package app.rill.core;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded HTTP/1 request parsing, response serialization and single-range semantics. */
final class RelayHttp {
  private RelayHttp() {}

  static class Request {
    String method, target;
    Map<String, String> headers = new HashMap<String, String>();
  }

  static String line(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    int c;
    while ((c = in.read()) != -1) {
      if (c == 10) break;
      if (c != 13) b.write(c);
      if (b.size() > 8192) throw new IOException("请求头过长");
    }
    if (c < 0 && b.size() == 0) return null;
    return new String(b.toByteArray(), "ISO-8859-1");
  }

  static Request parse(InputStream in) throws IOException {
    String s = line(in);
    if (s == null) return null;
    String[] p = s.split(" ");
    if (p.length != 3 || !p[2].matches("HTTP/1\\.[01]")) throw new IOException("无效 HTTP 请求");
    Request r = new Request();
    r.method = p[0];
    r.target = p[1];
    int count = 0, size = s.length();
    while ((s = line(in)) != null && !s.isEmpty()) {
      size += s.length();
      if (++count > 64 || size > 32768) throw new IOException("请求头过长");
      int colon = s.indexOf(':');
      if (colon <= 0 || !s.substring(0, colon).matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"))
        throw new IOException("无效 HTTP 请求头");
      String key = s.substring(0, colon).toLowerCase(Locale.ROOT);
      if (r.headers.containsKey(key)) throw new IOException("重复 HTTP 请求头");
      r.headers.put(key, s.substring(colon + 1).trim());
    }
    if (s == null) throw new EOFException();
    return r;
  }

  static void send(OutputStream out, int code, String reason, Map<String, String> headers)
      throws IOException {
    StringBuilder b = new StringBuilder("HTTP/1.1 " + code + " " + reason + "\r\n");
    for (Map.Entry<String, String> h : headers.entrySet())
      b.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
    b.append("\r\n");
    out.write(b.toString().getBytes("ISO-8859-1"));
    out.flush();
  }

  static Map<String, String> headers() {
    Map<String, String> m = new LinkedHashMap<String, String>();
    m.put("Connection", "close");
    return m;
  }

  static void error(OutputStream out, int code, String message) throws IOException {
    byte[] b = message.getBytes("UTF-8");
    Map<String, String> h = headers();
    h.put("Content-Type", "text/plain; charset=utf-8");
    h.put("Content-Length", String.valueOf(b.length));
    send(out, code, "Rill", h);
    out.write(b);
    out.flush();
  }

  static class RangeException extends IOException {
    long size;

    RangeException(long s) {
      size = s;
    }
  }

  public static long[] range(String value, long size) throws IOException {
    if (value == null) return new long[] {0, size - 1};
    Matcher m = Pattern.compile("bytes=(\\d*)-(\\d*)").matcher(value);
    if (!m.matches()) throw new RangeException(size);
    try {
      long a, b;
      if (m.group(1).isEmpty()) {
        long n = Long.parseLong(m.group(2));
        if (n <= 0) throw new RangeException(size);
        a = Math.max(0, size - n);
        b = size - 1;
      } else {
        a = Long.parseLong(m.group(1));
        b = m.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
      }
      if (a >= size || a > b) throw new RangeException(size);
      return new long[] {a, b};
    } catch (NumberFormatException e) {
      throw new RangeException(size);
    }
  }
}
