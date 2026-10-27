package app.rill.core;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;

public class ConfigAdapterTest {
  private static final String CONFIG =
      "mixed-port: 7890\nproxies: []\nproxy-groups: []\nrules:\n- MATCH,DIRECT\n";

  @Test
  public void repeatedAdaptationReusesLocalNodeAndGroup() throws Exception {
    Targets targets = Targets.parse("example.com\n127.0.0.2:8080");
    ConfigAdapter.Result first = ConfigAdapter.adapt(CONFIG, targets);
    ConfigAdapter.Result second = ConfigAdapter.adapt(first.yaml, targets);
    assertEquals(first.yaml, second.yaml);
    Map<?, ?> root = new Yaml().load(first.yaml);
    assertEquals("rule", root.get("mode"));
    assertEquals(1, ((List<?>) root.get("proxies")).size());
    assertEquals(1, ((List<?>) root.get("proxy-groups")).size());
    assertTrue(((List<?>) root.get("rules")).get(0).toString().startsWith("DOMAIN,example.com,"));
  }

  @Test
  public void preservesCollidingUserNodeAndExistingRules() throws Exception {
    String original =
        "proxies:\n"
            + "- {name: Rill Local, type: http, server: remote.example, port: 80}\n"
            + "rules:\n"
            + "- MATCH,DIRECT\n";
    ConfigAdapter.Result result = ConfigAdapter.adapt(original);
    assertEquals("Rill Local 2", result.node);
    Map<?, ?> root = new Yaml().load(result.yaml);
    List<?> nodes = (List<?>) root.get("proxies");
    assertEquals("remote.example", ((Map<?, ?>) nodes.get(1)).get("server"));
    assertTrue(((List<?>) root.get("rules")).contains("MATCH,DIRECT"));
  }

  @Test
  public void replacesOnlyOwnedTargetRules() throws Exception {
    String original = "rules:\n- DOMAIN,keep.example,DIRECT\n- MATCH,DIRECT\n";
    String first = ConfigAdapter.adapt(original, Targets.parse("old.example")).yaml;
    String updated = ConfigAdapter.adapt(first, Targets.parse("new.example")).yaml;
    assertFalse(updated.contains("old.example"));
    assertTrue(updated.contains("new.example"));
    assertTrue(updated.contains("DOMAIN,keep.example,DIRECT"));
  }

  @Test
  public void rejectsOutputThatWouldExceedTheImportLimit() {
    StringBuilder config = new StringBuilder("rules: []\npadding: '");
    while (config.length() < ConfigAdapter.MAX_BYTES - 10) config.append('x');
    config.append("'\n");
    assertThrows(IOException.class, () -> ConfigAdapter.adapt(config.toString()));
  }

  @Test
  public void rejectsMalformedAndUnsafeYaml() {
    for (String input :
        new String[] {
          "hello",
          "rules: []\nrules: []",
          "!!java.net.URL [https://example.com]",
          "proxies: wrong",
          "random: value"
        }) {
      assertThrows(IOException.class, () -> ConfigAdapter.adapt(input));
    }
  }
}
