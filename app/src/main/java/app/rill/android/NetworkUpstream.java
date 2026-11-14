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
import java.util.function.Consumer;
import org.json.JSONObject;

/** Uses Android's best matching physical network for the relay session. */
final class NetworkUpstream implements Closeable {
  private final ConnectivityManager connectivity;
  private final Consumer<String> events;
  private final HandlerThread worker = new HandlerThread("Rill-network");
  private Network network;
  private String transport;
  private boolean metered;
  private boolean policyKnown;
  private boolean blocked;
  private boolean registered;
  private boolean closed;
  private String state = "等待可用网络";

  NetworkUpstream(Context context, Consumer<String> events) {
    connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    this.events = events;
    worker.start();
  }

  private final ConnectivityManager.NetworkCallback callback =
      new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network next) {
          synchronized (NetworkUpstream.this) {
            if (closed) return;
            network = next;
            transport = null;
            policyKnown = false;
            blocked = false;
            updateState();
          }
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
          synchronized (NetworkUpstream.this) {
            if (closed || !network.equals(NetworkUpstream.this.network)) return;
            transport = transportOf(caps);
            metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
            updateState();
          }
        }

        @Override
        public void onLost(Network network) {
          synchronized (NetworkUpstream.this) {
            if (closed || !network.equals(NetworkUpstream.this.network)) return;
            clearNetwork();
            updateState();
          }
        }

        @Override
        public void onBlockedStatusChanged(Network network, boolean isBlocked) {
          synchronized (NetworkUpstream.this) {
            if (closed || !network.equals(NetworkUpstream.this.network)) return;
            policyKnown = true;
            blocked = isBlocked;
            updateState();
          }
        }

        @Override
        public void onUnavailable() {
          synchronized (NetworkUpstream.this) {
            if (closed) return;
            registered = false;
            clearNetwork();
            updateState();
          }
        }
      };

  synchronized void start() {
    if (closed || registered) return;
    NetworkRequest request =
        new NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build();
    connectivity.requestNetwork(request, callback, new Handler(worker.getLooper()));
    registered = true;
  }

  private static String transportOf(NetworkCapabilities caps) {
    if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return null;
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "wifi";
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "cellular";
    return null;
  }

  private boolean available() {
    return network != null && transport != null && policyKnown && !blocked;
  }

  private void clearNetwork() {
    network = null;
    transport = null;
    policyKnown = false;
    blocked = false;
  }

  private void updateState() {
    String next =
        available()
            ? ("wifi".equals(transport) ? "Wi-Fi 已就绪" : "蜂窝网络已就绪")
            : blocked ? "系统限制了后台网络" : "等待可用网络";
    if (!next.equals(state)) {
      state = next;
      events.accept(next);
    }
    notifyAll();
  }

  HttpURLConnection open(URL url) throws IOException {
    return (HttpURLConnection) awaitNetwork().openConnection(url, Proxy.NO_PROXY);
  }

  synchronized Network current() {
    return available() ? network : null;
  }

  synchronized String status() {
    return state;
  }

  Network awaitNetwork() throws IOException {
    Network upstream;
    synchronized (this) {
      long deadline = SystemClock.elapsedRealtime() + 10000;
      while (!closed && !available()) {
        long remaining = deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) throw new IOException(state);
        try {
          wait(remaining);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("取流已取消", e);
        }
      }
      if (closed) throw new IOException("服务已停止");
      upstream = network;
    }
    return upstream;
  }

  synchronized JSONObject report() {
    JSONObject result = new JSONObject();
    try {
      result.put("request_active", registered);
      result.put("available", available());
      result.put("blocked", blocked);
      result.put("transport", transport == null ? "none" : transport);
      if (transport != null) result.put("metered", metered);
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
    clearNetwork();
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
