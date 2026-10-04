package com.fluidlabel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 便携校验器：直接编译 {@link DistinctMds} 与本文件，比对 Java 移植版与 Python 生成器的输出。
 *
 * <p>为什么要单独跑：DYNAMIC 档的标签来自 {@code DistinctMds.assignLabels}（不是预生成表），
 * 只要两侧算法不完全同构，同一个液体会在 STABLE/DYNAMIC 之间换名字，或运行时给出与表里不同
 * 的风格（旧版本就是因此把 `石脑油` 显示成 `脑`）。这里把 JSON 的 byDisplayName 全量喂进
 * Java 版，逐条比对。
 *
 * <p>用法（无需 Gradle / Minecraft）：
 * <pre>
 *   javac -d out src/main/java/com/fluidlabel/DistinctMds.java tools/parity/ParityCheck.java
 *   java -cp out com.fluidlabel.ParityCheck src/main/resources/fluidlabel_labels.json
 * </pre>
 */
public final class ParityCheck {

    public static void main(String[] args) throws IOException {
        Path json = Path.of(args[0]);
        Map<String, String> expected = readByDisplayName(json);

        // 人工关键字层（tools/anchors.json）不是算法的一部分 —— 它是生成器与运行时的覆盖层，
        // 所以这些条目从"算法同构性"里剔除，单独计数。
        Map<String, String> anchors = new LinkedHashMap<>();
        if (args.length > 1) {
            Path ap = Path.of(args[1]);
            if (Files.exists(ap)) {
                anchors.putAll(readByDisplayName(ap));
            }
        }

        Map<String, String> nameByKey = new LinkedHashMap<>();
        for (String name : expected.keySet()) {
            nameByKey.put(name, name);
        }

        Map<String, String> got = DistinctMds.assignLabels(nameByKey);

        int ok = 0;
        int skipped = 0;
        List<String> bad = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            if (anchors.containsKey(e.getKey())) {
                skipped++;
                continue;                       // 人工覆盖，不参与算法 parity
            }
            String g = got.get(e.getKey());
            if (e.getValue().equals(g)) {
                ok++;
            } else {
                bad.add(e.getKey() + "   python=" + e.getValue() + "   java=" + g);
            }
        }

        int compared = expected.size() - skipped;
        Set<String> distinct = new HashSet<>(got.values());
        System.out.println("byDisplayName entries : " + expected.size());
        System.out.println("anchors (skipped)     : " + skipped);
        System.out.println("java labels produced  : " + got.size());
        System.out.println("parity (exact string) : " + ok + "/" + compared);
        System.out.println("java distinct labels  : " + distinct.size() + "/" + got.size());
        for (int i = 0; i < Math.min(30, bad.size()); i++) {
            System.out.println("  MISMATCH " + bad.get(i));
        }
        if (bad.size() > 30) {
            System.out.println("  ... +" + (bad.size() - 30) + " more");
        }
        System.out.println(bad.isEmpty() ? "PARITY OK" : "PARITY FAILED");
    }

    /** 只解析 byDisplayName 段（生成器写的是"每行一条 key: value"的紧凑 JSON）。 */
    private static Map<String, String> readByDisplayName(Path json) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        boolean inside = false;
        for (String line : Files.readAllLines(json, StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (!inside) {
                if (t.startsWith("\"byDisplayName\"")) {
                    inside = true;
                }
                continue;
            }
            if (t.startsWith("}")) {
                break;
            }
            int q1 = t.indexOf('"');
            int q2 = t.indexOf('"', q1 + 1);
            int q3 = t.indexOf('"', q2 + 1);
            int q4 = t.indexOf('"', q3 + 1);
            if (q1 < 0 || q2 < 0 || q3 < 0 || q4 < 0) {
                continue;
            }
            out.put(t.substring(q1 + 1, q2), t.substring(q3 + 1, q4));
        }
        return out;
    }

    private ParityCheck() {
    }
}
