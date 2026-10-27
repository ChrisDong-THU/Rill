package app.rill.android;

import static org.junit.Assert.*;

import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
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
      assertEquals("rules: [MATCH,DIRECT]", files.readAdapted());
      files.clear();
      assertFalse(files.hasSource());
      assertThrows(IOException.class, files::readAdapted);
    } finally {
      files.clear();
      assertTrue(directory.delete());
    }
  }
}
