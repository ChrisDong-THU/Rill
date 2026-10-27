package app.rill.core;

import static app.rill.core.ConfigAdapter.MAX_BYTES;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;

/** Bounded configuration input from files or HTTP subscriptions. */
public final class ConfigSource {
  private ConfigSource() {}

  public static String read(InputStream in) throws IOException {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("已取消");
        if (out.size() + n > MAX_BYTES) throw new IOException("配置文件超过 4 MiB");
        out.write(b, 0, n);
      }
      return out.toString("UTF-8").replaceFirst("^\\uFEFF", "");
    } finally {
      in.close();
    }
  }

  public static URL validateUrl(String value) throws IOException {
    try {
      URL u = new URL(value.trim());
      if ((!u.getProtocol().equalsIgnoreCase("https") && !u.getProtocol().equalsIgnoreCase("http"))
          || u.getHost().isEmpty()
          || u.getUserInfo() != null
          || u.getRef() != null
          || u.getPort() == 0
          || u.getPort() > 65535) throw new Exception();
      return u;
    } catch (Exception e) {
      throw new IOException("请输入有效的 HTTP 或 HTTPS 配置地址");
    }
  }

  public static String fetch(String address) throws IOException {
    URL url = validateUrl(address);
    long deadline = System.nanoTime() + 45000000000L;
    for (int hop = 0; hop < 5; hop++) {
      int remaining = remainingMillis(deadline);
      HttpURLConnection c = (HttpURLConnection) url.openConnection();
      try {
        c.setConnectTimeout(Math.min(10000, remaining));
        c.setReadTimeout(Math.min(15000, remaining));
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", "ClashForAndroid/2.5.12 Rill");
        c.setRequestProperty("Accept", "application/yaml, text/yaml, text/plain, */*");
        int code = c.getResponseCode();
        if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
          String loc = c.getHeaderField("Location");
          if (loc == null) throw new IOException("订阅跳转缺少地址");
          URL next = validateUrl(new URL(url, loc).toString());
          if (url.getProtocol().equalsIgnoreCase("https")
              && !next.getProtocol().equalsIgnoreCase("https"))
            throw new IOException("订阅跳转降低了连接安全性");
          url = next;
          continue;
        }
        if (code != 200) throw new IOException("订阅服务器返回 HTTP " + code);
        if (c.getContentLengthLong() > MAX_BYTES) throw new IOException("配置文件超过 4 MiB");
        final InputStream in = c.getInputStream();
        return read(
            new FilterInputStream(in) {
              public int read(byte[] b, int o, int n) throws IOException {
                c.setReadTimeout(Math.min(15000, remainingMillis(deadline)));
                int count = super.read(b, o, n);
                remainingMillis(deadline);
                return count;
              }
            });
      } catch (SocketTimeoutException e) {
        throw new IOException("订阅下载超时，请稍后重试");
      } catch (IOException e) {
        String s = e.getMessage();
        if (s != null && (s.startsWith("订阅") || s.startsWith("配置文件"))) throw e;
        throw new IOException("无法下载配置，请检查链接和网络");
      } finally {
        c.disconnect();
      }
    }
    throw new IOException("订阅跳转次数过多");
  }

  private static int remainingMillis(long deadline) throws IOException {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0 || Thread.currentThread().isInterrupted())
      throw new IOException("订阅下载超时或已取消");
    return (int) Math.max(1, remaining / 1000000);
  }
}
