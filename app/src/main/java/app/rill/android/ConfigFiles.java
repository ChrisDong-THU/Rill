package app.rill.android;

import android.util.AtomicFile;
import app.rill.core.ConfigSource;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Owns private configuration files; interrupted writes preserve the last complete file. */
final class ConfigFiles {
  private final AtomicFile source;
  private final AtomicFile adapted;

  ConfigFiles(File directory) {
    source = new AtomicFile(new File(directory, "source.yaml"));
    adapted = new AtomicFile(new File(directory, "adapted.yaml"));
  }

  boolean hasSource() {
    return source.getBaseFile().exists();
  }

  String readSource() throws IOException {
    return ConfigSource.read(source.openRead());
  }

  String readAdapted() throws IOException {
    return ConfigSource.read(adapted.openRead());
  }

  void save(String original, String result) throws IOException {
    // Each file is atomic. Publish the adapted result only after its source is saved.
    write(source, original);
    write(adapted, result);
  }

  void clear() {
    source.delete();
    adapted.delete();
  }

  private static void write(AtomicFile file, String value) throws IOException {
    if (Thread.currentThread().isInterrupted()) throw new IOException("配置保存已取消");
    FileOutputStream out = file.startWrite();
    try {
      out.write(value.getBytes(StandardCharsets.UTF_8));
      if (Thread.currentThread().isInterrupted()) throw new IOException("配置保存已取消");
      file.finishWrite(out);
    } catch (IOException | RuntimeException e) {
      file.failWrite(out);
      throw e;
    }
  }
}
