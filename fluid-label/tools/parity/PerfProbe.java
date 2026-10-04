package com.fluidlabel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 性能探针（离线，纯逻辑层）：量化"标签引擎"在渲染热路径与周期路径上的成本。
 *
 * <p><b>能测什么</b>：{@link DistinctMds} 的全部算法（DYNAMIC 快照重建、锚点叠加、
 * 运行时兜底）以及标签表的查表成本。
 * <b>测不了什么</b>：① GL/Font 的绘制成本（{@code GuiGraphics.drawString}）；
 * ② {@code ResourceLocation.tryParse}（需要 MC 类，见报告中的"未量化项"）。
 * 这两项只能在实机帧率里体现。
 *
 * <p><b>查表口径</b>：{@code LabelTables} 的字段是 {@code volatile Map} 包着
 * {@code Collections.unmodifiableMap(HashMap)}（见 {@code LabelTables.java:43-45}），
 * 本工具用同样的结构复现，唯一的差异是少了那次 volatile 读（~1 ns 量级）。
 * {@code LabelTables.reload()} 不能离线调（方法体引用 {@code FluidLabelConfig}，
 * 需要 Forge 配置类）—— 它是一次性成本，本工具不测。
 *
 * <p>跑法（JDK 21，与其它 parity 工具一致）：
 * <pre>
 * javac -encoding UTF-8 -nowarn -cp build\classes\java\main -d build\gates tools\parity\PerfProbe.java
 * java "-Dfile.encoding=UTF-8" -cp "build\gates;build\classes\java\main" com.fluidlabel.PerfProbe
 * </pre>
 */
public final class PerfProbe {

    private static final Pattern PAIR = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
    private static volatile long SINK;

    public static void main(String[] args) throws Exception {
        Path tablePath = Paths.get(args.length > 0 ? args[0] : "src/main/resources/fluidlabel_labels.json");
        Path anchorsPath = Paths.get(args.length > 1 ? args[1] : "src/main/resources/fluidlabel_anchors.json");
        String json = new String(Files.readAllBytes(tablePath), StandardCharsets.UTF_8);
        String anchorsJson = new String(Files.readAllBytes(anchorsPath), StandardCharsets.UTF_8);

        System.out.println("=== PerfProbe  JVM=" + System.getProperty("java.version")
                + "  os=" + System.getProperty("os.name") + "  cores=" + Runtime.getRuntime().availableProcessors() + " ===");

        List<String> names = new ArrayList<>(section(json, "byDisplayName").keySet());
        Map<String, String> registryLabels = section(json, "byRegistryName");
        Map<String, String> anchors = section(anchorsJson, "byDisplayName");

        // 复现 LabelTables 的真实结构：unmodifiableMap(HashMap)
        Map<String, String> tableByDisplayName = Collections.unmodifiableMap(new HashMap<>(section(json, "byDisplayName")));
        Map<String, String> tableByRegistryName = Collections.unmodifiableMap(new HashMap<>(registryLabels));
        Map<String, String> aliases = Collections.emptyMap();
        System.out.printf("语料：显示名 %d 条、注册名 %d 条、随包锚点 %d 条（表 %d 字符 / %.1f KB）%n%n",
                names.size(), registryLabels.size(), anchors.size(), json.length(), json.length() / 1024.0);

        // ---------------------------------------------------------------- 1. 查表（每槽每帧）
        bench("labelByRegistryName（每槽每帧 1 次）", 100, () -> {
            long acc = 0;
            for (String id : registryLabels.keySet()) {
                String v = tableByRegistryName.get(id);
                acc += v == null ? 0 : v.length();
            }
            return acc;
        }, registryLabels.size());

        bench("labelByDisplayName（注册名 miss 时才走）", 100, () -> {
            long acc = 0;
            for (String n : names) {
                String v = tableByDisplayName.get(n);
                acc += v == null ? 0 : v.length();
            }
            return acc;
        }, names.size());

        bench("aliasLabel（最高优先级，每槽每帧 1 次；默认为空表）", 100, () -> {
            long acc = 0;
            for (String n : names) {
                String v = aliases.get(n);
                acc += v == null ? 0 : v.length();
            }
            return acc;
        }, names.size());

        // ---------------------------------------------------------------- 2. 渲染热路径的"纯逻辑"部分
        Map<String, String> snapshot = new ConcurrentHashMap<>(registryLabels);
        List<String> regIds = new ArrayList<>(registryLabels.keySet());
        bench("CHM 快照 get + 布局键 String 拼接（每槽每帧各 1 次）", 100, () -> {
            long acc = 0;
            for (int i = 0; i < 200; i++) {
                String id = regIds.get(i % regIds.size());
                String name = names.get(i % names.size());
                String v = snapshot.get(id);
                acc += v == null ? 0 : v.length();
                String key = name + '\u0001' + 1.0F + '\u0001' + 3 + '\u0001' + 2; // LabelRenderer.layoutOf 的键
                acc += key.length();
            }
            return acc;
        }, 200);

        // 每帧 N 个槽的"除绘制外"全链路（查表 3 次 + 快照 1 次 + 键拼接 1 次）
        for (int slots : new int[] {1, 8, 32, 128}) {
            final int n = slots;
            bench(String.format("每帧 %d 个槽的纯逻辑全链路（不含 GL/解析）", n), 200, () -> {
                long acc = 0;
                for (int i = 0; i < n; i++) {
                    String name = names.get(i % names.size());
                    String id = regIds.get(i % regIds.size());
                    String a = aliases.get(name);
                    String b = tableByRegistryName.get(id);
                    String c = a != null ? a : (b != null ? b : tableByDisplayName.get(name));
                    acc += c == null ? 0 : c.length();
                    String s = snapshot.get(id);
                    acc += s == null ? 0 : s.length();
                    String key = name + '\u0001' + 1.0F + '\u0001' + 3 + '\u0001' + 2;
                    acc += key.length();
                }
                return acc;
            }, n);
        }

        // ---------------------------------------------------------------- 3. DYNAMIC 快照重建（每 epoch 一次，≤20 Hz）
        System.out.println();
        Random rnd = new Random(20261002L);
        List<String> shuffled = new ArrayList<>(names);
        Collections.shuffle(shuffled, rnd);
        for (int n : new int[] {16, 50, 100, 200, 500, 1898}) {
            final int size = Math.min(n, shuffled.size());
            final List<String> sample = new ArrayList<>(shuffled.subList(0, size));
            bench(String.format("DistinctMds.assignLabels（DYNAMIC 重建，可见 %d 个液体）", size), 9, () -> {
                Map<String, String> in = new LinkedHashMap<>();
                for (int i = 0; i < size; i++) {
                    in.put("k" + i, sample.get(i));
                }
                return DistinctMds.assignLabels(in).size();
            }, 1);
        }

        // ---------------------------------------------------------------- 4. 锚点叠加（每次重建后一次）
        Map<String, String> in200 = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            in200.put("k" + i, names.get(i % names.size()));
        }
        Map<String, String> assigned200 = DistinctMds.assignLabels(in200);
        bench("DistinctMds.applyAnchors（83 条锚点 / 可见 200 个）", 20, () -> {
            return DistinctMds.applyAnchors(assigned200, in200, anchors).size();
        }, 1);

        // ---------------------------------------------------------------- 5. 运行时兜底（每个新液体一次，非每帧）
        Set<String> taken = new HashSet<>(assigned200.values());
        List<String> fresh = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            fresh.add("熔融未知合金-" + i + "型");   // 表里没有的派生名
        }
        bench("DistinctMds.labelForName（冷：每个新名字一次）", 1, () -> {
            long acc = 0;
            for (String f : fresh) {
                acc += DistinctMds.labelForName(f, taken).length();
            }
            return acc;
        }, fresh.size());

        bench("DistinctMds.labelForName（热：同一个名字反复问）", 200, () -> {
            long acc = 0;
            for (String f : fresh) {
                acc += DistinctMds.labelForName(f, taken).length();
            }
            return acc;
        }, fresh.size());

        // ---------------------------------------------------------------- 6. 对照：纯 Map 分配/GC 压力
        Runtime rt = Runtime.getRuntime();
        bench("对照：每轮新建 4096 个 String 键（模拟 4096 槽次/秒的分配量）", 20, () -> {
            long acc = 0;
            for (int i = 0; i < 4096; i++) {
                String key = names.get(i % names.size()) + '\u0001' + 1.0F + '\u0001' + 3 + '\u0001' + 2;
                acc += key.length();
            }
            return acc;
        }, 4096);
        System.out.printf("%n堆：max=%.0f MB  used=%.1f MB%n",
                rt.maxMemory() / 1048576.0, (rt.totalMemory() - rt.freeMemory()) / 1048576.0);

        System.out.println();
        System.out.println("SINK=" + SINK + "（防止 JIT 把测量循环优化掉）");
        System.out.println("=== 说明：以上全是 CPU 侧纯逻辑；GuiGraphics/Font 的绘制与 ResourceLocation.tryParse 不在此列。===");
    }

    /** 截取 JSON 里某个顶层对象段，按 "key": "value" 抽出（本工程的表结构是扁平的）。 */
    private static Map<String, String> section(String json, String name) {
        Map<String, String> out = new LinkedHashMap<>();
        int start = json.indexOf("\"" + name + "\"");
        if (start < 0) {
            return out;
        }
        int brace = json.indexOf('{', start);
        if (brace < 0) {
            return out;
        }
        int depth = 0;
        int end = json.length();
        for (int i = brace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    end = i;
                    break;
                }
            }
        }
        Matcher m = PAIR.matcher(json.substring(brace, end));
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    private interface Body {
        long run();
    }

    /** 计时：先热身，再取 rounds 次的单次耗时中位数。 */
    private static void bench(String title, int rounds, Body body, int itemsPerRun) {
        for (int i = 0; i < Math.max(2, rounds / 3); i++) {
            SINK += body.run();
        }
        double[] ms = new double[rounds];
        for (int i = 0; i < rounds; i++) {
            long a = System.nanoTime();
            SINK += body.run();
            long b = System.nanoTime();
            ms[i] = (b - a) / 1e6;
        }
        Arrays.sort(ms);
        double median = ms[rounds / 2];
        double perItemUs = itemsPerRun > 0 ? median * 1000.0 / itemsPerRun : 0.0;
        System.out.printf("%-58s 中位 %8.3f ms / 轮   每次 %8.3f µs%n", title, median, perItemUs);
    }
}
