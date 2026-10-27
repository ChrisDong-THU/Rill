package app.rill.android;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.PowerManager;
import org.json.JSONObject;

/** Owned by the foreground relay session, not by Activity or individual tracks. */
public final class BackgroundGuard {
  private final Context context;
  private final PowerManager power;
  private final PowerManager.WakeLock cpu;
  private final WifiManager.WifiLock wifi;
  private boolean closed;

  public BackgroundGuard(Context context) {
    this.context = context.getApplicationContext();
    power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rill:RelaySession");
    cpu.setReferenceCounted(false);
    WifiManager manager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    wifi =
        manager == null || Build.VERSION.SDK_INT >= 34
            ? null
            : manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Rill:RelayWifi");
    if (wifi != null) wifi.setReferenceCounted(false);
  }

  @SuppressLint(
      "WakelockTimeout") // Bound to the user-enabled foreground service, released in close.
  public synchronized void maintain() {
    if (closed) return;
    // Do not let a delayed notification, disk write or network recovery expire the session lock.
    // Android releases it if the process dies; normal service shutdown calls close().
    if (!cpu.isHeld()) cpu.acquire();
    if (wifi != null && !wifi.isHeld()) wifi.acquire();
  }

  public synchronized void close() {
    closed = true;
    if (cpu.isHeld()) cpu.release();
    if (wifi != null && wifi.isHeld()) wifi.release();
  }

  public synchronized JSONObject report() {
    JSONObject j = new JSONObject();
    try {
      j.put("cpu_lock_held", cpu.isHeld());
      j.put("wifi_lock_held", wifi != null && wifi.isHeld());
      // API 34 maps HIGH_PERF to a foreground-only lock, unsuitable for this background relay.
      j.put("wifi_lock_supported", wifi != null);
      j.put("cpu_lock_scope", "foreground_service_session");
      j.put("battery_exempt", power.isIgnoringBatteryOptimizations(context.getPackageName()));
      j.put("device_idle", power.isDeviceIdleMode());
      j.put("power_save", power.isPowerSaveMode());
      j.put("interactive", power.isInteractive());
      if (Build.VERSION.SDK_INT >= 33)
        j.put("low_power_standby_enabled", power.isLowPowerStandbyEnabled());
      if (Build.VERSION.SDK_INT >= 34)
        j.put("low_power_standby_exempt", power.isExemptFromLowPowerStandby());
      if (Build.VERSION.SDK_INT >= 28)
        j.put(
            "background_restricted",
            ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE))
                .isBackgroundRestricted());
    } catch (Exception ignored) {
    }
    return j;
  }
}
