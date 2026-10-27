package app.rill.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.Closeable;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import org.json.JSONObject;

/** Keeps a Wi-Fi request alive for the foreground session and follows network changes. */
final class WifiUpstream implements Closeable {
  private final ConnectivityManager connectivity;
  private final Consumer<String> events;
  private final HandlerThread worker = new HandlerThread("Rill-network");
  private final Set<Network> available = new LinkedHashSet<>();
  private final Set<Network> blocked = new LinkedHashSet<>();
  private Network selected;
  private boolean registered;
  private boolean closed;
  private String state = "等待 Wi-Fi";

  WifiUpstream(Context context, Consumer<String> events) {
    connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    this.events = events;
    worker.start();
  }

  private final ConnectivityManager.NetworkCallback callback =
      new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
          synchronized (WifiUpstream.this) {
            if (closed) return;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) {
              available.add(network);
            } else {
              available.remove(network);
            }
            selectNetwork();
          }
        }

        @Override
        public void onLost(Network network) {
          synchronized (WifiUpstream.this) {
            available.remove(network);
            blocked.remove(network);
            selectNetwork();
          }
        }

        @Override
        public void onBlockedStatusChanged(Network network, boolean isBlocked) {
          synchronized (WifiUpstream.this) {
            if (isBlocked) blocked.add(network);
            else blocked.remove(network);
            selectNetwork();
          }
        }
      };

  synchronized void start() {
    if (closed || registered) return;
    NetworkRequest request =
        new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build();
    // Unlike enumerating getAllNetworks for every range, this request lives with the service.
    connectivity.requestNetwork(request, callback, new Handler(worker.getLooper()));
    registered = true;
  }

  private void selectNetwork() {
    if (closed) return;
    if (selected == null || !available.contains(selected) || blocked.contains(selected)) {
      selected = null;
      for (Network network : available) {
        if (!blocked.contains(network)) {
          selected = network;
          break;
        }
      }
    }
    String next =
        selected != null ? "Wi-Fi 已就绪" : available.isEmpty() ? "等待 Wi-Fi 恢复" : "系统限制了后台网络";
    if (!next.equals(state)) {
      state = next;
      events.accept(next);
    }
    notifyAll();
  }

  HttpURLConnection open(URL url) throws IOException {
    Network network;
    synchronized (this) {
      long deadline = SystemClock.elapsedRealtime() + 10000;
      while (!closed && selected == null) {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new IOException(state + "，未切换移动数据");
        try {
          wait(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("取流已取消", e);
        }
      }
      if (closed) throw new IOException("服务已停止");
      network = selected;
    }
    return (HttpURLConnection) network.openConnection(url, Proxy.NO_PROXY);
  }

  synchronized JSONObject report() {
    JSONObject result = new JSONObject();
    try {
      result.put("request_active", registered);
      result.put("available", selected != null);
      result.put("blocked", !available.isEmpty() && selected == null && !blocked.isEmpty());
      result.put("state", state);
    } catch (Exception ignored) {
    }
    return result;
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    closed = true;
    state = "服务已停止";
    selected = null;
    available.clear();
    blocked.clear();
    notifyAll();
    try {
      if (registered) connectivity.unregisterNetworkCallback(callback);
    } catch (IllegalArgumentException ignored) {
      // The system may already have released a callback during teardown.
    } finally {
      registered = false;
      worker.quitSafely();
    }
  }
}
