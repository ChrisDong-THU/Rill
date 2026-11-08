package app.rill.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Shares immutable configuration snapshots with an explicitly selected Clash app. */
final class ClashImporter {
  private static final long SNAPSHOT_LIFETIME_MS = 7L * 24 * 60 * 60 * 1000;

  private ClashImporter() {}

  static void importConfig(Activity activity, ConfigFiles files, Executor io) {
    Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("clash://install-config"));
    List<ResolveInfo> found =
        activity
            .getPackageManager()
            .queryIntentActivities(probe, PackageManager.MATCH_DEFAULT_ONLY);
    LinkedHashMap<String, ResolveInfo> apps = new LinkedHashMap<>();
    for (ResolveInfo target : found) apps.put(target.activityInfo.packageName, target);
    if (apps.isEmpty()) {
      toast(activity, "未找到支持导入的 Clash，请使用保存文件");
      return;
    }
    ArrayList<ResolveInfo> candidates = new ArrayList<>(apps.values());
    if (candidates.size() == 1) {
      send(activity, candidates.get(0), files, io);
      return;
    }
    String[] names = new String[candidates.size()];
    for (int i = 0; i < names.length; i++)
      names[i] = candidates.get(i).loadLabel(activity.getPackageManager()).toString();
    new AlertDialog.Builder(activity)
        .setTitle("导入到")
        .setItems(names, (dialog, which) -> send(activity, candidates.get(which), files, io))
        .show();
  }

  private static void send(Activity activity, ResolveInfo target, ConfigFiles files, Executor io) {
    io.execute(
        () -> {
          Intent intent = prepareImport(activity, target, files);
          activity.runOnUiThread(
              () -> {
                if (activity.isFinishing() || activity.isDestroyed()) return;
                try {
                  if (intent != null) {
                    activity.startActivity(intent);
                    return;
                  }
                } catch (RuntimeException ignored) {
                }
                toast(activity, "无法打开 Clash，请使用保存文件");
              });
        });
  }

  private static Intent prepareImport(Activity activity, ResolveInfo target, ConfigFiles files) {
    try {
      File directory = new File(activity.getFilesDir(), "shared");
      if (!directory.exists() && !directory.mkdirs()) throw new IOException();
      File[] previous = directory.listFiles();
      if (previous != null) {
        for (File file : previous) {
          if (System.currentTimeMillis() - file.lastModified() > SNAPSHOT_LIFETIME_MS) {
            String id = file.getName().replace(".yaml", "");
            activity.revokeUriPermission(
                snapshotUri(activity, id), Intent.FLAG_GRANT_READ_URI_PERMISSION);
            file.delete();
          }
        }
      }
      String id = UUID.randomUUID().toString();
      File snapshot = new File(directory, id + ".yaml");
      try (FileOutputStream out = new FileOutputStream(snapshot)) {
        files.copyAdapted(out);
      } catch (IOException e) {
        snapshot.delete();
        throw e;
      }
      Uri config = snapshotUri(activity, id);
      activity.grantUriPermission(
          target.activityInfo.packageName, config, Intent.FLAG_GRANT_READ_URI_PERMISSION);
      Uri link =
          new Uri.Builder()
              .scheme("clash")
              .authority("install-config")
              .appendQueryParameter("url", config.toString())
              .appendQueryParameter("name", "澜流 Rill")
              .appendQueryParameter("type", "url")
              .build();
      Intent intent =
          new Intent(Intent.ACTION_VIEW, link)
              .setComponent(
                  new ComponentName(target.activityInfo.packageName, target.activityInfo.name));
      intent.setClipData(ClipData.newRawUri("Rill-Clash.yaml", config));
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      return intent;
    } catch (Exception e) {
      return null;
    }
  }

  private static Uri snapshotUri(Activity activity, String id) {
    return Uri.parse(
        "content://" + activity.getPackageName() + ".config/" + id + "/Rill-Clash.yaml");
  }

  private static void toast(Activity activity, String message) {
    Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
  }
}
