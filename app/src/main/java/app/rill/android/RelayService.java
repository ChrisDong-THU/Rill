package app.rill.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import app.rill.core.LiveStats;
import app.rill.core.RelayCore;
import app.rill.core.Targets;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public final class RelayService extends Service {
  private static final int NOTIFICATION_ID = 41;
  private static final String NOTIFICATION_CHANNEL = "relay";

  public static volatile RelayService instance;
  private volatile String message = "服务未启动";
  public volatile RelayCore core;
  private final ArrayList<String> history = new ArrayList<String>();
  private final ArrayList<JSONObject> samples = new ArrayList<JSONObject>();
  private HandlerThread worker;
  private Handler handler;
  private BackgroundGuard guard;
  private WifiUpstream wifiUpstream;
  private final ExecutorService diagnostics = Executors.newSingleThreadExecutor();
  private final AtomicBoolean savePending = new AtomicBoolean();
  private long nextStartAttempt;
  private volatile int startFailures;
  private long started, lastSample;
  private volatile long lastTick;
  private volatile long maxTickGap;
  private volatile int recoveries;
  private String lastPowerState = "";
  private String lastGuardError = "";
  private String lastNetworkError = "";
  private final LiveStats live = new LiveStats();
  private String lastNotification = "";
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
    instance = this;
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
    wifiUpstream = new WifiUpstream(this, this::log);
  }

  public boolean isSessionActive() {
    return sessionActive && !stopping;
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
    // Like Clash: one standard title/body notification, no custom or expanded template.
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

  private String notificationKey() {
    return live.connectionsText + "\n" + live.downloadText;
  }

  private void updateNotification() {
    synchronized (notificationLock) {
      if (stopping) return;
      String value = notificationKey();
      if (value.equals(lastNotification)) return;
      ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
          .notify(NOTIFICATION_ID, notification());
      lastNotification = value;
    }
  }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null && STOP.equals(intent.getAction())) {
      getSharedPreferences("rill", 0).edit().putBoolean("relay_enabled", false).commit();
      stopSelf();
      return START_NOT_STICKY;
    }
    if (intent == null && !getSharedPreferences("rill", 0).getBoolean("relay_enabled", false)) {
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
      lastNotification = notificationKey();
      sessionActive = true;
    }
    try {
      guard.maintain();
    } catch (Exception e) {
      log("后台运行保护未生效：" + RelayCore.safe(e));
    }
    if (intent == null) log("系统回收后恢复加速会话");
    handler.removeCallbacks(tick);
    handler.post(tick);
    return START_STICKY;
  }

  private void startCore() throws IOException {
    if (stopping) return;
    RelayCore old = core;
    core = null;
    if (old != null) {
      old.close();
      recoveries++;
      log("本地监听已中断，正在恢复");
    }
    RelayCore c =
        new RelayCore(
            wifiUpstream::open,
            s -> log(s),
            getSharedPreferences("rill", 0).getInt("connections", 8));
    try {
      c.setTargets(
          Targets.parse(getSharedPreferences("rill", 0).getString("targets", Targets.DEFAULT)));
      if (stopping) {
        c.close();
        return;
      }
      c.start(RelayCore.PORT);
      core = c;
      message = "已启动，等待取流";
    } catch (IOException | RuntimeException e) {
      c.close();
      throw e;
    }
  }

  private void ensureCore(long now) {
    if ((core != null && core.isListening()) || now < nextStartAttempt) return;
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
            if (lastTick > 0 && now - lastTick > 5000)
              log("后台调度中断 " + ((now - lastTick) / 1000) + " 秒后恢复");
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
              wifiUpstream.start();
              lastNetworkError = "";
            } catch (Exception e) {
              String error = "Wi-Fi 监听异常：" + RelayCore.safe(e);
              if (!error.equals(lastNetworkError)) log(error);
              lastNetworkError = error;
            }
            ensureCore(now);
            RelayCore c = core;
            if (c == null || stopping) return;
            synchronized (notificationLock) {
              if (stopping) return;
              live.sample(now, c.downloaded.get(), c.connections.get(), c.getConnectionLimit());
              speed = live.downloadRate / 1000;
              updateNotification();
            }
            if (lastSample == 0 || now - lastSample >= 10000) {
              lastSample = now;
              JSONObject power = guard.report();
              String powerState = power.toString();
              if (!powerState.equals(lastPowerState)) {
                log("后台状态 " + powerState);
                lastPowerState = powerState;
              }
              JSONObject sample = new JSONObject();
              sample.put("time_ms", System.currentTimeMillis());
              sample.put("downloaded_bytes", c.downloaded.get());
              sample.put("served_bytes", c.served.get());
              sample.put("active_players", c.active.get());
              sample.put("upstream_connections", c.connections.get());
              sample.put("retries", c.retries.get());
              sample.put("errors", c.errors.get());
              sample.put("background", power);
              synchronized (samples) {
                if (samples.size() >= 900) samples.remove(0);
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
    String entry =
        new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date()) + "  " + s;
    synchronized (history) {
      if (history.size() >= 200) history.remove(0);
      history.add(entry);
    }
  }

  public String logText() {
    synchronized (history) {
      StringBuilder b = new StringBuilder();
      for (int i = Math.max(0, history.size() - 60); i < history.size(); i++)
        b.append(history.get(i)).append('\n');
      return b.toString();
    }
  }

  public String report() {
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
      j.put("wifi", wifiUpstream.report());
      j.put("state", message);
      j.put("upstream_transport", "Wi-Fi Network.openConnection + Proxy.NO_PROXY");
      j.put("proxy_listener", "127.0.0.1:18889");
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
        j.put("downloaded_bytes", c.downloaded.get());
        j.put("served_bytes", c.served.get());
        j.put("retries", c.retries.get());
        j.put("errors", c.errors.get());
        j.put("last_error", c.lastError);
        j.put("running", c.running);
        j.put("listener_alive", c.isListening());
      }
      synchronized (history) {
        j.put("events", new JSONArray(history));
      }
      synchronized (samples) {
        j.put("samples", new JSONArray(samples));
      }
      return j.toString(2);
    } catch (Exception e) {
      return "{}";
    }
  }

  private void saveAsync() {
    if (!savePending.compareAndSet(false, true)) return;
    diagnostics.execute(
        () -> {
          try {
            save();
          } finally {
            savePending.set(false);
          }
        });
  }

  private void save() {
    try {
      File temp = new File(getFilesDir(), "relay-result.tmp"),
          dest = new File(getFilesDir(), "relay-result.json");
      try (FileOutputStream out = new FileOutputStream(temp)) {
        out.write(report().getBytes("UTF-8"));
      }
      if (!temp.renameTo(dest)) {
        try (FileOutputStream out = new FileOutputStream(dest)) {
          out.write(report().getBytes("UTF-8"));
        }
      }
    } catch (Exception ignored) {
    }
  }

  public void onTimeout(int startId, int fgsType) {
    log("系统要求停止后台服务");
    getSharedPreferences("rill", 0).edit().putBoolean("relay_enabled", false).apply();
    stopSelf();
  }

  @Override
  public void onDestroy() {
    synchronized (notificationLock) {
      stopping = true;
      sessionActive = false;
      stopForeground(true);
    }
    handler.removeCallbacksAndMessages(null);
    guard.close();
    wifiUpstream.close();
    message = "服务已停止";
    if (instance == this) instance = null;
    handler.post(
        () -> {
          try {
            if (core != null) core.close();
            diagnostics.execute(this::save);
          } finally {
            diagnostics.shutdown();
            worker.quitSafely();
          }
        });
    super.onDestroy();
  }

  @Override
  public IBinder onBind(Intent intent) {
    return null;
  }
}
