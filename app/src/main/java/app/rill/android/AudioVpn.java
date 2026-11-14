package app.rill.android;

import android.app.PendingIntent;
import android.content.Intent;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import app.rill.core.RelayCore;
import app.rill.core.Targets;
import hev.htproxy.TProxyService;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Owns the per-app VPN and its native packet forwarding session. */
final class AudioVpn implements Closeable {
  static final String PLAYER = "com.bubblesoft.android.bubbleupnp";
  private static final String IPV4 = "198.18.0.1";
  private static final String IPV6 = "fd53:7269:6c6c::1";
  private static final int MTU = 1500;
  private final ParcelFileDescriptor tunnel;
  private final TunnelBridge bridge;
  private final File config;

  static Set<InetAddress> resolve(Network network, Targets targets) throws IOException {
    Set<InetAddress> addresses = new LinkedHashSet<>();
    for (Targets.Origin origin : targets.origins) {
      InetAddress[] resolved;
      try {
        resolved = network.getAllByName(origin.host);
      } catch (UnknownHostException unavailable) {
        continue;
      }
      for (InetAddress address : resolved) {
        // Loopback and link-local destinations are handled by the kernel's local routes.
        if (!address.isLoopbackAddress()
            && !address.isLinkLocalAddress()
            && !address.isAnyLocalAddress()) addresses.add(address);
      }
    }
    if (addresses.isEmpty()) throw new IOException("未解析到可路由的音频地址");
    return addresses;
  }

  AudioVpn(
      VpnService service,
      NetworkUpstream upstream,
      RelayCore relay,
      Network network,
      Set<InetAddress> addresses)
      throws IOException {
    PendingIntent configure =
        PendingIntent.getActivity(
            service, 0, new Intent(service, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
    VpnService.Builder builder =
        service.new Builder()
            .setSession("澜流")
            .setConfigureIntent(configure)
            .setMtu(MTU)
            .addAddress(IPV4, 32)
            .addAddress(IPV6, 128)
            .setMetered(false)
            .setUnderlyingNetworks(new Network[] {network});
    try {
      builder.addAllowedApplication(PLAYER);
    } catch (android.content.pm.PackageManager.NameNotFoundException error) {
      throw new IOException("请先安装 BubbleUPnP", error);
    }
    for (InetAddress address : addresses)
      builder.addRoute(address, address instanceof Inet4Address ? 32 : 128);

    String token = UUID.randomUUID().toString();
    bridge = new TunnelBridge(service, upstream, relay, addresses, token);
    ParcelFileDescriptor created = null;
    boolean running = false;
    File file = null;
    try {
      file = File.createTempFile("rill-tun-", ".yaml", service.getCacheDir());
      String yaml =
          "tunnel:\n  mtu: "
              + MTU
              + "\n  ipv4: "
              + IPV4
              + "\n  ipv6: '"
              + IPV6
              + "'\n"
              + "socks5:\n  address: 127.0.0.1\n  port: "
              + bridge.port()
              + "\n  username: rill\n  password: '"
              + token
              + "'\n"
              + "misc:\n  max-session-count: "
              + TunnelBridge.MAX_SESSIONS
              + "\n  tcp-buffer-size: 32768\n"
              + "  task-stack-size: 53248\n  log-level: error\n";
      try (FileOutputStream out = new FileOutputStream(file)) {
        out.write(yaml.getBytes(StandardCharsets.UTF_8));
      }
      created = builder.establish();
      if (created == null) throw new IOException("系统未授权澜流建立 VPN");
      running = TProxyService.TProxyStartService(file.getAbsolutePath(), created.getFd());
      if (!running) throw new IOException("无法启动 VPN 转发");
      tunnel = created;
      config = file;
    } finally {
      if (!running) {
        if (file != null) file.delete();
        bridge.close();
        if (created != null) created.close();
      }
    }
  }

  boolean isRunning() {
    return bridge.isListening() && TProxyService.TProxyIsRunning();
  }

  @Override
  public void close() throws IOException {
    bridge.close();
    try {
      TProxyService.TProxyStopService();
    } finally {
      try {
        tunnel.close();
      } finally {
        config.delete();
      }
    }
  }
}
