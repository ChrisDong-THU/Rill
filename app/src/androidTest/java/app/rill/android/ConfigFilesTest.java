package app.rill.android;

import static org.junit.Assert.*;

import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.Test;

public class ConfigFilesTest {
  @Test
  public void cancelledSavePreservesLastConfigurationAndClearRemovesIt() throws Exception {
    File directory =
        new File(
            InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(),
            UUID.randomUUID().toString());
    assertTrue(directory.mkdir());
    ConfigFiles files = new ConfigFiles(directory);
    try {
      files.save("rules: []", "rules: [MATCH,DIRECT]");
      Thread.currentThread().interrupt();
      try {
        assertThrows(IOException.class, () -> files.save("replacement", "replacement"));
      } finally {
        Thread.interrupted();
      }
      assertEquals("rules: []", files.readSource());
      assertTrue(files.hasAdapted());
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      files.copyAdapted(output);
      assertArrayEquals(
          "rules: [MATCH,DIRECT]".getBytes(StandardCharsets.UTF_8), output.toByteArray());
      files.clear();
      assertFalse(files.hasSource());
      assertFalse(files.hasAdapted());
      assertThrows(IOException.class, () -> files.copyAdapted(output));
    } finally {
      files.clear();
      assertTrue(directory.delete());
    }
  }
}
