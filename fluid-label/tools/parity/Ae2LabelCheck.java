package com.fluidlabel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AE2 侧离线门禁（PLAN §6.7.A）—— 与 {@link DynamicAnchorCheck} / DerivedLabelCheck 同风格的
 * 纯函数测试：<b>只依赖 JDK + {@link DistinctMds}</b>，不碰 Minecraft/AE2/EMI。
 *
 * <p>为什么 AE2 侧需要一个独立的离线门禁：注入点 3/4 的"存在性"只能在运行期测（由
 * {@code Ae2SelfCheck} 兜），但<b>标签规则的正确性</b>完全可以在离线验证 —— AE2 路径与 EMI
 * 路径共用同一个 {@code LabelEngine.labelFor}，所以只要"GT 的 lang 模板派生名"走通规则阶梯，
 * 两侧的标签必然逐字相同（PLAN §6.3）。
 *
 * <p>四条断言（与 PLAN §6.7.A 逐条对应）：
 * <ol>
 *   <li>① 每条标签 ≤ 6 码点（渲染端预算 = {@code maxChars(3) × maxLines(2)}，超出的会被静默截断）；</li>
 *   <li>② 同族（同一状态模板派生的一组名）内标签两两互异。<b>入口必须是
 *       {@link DistinctMds#labelForName}（= 渲染路径真正调用的那个）</b>，不能用整池分配器
 *       {@code assignLabels}：后者的"无解条目"收尾会把**超长原名**原样写进结果（生成器允许，
 *       渲染路径不允许），拿它当渲染门禁会报出一堆假失败 —— 2026-10 实测：把 lang 里 13357 条
 *       机器名（含 {@code §} 颜色码）当液体基名喂进去，{@code assignLabels} 报 1793 条
 *       "超长/空"，而同一批数据走 {@code labelForName} 一条都不超。</li>
 *   <li>③ parity：同一 {@code (id, name)} 连续两次进同一条目得到同一标签（确定性）；
 *       另加人工锚点（{@code tools/anchors.json}）必须 83/83 仍生效；</li>
 *   <li>④ 逐字样例：{@code 熔融高速钢-G → 熔高速钢-G}、{@code 熔融铝合金-2090 → 熔铝2090}
 *       （与 PLAN §5.4 的用户样例逐字一致）。</li>
 * </ol>
 *
 * <p>输入语料：{@code ..\_gtodata\assets\{gtceu,gtocore}\lang\zh_cn.json} 里键前缀为
 * {@code material.} / {@code fluid.} 的**值**（= GT 液体显示名的真实来源；含 {@code %s} 的模板值
 * 会被丢弃，因为模板不是显示名）+ §5.4 的 5 个状态模板（{@code 熔融%s} / {@code 液态%s} /
 * {@code 气态%s} / {@code %s等离子体} / {@code %s蒸气}）派生。<b>语料文件不存在时打印 SKIP
 * 并跳过该步（不报 FAIL）</b>：门禁的判据是"确定性 + 预算 + 互异"，语料只是放大样本。
 *
 * <p><b>为什么必须过滤键前缀</b>：lang 里 13357 条字符串值绝大多数是 GT 机器/GUI 文案
 * （{@code "gtceu.machine.fusion_reactor.uv.tier" : "§5高级电路组装机 III§r"}），把它们当液体基名
 * 套状态模板，得到的是现实中不存在的名字，门禁于是变成了在测"渲染层对机器名友不友好" ——
 * 而 AE2 路径只会拿到 {@code AEFluidKey}（真实液体），永远拿不到机器名（2026-10 实测教训）。
 *
 * <p>运行方式（PLAN §6.7.A）：
 * <pre>
 * javac -encoding UTF-8 -d out src\main\java\com\fluidlabel\DistinctMds.java tools\parity\Ae2LabelCheck.java
 * java '-Dfile.encoding=UTF-8' -cp out com.fluidlabel.Ae2LabelCheck
 * </pre>
 */
public final class Ae2LabelCheck {

    /** 标签预算（PLAN §5.5：按码点计；= DistinctMds.MAX_LEN）。 */
    private static final int BUDGET = DistinctMds.MAX_LEN;

    /** 失败清单最多打印多少条（避免刷屏）。 */
    private static final int MAX_PRINT = 20;

    /** 液体显示名的真实来源：lang 里这些前缀的键，其值才是"可能是液体名"的字符串。 */
    private static final String[] LANG_KEY_PREFIXES = {"material.", "fluid."};

    /** §5.4 的 5 个 GT 状态模板（{@code gtceu.fluid.*}）。 */
    private static final String[][] TEMPLATES = {
        {"熔融", "熔融%s"},
        {"液态", "液态%s"},
        {"气态", "气态%s"},
        {"等离子体", "%s等离子体"},
        {"蒸气", "%s蒸气"},
    };

    private static int failures = 0;
    private static int printed = 0;

    public static void main(String[] args) throws IOException {
        Path langDir = args.length > 0 ? Paths.get(args[0])
                : Paths.get("..", "_gtodata", "assets");

        // ---------------------------------------------------------- 语料
        Set<String> names = new LinkedHashSet<>();
        int langFiles = 0;
        int skippedFiles = 0;
        int rawValues = 0;
        List<String> langPaths = new ArrayList<>();
        langPaths.add("gtceu/lang/zh_cn.json");
        langPaths.add("gtocore/lang/zh_cn.json");
        for (String rel : langPaths) {
            Path p = langDir.resolve(rel);
            if (!Files.isRegularFile(p)) {
                System.out.println("[SKIP] 语料不存在：" + p.toAbsolutePath());
                skippedFiles++;
                continue;
            }
            Map<String, String> all = readFlatStringMap(p, null);
            Set<String> values = fluidishValues(all);
            names.addAll(values);
            rawValues += all.size();
            langFiles++;
            System.out.println("corpus        : " + rel + " → " + all.size() + " 个键值对，其中 "
                    + values.size() + " 条 material.*/fluid.* 显示名");
        }

        // ---------------------------------------------------------- 派生名
        Map<String, Set<String>> byTemplate = new LinkedHashMap<>();
        Set<String> derived = new LinkedHashSet<>();
        for (String[] t : TEMPLATES) {
            byTemplate.put(t[1], new LinkedHashSet<>());
        }
        for (String name : names) {
            if (name.isEmpty()) {
                continue;
            }
            derived.add(name);                                  // 基名本身也算一条（generic 模板）
            for (String[] t : TEMPLATES) {
                // 模板形如 "熔融%s"（前缀）或 "%s等离子体"（后缀）——两者的 [0] 都是中文词缀。
                String d = t[1].startsWith("%s")
                        ? name + t[1].substring(2)
                        : t[0] + name;
                byTemplate.get(t[1]).add(d);
                derived.add(d);
            }
        }
        System.out.println("corpus files  : " + langFiles + "（跳过 " + skippedFiles
                + "；lang 键值对合计 " + rawValues + "）");
        System.out.println("base names    : " + names.size());
        System.out.println("derived names : " + derived.size());

        if (derived.isEmpty()) {
            System.out.println("[SKIP] 没有可用语料 ⇒ 只跑「用户样例 + 锚点」两条断言。");
        } else {
            checkBudget(derived);
            checkDistinctPerFamily(byTemplate);
            checkDeterminism(derived);
        }

        checkUserCases();
        checkAnchors();
        checkCacheIsolation();

        System.out.println("failures      : " + failures);
        System.out.println(failures == 0 ? "VERDICT: ALL PASS" : "VERDICT: " + failures + " FAILED");
        if (failures != 0) {
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ ① 预算

    /** ① 每条标签 ≤ 6 码点：超出的会被渲染端按 3 字 × 2 行静默截断（§5.2.1 的老坑）。 */
    private static void checkBudget(Set<String> derived) {
        Set<String> taken = new HashSet<>();
        int checked = 0;
        int overBudget = 0;
        int worst = 0;
        int withColorCode = 0;
        for (String name : derived) {
            String label = DistinctMds.labelForName(name, taken);
            checked++;
            if (cpLen(label) > BUDGET) {
                overBudget++;
                fail("① 预算超长：" + name + " → `" + label + "`（" + cpLen(label) + " 码点）");
            }
            if (label == null || label.isEmpty()) {
                fail("① 空标签：" + name);
            } else {
                worst = Math.max(worst, cpLen(label));
                if (label.indexOf('§') >= 0) {
                    withColorCode++;
                    fail("① 标签含颜色码：" + name + " → `" + label + "`");
                }
            }
            taken.add(label);
        }
        System.out.println("① budget      : " + checked + " 条派生名逐条 labelForName，超长 "
                + overBudget + " 条，最长 " + worst + " 码点（预算 " + BUDGET + "），含颜色码 "
                + withColorCode + " 条");
    }

    // ------------------------------------------------------------------ ② 同族互异

    /**
     * ② 同一状态模板派生的一组名字，标签必须两两互异（同屏最坏情况就是同族）。
     *
     * <p><b>入口是 {@link DistinctMds#labelForName}</b>（渲染路径真正调用的那个），按族内出现顺序
     * 逐个占位 —— 与运行期"第一次遇到某液体时在已占用集合里挑标签"的行为同构。这里**不能**用
     * {@code assignLabels}：它的收尾对"无解条目"允许写回超长原名（生成器口径），会报假失败。
     *
     * <p>判据用 <b>{@code visible(label)}</b>（截到预算的可见形式）而不是标签字符串本身 ——
     * 渲染端只画 {@code maxChars × maxLines} 个码点，两条"完整字符串互异但画出来同形"的标签
     * 在屏幕上就是同一个（§5.2.1 的实测老坑）。
     */
    private static void checkDistinctPerFamily(Map<String, Set<String>> byTemplate) {
        for (Map.Entry<String, Set<String>> e : byTemplate.entrySet()) {
            Set<String> family = e.getValue();
            if (family.isEmpty()) {
                continue;
            }
            Set<String> taken = new HashSet<>();
            Map<String, String> visToName = new LinkedHashMap<>();
            int collisions = 0;
            int overBudget = 0;
            int worst = 0;
            for (String n : family) {
                String label = DistinctMds.labelForName(n, taken);
                if (label == null || label.isEmpty() || cpLen(label) > BUDGET) {
                    overBudget++;
                    fail("② 族内超长/空（" + e.getKey() + "）：" + n + " → `" + label + "`");
                    continue;
                }
                worst = Math.max(worst, cpLen(label));
                String vis = visible(label);
                String prev = visToName.put(vis, n);
                if (prev != null) {
                    collisions++;
                    fail("② 族内同形（" + e.getKey() + "）：`" + vis + "` ← " + prev + " / " + n);
                }
                taken.add(label);
            }
            System.out.println("② family      : 模板 `" + e.getKey() + "` → " + family.size()
                    + " 条，可见形式撞车 " + collisions + " 组，超长 " + overBudget
                    + " 条，最长标签 " + worst + " 码点");
        }
    }

    // ------------------------------------------------------------------ ③ 确定性

    /** ③ 同一条目连续两次得到同一标签（AE2 侧与 EMI 侧共用该入口 ⇒ 由构造保证跨界面一致）。 */
    private static void checkDeterminism(Set<String> derived) {
        Map<String, String> nameByKey = new LinkedHashMap<>();
        for (String n : derived) {
            nameByKey.put(n, n);
        }
        Map<String, String> first = DistinctMds.assignLabels(nameByKey);
        Map<String, String> second = DistinctMds.assignLabels(nameByKey);
        int mismatches = 0;
        for (String n : derived) {
            String a = first.get(n);
            String b = second.get(n);
            if (a == null ? b != null : !a.equals(b)) {
                mismatches++;
                fail("③ 非确定性：" + n + " → `" + a + "` vs `" + b + "`");
            }
        }
        // 同一条目在同一池里再查一次（"同一液体在两帧之间不该换名字"）。判据只在
        // 同一 taken 集合下成立：labelForName 的输出依赖"已被占用的标签"这个入参
        // （与生成器的分配顺序同构），给定 (名字, taken) 才有确定性可言。
        Set<String> takenAll = new HashSet<>(first.values());
        takenAll.addAll(second.values());
        int repeated = 0;
        for (String n : derived) {
            String a = DistinctMds.labelForName(n, takenAll);
            String b = DistinctMds.labelForName(n, takenAll);
            repeated++;
            if (!a.equals(b)) {
                fail("③ 同输入重复查表不一致：" + n + " → `" + a + "` vs `" + b + "`");
            }
        }
        System.out.println("③ determinism : assignLabels 跑两遍，" + derived.size()
                + " 条；labelForName 同输入复算 " + repeated + " 条，mismatch=" + mismatches);
    }

    // ------------------------------------------------------------------ ④ 用户样例

    /** ④ 用户 2026-10 实测样例（与 PLAN §5.4 / DerivedLabelCheck 逐字一致）。 */
    private static void checkUserCases() {
        Set<String> taken = new HashSet<>();
        String[][] cases = {
            {"熔融高速钢", "熔融高速钢"},
            {"熔融高速钢-G", "熔高速钢-G"},
            {"熔融高速钢-S", "熔高速钢-S"},
            {"熔融铝合金-2090", "熔铝2090"},
            {"熔融铝合金-7050", "熔铝7050"},
            {"熔融不锈钢-316", "熔不锈316"},
            {"铝合金-2090等离子体", "铝2090浆"},
        };
        Set<String> seen = new HashSet<>();
        for (String[] c : cases) {
            String got = DistinctMds.labelForName(c[0], taken);
            if (!c[1].equals(got)) {
                fail("④ 用户样例不符：" + c[0] + " 期望 `" + c[1] + "` 实得 `" + got + "`");
            } else if (cpLen(got) > BUDGET) {
                fail("④ 用户样例超长：" + c[0] + " → `" + got + "`");
            } else if (!seen.add(got)) {
                fail("④ 用户样例同形：" + got);
            }
            taken.add(got);
        }
        System.out.println("④ user cases  : " + cases.length + " 条逐字比对完成");
    }

    // ------------------------------------------------------------------ ③b 锚点

    /**
     * 人工锚点（{@code tools/anchors.json}）在 AE2 侧同样必须生效 —— AE2 与 EMI 共用
     * {@code LabelEngine}，而锚点是最高优先级别名层（PLAN §0.3/§0.5）。
     *
     * <p>做法与 PLAN §6.7.A 一致：把锚点显示名喂进 {@code assignLabels} 再
     * {@code applyAnchors}，逐条确认命中值；顺带确认锚点值与"非锚点条目"的两两互异没有被破坏。
     */
    private static void checkAnchors() throws IOException {
        Path anchorsPath = Paths.get("tools", "anchors.json");
        if (!Files.isRegularFile(anchorsPath)) {
            anchorsPath = Paths.get("..", "tools", "anchors.json");
        }
        if (!Files.isRegularFile(anchorsPath)) {
            System.out.println("[SKIP] 找不到 tools/anchors.json ⇒ 跳过锚点断言。");
            return;
        }
        Map<String, String> anchors = readFlatStringMap(anchorsPath, "byDisplayName");
        if (anchors.isEmpty()) {
            System.out.println("[SKIP] anchors.json 里没有 byDisplayName 条目。");
            return;
        }
        Map<String, String> nameByKey = new LinkedHashMap<>();
        // 锚点的键都是真实显示名 ⇒ 直接当身份键；再补一批普通语料名，制造"需要消解冲突"的环境。
        for (String name : anchors.keySet()) {
            nameByKey.put(name, name);
        }
        Path labelsPath = Paths.get("src", "main", "resources", "fluidlabel_labels.json");
        if (Files.isRegularFile(labelsPath)) {
            Map<String, String> table = readFlatStringMap(labelsPath, "byDisplayName");
            int added = 0;
            for (String name : table.keySet()) {
                if (added >= 400) {
                    break;
                }
                if (nameByKey.putIfAbsent("table:" + name, name) == null) {
                    added++;
                }
            }
        }
        Map<String, String> assigned = DistinctMds.assignLabels(nameByKey);
        Map<String, String> withAnchors = DistinctMds.applyAnchors(assigned, nameByKey, anchors);
        int applied = 0;
        for (Map.Entry<String, String> e : anchors.entrySet()) {
            String got = withAnchors.get(e.getKey());
            if (e.getValue().equals(got)) {
                applied++;
            } else {
                fail("③b 锚点未生效：" + e.getKey() + " 期望 `" + e.getValue() + "` 实得 `" + got + "`");
            }
        }
        // 互异性必须在"按显示名"的层面判，而不是按条目键：本方法刻意制造了 `table:X` 这类
        // **别名键**，而 AE2/EMI 两侧都要求"同一液体的多个身份键拿到同一标签"（§0.5 的同名多键
        // memo）。按 854 个键去数互异值，会把"别名共用同一标签"这个**正确行为**误判成撞车。
        Map<String, String> labelByName = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : withAnchors.entrySet()) {
            String key = e.getKey();
            String name = nameByKey.containsKey(key) ? nameByKey.get(key)
                    : (key.startsWith("table:") ? key.substring("table:".length()) : key);
            labelByName.put(name, e.getValue());
        }
        Set<String> values = new HashSet<>(labelByName.values());
        System.out.println("③b anchors    : " + applied + "/" + anchors.size()
                + " 生效；叠加后互异标签 " + values.size() + "/" + labelByName.size()
                + " 个显示名（条目键 " + withAnchors.size() + " 个）");
        if (values.size() != labelByName.size()) {
            fail("③b 叠加锚点后出现同形标签：" + values.size() + " 个互异值 / "
                    + labelByName.size() + " 个显示名");
        }
    }

    // ------------------------------------------------------------------ 缓存隔离

    /**
     * AE2 侧缓存纪律的离线可测部分：{@link com.fluidlabel.compat.Ae2Compat} 依赖 AE2 类型，
     * 这里<b>不</b>测它本身，而是确认"标签入口是纯函数" ——
     * 同一条目无论查多少次、无论插入了多少别的条目，都得到同一个标签（缓存只是加速，
     * 不改变语义）。这条不成立的话，AE2 屏与 EMI 侧栏就会出现"同液体不同标签"。
     */
    private static void checkCacheIsolation() {
        String[] names = {"熔融高速钢-G", "熔融铝合金-2090", "石脑油", "重度加氢裂化石脑油"};
        Set<String> taken = new HashSet<>();
        boolean allDistinct = true;
        int checked = 0;
        for (String n : names) {
            // 同一 (名字, taken) 连续两次必须同结果 —— 缓存只允许加速，不允许改变标签。
            String a = DistinctMds.labelForName(n, taken);
            String b = DistinctMds.labelForName(n, taken);
            checked++;
            if (!a.equals(b)) {
                fail("缓存隔离：`" + n + "` 同输入两次不一致（`" + a + "` vs `" + b + "`）");
            }
            if (cpLen(a) > BUDGET) {
                fail("缓存隔离：" + n + " → `" + a + "` 超长");
            }
            if (!seenNone(taken, a)) {
                allDistinct = false;
            }
            taken.add(a);
        }
        if (!allDistinct) {
            fail("缓存隔离：逐条分配出现同形标签");
        }
        System.out.println("cache isolate : " + checked + " 条重复查询一致且两两互异");
    }

    /** {@code label} 在 {@code taken} 里是否尚未出现（早退，避免误触发 fail）。 */
    private static boolean seenNone(Set<String> taken, String label) {
        return !taken.contains(label);
    }

    /** 标签**真正被画出来**的部分（PLAN §5.2.1：截到预算的可见形式）。 */
    private static String visible(String label) {
        if (label == null) {
            return "";
        }
        return cpLen(label) <= BUDGET ? label : label.substring(0, label.offsetByCodePoints(0, BUDGET));
    }

    // ------------------------------------------------------------------ 工具

    private static void fail(String message) {
        failures++;
        if (printed < MAX_PRINT) {
            printed++;
            System.out.println("[fail] " + message);
        }
    }

    private static int cpLen(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /**
     * 只解析"平铺的字符串映射"（{@code {"k": "v", …}}）—— 够用且不引入 JSON 依赖。
     *
     * <p>支持的输入形态：mixin 式紧凑 JSON（每行一条 {@code "k": "v",}）、
     * 以及键值同行或分行两种排版。
     */
    private static Map<String, String> readFlatStringMap(Path file, String section) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String[] kv = parseStringPair(line);
            if (kv != null) {
                out.put(kv[0], kv[1]);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        // 兜底：整串扫描（处理"同行多个键值对"或"键与值分行"的排版）
        String all = Files.readString(file, StandardCharsets.UTF_8);
        int idx = section == null ? -1 : all.indexOf("\"" + section + "\"");
        int from = idx < 0 ? 0 : idx;
        int i = from;
        while (i < all.length()) {
            int k1 = all.indexOf('"', i);
            if (k1 < 0) {
                break;
            }
            int k2 = stringEnd(all, k1);
            int c = all.indexOf(':', k2);
            int v1 = c < 0 ? -1 : all.indexOf('"', c);
            if (v1 < 0) {
                break;
            }
            int v2 = stringEnd(all, v1);
            out.put(unescape(all.substring(k1 + 1, k2)), unescape(all.substring(v1 + 1, v2)));
            i = v2 + 1;
        }
        return out;
    }

    /**
     * 从 lang 键值对里挑出"可能是液体显示名"的值：键以 {@link #LANG_KEY_PREFIXES} 之一开头，
     * 且值不是模板（不含 {@code %s}）、非空。
     *
     * <p>过掉模板很关键：{@code gtceu.fluid.molten = "熔融%s"} 是**模板**不是显示名，
     * 把它当基名会派生出 {@code 熔融熔融%s} 这种垃圾，进而把门禁变成噪声源。
     */
    private static Set<String> fluidishValues(Map<String, String> all) {
        Set<String> out = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : all.entrySet()) {
            String key = e.getKey();
            boolean prefixHit = false;
            for (String p : LANG_KEY_PREFIXES) {
                if (key.startsWith(p)) {
                    prefixHit = true;
                    break;
                }
            }
            if (!prefixHit) {
                continue;
            }
            String v = e.getValue();
            if (v == null || v.isEmpty() || v.contains("%s")) {
                continue;
            }
            out.add(v);
        }
        return out;
    }

    /** 从一行里取前两个 JSON 字符串（键、值）；不足两个则返回 null。 */
    private static String[] parseStringPair(String line) {
        String s = line;
        if (s == null) {
            return null;
        }
        int k1 = s.indexOf('"');
        if (k1 < 0) {
            return null;
        }
        int k2 = stringEnd(s, k1);
        if (k2 < 0) {
            return null;
        }
        int c = s.indexOf(':', k2);
        if (c < 0) {
            return null;
        }
        int v1 = s.indexOf('"', c);
        if (v1 < 0) {
            return null;
        }
        int v2 = stringEnd(s, v1);
        if (v2 < 0) {
            return null;
        }
        return new String[] {unescape(s.substring(k1 + 1, k2)), unescape(s.substring(v1 + 1, v2))};
    }

    /** 未转义字符串的结束引号下标（返回它，或在未闭合时返回 -1）。 */
    private static int stringEnd(String s, int start) {
        int i = start + 1;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i += 2;
                continue;
            }
            if (ch == '"') {
                return i;
            }
            i++;
        }
        return -1;
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'u' -> {
                        if (i + 4 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException ex) {
                                sb.append(n);
                            }
                        } else {
                            sb.append(n);
                        }
                    }
                    default -> sb.append(n);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private Ae2LabelCheck() {
    }
}
