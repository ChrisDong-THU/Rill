package app.rill.android;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONObject;

/** Keeps the device awake during validated relay requests and a short idle grace period. */
public final class BackgroundGuard {
  private static final long IDLE_GRACE_MS = 8000;
  private final Context context;
  private final PowerManager power;
  private final PowerManager.WakeLock cpu;
  private int requests;
  private long idleUntil;
  private boolean closed;

  public BackgroundGuard(Context context) {
    this.context = context.getApplicationContext();
    power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    cpu = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Rill:RelaySession");
    cpu.setReferenceCounted(false);
  }

  public synchronized void requestStarted() {
    if (closed) return;
    requests++;
    maintain();
  }

  public synchronized void requestFinished() {
    if (closed) return;
    if (--requests == 0) idleUntil = SystemClock.elapsedRealtime() + IDLE_GRACE_MS;
  }

  @SuppressLint("WakelockTimeout") // Released after request completion or foreground service stop.
  public synchronized void maintain() {
    if (closed) return;
    if (requests > 0 || SystemClock.elapsedRealtime() < idleUntil) {
      if (!cpu.isHeld()) cpu.acquire();
    } else {
      releaseLock();
    }
  }

  public synchronized void close() {
    closed = true;
    requests = 0;
    releaseLock();
  }

  private void releaseLock() {
    if (cpu.isHeld()) cpu.release();
  }

  public synchronized JSONObject report() {
    JSONObject j = new JSONObject();
    try {
      j.put("cpu_lock_held", cpu.isHeld());
      j.put("cpu_lock_scope", "relay_requests_and_idle_grace");
      j.put("requests_in_flight", requests);
      j.put("idle_grace_ms", IDLE_GRACE_MS);
      j.put("battery_exempt", power.isIgnoringBatteryOptimizations(context.getPackageName()));
      j.put("device_idle", power.isDeviceIdleMode());
      j.put("power_save", power.isPowerSaveMode());
      j.put("interactive", power.isInteractive());
      if (Build.VERSION.SDK_INT >= 33)
        j.put("low_power_standby_enabled", power.isLowPowerStandbyEnabled());
      if (Build.VERSION.SDK_INT >= 34)
        j.put("low_power_standby_exempt", power.isExemptFromLowPowerStandby());
      j.put(
          "background_restricted",
          ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE))
              .isBackgroundRestricted());
    } catch (Exception ignored) {
    }
    return j;
  }
}
