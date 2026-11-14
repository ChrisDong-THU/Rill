package app.rill.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Network;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import app.rill.core.LiveStats;
import app.rill.core.RelayCore;
import app.rill.core.Targets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RelayService extends VpnService {
  private static final int NOTIFICATION_ID = 41;
  private static final String NOTIFICATION_CHANNEL = "relay";
  private static final long ACTIVE_SAMPLE_MS = 10000, IDLE_SAMPLE_MS = 60000;
  private static final long ROUTE_REFRESH_MS = 60_000, ROUTE_RETRY_MS = 10_000;
  private static final int MAX_EVENTS = 200, MAX_SAMPLES = 900;
  private static final int SNAPSHOT_EVENTS = 60, SNAPSHOT_SAMPLES = 24;
  private static final Object DIAGNOSTIC_FILE_LOCK = new Object();
  private static volatile long latestSessionId;

  public static volatile RelayService instance;
  private volatile String message = "服务未启动";
  public volatile RelayCore core;
  private final ArrayDeque<String> history = new ArrayDeque<>();
  private final ArrayDeque<JSONObject> samples = new ArrayDeque<>();
  private final SimpleDateFormat eventTime = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
  private String cachedLogText;
  private volatile int eventCount;
  private HandlerThread worker;
  private Handler handler;
  private BackgroundGuard guard;
  private NetworkUpstream networkUpstream;
  private volatile AudioVpn vpn;
  private final ExecutorService resolver = Executors.newSingleThreadExecutor();
  private boolean resolvingRoutes;
  private Network routeNetwork;
  private Set<InetAddress> routeAddresses;
  private String routeTargets;
  private long nextRouteRefresh;
  private String lastRouteError = "";
  private final ExecutorService diagnostics = Executors.newSingleThreadExecutor();
  private final AtomicBoolean savePending = new AtomicBoolean();
  private long nextStartAttempt;
  private volatile int startFailures;
  private long started, lastSample;
  private long sessionId;
  private RelayCore sampledCore;
  private long sampledRequests, sampledDownloads, sampledServed;
  private int sampledEvents;
  private volatile long lastTick;
  private volatile long maxTickGap;
  private volatile int recoveries;
  private String lastPowerState = "";
  private JSONObject sampledPower;
  private String lastGuardError = "";
  private String lastNetworkError = "";
  private boolean networkChanged;
  private final LiveStats live = new LiveStats();
  private String notifiedConnections = "", notifiedDownload = "";
  private final Object notificationLock = new Object();
  // Created once per service session; all subsequent access holds notificationLock.
  private Notification.Builder notificationBuilder;
  public volatile double speed;
  private volatile boolean stopping;
  private volatile boolean sessionActive;

  public static final String STOP = "app.rill.android.STOP";

  @Override
  public void onCreate() {
    super.onCreate();
    started = System.currentTimeMillis();
    NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    nm.createNotificationChannel(
        new NotificationChannel(
            NOTIFICATION_CHANNEL, "播放加速服务", NotificationManager.IMPORTANCE_LOW));
    notificationBuilder = createNotificationBuilder();
    worker = new HandlerThread("Rill-session");
    worker.start();
    handler = new Handler(worker.getLooper());
    guard = new BackgroundGuard(this);
    networkUpstream = new NetworkUpstream(this, this::onNetworkStateChanged);
    sessionId = ++latestSessionId;
    instance = this;
  }

  public boolean isSessionActive() {
    return sessionActive && !stopping;
  }

  boolean isVpnReady() {
    AudioVpn activeVpn = vpn;
    return activeVpn != null && activeVpn.isRunning();
  }

  String connectionStatus() {
    return message;
  }

  void targetsChanged() {
    handler.post(() -> nextRouteRefresh = 0);
  }

  private void onNetworkStateChanged(String state) {
    handler.post(
        () -> {
          if (stopping) return;
          log(state);
          networkChanged = true;
        });
  }

  private CharSequence notificationMetrics() {
    android.text.SpannableStringBuilder line = new android.text.SpannableStringBuilder();
    String[] values = {live.connectionsText, live.downloadText};
    String[] symbols = {"⛓︎", "↓"};
    for (int i = 0; i < values.length; i++) {
      // Keep each value/icon pair together, with a wider, consistent gap between metrics.
      if (i > 0) line.append("\u2003");
      line.append(values[i]).append("\u2005");
      int start = line.length();
      line.append(symbols[i]);
      line.setSpan(
          new android.text.style.RelativeSizeSpan(.85f),
          start,
          line.length(),
          android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }
    return line;
  }

  private Notification.Builder createNotificationBuilder() {
    Intent open = new Intent(this, MainActivity.class);
    PendingIntent content =
        PendingIntent.getActivity(
            this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    Notification.Builder builder =
        new Notification.Builder(this, NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_rill_status)
            .setColor(android.graphics.Color.rgb(36, 94, 73))
            .setContentTitle("澜流")
            .setContentIntent(content)
            .setOnlyAlertOnce(true)
            // Data updates must retain the session timestamp, including between tracks.
            .setWhen(started)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true);
    if (Build.VERSION.SDK_INT >= 31)
      builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
    return builder;
  }

  private Notification notification() {
    return notificationBuilder.setContentText(notificationMetrics()).build();
  }

  private void rememberNotificationMetrics() {
    notifiedConnections = live.connectionsText;
    notifiedDownload = live.downloadText;
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && STOP.equals(intent.getAction())) {
      stopSession();
      return START_NOT_STICKY;
    }
    if (stopping) return START_NOT_STICKY;
    if (intent == null && !getSharedPreferences("rill", 0).getBoolean("relay_enabled", false)) {
      stopSelf();
      return START_NOT_STICKY;
    }
    if (prepare(this) != null) {
      getSharedPreferences("rill", 0).edit().putBoolean("relay_enabled", false).apply();
      stopSelf();
      return START_NOT_STICKY;
    }
    getSharedPreferences("rill", 0).edit().putBoolean("relay_enabled", true).commit();
    synchronized (notificationLock) {
      if (stopping) return START_NOT_STICKY;
      if (Build.VERSION.SDK_INT >= 34)
        startForeground(
            NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
      else startForeground(NOTIFICATION_ID, notification());
      rememberNotificationMetrics();
      sessionActive = true;
    }
    if (intent == null) log("系统回收后恢复加速会话");
    // Replace the next tick on its own looper, including when a start arrives during a tick.
    handler.post(
        () -> {
          handler.removeCallbacks(tick);
          tick.run();
        });
    return START_STICKY;
  }

  private void startCore() throws IOException {
    if (stopping) return;
    RelayCore old = core;
    core = null;
    if (old != null) {
      closeVpn();
      old.close();
      recoveries++;
      log("本地监听已中断，正在恢复");
    }
    RelayCore c =
        new RelayCore(
            networkUpstream::open,
            new RelayCore.Events() {
              @Override
              public void event(String message) {
                log(message);
              }

              @Override
              public void requestStarted() {
                try {
                  guard.requestStarted();
                } catch (RuntimeException ignored) {
                  // The maintenance tick retries and reports a persistent lock failure.
                }
              }

              @Override
              public void requestFinished() {
                guard.requestFinished();
              }
            },
            getSharedPreferences("rill", 0).getInt("connections", 8));
    try {
      c.setTargets(
          Targets.parse(getSharedPreferences("rill", 0).getString("targets", Targets.DEFAULT)));
      if (stopping) {
        c.close();
        return;
      }
      if (BuildConfig.DEBUG) c.start(RelayCore.PORT);
      else {
        c.start();
        log("音频中继已启动");
      }
      core = c;
      message = "正在建立 VPN";
    } catch (IOException | RuntimeException e) {
      c.close();
      throw e;
    }
  }

  private void ensureVpn(RelayCore relay, long now) {
    Network network = networkUpstream.current();
    if (network == null) {
      message = networkUpstream.status();
      return;
    }
    if (resolvingRoutes) return;
    String configured = getSharedPreferences("rill", 0).getString("targets", Targets.DEFAULT);
    if (now < nextRouteRefresh && network.equals(routeNetwork) && configured.equals(routeTargets))
      return;
    routeNetwork = network;
    routeTargets = configured;
    resolvingRoutes = true;
    nextRouteRefresh = now + ROUTE_REFRESH_MS;
    resolver.execute(
        () -> {
          try {
            Targets targets = Targets.parse(configured);
            Set<InetAddress> addresses = AudioVpn.resolve(network, targets);
            handler.post(() -> applyRoutes(relay, network, configured, targets, addresses));
          } catch (IOException | RuntimeException error) {
            handler.post(
                () -> {
                  if (acceptRouteResult(relay, network, configured)) routeFailed(error);
                });
          }
        });
  }

  private boolean acceptRouteResult(RelayCore relay, Network network, String configured) {
    resolvingRoutes = false;
    if (stopping || relay != core) return false;
    if (!network.equals(networkUpstream.current())
        || !configured.equals(
            getSharedPreferences("rill", 0).getString("targets", Targets.DEFAULT))) {
      nextRouteRefresh = 0;
      return false;
    }
    return true;
  }

  private void applyRoutes(
      RelayCore relay,
      Network network,
      String configured,
      Targets targets,
      Set<InetAddress> addresses) {
    if (!acceptRouteResult(relay, network, configured)) return;
    try {
      relay.setTargets(targets);
      boolean rebuilt = !isVpnReady() || !addresses.equals(routeAddresses);
      if (rebuilt) {
        closeVpn();
        vpn = new AudioVpn(this, networkUpstream, relay, network, addresses);
        routeAddresses = addresses;
        nextRouteRefresh = SystemClock.elapsedRealtime() + ROUTE_REFRESH_MS;
        log("音频 VPN 已就绪 · " + addresses.size() + " 条地址路由");
      } else {
        setUnderlyingNetworks(new Network[] {network});
      }
      networkChanged |= rebuilt || !"VPN 已连接".equals(message) || !lastRouteError.isEmpty();
      message = "VPN 已连接";
      lastRouteError = "";
    } catch (IOException | RuntimeException | LinkageError error) {
      routeFailed(error);
    }
  }

  private void routeFailed(Throwable error) {
    String detail =
        error instanceof IOException
            ? RelayCore.safe((IOException) error)
            : error.getClass().getSimpleName();
    String failure = "VPN 路由更新失败：" + detail;
    if (!failure.equals(lastRouteError)) {
      log(failure);
      networkChanged = true;
    }
    lastRouteError = failure;
    if (!isVpnReady()) message = failure;
    nextRouteRefresh = SystemClock.elapsedRealtime() + ROUTE_RETRY_MS;
  }

  private void closeVpn() {
    AudioVpn current = vpn;
    vpn = null;
    routeAddresses = null;
    nextRouteRefresh = 0;
    if (current != null) {
      try {
        current.close();
      } catch (IOException error) {
        log("关闭 VPN：" + RelayCore.safe(error));
      }
    }
  }

  private void ensureCore(long now) {
    if ((core != null && (BuildConfig.DEBUG ? core.isListening() : core.running))
        || now < nextStartAttempt) return;
    try {
      startCore();
      startFailures = 0;
      nextStartAttempt = 0;
    } catch (Exception e) {
      startFailures++;
      long delay = Math.min(30000L, 1000L << Math.min(startFailures, 5));
      nextStartAttempt = now + delay;
      message = "本地服务恢复失败，将自动重试：" + RelayCore.safe(e);
      log(message);
      saveAsync();
    }
  }

  private final Runnable tick =
      new Runnable() {
        public void run() {
          if (stopping) return;
          try {
            long now = SystemClock.elapsedRealtime();
            if (lastTick > 0) maxTickGap = Math.max(maxTickGap, now - lastTick);
            boolean resumedAfterGap = lastTick > 0 && now - lastTick > 5000;
            if (resumedAfterGap) log("后台调度中断 " + ((now - lastTick) / 1000) + " 秒后恢复");
            lastTick = now;
            try {
              guard.maintain();
              lastGuardError = "";
            } catch (Exception e) {
              String error = "后台保护异常：" + RelayCore.safe(e);
              if (!error.equals(lastGuardError)) log(error);
              lastGuardError = error;
            }
            try {
              networkUpstream.start();
              lastNetworkError = "";
            } catch (Exception e) {
              String error = "网络监听异常：" + RelayCore.safe(e);
              if (!error.equals(lastNetworkError)) log(error);
              lastNetworkError = error;
            }
            ensureCore(now);
            RelayCore c = core;
            if (c == null || stopping) return;
            ensureVpn(c, now);
            long downloaded = c.downloaded.get();
            int connections = c.connections.get();
            synchronized (notificationLock) {
              if (stopping) return;
              live.sample(now, downloaded, connections, c.getConnectionLimit());
              speed = live.downloadRate / 1000;
              if (!notifiedConnections.equals(live.connectionsText)
                  || !notifiedDownload.equals(live.downloadText)) {
                ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIFICATION_ID, notification());
                rememberNotificationMetrics();
              }
            }
            long requests = c.requests.get(), served = c.served.get();
            int events = eventCount;
            boolean hasTraffic =
                connections > 0
                    || c.active.get() > 0
                    || requests != sampledRequests
                    || downloaded != sampledDownloads
                    || served != sampledServed
                    || events != sampledEvents;
            if (sampledCore != c
                || resumedAfterGap
                || networkChanged && !savePending.get()
                || now - lastSample >= (hasTraffic ? ACTIVE_SAMPLE_MS : IDLE_SAMPLE_MS)) {
              if (!savePending.get()) networkChanged = false;
              lastSample = now;
              sampledCore = c;
              sampledRequests = requests;
              sampledDownloads = downloaded;
              sampledServed = served;
              JSONObject power = guard.report();
              String powerState = power.toString();
              if (!powerState.equals(lastPowerState)) {
                log("后台状态 " + powerState);
                lastPowerState = powerState;
                sampledPower = power;
              }
              sampledEvents = eventCount;
              JSONObject sample = new JSONObject();
              sample.put("time_ms", System.currentTimeMillis());
              sample.put("downloaded_bytes", downloaded);
              sample.put("served_bytes", served);
              sample.put("active_players", c.active.get());
              sample.put("upstream_connections", connections);
              sample.put("retries", c.retries.get());
              sample.put("errors", c.errors.get());
              sample.put("background", sampledPower);
              synchronized (samples) {
                if (samples.size() >= MAX_SAMPLES) samples.removeFirst();
                samples.add(sample);
              }
              saveAsync();
            }
          } catch (Exception e) {
            message = "服务检查异常：" + RelayCore.safe(e);
            log(message);
            // A transient failure must not clear the user's enabled session.
          } finally {
            if (!stopping) handler.postDelayed(this, 1000);
          }
        }
      };

  public void log(String s) {
    synchronized (history) {
      if (history.size() >= MAX_EVENTS) history.removeFirst();
      history.add(eventTime.format(new Date()) + "  " + s);
      eventCount++;
      cachedLogText = null;
    }
  }

  public String logText() {
    synchronized (history) {
      if (cachedLogText == null) {
        StringBuilder b = new StringBuilder();
        int skip = Math.max(0, history.size() - 60);
        for (String event : history) if (skip-- <= 0) b.append(event).append('\n');
        cachedLogText = b.toString();
      }
      return cachedLogText;
    }
  }

  public String report() {
    return report(true, MAX_EVENTS, MAX_SAMPLES);
  }

  private String report(boolean pretty, int eventLimit, int sampleLimit) {
    try {
      JSONObject j = new JSONObject();
      j.put("tool", "Rill " + BuildConfig.VERSION_NAME);
      j.put("started_at_ms", started);
      j.put("exported_at_ms", System.currentTimeMillis());
      j.put("android", Build.VERSION.RELEASE);
      j.put("model", Build.MODEL);
      j.put("process_id", android.os.Process.myPid());
      j.put("background", guard.report());
      j.put("service_tick_age_ms", lastTick == 0 ? -1 : SystemClock.elapsedRealtime() - lastTick);
      j.put("service_max_tick_gap_ms", maxTickGap);
      j.put("listener_recoveries", recoveries);
      j.put("listener_start_failures", startFailures);
      j.put("network", networkUpstream.report());
      j.put("vpn_running", isVpnReady());
      j.put("state", message);
      j.put("upstream_transport", "Wi-Fi/cellular Network.openConnection + Proxy.NO_PROXY");
      if (BuildConfig.DEBUG) j.put("test_listener", "127.0.0.1:18889");
      j.put("targets", getSharedPreferences("rill", 0).getString("targets", Targets.DEFAULT));
      RelayCore c = core;
      j.put(
          "connections_limit",
          c == null
              ? getSharedPreferences("rill", 0).getInt("connections", 8)
              : c.getConnectionLimit());
      j.put("chunk_bytes", RelayCore.BLOCK);
      j.put("raw_urls_exported", false);
      j.put(
          "limitation",
          "HTTP Range only; no audio decoding, output bit-perfect verification or full-file"
              + " checksum.");
      if (c != null) {
        j.put("format", c.format);
        j.put("requests", c.requests.get());
        j.put("open_clients", c.getOpenClientCount());
        j.put("pending_preflights", c.getPendingPreflights());
        j.put("cached_audio_bytes", c.getCachedBytes());
        j.put("downloaded_bytes", c.downloaded.get());
        j.put("served_bytes", c.served.get());
        j.put("retries", c.retries.get());
        j.put("errors", c.errors.get());
        j.put("last_error", c.lastError);
        j.put("running", c.running);
        if (BuildConfig.DEBUG) j.put("listener_alive", c.isListening());
      }
      synchronized (history) {
        j.put("events", latest(history, eventLimit));
      }
      synchronized (samples) {
        j.put("samples", latest(samples, sampleLimit));
      }
      return pretty ? j.toString(2) : j.toString();
    } catch (Exception e) {
      return "{}";
    }
  }

  private static JSONArray latest(ArrayDeque<?> entries, int limit) {
    if (entries.size() <= limit) return new JSONArray(entries);
    ArrayList<Object> tail = new ArrayList<>(limit);
    Iterator<?> newest = entries.descendingIterator();
    while (tail.size() < limit) tail.add(newest.next());
    Collections.reverse(tail);
    return new JSONArray(tail);
  }

  private void saveAsync() {
    if (!savePending.compareAndSet(false, true)) return;
    diagnostics.execute(
        () -> {
          try {
            save(SNAPSHOT_EVENTS, SNAPSHOT_SAMPLES);
          } finally {
            savePending.set(false);
          }
        });
  }

  private void save(int eventLimit, int sampleLimit) {
    byte[] value = report(false, eventLimit, sampleLimit).getBytes(StandardCharsets.UTF_8);
    try {
      synchronized (DIAGNOSTIC_FILE_LOCK) {
        if (sessionId != latestSessionId) return;
        File temp = new File(getFilesDir(), "relay-result.tmp"),
            dest = new File(getFilesDir(), "relay-result.json");
        try (FileOutputStream out = new FileOutputStream(temp)) {
          out.write(value);
        }
        if (!temp.renameTo(dest)) temp.delete();
      }
    } catch (Exception ignored) {
    }
  }

  public void onTimeout(int startId, int fgsType) {
    log("系统要求停止后台服务");
    stopSession();
  }

  private void stopSession() {
    synchronized (notificationLock) {
      if (stopping) return;
      stopping = true;
      sessionActive = false;
    }
    getSharedPreferences("rill", 0).edit().putBoolean("relay_enabled", false).apply();
    handler.postAtFrontOfQueue(
        () -> {
          // Android keeps this service bound until the VPN's TUN descriptor closes.
          try {
            closeVpn();
          } finally {
            stopSelf();
          }
        });
  }

  @Override
  public void onDestroy() {
    synchronized (notificationLock) {
      stopping = true;
      sessionActive = false;
      stopForeground(true);
    }
    handler.removeCallbacksAndMessages(null);
    resolver.shutdownNow();
    guard.close();
    networkUpstream.close();
    message = "服务已停止";
    if (instance == this) instance = null;
    handler.post(
        () -> {
          try {
            closeVpn();
            if (core != null) core.close();
            diagnostics.execute(() -> save(MAX_EVENTS, MAX_SAMPLES));
          } finally {
            diagnostics.shutdown();
            worker.quitSafely();
          }
        });
    super.onDestroy();
  }

  @Override
  public void onRevoke() {
    stopSession();
  }
}
