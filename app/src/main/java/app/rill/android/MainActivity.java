package app.rill.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import app.rill.core.ConfigAdapter;
import app.rill.core.ConfigSource;
import app.rill.core.RelayCore;
import app.rill.core.Targets;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

public final class MainActivity extends Activity {
  private Design d;
  private SharedPreferences prefs;
  private ConfigFiles configFiles;
  private FrameLayout host;
  private LinearLayout navigation;
  private TextView title, status, rate, total, toggle, logText, backgroundButton;
  private Design.Flow flow;
  private int page, pendingExport;
  private boolean working, resumed;
  private String sourceName = "", adapted = "", adaptError = "";
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
    configFiles = new ConfigFiles(getFilesDir());
    d = new Design(this);
    if (saved != null) {
      page = saved.getInt("page", 0);
      pendingExport = saved.getInt("export", 0);
    }
    loadAdapted();
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
    View previous = host.getChildCount() > 0 ? host.getChildAt(host.getChildCount() - 1) : null;
    View current = page == 0 ? home() : page == 1 ? configuration() : records();
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
  }

  private void buildNavigation() {
    navigation.removeAllViews();
    String[] names = {"连接", "配置", "记录"}, icons = {"flow", "config", "log"};
    for (int i = 0; i < 3; i++) {
      final int target = i;
      LinearLayout item = vertical();
      item.setGravity(Gravity.CENTER);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, 1);
      p.setMargins(d.dp(4), 0, d.dp(4), 0);
      navigation.addView(item, p);
      item.setBackground(d.rounded(i == page ? d.soft : Color.TRANSPARENT, 22));
      Design.Icon icon = new Design.Icon(this, icons[i], i == page ? d.accent : d.muted);
      item.addView(icon, new LinearLayout.LayoutParams(d.dp(23), d.dp(23)));
      gap(item, 4);
      TextView t = d.text(names[i], 11, i == page ? d.ink : d.muted);
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
    status = d.text("Wi-Fi 直连 · 原样传输", 13, d.muted);
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

  private View configuration() {
    LinearLayout body = pageBody("Clash 配置");
    LinearLayout imports = new LinearLayout(this);
    body.addView(imports, new LinearLayout.LayoutParams(-1, d.dp(88)));
    importTile(imports, "file", "选择文件", () -> pickFile());
    View space = new View(this);
    imports.addView(space, new LinearLayout.LayoutParams(d.dp(12), 1));
    importTile(imports, "link", "使用链接", () -> urlSheet());
    gap(body, 22);
    LinearLayout output = vertical();
    output.setPadding(d.dp(22), d.dp(22), d.dp(22), d.dp(22));
    output.setBackground(d.rounded(d.surface, 26));
    body.addView(output, new LinearLayout.LayoutParams(-1, -2));
    if (working) {
      ProgressBar spinner = new ProgressBar(this);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(d.dp(32), d.dp(32));
      p.gravity = Gravity.CENTER;
      output.addView(spinner, p);
      gap(output, 18);
      TextView t = label("正在读取配置", 17);
      t.setGravity(Gravity.CENTER);
      output.addView(t);
    } else if (adapted.isEmpty()) {
      Design.Icon icon = new Design.Icon(this, "config", d.muted);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(d.dp(38), d.dp(38));
      p.gravity = Gravity.CENTER;
      output.addView(icon, p);
      gap(output, 16);
      TextView t = label(adaptError.isEmpty() ? "等待导入" : "未能适配", 18);
      t.setGravity(Gravity.CENTER);
      output.addView(t);
      if (!adaptError.isEmpty()) {
        gap(output, 9);
        TextView note = d.text(adaptError, 13, d.muted);
        note.setGravity(Gravity.CENTER);
        output.addView(note);
      }
    } else {
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      Design.Icon ok = new Design.Icon(this, "check", d.accent);
      row.addView(ok, new LinearLayout.LayoutParams(d.dp(23), d.dp(23)));
      TextView name = label("已适配", 18);
      name.setPadding(d.dp(10), 0, 0, 0);
      row.addView(name);
      output.addView(row);
      gap(output, 16);
      TextView from = d.text(sourceName, 13, d.muted);
      from.setMaxLines(2);
      output.addView(from);
      gap(output, 10);
      output.addView(d.text(targets().summary(), 13, d.muted));
      if (!adaptError.isEmpty()) {
        gap(output, 10);
        output.addView(d.text("本次导入失败，保留上次配置。\n" + adaptError, 13, d.muted));
      }
      gap(output, 22);
      TextView send = d.button("导入 Clash", true);
      addButton(output, send);
      send.setOnClickListener(v -> ClashImporter.importConfig(this, adapted));
      gap(output, 10);
      TextView save = d.button("保存文件", false);
      addButton(output, save);
      save.setOnClickListener(v -> export(1));
      gap(output, 10);
      TextView clear = d.text("清除本次配置", 12, d.muted);
      clear.setGravity(Gravity.CENTER);
      clear.setPadding(0, d.dp(10), 0, d.dp(10));
      output.addView(clear);
      clear.setOnClickListener(
          v -> {
            adapted = "";
            sourceName = "";
            adaptError = "";
            configFiles.clear();
            prefs.edit().remove("config_source").remove("config_group").apply();
            show(1, false);
          });
    }
    return scrolled(body);
  }

  private void importTile(LinearLayout parent, String iconName, String title, Runnable action) {
    LinearLayout tile = vertical();
    tile.setGravity(Gravity.CENTER);
    tile.setBackground(d.rounded(d.soft, 24));
    parent.addView(tile, new LinearLayout.LayoutParams(0, -1, 1));
    tile.addView(
        new Design.Icon(this, iconName, d.accent),
        new LinearLayout.LayoutParams(d.dp(26), d.dp(26)));
    gap(tile, 12);
    TextView name = label(title, 14);
    name.setGravity(Gravity.CENTER);
    tile.addView(name);
    tile.setContentDescription(title);
    tile.setEnabled(!working);
    tile.setAlpha(working ? .5f : 1);
    tile.setClickable(true);
    tile.setOnClickListener(
        v -> {
          if (!working) action.run();
        });
    d.press(tile);
  }

  private View records() {
    LinearLayout body = pageBody("运行记录");
    backgroundButton = d.button(batteryExempt() ? "后台运行 · 已允许" : "允许后台运行", false);
    addButton(body, backgroundButton);
    backgroundButton.setOnClickListener(v -> backgroundAccess());
    gap(body, 12);
    TextView save = d.button("导出诊断", false);
    addButton(body, save);
    save.setOnClickListener(v -> export(2));
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
    boolean ready = on && c != null && c.running;
    if (page == 0 && title != null) {
      String heading =
          !on
              ? "尚未开启"
              : !ready
                  ? "正在恢复服务"
                  : c.active.get() > 0 ? (c.state.contains("缓冲") ? "正在缓冲" : "正在播放") : "等待播放";
      if (!heading.contentEquals(title.getText())) {
        title.setText(heading);
        if (d.animate()) {
          title.setAlpha(.25f);
          title.animate().alpha(1).setDuration(240).start();
        }
      }
      status.setText(ready && c.active.get() > 0 ? c.format : targets().summary());
      toggle.setText(on ? "停止" : "开启");
      rate.setText(ready ? String.format(Locale.ROOT, "%.0f KB/s", s.speed) : "—");
      total.setText(
          ready ? String.format(Locale.ROOT, "%.1f MiB", c.served.get() / 1048576.0) : "—");
      flow.setState(on, ready && c.connections.get() > 0);
    }
    if (page == 2 && logText != null) {
      if (backgroundButton != null)
        backgroundButton.setText(batteryExempt() ? "后台运行 · 已允许" : "允许后台运行");
      String text = s == null ? lastEvents() : s.logText();
      if (!text.contentEquals(logText.getText())) logText.setText(text.isEmpty() ? "尚无播放记录" : text);
    }
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
      requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 3);
      return;
    }
    launchService();
  }

  private void launchService() {
    try {
      startForegroundService(new Intent(this, RelayService.class));
      if (title != null) title.setText("正在开启");
      if (!batteryExempt() && !prefs.getBoolean("background_asked", false)) {
        prefs.edit().putBoolean("background_asked", true).apply();
        backgroundAccess();
      }
    } catch (Exception e) {
      toast("未能启动，请保持应用在前台后重试");
    }
  }

  @Override
  public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(request, permissions, grants);
    if (request == 3) launchService();
  }

  private boolean batteryExempt() {
    return ((PowerManager) getSystemService(POWER_SERVICE))
        .isIgnoringBatteryOptimizations(getPackageName());
  }

  private void backgroundAccess() {
    if (batteryExempt()) {
      try {
        startActivity(
            new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + getPackageName())));
      } catch (Exception e) {
        toast("请在系统设置中允许澜流后台运行");
      }
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("允许后台运行")
        .setMessage("避免锁屏或切换应用后暂停加速。开启期间会保持 CPU 和 Wi-Fi 工作，停止后释放。")
        .setPositiveButton(
            "去设置",
            (dialog, which) -> {
              try {
                startActivity(
                    new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName())));
              } catch (Exception e) {
                try {
                  startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                } catch (Exception ignored) {
                  toast("请在系统设置中允许澜流后台运行");
                }
              }
            })
        .setNegativeButton("稍后", null)
        .show();
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
    try {
      return Targets.parse(prefs.getString("targets", Targets.DEFAULT));
    } catch (IOException e) {
      return Targets.defaults();
    }
  }

  private void targetSheet() {
    if (working) {
      toast("请等待配置读取完成");
      return;
    }
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
    content.addView(d.text("HTTP 链接或域名，每行一个。", 12, d.muted));
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
            if (service != null && service.core != null) {
              service.core.setTargets(value);
              service.log("加速链接已更新");
            }
            dialog.dismiss();
            update();
            if (configFiles.hasSource()) work(sourceName, configFiles::readSource);
            else toast("已保存");
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

  private void pickFile() {
    if (working) return;
    Intent i =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE);
    startActivityForResult(i, 1);
  }

  private void urlSheet() {
    if (working) return;
    LinearLayout content = vertical();
    EditText input = new EditText(this);
    input.setTextColor(d.ink);
    input.setHintTextColor(d.muted);
    input.setTextSize(14);
    input.setHint("https://…");
    input.setSingleLine(true);
    input.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    input.setPadding(d.dp(16), 0, d.dp(16), 0);
    input.setBackground(d.rounded(d.soft, 16));
    content.addView(input, new LinearLayout.LayoutParams(-1, d.dp(54)));
    gap(content, 12);
    content.addView(d.text("需要返回完整 Clash YAML 的订阅地址。", 12, d.muted));
    gap(content, 22);
    TextView get = d.button("读取并适配", true);
    addButton(content, get);
    Dialog dialog = sheet("从链接导入", content);
    get.setOnClickListener(
        v -> {
          try {
            String url = input.getText().toString().trim();
            String hostName = ConfigSource.validateUrl(url).getHost();
            dialog.dismiss();
            work("订阅 · " + hostName, () -> ConfigSource.fetch(url));
          } catch (IOException e) {
            input.setError(e.getMessage());
          }
        });
    dialog.show();
  }

  interface Input {
    String read() throws Exception;
  }

  private void work(String source, Input input) {
    if (working) return;
    working = true;
    adaptError = "";
    Targets requestedTargets = targets();
    show(1, false);
    io.execute(
        () -> {
          try {
            String raw = input.read();
            ConfigAdapter.Result result = ConfigAdapter.adapt(raw, requestedTargets);
            configFiles.save(raw, result.yaml);
            prefs.edit().putString("config_source", source).apply();
            runOnUiThread(
                () -> {
                  if (isFinishing() || isDestroyed()) return;
                  working = false;
                  adapted = result.yaml;
                  sourceName = source;
                  if (page == 1) show(1, false);
                  toast("配置已适配");
                });
          } catch (Exception e) {
            final String msg = e instanceof IOException ? e.getMessage() : "无法读取配置文件";
            runOnUiThread(
                () -> {
                  if (isFinishing() || isDestroyed()) return;
                  working = false;
                  adaptError = msg == null ? "无法读取配置文件" : msg;
                  if (page == 1) show(1, false);
                  else toast("配置适配失败");
                });
          }
        });
  }

  private void loadAdapted() {
    try {
      adapted = configFiles.readAdapted();
      sourceName = prefs.getString("config_source", "Clash 配置");
    } catch (Exception ignored) {
      adapted = "";
    }
  }

  private void export(int kind) {
    if (kind == 1 && adapted.isEmpty()) {
      toast("请先导入配置");
      return;
    }
    if (kind == 2
        && RelayService.instance == null
        && !new File(getFilesDir(), "relay-result.json").exists()) {
      toast("尚无诊断记录");
      return;
    }
    pendingExport = kind;
    Intent i =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(kind == 1 ? "application/octet-stream" : "application/json");
    i.putExtra(
        Intent.EXTRA_TITLE,
        kind == 1
            ? "Rill-Clash.yaml"
            : "Rill-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date())
                + ".json");
    startActivityForResult(i, 2);
  }

  private String lastEvents() {
    try {
      JSONObject j =
          new JSONObject(
              ConfigSource.read(new FileInputStream(new File(getFilesDir(), "relay-result.json"))));
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

  @Override
  protected void onActivityResult(int req, int code, Intent data) {
    super.onActivityResult(req, code, data);
    if (code != RESULT_OK || data == null || data.getData() == null) return;
    Uri uri = data.getData();
    if (req == 1) {
      String name = "Clash 配置";
      try (Cursor cursor =
          getContentResolver()
              .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
        if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
      } catch (Exception ignored) {
      }
      work(name, () -> ConfigSource.read(getContentResolver().openInputStream(uri)));
    }
    if (req == 2) {
      try {
        String value;
        if (pendingExport == 1) value = adapted;
        else {
          RelayService s = RelayService.instance;
          value =
              s != null
                  ? s.report()
                  : ConfigSource.read(
                      new FileInputStream(new File(getFilesDir(), "relay-result.json")));
        }
        try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
          out.write(value.getBytes("UTF-8"));
        }
        toast(pendingExport == 1 ? "已保存，请在 Clash 中导入" : "诊断已导出");
      } catch (Exception e) {
        toast("保存失败，请选择可写入的位置");
      }
    }
  }

  private void toast(String s) {
    Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
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
    b.putInt("export", pendingExport);
  }

  @Override
  public void onBackPressed() {
    if (page != 0) show(0, true);
    else super.onBackPressed();
  }

  @Override
  protected void onDestroy() {
    io.shutdownNow();
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
