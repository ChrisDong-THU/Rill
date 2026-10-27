package app.rill.android;

import static org.junit.Assert.*;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.rill.core.RelayCore;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Run on an isolated emulator: these tests toggle Wi-Fi and start/stop the app's service. */
@RunWith(AndroidJUnit4.class)
public class RelayServiceTest {
  private Context context;

  @Before
  public void setUp() throws Exception {
    Assume.assumeTrue(
        "Requires an isolated Android 13+ emulator",
        Build.VERSION.SDK_INT >= 33
            && (Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish")));
    context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    context.stopService(new Intent(context, RelayService.class));
    await(() -> RelayService.instance == null, 5000);
    context
        .getSharedPreferences("rill", 0)
        .edit()
        .putBoolean("notifications_asked", true)
        .putBoolean("background_asked", true)
        .commit();
    shell("pm grant " + context.getPackageName() + " android.permission.POST_NOTIFICATIONS");
    shell("svc wifi enable");
    shell("cmd wifi connect-network AndroidWifi open");
  }

  private RelayService startInBackground() throws Exception {
    Activity activity =
        InstrumentationRegistry.getInstrumentation()
            .startActivitySync(
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    context.startForegroundService(new Intent(context, RelayService.class));
    await(() -> RelayService.instance != null && RelayService.instance.isSessionActive(), 5000);
    InstrumentationRegistry.getInstrumentation().runOnMainSync(activity::finish);
    await(activity::isDestroyed, 5000);
    return RelayService.instance;
  }

  @After
  public void tearDown() throws Exception {
    if (context == null) return;
    shell("svc wifi enable");
    if (RelayService.instance != null) {
      context.startService(new Intent(context, RelayService.class).setAction(RelayService.STOP));
      await(() -> RelayService.instance == null, 5000);
    }
  }

  @Test
  public void backgroundSessionKeepsHeartbeatAndOriginalNotificationTime() throws Exception {
    RelayService service = startInBackground();
    await(() -> service.core != null && service.core.isListening(), 5000);
    Notification first = notification();
    assertNotNull(first);
    service.core.setConnectionLimit(5);
    await(
        () ->
            notification() != null
                && notification()
                    .extras
                    .getCharSequence(Notification.EXTRA_TEXT)
                    .toString()
                    .startsWith("0/5"),
        5000);
    SystemClock.sleep(2500);
    JSONObject report = new JSONObject(service.report());
    assertTrue(report.getJSONObject("background").getBoolean("cpu_lock_held"));
    assertTrue(report.getJSONObject("wifi").getBoolean("request_active"));
    assertTrue(report.getLong("service_tick_age_ms") < 2500);
    assertEquals(first.when, notification().when);
    assertTrue(health().startsWith("HTTP/1.1 200"));
    context.startService(new Intent(context, RelayService.class).setAction(RelayService.STOP));
    await(() -> RelayService.instance == null, 5000);
    await(() -> service.core == null || !service.core.isListening(), 5000);
    assertFalse(
        new JSONObject(service.report()).getJSONObject("background").getBoolean("cpu_lock_held"));
    assertFalse(
        new JSONObject(service.report()).getJSONObject("wifi").getBoolean("request_active"));
    assertNull(notification());
  }

  @Test
  public void bindFailureRetriesWithoutReopeningActivity() throws Exception {
    RelayService service;
    try (ServerSocket occupied =
        new ServerSocket(RelayCore.PORT, 1, InetAddress.getByName("127.0.0.1"))) {
      service = startInBackground();
      await(() -> new JSONObject(service.report()).getInt("listener_start_failures") > 0, 5000);
      assertTrue(service.isSessionActive());
      assertTrue(context.getSharedPreferences("rill", 0).getBoolean("relay_enabled", false));
    }
    await(() -> service.core != null && service.core.isListening(), 10000);
    assertTrue(health().startsWith("HTTP/1.1 200"));
    long when = notification().when;
    RelayCore original = service.core;
    original.close();
    await(
        () -> service.core != null && service.core != original && service.core.isListening(), 5000);
    assertTrue(health().startsWith("HTTP/1.1 200"));
    assertEquals(when, notification().when);
  }

  @Test
  public void wifiReconnectIsObservedWhileActivityIsClosed() throws Exception {
    RelayService service = startInBackground();
    await(
        () -> new JSONObject(service.report()).getJSONObject("wifi").getBoolean("available"),
        20000);
    long when = notification().when;
    shell("svc wifi disable");
    await(
        () -> !new JSONObject(service.report()).getJSONObject("wifi").getBoolean("available"),
        10000);
    assertTrue(service.isSessionActive());
    assertTrue(health().startsWith("HTTP/1.1 200"));
    shell("svc wifi enable");
    // The emulator's unvalidated test AP may not autojoin after toggling Wi-Fi.
    shell("cmd wifi connect-network AndroidWifi open");
    await(
        () -> new JSONObject(service.report()).getJSONObject("wifi").getBoolean("available"),
        20000);
    assertTrue(health().startsWith("HTTP/1.1 200"));
    assertEquals(when, notification().when);
  }

  private Notification notification() {
    NotificationManager manager =
        (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
    for (StatusBarNotification item : manager.getActiveNotifications()) {
      if (item.getId() == 41) return item.getNotification();
    }
    return null;
  }

  private static String health() throws Exception {
    try (Socket socket = new Socket("127.0.0.1", RelayCore.PORT)) {
      socket.setSoTimeout(3000);
      socket
          .getOutputStream()
          .write(
              "GET /health HTTP/1.1\r\nHost: localhost\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII));
      return read(socket.getInputStream());
    }
  }

  private static void shell(String command) throws Exception {
    try (ParcelFileDescriptor result =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation()
                .executeShellCommand(command);
        InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(result)) {
      read(input);
    }
  }

  private static String read(InputStream input) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int length;
    while ((length = input.read(buffer)) != -1) result.write(buffer, 0, length);
    return result.toString("UTF-8");
  }

  private interface Condition {
    boolean holds() throws Exception;
  }

  private static void await(Condition condition, long timeout) throws Exception {
    long deadline = SystemClock.elapsedRealtime() + timeout;
    while (!condition.holds()) {
      if (SystemClock.elapsedRealtime() >= deadline)
        fail("Condition timed out after " + timeout + " ms");
      SystemClock.sleep(100);
    }
  }
}
