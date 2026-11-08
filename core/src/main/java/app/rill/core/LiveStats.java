package app.rill.core;

import java.util.Locale;

/** Monotonic transfer counters shared by the app and notification. */
public final class LiveStats {
  private long previousTime = -1, previousDownload;
  private int previousConnections, previousLimit = 8;
  public double downloadRate;
  public String connectionsText = "0/8", downloadText = "0 B/s";

  public void sample(long now, long download, int connections, int limit) {
    double nextRate =
        previousTime >= 0 && now > previousTime
            ? Math.max(0, download - previousDownload) * 1000.0 / (now - previousTime)
            : 0;
    if (connections != previousConnections || limit != previousLimit) {
      connectionsText = connections + "/" + limit;
      previousConnections = connections;
      previousLimit = limit;
    }
    if (downloadRate != nextRate) downloadText = rate(nextRate);
    downloadRate = nextRate;
    previousTime = now;
    previousDownload = download;
  }

  public static String rate(double value) {
    if (value >= 1000000) return String.format(Locale.ROOT, "%.1f MB/s", value / 1000000);
    if (value >= 1000) return String.format(Locale.ROOT, "%.0f KB/s", value / 1000);
    return String.format(Locale.ROOT, "%.0f B/s", value);
  }
}
