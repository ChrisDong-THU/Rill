package app.rill.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.representer.Representer;

/** Safe, bounded structural conversion. No credentials or source URL in errors. */
public final class ConfigAdapter {
  public static final int MAX_BYTES = 4 * 1024 * 1024;
  public static final String NODE = "Rill Local", GROUP = "澜流 · 音频";

  private ConfigAdapter() {}

  public static final class Result {
    public final String yaml, node, group;

    Result(String y, String n, String g) {
      yaml = y;
      node = n;
      group = g;
    }
  }

  public static Result adapt(String input) throws IOException {
    return adapt(input, Targets.defaults());
  }

  @SuppressWarnings("unchecked")
  public static Result adapt(String input, Targets targets) throws IOException {
    if (input.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
      throw new IOException("配置文件超过 4 MiB");
    try {
      LoaderOptions load = new LoaderOptions();
      load.setAllowDuplicateKeys(false);
      load.setMaxAliasesForCollections(30);
      load.setNestingDepthLimit(40);
      load.setCodePointLimit(MAX_BYTES);
      DumperOptions dump = new DumperOptions();
      dump.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      dump.setPrettyFlow(false);
      dump.setIndent(2);
      dump.setIndicatorIndent(0);
      dump.setWidth(160);
      dump.setSplitLines(false);
      Yaml yaml = new Yaml(new SafeConstructor(load), new Representer(dump), dump, load);
      Object root = yaml.load(input);
      if (!(root instanceof Map)) throw new IOException("请导入完整的 Clash YAML 配置");
      Map<Object, Object> map = (Map<Object, Object>) root;
      if (!map.containsKey("proxies")
          && !map.containsKey("proxy-providers")
          && !map.containsKey("rules")
          && !map.containsKey("mixed-port")
          && !map.containsKey("port"))
        throw new IOException("这不是完整的 Clash 配置，节点订阅请先转换为 Clash YAML");
      List<Object> proxies = list(map, "proxies"),
          groups = list(map, "proxy-groups"),
          rules = list(map, "rules");
      Set<String> names = new HashSet<String>();
      for (Object x : proxies) {
        if (!(x instanceof Map)) throw new IOException("节点列表格式不正确");
        Object name = ((Map<?, ?>) x).get("name");
        if (name != null) names.add(String.valueOf(name));
      }
      for (Object x : groups) {
        if (!(x instanceof Map)) throw new IOException("代理组列表格式不正确");
        Object name = ((Map<?, ?>) x).get("name");
        if (name != null) names.add(String.valueOf(name));
      }
      String node = null, group = null;
      // Reuse only a verified local endpoint; never overwrite a same-named user's node.
      for (Object x : proxies) {
        Map<?, ?> p = (Map<?, ?>) x;
        String name = String.valueOf(p.get("name"));
        if ((name.equals(NODE) || name.startsWith(NODE + " "))
            && "http".equals(p.get("type"))
            && "127.0.0.1".equals(p.get("server"))
            && "18889".equals(String.valueOf(p.get("port")))
            && !Boolean.TRUE.equals(p.get("tls"))
            && !p.containsKey("username")
            && !p.containsKey("password")) {
          node = name;
          break;
        }
      }
      if (node == null) {
        node = unique(NODE, names);
        Map<String, Object> local = new LinkedHashMap<String, Object>();
        local.put("name", node);
        local.put("type", "http");
        local.put("server", "127.0.0.1");
        local.put("port", 18889);
        local.put("tls", false);
        proxies.add(0, local);
        names.add(node);
      }
      for (Object x : groups) {
        Map<?, ?> g = (Map<?, ?>) x;
        String name = String.valueOf(g.get("name"));
        if ((name.equals(GROUP) || name.startsWith(GROUP + " "))
            && "select".equals(g.get("type"))
            && Arrays.asList(node, "DIRECT").equals(g.get("proxies"))) {
          group = name;
          break;
        }
      }
      if (group == null) {
        group = unique(GROUP, names);
        Map<String, Object> g = new LinkedHashMap<String, Object>();
        g.put("name", group);
        g.put("type", "select");
        g.put("proxies", new ArrayList<String>(Arrays.asList(node, "DIRECT")));
        groups.add(0, g);
      }
      final String ownGroup = group;
      rules.removeIf(
          value -> {
            if (!(value instanceof String)) return false;
            String[] fields = ((String) value).split(",");
            return fields.length >= 3
                && (fields[0].equals("DOMAIN") || fields[0].equals("IP-CIDR"))
                && fields[2].equals(ownGroup);
          });
      Set<String> added = new LinkedHashSet<String>();
      for (Targets.Origin origin : targets.origins) added.add(origin.rule(group));
      rules.addAll(0, added);
      map.put("mode", "rule");
      String output =
          "# Adapted locally by Rill. Import into Clash; bypass the Rill app.\n" + yaml.dump(map);
      if (output.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
        throw new IOException("适配后的配置文件超过 4 MiB");
      // Parse our output again before delivery; ensures it remains valid YAML.
      if (!(yaml.load(output) instanceof Map)) throw new IOException("配置生成失败");
      return new Result(output, node, group);
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("YAML 格式不正确，或包含重复字段 / 不支持的标签");
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Object> list(Map<Object, Object> m, String key) throws IOException {
    Object o = m.get(key);
    if (o == null) {
      List<Object> a = new ArrayList<Object>();
      m.put(key, a);
      return a;
    }
    if (!(o instanceof List)) throw new IOException(key + " 必须为列表");
    return (List<Object>) o;
  }

  private static String unique(String base, Set<String> names) {
    String value = base;
    for (int n = 2; names.contains(value); n++) value = base + " " + n;
    return value;
  }
}
