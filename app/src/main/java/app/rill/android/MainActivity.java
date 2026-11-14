package app.rill.android;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import app.rill.core.RelayCore;
import app.rill.core.Targets;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private static final int EXPORT_DIAGNOSTICS = 2;
  private static final int NOTIFICATIONS = 3;
  private static final int VPN_CONSENT = 4;
  private static final int RECORDS = 2;

  private Design d;
  private SharedPreferences prefs;
  private FrameLayout host;
  private LinearLayout navigation;
  private TextView title, status, rate, total, toggle, logText;
  private Design.Flow flow;
  private int page;
  private boolean resumed, loadingEvents;
  private String savedEvents;
  private String targetsPreference;
  private Targets parsedTargets;
  private RelayService recordsService;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private final Runnable refresh =
      new Runnable() {
        public void run() {
          if (!resumed) return;
          update();
          handler.postDelayed(this, 1000);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    prefs = getSharedPreferences("rill", 0);
    d = new Design(this);
    if (saved != null) {
      page = saved.getInt("page", 0) == RECORDS ? RECORDS : 0;
    }
    getWindow().setStatusBarColor(d.bg);
    getWindow().setNavigationBarColor(d.bg);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            d.dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    LinearLayout shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    shell.setBackgroundColor(d.bg);
    setContentView(shell);
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    header.setPadding(d.dp(24), 0, d.dp(18), 0);
    shell.addView(header, new LinearLayout.LayoutParams(-1, d.dp(66)));
    LinearLayout brand = new LinearLayout(this);
    brand.setGravity(Gravity.CENTER_VERTICAL);
    Design.Icon mark = new Design.Icon(this, "flow", d.accent);
    brand.addView(mark, new LinearLayout.LayoutParams(d.dp(28), d.dp(28)));
    TextView word = d.text("Rill", 25, d.ink);
    word.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(-2, -2);
    wp.leftMargin = d.dp(9);
    brand.addView(word, wp);
    header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
    FrameLayout gear = iconButton("settings", "设置");
    gear.setOnClickListener(v -> settings());
    header.addView(gear, new LinearLayout.LayoutParams(d.dp(48), d.dp(48)));
    host = new FrameLayout(this);
    shell.addView(host, new LinearLayout.LayoutParams(-1, 0, 1));
    navigation = new LinearLayout(this);
    navigation.setPadding(d.dp(30), d.dp(8), d.dp(30), d.dp(10));
    navigation.setGravity(Gravity.CENTER);
    shell.addView(navigation, new LinearLayout.LayoutParams(-1, d.dp(78)));
    show(page, false);
  }

  private LinearLayout vertical() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private void gap(LinearLayout l, int height) {
    l.addView(new View(this), new LinearLayout.LayoutParams(1, d.dp(height)));
  }

  private TextView label(String s, int size) {
    return d.text(s, size, d.ink);
  }

  private FrameLayout iconButton(String name, String description) {
    FrameLayout f = new FrameLayout(this);
    f.setContentDescription(description);
    f.setFocusable(true);
    f.setClickable(true);
    f.setBackground(d.rounded(d.soft, 24));
    Design.Icon icon = new Design.Icon(this, name, d.ink);
    FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(d.dp(22), d.dp(22), Gravity.CENTER);
    f.addView(icon, lp);
    d.press(f);
    return f;
  }

  private void addButton(LinearLayout parent, TextView b) {
    parent.addView(b, new LinearLayout.LayoutParams(-1, d.dp(54)));
  }

  private void show(int next, boolean animate) {
    int old = page;
    page = next;
    savedEvents = null;
    if (page != RECORDS) {
      logText = null;
      recordsService = null;
    }
    if (page != 0 && flow != null) {
      flow.setState(flow.active, false);
      flow = null;
      title = status = rate = total = toggle = null;
    }
    View previous = host.getChildCount() > 0 ? host.getChildAt(host.getChildCount() - 1) : null;
    View current = page == 0 ? home() : records();
    host.addView(current, new FrameLayout.LayoutParams(-1, -1));
    if (animate && d.animate() && previous != null) {
      current.setAlpha(0);
      current.setTranslationX(d.dp(next >= old ? 28 : -28));
      current
          .animate()
          .alpha(1)
          .translationX(0)
          .setDuration(260)
          .setInterpolator(new PathInterpolator(.2f, 0, 0, 1))
          .start();
      previous
          .animate()
          .alpha(0)
          .translationX(d.dp(next >= old ? -20 : 20))
          .setDuration(180)
          .withEndAction(() -> host.removeView(previous))
          .start();
    } else {
      for (int i = host.getChildCount() - 2; i >= 0; i--) host.removeViewAt(i);
    }
    buildNavigation();
    update();
    handler.removeCallbacks(refresh);
    if (resumed) handler.postDelayed(refresh, 1000);
  }

  private void buildNavigation() {
    navigation.removeAllViews();
    String[] names = {"连接", "记录"}, icons = {"flow", "log"};
    for (int i = 0; i < names.length; i++) {
      final int target = i == 0 ? 0 : RECORDS;
      LinearLayout item = vertical();
      item.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
      p.setMargins(d.dp(4), 0, d.dp(4), 0);
      navigation.addView(item, p);
      item.setBackground(d.rounded(target == page ? d.soft : Color.TRANSPARENT, 22));
      Design.Icon icon = new Design.Icon(this, icons[i], target == page ? d.accent : d.muted);
      item.addView(icon, new LinearLayout.LayoutParams(d.dp(23), d.dp(23)));
      gap(item, 4);
      TextView t = d.text(names[i], 11, target == page ? d.ink : d.muted);
      t.setGravity(Gravity.CENTER);
      item.addView(t);
      item.setContentDescription(names[i]);
      item.setClickable(true);
      item.setFocusable(true);
      d.press(item);
      item.setOnClickListener(
          v -> {
            if (page != target) show(target, true);
          });
    }
  }

  private View home() {
    LinearLayout body = vertical();
    body.setGravity(Gravity.CENTER);
    body.setPadding(d.dp(26), 0, d.dp(26), d.dp(8));
    flow = new Design.Flow(this, d);
    int hero =
        Math.min(
            218,
            Math.max(
                130,
                (int)
                    (getResources().getDisplayMetrics().heightPixels
                        / getResources().getDisplayMetrics().density
                        * .28)));
    body.addView(flow, new LinearLayout.LayoutParams(d.dp(hero), d.dp(hero)));
    gap(body, 18);
    title = label("尚未开启", 27);
    title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    title.setGravity(Gravity.CENTER);
    body.addView(title);
    gap(body, 9);
    status = d.text("BubbleUPnP 音频 VPN", 13, d.muted);
    status.setMaxLines(2);
    status.setGravity(Gravity.CENTER);
    body.addView(status);
    gap(body, 26);
    toggle = d.button("开启", true);
    body.addView(toggle, new LinearLayout.LayoutParams(d.dp(204), d.dp(56)));
    toggle.setOnClickListener(v -> toggle());
    gap(body, 10);
    gap(body, 34);
    LinearLayout metrics = new LinearLayout(this);
    metrics.setGravity(Gravity.CENTER);
    body.addView(metrics, new LinearLayout.LayoutParams(-1, d.dp(62)));
    rate = metric(metrics, "实时下载");
    View divider = new View(this);
    divider.setBackgroundColor(d.line);
    metrics.addView(divider, new LinearLayout.LayoutParams(d.dp(1), d.dp(30)));
    total = metric(metrics, "已交付");
    return scrolled(body);
  }

  private TextView metric(LinearLayout parent, String caption) {
    LinearLayout box = vertical();
    box.setGravity(Gravity.CENTER);
    parent.addView(box, new LinearLayout.LayoutParams(0, -1, 1));
    TextView value = label("—", 20);
    value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    value.setGravity(Gravity.CENTER);
    box.addView(value);
    gap(box, 6);
    TextView name = d.text(caption, 11, d.muted);
    name.setGravity(Gravity.CENTER);
    box.addView(name);
    return value;
  }

  private LinearLayout pageBody(String heading) {
    LinearLayout l = vertical();
    l.setPadding(d.dp(26), d.dp(14), d.dp(26), d.dp(20));
    TextView h = label(heading, 29);
    h.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    l.addView(h);
    gap(l, 24);
    return l;
  }

  private View scrolled(LinearLayout body) {
    ScrollView s = new ScrollView(this);
    s.setFillViewport(true);
    s.setClipToPadding(false);
    s.addView(body);
    return s;
  }

  private View records() {
    LinearLayout body = pageBody("运行记录");
    TextView save = d.button("导出诊断", false);
    addButton(body, save);
    save.setOnClickListener(v -> export());
    gap(body, 20);
    logText = d.text("尚无播放记录", 12, d.muted);
    logText.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
    logText.setLineSpacing(d.dp(7), 1);
    logText.setTextIsSelectable(true);
    logText.setPadding(d.dp(18), d.dp(18), d.dp(18), d.dp(18));
    logText.setBackground(d.rounded(d.surface, 22));
    body.addView(logText, new LinearLayout.LayoutParams(-1, -2));
    return scrolled(body);
  }

  private void update() {
    RelayService s = RelayService.instance;
    RelayCore c = s == null ? null : s.core;
    boolean on = s != null && s.isSessionActive();
    boolean ready = on && c != null && c.running && s.isVpnReady();
    if (page == 0 && title != null) {
      String heading =
          !on
              ? "尚未开启"
              : !ready
                  ? "正在连接 VPN"
                  : c.active.get() > 0 ? (c.state.contains("缓冲") ? "正在缓冲" : "正在播放") : "等待播放";
      if (!heading.contentEquals(title.getText())) {
        title.setText(heading);
        if (d.animate()) {
          title.setAlpha(.25f);
          title.animate().alpha(1).setDuration(240).start();
        }
      }
      setTextIfChanged(
          status,
          on && !ready
              ? s.connectionStatus()
              : ready && c.active.get() > 0 ? c.format : targets().summary());
      setTextIfChanged(toggle, on ? "停止" : "开启");
      setTextIfChanged(rate, ready ? Math.round(s.speed) + " KB/s" : "—");
      setTextIfChanged(
          total, ready ? String.format(Locale.ROOT, "%.1f MiB", c.served.get() / 1048576.0) : "—");
      flow.setState(on, ready && c.connections.get() > 0);
    }
    if (page == RECORDS && logText != null) {
      if (s != recordsService) {
        if (s == null && recordsService != null) savedEvents = recordsService.logText();
        recordsService = s;
      }
      if (s == null && savedEvents == null) loadSavedEvents();
      String events = s == null ? savedEvents : s.logText();
      if (events != null) setTextIfChanged(logText, events.isEmpty() ? "尚无播放记录" : events);
    }
  }

  private static void setTextIfChanged(TextView view, String value) {
    if (!value.contentEquals(view.getText())) view.setText(value);
  }

  private int limit() {
    return prefs.getInt("connections", 8);
  }

  private void toggle() {
    RelayService s = RelayService.instance;
    if (s != null && s.isSessionActive()) {
      startService(new Intent(this, RelayService.class).setAction(RelayService.STOP));
      update();
      return;
    }
    start();
  }

  private void start() {
    if (Build.VERSION.SDK_INT >= 33
        && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        && !prefs.getBoolean("notifications_asked", false)) {
      prefs.edit().putBoolean("notifications_asked", true).apply();
      requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, NOTIFICATIONS);
      return;
    }
    launchService();
  }

  private void launchService() {
    try {
      Intent consent = VpnService.prepare(this);
      if (consent != null) {
        startActivityForResult(consent, VPN_CONSENT);
        return;
      }
      startForegroundService(new Intent(this, RelayService.class));
      if (title != null) title.setText("正在开启");
    } catch (Exception e) {
      toast("未能启动，请保持应用在前台后重试");
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == NOTIFICATIONS) launchService();
  }

  private Dialog sheet(String heading, LinearLayout content) {
    Dialog dialog = new Dialog(this);
    LinearLayout outer = vertical();
    outer.setPadding(d.dp(24), d.dp(14), d.dp(24), d.dp(28));
    outer.setBackground(d.rounded(d.surface, 30));
    View handle = new View(this);
    handle.setBackground(d.rounded(d.line, 3));
    LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(d.dp(34), d.dp(4));
    hp.gravity = Gravity.CENTER;
    outer.addView(handle, hp);
    gap(outer, 22);
    TextView t = label(heading, 23);
    t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    outer.addView(t);
    gap(outer, 22);
    outer.addView(content, new LinearLayout.LayoutParams(-1, -2));
    dialog.setContentView(outer);
    Window w = dialog.getWindow();
    if (w != null) {
      w.setBackgroundDrawableResource(android.R.color.transparent);
      w.setLayout(-1, -2);
      w.setGravity(Gravity.BOTTOM);
      w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams p = w.getAttributes();
      p.dimAmount = .25f;
      w.setAttributes(p);
      w.setNavigationBarColor(d.surface);
      if (d.animate()) w.setWindowAnimations(R.style.SheetMotion);
    }
    return dialog;
  }

  private void connections() {
    LinearLayout content = vertical();
    final int[] choice = {limit()};
    TextView value = label(String.valueOf(choice[0]), 44);
    value.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    content.addView(value);
    gap(content, 8);
    content.addView(d.text("默认 8 · 可选择 1–16", 13, d.muted));
    gap(content, 20);
    SeekBar slider = new SeekBar(this);
    slider.setMax(15);
    slider.setProgress(choice[0] - 1);
    slider.setProgressTintList(android.content.res.ColorStateList.valueOf(d.accent));
    slider.setThumbTintList(android.content.res.ColorStateList.valueOf(d.accent));
    slider.setContentDescription("连接上限");
    content.addView(slider, new LinearLayout.LayoutParams(-1, d.dp(48)));
    slider.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar s, int p, boolean user) {
            choice[0] = p + 1;
            value.setText(String.valueOf(choice[0]));
          }

          public void onStartTrackingTouch(SeekBar s) {}

          public void onStopTrackingTouch(SeekBar s) {}
        });
    LinearLayout presets = new LinearLayout(this);
    content.addView(presets, new LinearLayout.LayoutParams(-1, d.dp(44)));
    for (int n : new int[] {4, 8, 12, 16}) {
      TextView chip = d.text(String.valueOf(n), 14, d.accent);
      chip.setGravity(Gravity.CENTER);
      chip.setBackground(d.rounded(d.soft, 16));
      LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -1, 1);
      cp.setMargins(d.dp(3), 0, d.dp(3), 0);
      presets.addView(chip, cp);
      chip.setOnClickListener(v -> slider.setProgress(n - 1));
      d.press(chip);
    }
    gap(content, 18);
    content.addView(d.text("调低上限时，已建立的连接会先完成。", 12, d.muted));
    gap(content, 22);
    TextView apply = d.button("应用", true);
    addButton(content, apply);
    Dialog dialog = sheet("连接上限", content);
    apply.setOnClickListener(
        v -> {
          prefs.edit().putInt("connections", choice[0]).apply();
          RelayService s = RelayService.instance;
          if (s != null && s.core != null) {
            s.core.setConnectionLimit(choice[0]);
            s.log("连接上限调整为 " + choice[0]);
          }
          dialog.dismiss();
          update();
          toast("已设为 " + choice[0] + " 路");
        });
    dialog.show();
  }

  private void settings() {
    LinearLayout content = vertical();
    TextView count = row(content, "连接上限", limit() + " 路");
    TextView links = row(content, "加速链接", targets().summary());
    Dialog dialog = sheet("设置", content);
    count.setOnClickListener(
        v -> {
          dialog.dismiss();
          connections();
        });
    links.setOnClickListener(
        v -> {
          dialog.dismiss();
          targetSheet();
        });
    dialog.show();
  }

  private Targets targets() {
    String value = prefs.getString("targets", Targets.DEFAULT);
    if (!value.equals(targetsPreference)) {
      try {
        parsedTargets = Targets.parse(value);
      } catch (IOException e) {
        parsedTargets = Targets.defaults();
      }
      targetsPreference = value;
    }
    return parsedTargets;
  }

  private void targetSheet() {
    LinearLayout content = vertical();
    EditText input = new EditText(this);
    input.setTextColor(d.ink);
    input.setTextSize(14);
    input.setGravity(Gravity.TOP);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT
            | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    input.setText(targets().text());
    input.setMinLines(2);
    input.setMaxLines(5);
    input.setPadding(d.dp(16), d.dp(14), d.dp(16), d.dp(14));
    input.setBackground(d.rounded(d.soft, 16));
    content.addView(input, new LinearLayout.LayoutParams(-1, -2));
    gap(content, 14);
    content.addView(d.text("HTTP 音频地址或域名，每行一个。", 12, d.muted));
    gap(content, 22);
    TextView save = d.button("保存", true);
    addButton(content, save);
    Dialog dialog = sheet("加速链接", content);
    save.setOnClickListener(
        v -> {
          try {
            Targets value = Targets.parse(input.getText().toString());
            prefs.edit().putString("targets", value.text()).apply();
            RelayService service = RelayService.instance;
            if (service != null) service.targetsChanged();
            dialog.dismiss();
            update();
            toast("已保存");
          } catch (IOException e) {
            input.setError(e.getMessage());
          }
        });
    dialog.show();
  }

  private TextView row(LinearLayout parent, String name, String caption) {
    TextView t = d.text(name + "   ·   " + caption, 14, d.ink);
    t.setPadding(0, d.dp(20), 0, d.dp(20));
    parent.addView(t, new LinearLayout.LayoutParams(-1, -2));
    t.setSingleLine(true);
    t.setEllipsize(android.text.TextUtils.TruncateAt.END);
    t.setClickable(true);
    d.press(t);
    return t;
  }

  private void export() {
    if (RelayService.instance == null && !new File(getFilesDir(), "relay-result.json").exists()) {
      toast("尚无诊断记录");
      return;
    }
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json")
            .putExtra(
                Intent.EXTRA_TITLE,
                "Rill-"
                    + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT)
                        .format(new Date())
                    + ".json");
    startActivityForResult(intent, EXPORT_DIAGNOSTICS);
  }

  private String readReport() throws IOException {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(
                new FileInputStream(new File(getFilesDir(), "relay-result.json")),
                StandardCharsets.UTF_8))) {
      StringBuilder report = new StringBuilder();
      for (String line; (line = reader.readLine()) != null; ) report.append(line).append('\n');
      return report.toString();
    }
  }

  private String lastEvents() {
    try {
      JSONObject j = new JSONObject(readReport());
      JSONArray list = j.optJSONArray("events");
      if (list == null) return "";
      StringBuilder b = new StringBuilder();
      for (int i = Math.max(0, list.length() - 40); i < list.length(); i++)
        b.append(list.getString(i)).append("\n\n");
      return b.toString();
    } catch (Exception e) {
      return "";
    }
  }

  private void loadSavedEvents() {
    if (loadingEvents) return;
    loadingEvents = true;
    io.execute(
        () -> {
          String events = lastEvents();
          runOnUiThread(
              () -> {
                if (isFinishing() || isDestroyed()) return;
                loadingEvents = false;
                if (savedEvents == null) savedEvents = events;
                if (page == RECORDS) update();
              });
        });
  }

  @Override
  protected void onActivityResult(int req, int code, Intent data) {
    super.onActivityResult(req, code, data);
    if (req == VPN_CONSENT) {
      if (code == RESULT_OK) launchService();
      else toast("需要授权音频 VPN 才能开启");
    } else if (req == EXPORT_DIAGNOSTICS
        && code == RESULT_OK
        && data != null
        && data.getData() != null) {
      Uri uri = data.getData();
      io.execute(() -> saveExport(uri));
    }
  }

  private void saveExport(Uri uri) {
    String result;
    try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
      RelayService service = RelayService.instance;
      String report = service != null ? service.report() : readReport();
      out.write(report.getBytes(StandardCharsets.UTF_8));
      result = "诊断已导出";
    } catch (Exception e) {
      result = "保存失败，请选择可写入的位置";
    }
    String message = result;
    runOnUiThread(
        () -> {
          if (!isDestroyed()) toast(message);
        });
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (page == RECORDS && RelayService.instance == null) savedEvents = null;
    handler.post(refresh);
  }

  @Override
  protected void onPause() {
    super.onPause();
    resumed = false;
    handler.removeCallbacks(refresh);
    if (flow != null) flow.setState(flow.active, false);
  }

  @Override
  protected void onSaveInstanceState(Bundle b) {
    super.onSaveInstanceState(b);
    b.putInt("page", page);
  }

  @Override
  public void onBackPressed() {
    if (page != 0) show(0, true);
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    // Let an export finish even if the system recreates the Activity while it writes.
    io.shutdown();
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
