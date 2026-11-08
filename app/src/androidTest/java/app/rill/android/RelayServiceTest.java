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
import android.util.Log;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import app.rill.core.RelayCore;
import app.rill.core.Targets;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import org.json.JSONArray;
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
    assertFalse(report.getJSONObject("background").getBoolean("cpu_lock_held"));
    assertTrue(report.getJSONObject("wifi").getBoolean("request_active"));
    assertTrue(report.getLong("service_tick_age_ms") < 2500);
    assertEquals(first.when, notification().when);
    assertTrue(health().startsWith("HTTP/1.1 200"));
    assertFalse(background(service).getBoolean("cpu_lock_held"));
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
  public void relayRequestAcquiresWakeLockBeforePreflightAndKeepsIdleGrace() throws Exception {
    RelayService service = startInBackground();
    await(() -> service.core != null && service.core.isListening(), 5000);
    await(
        () -> new JSONObject(service.report()).getJSONObject("wifi").getBoolean("available"),
        20000);
    try (ServerSocket origin = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        Socket player = new Socket("127.0.0.1", RelayCore.PORT)) {
      origin.setSoTimeout(6000);
      player.setSoTimeout(6000);
      String url = "http://127.0.0.1:" + origin.getLocalPort();
      service.core.setTargets(Targets.parse(url));
      player
          .getOutputStream()
          .write(("GET " + url + "/audio HTTP/1.1\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      try (Socket upstream = origin.accept()) {
        assertTrue(background(service).getBoolean("cpu_lock_held"));
        assertEquals(1, background(service).getInt("requests_in_flight"));
        upstream
            .getOutputStream()
            .write(
                ("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes 0-3/4\r\n"
                        + "Content-Length: 4\r\nConnection: close\r\n\r\ntest")
                    .getBytes(StandardCharsets.US_ASCII));
        assertTrue(read(player.getInputStream()).endsWith("\r\n\r\ntest"));
      }
      await(() -> background(service).getInt("requests_in_flight") == 0, 5000);
      assertTrue(background(service).getBoolean("cpu_lock_held"));
    }
    context.startService(new Intent(context, RelayService.class).setAction(RelayService.STOP));
    await(() -> RelayService.instance == null, 5000);
    assertFalse(background(service).getBoolean("cpu_lock_held"));
  }

  private static JSONObject background(RelayService service) throws Exception {
    return new JSONObject(service.report()).getJSONObject("background");
  }

  @Test
  @SuppressWarnings("unchecked")
  public void periodicSnapshotKeepsRecentHistoryWhenSessionIsFull() throws Exception {
    RelayService service = startInBackground();
    await(() -> service.core != null && service.core.isListening(), 5000);
    JSONObject power = background(service);
    long startedAt = new JSONObject(service.report()).getLong("started_at_ms");
    Field field = RelayService.class.getDeclaredField("samples");
    field.setAccessible(true);
    ArrayDeque<JSONObject> samples = (ArrayDeque<JSONObject>) field.get(service);
    long seededAt = System.currentTimeMillis();
    // Seed 150 minutes of history without changing the production sampling clock.
    synchronized (samples) {
      samples.clear();
      for (int i = 0; i < 900; i++) {
        samples.add(
            new JSONObject()
                .put("time_ms", seededAt - (900L - i) * 10000)
                .put("downloaded_bytes", i * 18000000L)
                .put("served_bytes", i * 17900000L)
                .put("active_players", 1)
                .put("upstream_connections", 4)
                .put("retries", 0)
                .put("errors", 0)
                .put("background", power));
      }
    }
    for (int i = 0; i < 200; i++) service.log("long-session fixture event " + i);
    File path = new File(context.getFilesDir(), "relay-result.json");
    await(
        () -> {
          JSONObject report = savedReport(path);
          if (report.optLong("started_at_ms") != startedAt) return false;
          JSONArray events = report.optJSONArray("events");
          if (events == null || events.length() == 0) return false;
          return events.toString().contains("long-session fixture event 199");
        },
        18000);
    JSONObject snapshot = savedReport(path);
    JSONArray recent = snapshot.getJSONArray("samples");
    Log.i(
        "RillTest",
        "Long-session periodic snapshot bytes="
            + path.length()
            + " samples="
            + recent.length()
            + " events="
            + snapshot.getJSONArray("events").length());
    assertEquals(24, recent.length());
    assertEquals(60, snapshot.getJSONArray("events").length());
    assertTrue(recent.getJSONObject(0).getLong("time_ms") >= seededAt - 24 * 10000L);
    assertTrue(recent.getJSONObject(recent.length() - 1).getLong("time_ms") >= seededAt);
    JSONObject full = new JSONObject(service.report());
    assertEquals(900, full.getJSONArray("samples").length());
    assertEquals(200, full.getJSONArray("events").length());
    context.startService(new Intent(context, RelayService.class).setAction(RelayService.STOP));
    await(
        () -> {
          JSONArray archive = savedReport(path).optJSONArray("samples");
          return archive != null && archive.length() == 900;
        },
        5000);
    assertEquals(200, savedReport(path).getJSONArray("events").length());
  }

  private static JSONObject savedReport(File path) {
    try (FileInputStream stream = new FileInputStream(path)) {
      return new JSONObject(read(stream));
    } catch (Exception ignored) {
      return new JSONObject();
    }
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
