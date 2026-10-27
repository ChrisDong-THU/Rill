package app.rill.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class LiveStatsTest {
  @Test
  public void computesRateUsingElapsedTimeAndHandlesCounterReset() {
    LiveStats stats = new LiveStats();
    stats.sample(1000, 2000, 2, 8);
    assertEquals(0, stats.downloadRate, 0);
    stats.sample(3000, 6000, 3, 8);
    assertEquals(2000, stats.downloadRate, 0);
    assertEquals("3/8", stats.connectionsText);
    assertEquals("2 KB/s", stats.downloadText);
    stats.sample(4000, 100, 0, 4);
    assertEquals(0, stats.downloadRate, 0);
    stats.sample(4000, 200, 0, 4);
    assertEquals(0, stats.downloadRate, 0);
  }
}
