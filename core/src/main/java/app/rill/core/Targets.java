package app.rill.core;

import java.io.IOException;
import java.net.IDN;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Explicit HTTP origins. Paths and signed query strings are never persisted. */
public final class Targets {
  public static final String DEFAULT = "http://lgf.audio.tidal.com";

  public static final class Origin {
    public final String host;
    public final int port;

    Origin(String h, int p) {
      host = h;
      port = p;
    }

    public String toString() {
      return "http://" + host + (port == 80 ? "" : ":" + port);
    }

    public String rule(String group) {
      return host.matches("[0-9.]+")
          ? "IP-CIDR," + host + "/32," + group + ",no-resolve"
          : "DOMAIN," + host + "," + group;
    }
  }

  public final List<Origin> origins;

  private Targets(List<Origin> values) {
    origins = Collections.unmodifiableList(values);
  }

  public static Targets defaults() {
    try {
      return parse(DEFAULT);
    } catch (IOException impossible) {
      throw new AssertionError(impossible);
    }
  }

  public static Targets parse(String text) throws IOException {
    LinkedHashMap<String, Origin> result = new LinkedHashMap<String, Origin>();
    if (text == null || text.length() > 16384) throw new IOException("链接过长");
    for (String line : text.split("[\\r\\n]+")) {
      String value = line.trim();
      if (value.isEmpty()) continue;
      try {
        URI uri = new URI(value.contains("://") ? value : "http://" + value);
        if ("https".equalsIgnoreCase(uri.getScheme()))
          throw new IOException("HTTPS 无法分段加速，请使用 HTTP 取流链接");
        if (!"http".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null
            || uri.getRawUserInfo() != null) throw new Exception();
        String host = IDN.toASCII(uri.getHost()).toLowerCase(Locale.ROOT);
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (!host.matches("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?")) throw new Exception();
        if (host.matches("[0-9.]+")) {
          String[] parts = host.split("\\.");
          if (parts.length != 4) throw new Exception();
          for (String part : parts)
            if (Integer.parseInt(part) > 255 || part.length() > 1 && part.startsWith("0"))
              throw new Exception();
        } else
          for (String label : host.split("\\.", -1))
            if (label.isEmpty()
                || label.length() > 63
                || label.startsWith("-")
                || label.endsWith("-")) throw new Exception();
        int port = uri.getPort() == -1 ? 80 : uri.getPort();
        if (port < 1
            || port > 65535
            || port == 18889 && (host.equals("127.0.0.1") || host.equals("localhost")))
          throw new Exception();
        Origin origin = new Origin(host, port);
        result.put(origin.toString(), origin);
      } catch (IOException e) {
        throw e;
      } catch (Exception e) {
        throw new IOException("请输入有效的 HTTP 链接或域名");
      }
    }
    if (result.isEmpty()) throw new IOException("至少填写一个加速链接");
    if (result.size() > 16) throw new IOException("最多设置 16 个域名");
    return new Targets(new ArrayList<Origin>(result.values()));
  }

  public boolean allows(URL url) {
    if (!"http".equalsIgnoreCase(url.getProtocol())
        || url.getUserInfo() != null
        || url.getRef() != null) return false;
    int port = url.getPort() == -1 ? 80 : url.getPort();
    for (Origin o : origins)
      if (o.host.equalsIgnoreCase(url.getHost()) && o.port == port) return true;
    return false;
  }

  public boolean tunnel(String authority) {
    try {
      return allows(new URL("http://" + authority + "/"));
    } catch (Exception e) {
      return false;
    }
  }

  public String text() {
    StringBuilder b = new StringBuilder();
    for (Origin o : origins) {
      if (b.length() > 0) b.append('\n');
      b.append(o);
    }
    return b.toString();
  }

  public String summary() {
    return origins.get(0).host + (origins.size() > 1 ? " +" + (origins.size() - 1) : "");
  }
}
