package app.rill.android;

import android.util.AtomicFile;
import app.rill.core.ConfigAdapter;
import app.rill.core.ConfigSource;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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

  boolean hasAdapted() {
    try (FileInputStream in = adapted.openRead()) {
      long size = in.getChannel().size();
      return size > 0 && size <= ConfigAdapter.MAX_BYTES;
    } catch (IOException e) {
      return false;
    }
  }

  void copyAdapted(OutputStream out) throws IOException {
    try (InputStream in = adapted.openRead()) {
      byte[] buffer = new byte[8192];
      int length;
      while ((length = in.read(buffer)) != -1) {
        if (Thread.currentThread().isInterrupted()) throw new IOException("配置读取已取消");
        out.write(buffer, 0, length);
      }
    }
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
