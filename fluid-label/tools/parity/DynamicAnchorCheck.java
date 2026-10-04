package com.fluidlabel;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 离线测试：DYNAMIC 档的人工锚点叠加层（{@link DistinctMds#applyAnchors}）。
 *
 * <p>只依赖 JDK + {@code DistinctMds}（不碰 Minecraft/EMI），因此可以脱离游戏运行：
 * <pre>
 * javac -encoding UTF-8 -d out src\main\java\com\fluidlabel\DistinctMds.java tools\parity\DynamicAnchorCheck.java
 * java '-Dfile.encoding=UTF-8' -cp out com.fluidlabel.DynamicAnchorCheck
 * </pre>
 *
 * <p>覆盖四个断言：
 * <ol>
 *   <li>锚点值确实覆盖了重算结果（含"注册名键"与"显示名键"两条）；</li>
 *   <li>未命中锚点的条目<b>保持</b>原标签；</li>
 *   <li>非锚点条目的标签若与锚点值相撞，会被重算成别的值（不变量：两两互异）；</li>
 *   <li>真实语料（表 + 随包锚点）跑一遍 {@code assignLabels} + {@code applyAnchors}，
 *       最终每个不同显示名的标签互异，且全部锚点都在结果里生效。</li>
 * </ol>
 */
public final class DynamicAnchorCheck {

    private static int failures = 0;

    public static void main(String[] args) throws IOException {
        Path table = args.length > 0 ? Paths.get(args[0])
                : Paths.get("src", "main", "resources", "fluidlabel_labels.json");
        Path bundled = args.length > 1 ? Paths.get(args[1])
                : Paths.get("src", "main", "resources", "fluidlabel_anchors.json");

        case1_anchorOverrides();
        case2_untouched();
        case3_collisionResolved();

        Map<String, String> anchors = readStringMap(bundled, "byDisplayName");
        System.out.println("bundled anchors       : " + anchors.size());
        if (!anchors.isEmpty()) {
            case4_realCorpus(anchors);
        } else {
            System.out.println("[warn] 没读到随包锚点，跳过 case4");
        }

        System.out.println(failures == 0 ? "VERDICT: ALL PASS" : "VERDICT: " + failures + " FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    /** 1) 锚点覆盖：注册名键与显示名键都要变成锚点值。 */
    private static void case1_anchorOverrides() {
        Map<String, String> nameByKey = new LinkedHashMap<>();
        nameByKey.put("gtceu:boron_carbide", "碳化硼复合防护陶瓷");
        Map<String, String> assigned = DistinctMds.assignLabels(nameByKey);
        String auto = assigned.get("gtceu:boron_carbide");

        Map<String, String> anchors = new HashMap<>();
        anchors.put("碳化硼复合防护陶瓷", "碳化硼陶");
        Map<String, String> out = DistinctMds.applyAnchors(assigned, nameByKey, anchors);

        check("case1 自动值不等于锚点（前置条件）", !"碳化硼陶".equals(auto), "auto=" + auto);
        check("case1 注册名键 = 锚点值", "碳化硼陶".equals(out.get("gtceu:boron_carbide")),
                String.valueOf(out.get("gtceu:boron_carbide")));
        check("case1 显示名键也已写入锚点值",
                "碳化硼陶".equals(out.get("碳化硼复合防护陶瓷")),
                String.valueOf(out.get("碳化硼复合防护陶瓷")));
    }

    /** 2) 未命中锚点的条目保持原标签（叠加层不许"顺手"改动别的液体）。 */
    private static void case2_untouched() {
        Map<String, String> nameByKey = new LinkedHashMap<>();
        nameByKey.put("a", "碳化硼复合防护陶瓷");
        nameByKey.put("b", "溢满的钆纳米萃取树脂");
        Map<String, String> assigned = DistinctMds.assignLabels(nameByKey);
        String untouched = assigned.get("b");

        Map<String, String> anchors = new HashMap<>();
        anchors.put("碳化硼复合防护陶瓷", "碳化硼陶");
        Map<String, String> out = DistinctMds.applyAnchors(assigned, nameByKey, anchors);

        check("case2 未命中锚点者不变", untouched.equals(out.get("b")),
                "before=" + untouched + " after=" + out.get("b"));
    }

    /** 3) 冲突消解：非锚点条目的标签若正好等于某个锚点值，必须被改掉且不与任何标签重复。 */
    private static void case3_collisionResolved() {
        Map<String, String> nameByKey = new LinkedHashMap<>();
        nameByKey.put("a", "碳化硼复合防护陶瓷");
        nameByKey.put("b", "某个别的长名字复合材料");
        Map<String, String> assigned = new LinkedHashMap<>();
        assigned.put("a", "碳化硼陶");
        assigned.put("b", "碳化硼陶");     // 人为制造"与锚点值撞车"的场景
        assigned.put("某个别的长名字复合材料", "碳化硼陶");

        Map<String, String> anchors = new HashMap<>();
        anchors.put("碳化硼复合防护陶瓷", "碳化硼陶");
        Map<String, String> out = DistinctMds.applyAnchors(assigned, nameByKey, anchors);

        check("case3 锚点方拿到锚点值", "碳化硼陶".equals(out.get("a")), String.valueOf(out.get("a")));
        check("case3 撞车方被改掉", !"碳化硼陶".equals(out.get("b")), String.valueOf(out.get("b")));
        // 不变量是"不同显示名 → 不同标签"（同名会有多个键，本来就该共用同一标签）
        Map<String, String> byName = new LinkedHashMap<>();
        byName.put("碳化硼复合防护陶瓷", out.get("a"));
        byName.put("某个别的长名字复合材料", out.get("b"));
        check("case3 不同显示名之间两两互异",
                new HashSet<>(byName.values()).size() == byName.size(), byName.toString());
        check("case3 同一个名字的两个键标签一致",
                java.util.Objects.equals(out.get("b"), out.get("某个别的长名字复合材料")),
                out.get("b") + " vs " + out.get("某个别的长名字复合材料"));
    }

    /** 4) 真实语料：全表名字重算一次，再叠加随包锚点，检查互异性与锚点生效。 */
    private static void case4_realCorpus(Map<String, String> anchors) throws IOException {
        Map<String, String> display = readStringMap(
                Paths.get("src", "main", "resources", "fluidlabel_labels.json"), "byDisplayName");
        System.out.println("table display names   : " + display.size());

        Map<String, String> nameByKey = new LinkedHashMap<>();
        int i = 0;
        for (String name : display.keySet()) {
            nameByKey.put("k" + (i++), name);
        }
        Map<String, String> assigned = DistinctMds.assignLabels(nameByKey);
        Map<String, String> out = DistinctMds.applyAnchors(assigned, nameByKey, anchors);

        Set<String> seen = new HashSet<>();
        List<String> dup = new ArrayList<>();
        for (Map.Entry<String, String> e : nameByKey.entrySet()) {
            String v = out.get(e.getKey());
            if (v == null || v.isEmpty()) {
                dup.add(e.getValue() + "=<empty>");
            } else if (!seen.add(v)) {
                dup.add(e.getValue() + "→" + v);
            }
        }
        check("case4 叠加后两两互异", dup.isEmpty(), dup.size() + " 例：" + first(dup, 5));

        int applied = 0;
        List<String> missed = new ArrayList<>();
        for (Map.Entry<String, String> e : nameByKey.entrySet()) {
            String want = anchors.get(e.getValue());
            if (want != null) {
                if (want.equals(out.get(e.getKey()))) {
                    applied++;
                } else {
                    missed.add(e.getValue() + " want=" + want + " got=" + out.get(e.getKey()));
                }
            }
        }
        check("case4 锚点全部生效", missed.isEmpty(),
                "applied=" + applied + " missed=" + missed.size() + " " + first(missed, 5));
        System.out.println("anchors applied       : " + applied + "/" + anchors.size());
    }

    private static String first(List<String> list, int n) {
        if (list.isEmpty()) {
            return "";
        }
        return String.join(" | ", list.subList(0, Math.min(n, list.size())));
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS  " + what);
        } else {
            failures++;
            System.out.println("FAIL  " + what + "  [" + detail + "]");
        }
    }

    /** 极简 JSON 读取：只取根部某个字符串映射（够用即可，不引入依赖）。 */
    private static Map<String, String> readStringMap(Path path, String key) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.isRegularFile(path)) {
            System.out.println("[warn] 找不到 " + path);
            return out;
        }
        String text;
        try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            text = sb.toString();
        }
        int at = text.indexOf("\"" + key + "\"");
        if (at < 0) {
            return out;
        }
        int open = text.indexOf('{', at);
        if (open < 0) {
            return out;
        }
        int depth = 0;
        int i = open;
        boolean inStr = false;
        boolean esc = false;
        for (; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    break;
                }
            }
        }
        String body = text.substring(open + 1, i);
        // 逐对解析 "k": "v"
        int p = 0;
        while (p < body.length()) {
            int ks = body.indexOf('"', p);
            if (ks < 0) {
                break;
            }
            int ke = endOfString(body, ks + 1);
            if (ke < 0) {
                break;
            }
            String k = unescape(body.substring(ks + 1, ke));
            int vs = body.indexOf('"', ke + 1);
            if (vs < 0) {
                break;
            }
            int ve = endOfString(body, vs + 1);
            if (ve < 0) {
                break;
            }
            out.put(k, unescape(body.substring(vs + 1, ve)));
            p = ve + 1;
        }
        return out;
    }

    private static int endOfString(String s, int from) {
        boolean esc = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (esc) {
                esc = false;
            } else if (c == '\\') {
                esc = true;
            } else if (c == '"') {
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char d = s.charAt(++i);
            switch (d) {
                case 'n': sb.append('\n'); break;
                case 't': sb.append('\t'); break;
                case 'r': sb.append('\r'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'u':
                    if (i + 4 < s.length()) {
                        sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                        i += 4;
                    }
                    break;
                default: sb.append(d);
            }
        }
        return sb.toString();
    }
}
