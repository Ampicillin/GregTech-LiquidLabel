package com.fluidlabel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 运行时标签规则引擎（<b>T1 规则</b>）—— 与离线生成器 {@code tools/gen_labels.py} 的
 * {@code long_candidates} / {@code assign_v2} / {@code _rescue_truncation} 同构。
 *
 * <p>运行时既要在第 3 层兜底里给表外的液体起名，又要在 DYNAMIC 档对"本帧画到的那些液体"
 * 求一套互异标签；两处都必须产出与预生成表同一风格的标签，否则同一个液体会在表内/表外两套
 * 命名之间抖动。
 *
 * <p>T1 规则（2026-09 用户新规则，取代旧的"越短越好"MDS 阶梯）：
 * <ol>
 *   <li>显示名长度 <b>≤ {@link #MAX_LABEL}(6)</b> ⇒ 标签 = 名字本身，<b>一字不改</b>；</li>
 *   <li>&gt; 6 ⇒ 按候选阶梯压到 ≤ 6 字，阶梯顺序（与生成器逐条对齐）：
 *       <pre>
 *       prefix    : 前缀简称 + 原样剩余 → 前缀简称 + 去助词(剩余)
 *                   → 前缀简称 + 去助词(剩余) 截到 (6-len(前缀))
 *                   → 前缀简称 + 牌号(去 '-') → 前缀简称 + 词尾压缩(剩余)
 *       tokens    : 词尾压缩(整条) → 去助词(词尾压缩(整条)) → 词尾压缩(去助词(整条))
 *       code      : 牌号（先去 '-' 的整串，超 6 则取最后一段）+ 尽可能多的中文核心
 *                   （先 牌号+中文前缀，再 中文后缀+牌号，最后光牌号）
 *       head-tail : 压缩串的 c[:2]+c[-4:] / c[:3]+c[-3:] / c[:2]+c[-3:] / c[:4]+c[-2:]
 *       cut       : c[:6] / name[:6]
 *       </pre>
 *       每个候选都必须 ≤ 6 字、非空、且不含 {@link #BAD_CHARS}；全局去重。</li>
 *   <li>分配顺序 = <b>先短后长</b>（{@code sorted(longs, key=(len, name))}），每条取阶梯里
 *       第一个"未被别人占用"的候选；</li>
 *   <li>同一个显示名对应多条材料 key 时共用同一个标签。</li>
 * </ol>
 *
 * <p>唯一性判据（PLAN §5.2）原样保留：<b>标签两两互异</b>，而不是"标签不是别的名字的子序列"。
 *
 * <p>不变量：同一输入集合无论被调用多少次，输出完全一致（确定性），这是 STABLE 档
 * "跨界面恒定"的前提。
 */
public final class DistinctMds {

    /** 功能字（生成器 FUNC）：仅截断救援层用它剥首尾。 */
    private static final String FUNC_CHARS = "的了之与和及，,、（）()[]·-";

    /** 一旦出现在候选里就整条丢弃（生成器 BAD_CHARS）：路径/命名空间分隔符做标签读不出来。 */
    private static final String BAD_CHARS = "/\\:;<>|\"'";

    /**
     * 短名保全名的长度上限（生成器 NAME_FULL_LIMIT = MAX_LABEL）：名字 ≤ 该长度时标签就是
     * 名字本身，不做任何截断与简化。
     */
    public static final int MAX_LEN = 6;

    /** 标签长度硬上限 = 2 行 × 3 字（PLAN §4.1 几何上限，生成器 MAX_LABEL）。 */
    private static final int MAX_LABEL = MAX_LEN;

    /** 单名候选列表缓存的条目上限；到达上限后只停止写入（重算结果不变，输出不受影响）。 */
    private static final int MAX_CANDIDATE_CACHE = 4096;

    /** 显示名 → 候选列表（按偏好降序）。渲染路径每帧复用，避免重复枚举。 */
    private static final Map<String, List<String>> CANDIDATE_CACHE = new ConcurrentHashMap<>();

    /**
     * 长前缀 → 简称（生成器 {@code PREFIX_ABBREV}，<b>逐条抄写，不得改写/漏项</b>）。
     * 匹配时取<b>最长命中</b>，同长取表里靠前者。
     */
    private static final String[][] PREFIX_ABBREV = {
            {"重度加氢裂化的", "重氢裂"},
            {"重度加氢裂化", "重氢裂"},
            {"轻度加氢裂化的", "轻氢裂"},
            {"轻度加氢裂化", "轻氢裂"},
            {"重度蒸汽裂化的", "重蒸裂"},
            {"重度蒸汽裂化", "重蒸裂"},
            {"轻度蒸汽裂化的", "轻蒸裂"},
            {"轻度蒸汽裂化", "轻蒸裂"},
            {"加氢裂化的", "氢裂"},
            {"加氢裂化", "氢裂"},
            {"蒸汽裂化的", "蒸裂"},
            {"蒸汽裂化", "蒸裂"},
            {"催化裂化的", "催裂"},
            {"催化裂化", "催裂"},
            {"溢满的", "满"},
            {"哈斯特洛依合金", "哈氏"},
            {"镍铬基合金-", "镍铬"},
            {"不锈钢-", "不锈"},
            {"钛合金-", "钛合"},
            {"铝合金-", "铝合"},
            {"钨合金-", "钨合"},
            {"碳纤维-", "碳纤"},
            {"二氧化钛纳米管", "钛纳管"},
            {"碳纳米管", "纳管"},
            {"氧化物弥散强化", "弥散强"},
            {"浓缩混合超能燃料", "浓超燃"},
            {"超能燃料", "超燃"},
            {"超重元素-", "超重"},
            {"碳化硅纤维增强", "硅纤增"},
            {"碳化硅纤维强化", "硅纤强"},
            {"碳化硅颗粒增强", "硅颗增"},
            {"氟碳镧铈稀土", "氟镧铈"},
            {"氟碳镧铈", "氟镧铈"},
            {"氯化稀土", "氯稀土"},
            {"独居石稀土", "独居石"},
            {"镧-富勒烯", "镧富勒"},
            {"多能性诱导", "多能诱"},
            {"高密度自由", "高密自"},
            {"间苯二甲", "间苯二"},
            {"二乙烯三胺", "二乙烯"},
            {"六甲基二硅", "六甲硅"},
            {"富集硅岩", "富硅岩"},
            {"超能硅岩", "超硅岩"},
            {"凯金化合物", "凯金"},
            {"火箭燃料", "火箭燃"},
            {"纳米金沉积", "纳金沉"},
            {"镀银的", "银"},
            {"回收的", "回收"},
            {"失效的", "失效"},
            {"激发的", "激发"},
            {"污浊的", "浊"},
            {"洁净的", "洁净"},
            {"纯净的", "纯"},
            {"稀释的", "稀"},
            {"浓缩的", "浓"},
            {"重新活化的", "活化"},
    };

    /** 词尾通用词 → 压缩写法（生成器 {@code TOKEN_ABBREV}，逐条抄写）。 */
    private static final String[][] TOKEN_ABBREV = {
            {"纳米萃取树脂", "树脂"},
            {"萃取树脂", "树脂"},
            {"复合材料", "复材"},
            {"混合物", "混"},
            {"溶液", "液"},
            {"浆液", "浆"},
            {"泡沫", "泡"},
            {"陶瓷", "陶"},
            {"催化剂", "催化"},
            {"共聚物", "共聚"},
            {"微珠", "珠"},
            {"纳米管", "纳管"},
            {"纳米", "纳"},
            {"纤维", "纤"},
    };

    /** 压缩时丢弃的助词/分隔符（生成器 {@code _compact} 的 drop 集合，逐字抄写）。 */
    private static final String COMPACT_DROP = "的了之与和及·()（）[]{}<>-—_ '\"/\\,，、";

    private DistinctMds() {
    }

    /**
     * 把人工锚点（{@code 显示名 → 标签}）叠加到一组已分配标签上，并就地消解冲突。
     *
     * <p>纯函数、不依赖 Minecraft 类型，便于离线测试（{@code tools/parity/DynamicAnchorCheck.java}）。
     *
     * <p>规则：
     * <ol>
     *   <li>锚点值<b>最高优先</b>：命中锚点的显示名，其所有键都改成锚点值；</li>
     *   <li>其余条目若标签与某个锚点值相同，就地用 {@link #labelForName(String, Set)} 重算，
     *       保证"不同显示名两两互异"这条不变量在叠加后依然成立；</li>
     *   <li>未命中锚点的条目保持原标签。</li>
     * </ol>
     *
     * @param assigned  身份键 → 标签（{@link #assignLabels(Map)} 的结果）
     * @param nameByKey 身份键 → 显示名（与 {@code assignLabels} 入参同一个映射）
     * @param anchors   显示名 → 人工标签
     * @return 新的身份键 → 标签（{@code assigned} 不被修改；无锚点命中时原样返回）
     */
    public static Map<String, String> applyAnchors(Map<String, String> assigned,
                                                  Map<String, String> nameByKey,
                                                  Map<String, String> anchors) {
        if (assigned == null || assigned.isEmpty() || anchors == null || anchors.isEmpty()
                || nameByKey == null || nameByKey.isEmpty()) {
            return assigned;
        }
        Set<String> anchorValues = new HashSet<>();
        // 注意：assignLabels 返回的映射里除了 nameByKey 的键，还额外写入了"显示名键"。
        // 这些键同样可能命中锚点，所以键 → 名字的解析要走 resolveName（键不在 nameByKey 里时，
        // 键本身就是显示名）。
        for (String key : assigned.keySet()) {
            String hit = anchors.get(resolveName(key, nameByKey));
            if (hit != null && !hit.isEmpty()) {
                anchorValues.add(hit);
            }
        }
        if (anchorValues.isEmpty()) {
            return assigned;
        }
        Map<String, String> out = new LinkedHashMap<>(assigned);
        Set<String> taken = new LinkedHashSet<>(assigned.values());
        taken.addAll(anchorValues);
        boolean changed = false;
        // 第一遍：锚点最高优先（覆盖身份键与显示名键）
        for (Map.Entry<String, String> e : out.entrySet()) {
            String anchored = anchors.get(resolveName(e.getKey(), nameByKey));
            if (anchored != null && !anchored.isEmpty() && !anchored.equals(e.getValue())) {
                e.setValue(anchored);
                changed = true;
            }
        }
        // 第二遍：标签落在锚点值集合里的"非锚点条目"就地重算，保住"两两互异"。
        // 同一个显示名可能对应多个键（身份键 + assignLabels 补写的显示名键），必须复用同一个
        // 重算结果，否则按名查询与按键查询会给出不同标签。
        Map<String, String> memo = new HashMap<>();
        for (Map.Entry<String, String> e : out.entrySet()) {
            String name = resolveName(e.getKey(), nameByKey);
            String anchored = anchors.get(name);
            if (anchored != null && !anchored.isEmpty()) {
                continue;
            }
            String current = e.getValue();
            if (current == null || !anchorValues.contains(current)) {
                continue;
            }
            String fixed = memo.get(name);
            if (fixed == null) {
                fixed = labelForName(name, taken);
                if (fixed != null && !fixed.isEmpty()) {
                    taken.add(fixed);
                    memo.put(name, fixed);
                }
            }
            if (fixed != null && !fixed.isEmpty() && !fixed.equals(current)) {
                e.setValue(fixed);
                changed = true;
            }
        }
        return changed ? out : assigned;
    }

    /** 键 → 显示名：键在 {@code nameByKey} 里就用映射值，否则认为键本身就是显示名。 */
    private static String resolveName(String key, Map<String, String> nameByKey) {
        String mapped = nameByKey.get(key);
        return mapped == null ? key : mapped;
    }

    /**
     * 为一批"需要互相区分"的名字分配互异标签（生成器 {@code assign_v2} + {@code _rescue_truncation}）。
     *
     * <p>身份 = 显示名（{@code nameByKey} 里同名条目合并成同一身份，运行时分不清）；
     * 先让所有 ≤ {@link #MAX_LEN} 字的名字原样占位，再按"先短后长"给长名沿候选阶梯取第一个
     * 未被占用的候选；实在无解时退回名字本身（必要时唯一化）。
     *
     * @param nameByKey 身份键 → 显示名（键随便取，只要唯一；调用方用注册名或显示名均可）。
     *                  <b>显示名完全相同的条目会被合并成同一身份</b>，因为它们运行时分不清
     *                  （实测全池仅 2 组：乙腈 / 金属残渣），合并后必然拿到同一个标签。
     * @return 身份键 → 标签；<b>显示名也能直接查到</b>（额外写入显示名键，不会覆盖已有键）。
     *         每个返回值的标签在"不同显示名"之间两两互异，且绝不为空。
     */
    public static Map<String, String> assignLabels(Map<String, String> nameByKey) {
        Map<String, String> out = new LinkedHashMap<>();
        if (nameByKey == null || nameByKey.isEmpty()) {
            return out;
        }

        // 身份 = 显示名；同一显示名的多个键共享标签。
        List<String> keys = new ArrayList<>(nameByKey.keySet());
        List<String> names = new ArrayList<>(keys.size());
        Set<String> uniq = new LinkedHashSet<>();
        for (String k : keys) {
            String n = nameByKey.get(k);
            if (n == null) {
                n = "";
            }
            names.add(n);
            uniq.add(n);
        }

        Map<String, String> perName = new LinkedHashMap<>();   // 显示名 → 标签
        Set<String> taken = new HashSet<>();                   // 已被占用的标签
        List<String> longs = new ArrayList<>();                // > MAX_LABEL 的名字

        // 阶段 0（生成器 assign_v2 的 verbatim 轮）：≤ 6 字的名字原样占位。
        for (String name : uniq) {
            if (cpLen(name) <= MAX_LEN && !taken.contains(name)) {
                perName.put(name, name);
                taken.add(name);
            } else {
                longs.add(name);
            }
        }

        // 阶段 1（生成器 assign_v2 的长名轮）：**先短后长**，每条取阶梯里第一个未被占用的候选。
        longs.sort((a, b) -> {
            int c = Integer.compare(cpLen(a), cpLen(b));
            return c != 0 ? c : compareCodePoints(a, b);
        });
        List<String> leftovers = new ArrayList<>();
        for (String name : longs) {
            String picked = null;
            for (String cand : candidateList(name)) {
                if (!taken.contains(cand)) {
                    picked = cand;
                    break;
                }
            }
            if (picked == null) {
                leftovers.add(name);
                continue;
            }
            perName.put(name, picked);
            taken.add(picked);
        }

        // 阶段 2：显示截断救援（生成器 _rescue_truncation）。生成器的调用顺序是
        // assign_v2 → _rescue_truncation → 无解条目按 `per_name.get(name) or name` 收尾，
        // 这里必须一致：救援只作用于已求到解的标签。
        rescueTruncation(perName);

        // 阶段 3：无解 ⇒ 退回名字本身；若这个名字也已被占用，再做唯一化，保证
        // "每个 key 都有非空标签"且标签两两互异。
        Set<String> takenFinal = new HashSet<>(perName.values());
        for (String name : leftovers) {
            String chosen = uniqueLabel(name, takenFinal);
            perName.put(name, chosen);
            takenFinal.add(chosen);
        }

        for (int i = 0; i < keys.size(); i++) {
            out.put(keys.get(i), perName.get(names.get(i)));
        }
        // 显示名索引：调用方（LabelEngine）用 "\u0000" 前缀键区分，这里补的是裸显示名键，
        // 只在键尚未存在时写入，避免与注册名键互相覆盖。
        for (String name : uniq) {
            String label = perName.get(name);
            if (label != null && !label.isEmpty() && !out.containsKey(name)) {
                out.put(name, label);
            }
        }
        return out;
    }

    /**
     * 单条兜底：在已知"已被占用标签集合"的前提下，给一个名字挑一个没被占用的标签。
     *
     * <p>用于运行时（第 3 层兜底）逐条计算。注意它与 {@link #assignLabels} 不同：
     * 这里不保证与尚未计算的名字互异，只能保证与 {@code taken} 中已有的互异。
     *
     * @param name     显示名
     * @param takenSet 已被占用的标签（会被本方法读取，不会修改）
     * @return 标签（绝不返回 null；候选全被占用时在<b>预算内</b>唯一化，绝不返回超长原名）
     */
    public static String labelForName(String name, Set<String> takenSet) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        Set<String> taken = takenSet == null ? Collections.emptySet() : takenSet;
        // T1：名字本身 ≤ MAX_LEN 且没被别人占用时，直接用全名（含硫石脑油 / 生物柴油）。
        if (cpLen(name) <= MAX_LEN && !taken.contains(name)) {
            return name;
        }
        String chosen = firstFreeCandidate(name, taken);
        if (chosen != null) {
            return chosen;
        }
        // 候选全被占用 ⇒ 在**预算内**唯一化。绝不返回 > MAX_LEN 的原名：渲染层只有
        // maxChars × maxLines = 6 码点的位置，超出部分被静默丢掉 —— 用户 2026-10 报的
        // `熔融高速钢-G` / `熔融铝合金-2090` 显示成一模一样，根因正是这里返回了超长原名。
        List<String> cands = candidateList(name);
        String base = cands.isEmpty() ? name : cands.get(cands.size() - 1);
        return uniqueWithinBudget(base, taken);
    }

    /**
     * 预算内唯一化：优先用 {@code base} 本身，被占用则依次追加 2、3、…（必要时从尾部截断，
     * 保证结果 ≤ {@link #MAX_LEN}）。
     *
     * <p>与 {@link #uniqueLabel} 的分工：那个用于生成器"无解条目"的收尾（允许超出预算），
     * 这里是渲染路径的兜底，<b>必须</b>留在预算内，否则又会被截断成同形。
     */
    private static String uniqueWithinBudget(String base, Set<String> taken) {
        String s = base == null ? "" : base.strip();
        if (cpLen(s) > MAX_LEN) {
            s = cpHead(s, MAX_LEN);
        }
        if (s.isEmpty()) {
            s = "?";
        }
        if (!taken.contains(s)) {
            return s;
        }
        for (int i = 2; i < 1000; i++) {
            String suffix = Integer.toString(i);
            String stem = cpLen(s) + cpLen(suffix) > MAX_LEN
                    ? cpHead(s, MAX_LEN - cpLen(suffix)) : s;
            String cand = stem + suffix;
            if (!taken.contains(cand)) {
                return cand;
            }
        }
        return s;
    }

    /**
     * 名字的候选阶梯（{@link #candidates(String, int)}，默认预算），<b>按人类偏好降序</b>，
     * 下标即优先次序。
     *
     * <p>结果按显示名缓存（{@link #CANDIDATE_CACHE}），渲染路径每帧复用；阶梯只与名字有关，
     * 与池无关，因此缓存安全。返回的列表不可修改。
     *
     * @param name 显示名（null 视作空串）
     * @return 候选列表；全部 ≤ MAX_LABEL、非空、不含 BAD_CHARS
     */
    static List<String> candidateList(String name) {
        String n0 = name == null ? "" : name;
        List<String> cached = CANDIDATE_CACHE.get(n0);
        if (cached != null) {
            return cached;
        }

        List<String> out;
        if (n0.isEmpty()) {
            out = List.of();
        } else if (cpLen(n0) <= MAX_LEN) {
            out = List.of(n0);          // T1 ①：短名 = 名字本身，一字不改
        } else {
            out = candidates(n0, MAX_LABEL);
        }
        if (CANDIDATE_CACHE.size() < MAX_CANDIDATE_CACHE) {
            CANDIDATE_CACHE.putIfAbsent(n0, out);
        }
        return out;
    }

    /**
     * 候选列表里第一个未被占用的标签（生成器阶段 2 的那一步）。
     *
     * @return 候选；无可用候选时返回 null
     */
    static String firstFreeCandidate(String name, Set<String> taken) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        Set<String> occupied = taken == null ? Collections.emptySet() : taken;
        for (String c : candidateList(name)) {
            if (!occupied.contains(c)) {
                return c;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ T1 候选阶梯

    /** 生成器的 {@code STATE_PREFIX}：{词缀, 长写法, 短写法}（长写法在前）。 */
    private static final String[][] STATE_PREFIX = {
        {"熔融", "熔融", "熔"},
        {"液态", "液态", "液"},
        {"气态", "气态", "气"},
    };

    /** 生成器的 {@code STATE_SUFFIX}：{词缀, 长写法, 短写法}。 */
    private static final String[][] STATE_SUFFIX = {
        {"等离子体", "等离子", "浆"},
        {"蒸气", "蒸气", "汽"},
    };

    /**
     * 候选阶梯的"入列"助手（生成器 {@code add()} 与 {@code push()} 的合并体）：
     * strip → 非空 / ≤ budget / 全局去重 / {@link #BAD_CHARS} 卫生检查。
     */
    private static final class Ladder {
        private final int budget;
        private final List<String> out = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();

        Ladder(int budget) {
            this.budget = budget;
        }

        void add(String raw) {
            if (raw == null) {
                return;
            }
            String s = raw.strip();
            if (s.isEmpty() || cpLen(s) > budget || seen.contains(s) || containsBadChar(s)) {
                return;
            }
            seen.add(s);
            out.add(s);
        }

        List<String> done() {
            return List.copyOf(out);
        }
    }

    /**
     * 生成器 {@code _split_state_affix()}：把状态派生名拆成
     * {@code {基体名, "1"=前缀/"0"=后缀, 长写法, 短写法}}；不是派生名则返回 null。
     *
     * <p>GT 的 {@code gtceu.fluid.molten} = {@code 熔融%s} 等 6 个模板是<b>运行期拼接</b>的，
     * 派生名不在预计算表里，只能靠这里还原"状态字 + 基体"。
     */
    private static String[] splitStateAffix(String name) {
        for (String[] p : STATE_PREFIX) {
            if (name.startsWith(p[0]) && name.length() > p[0].length()) {
                return new String[] {name.substring(p[0].length()), "1", p[1], p[2]};
            }
        }
        for (String[] s : STATE_SUFFIX) {
            if (name.endsWith(s[0]) && name.length() > s[0].length()) {
                return new String[] {name.substring(0, name.length() - s[0].length()), "0", s[1], s[2]};
            }
        }
        return null;
    }

    /**
     * 生成器 {@code _grade_split()}：拆出 {@code {中文描述, 牌号, 原始 token}}。
     *
     * <p>牌号 = 含数字的最长拉丁串（去掉 {@code -}/{@code '}）；没有数字牌号则返回 null
     * （纯字母罗马数字 {@code 超能燃料 II} 与单字母 {@code 铍铝合金-F} 不算"编号"）。
     */
    private static String[] gradeSplit(String name) {
        String tok = null;
        int i = 0;
        while (i < name.length()) {
            char ch = name.charAt(i);
            if (ch <= 0x7F && isAsciiAlnum(ch)) {
                int j = i + 1;
                while (j < name.length()) {
                    char c2 = name.charAt(j);
                    if (c2 <= 0x7F && (isAsciiAlnum(c2) || c2 == '\'' || c2 == '-')) {
                        j++;
                    } else {
                        break;
                    }
                }
                String t = name.substring(i, j);
                if (hasDigit(t) && (tok == null || t.length() > tok.length()
                        || (t.length() == tok.length() && t.compareTo(tok) > 0))) {
                    tok = t;
                }
                i = j;
            } else {
                i++;
            }
        }
        if (tok == null) {
            return null;
        }
        String g = tok.replace("-", "").replace("'", "");
        StringBuilder cjkSb = new StringBuilder(name.length());
        for (int k = 0; k < name.length(); k++) {
            char ch = name.charAt(k);
            if (!(ch <= 0x7F && (isAsciiAlnum(ch) || ch == '\'' || ch == '-'))) {
                cjkSb.append(ch);
            }
        }
        String cjk = compact(cjkSb.toString());
        if (cpLen(g) < 2 || cjk.isEmpty()) {
            return null;
        }
        return new String[] {cjk, g, tok};
    }

    private static boolean hasDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch >= '0' && ch <= '9') {
                return true;
            }
        }
        return false;
    }

    /**
     * 保牌号档（生成器 {@code _grade_candidates()}）。
     *
     * <p>用户 2026-10 要求：牌号（{@code 2090} / {@code Ti53311S} / {@code Q690}）是重要区分，
     * 必须留下。只做"严格更好"的三件事（第一版曾把 {@code 不锈JBK7} 改成 {@code 不JBK75}、
     * {@code 钛合Ti53} 改成 {@code 钛合金Ti5} —— 牌号字符更多但可读性下降，已收紧）：
     * ① 中文前缀简称 + <b>完整牌号</b>；② 牌号在尾 ⇒ 中文头部 k 字 + 完整牌号（k ≥ 2）；
     * ③ 牌号在头 ⇒ 牌号 + 中文头部 k 字。
     *
     * @param allowK1 允许 k = 1（只给状态词缀组合用：状态字已占一格，基体预算只剩 5，
     *                {@code 熔融铝合金-2090} 必须给成 {@code 熔铝2090} 而不是 {@code 熔铝合209}）
     */
    private static void gradeCandidates(String name, int budget, Ladder l, boolean allowK1) {
        String[] sp = gradeSplit(name);
        if (sp == null) {
            return;
        }
        String cjk = sp[0];
        String g = sp[1];
        String tok = sp[2];

        String[] pref = abbrevPrefix(name);
        if (pref != null && cpLen(pref[0]) + cpLen(g) <= budget) {
            l.add(pref[0] + g);
        }

        String stripped = name.strip();
        if (stripped.endsWith(tok)) {                 // 牌号在尾：保牌号、裁中文
            int room = budget - cpLen(g);
            int top = Math.min(cpLen(cjk), Math.max(0, room));
            for (int k = top; k > 0; k--) {
                if (k < 2 && !allowK1) {
                    continue;
                }
                l.add(cpHead(cjk, k) + g);
            }
        } else if (stripped.startsWith(tok)) {        // 牌号在头：牌号在前，中文尾部跟上
            int room = budget - cpLen(g);
            int top = Math.min(cpLen(cjk), Math.max(0, room));
            for (int k = top; k > 1; k--) {
                l.add(g + cpHead(cjk, k));
            }
        }
    }

    /**
     * 生成器的 {@code long_candidates()}：候选阶梯（下标即优先次序）。
     *
     * <p>档位顺序（必须逐条对齐 Python）：grade → prefix → tokens → code → head-tail → cut；
     * 每个候选都 ≤ {@code budget}、非空、不含 {@link #BAD_CHARS}，且全局去重。
     *
     * @param budget  本次预算（码点数；默认 {@link #MAX_LABEL}，状态词缀组合会调小）
     * @param allowK1 「中文 1 字 + 完整牌号」是否允许（只在状态词缀组合里为 true）
     */
    static List<String> longCandidates(String name, int budget, boolean allowK1) {
        String n = name == null ? "" : name;
        Ladder l = new Ladder(budget);

        // 保牌号档排在最前：牌号是玩家真正的区分点，中文可以压。
        gradeCandidates(n, budget, l, allowK1);

        // prefix 档：前缀简称 + 剩余（逐级压缩）。
        String[] pref = abbrevPrefix(n);
        if (pref != null) {
            String prem = pref[0];
            String rest = pref[1];
            l.add(prem + rest);
            l.add(prem + compact(rest));
            int room = budget - cpLen(prem);
            if (room >= 1) {
                String cr = compact(rest);
                l.add(prem + cpHead(cr, room));
            }
            for (String tok : rawTokens(rest)) {
                l.add(prem + tok.replace("-", ""));
            }
            l.add(prem + abbrevTokens(rest));
        }

        // tokens 档：整条的词尾压缩 / 去助词组合。
        l.add(abbrevTokens(n));
        l.add(compact(abbrevTokens(n)));
        l.add(abbrevTokens(compact(n)));

        // code 档：牌号是关键区分点 ⇒ 先"牌号 + 尽可能多的中文核心"，再退到光牌号。
        List<String> toks = new ArrayList<>(rawTokens(n));
        toks.sort((a, b) -> Integer.compare(cpLen(b), cpLen(a)));   // 稳定排序，仅按长度降序
        StringBuilder cjkSb = new StringBuilder();
        for (int i = 0; i < n.length(); i++) {
            char ch = n.charAt(i);
            if (!(ch <= 0x7F && (isAsciiAlnum(ch) || ch == '\'' || ch == '-'))) {
                cjkSb.append(ch);
            }
        }
        String cjk = compact(cjkSb.toString());
        for (String tok : toks) {
            String ct = tok.replace("-", "").replace("'", "");
            String shortTok = lastDashSegment(tok).replace("'", "");
            List<String> bases = new ArrayList<>(2);
            if (cpLen(ct) <= budget) {
                bases.add(ct);
            }
            bases.add(shortTok);
            for (String base : bases) {
                if (cpLen(base) < 2 || cpLen(base) > budget) {
                    continue;
                }
                int room = budget - cpLen(base);
                if (room >= 2 && !cjk.isEmpty()) {
                    l.add(base + cpHead(cjk, room));
                    l.add(cpTail(cjk, room) + base);
                }
                l.add(base);
            }
        }

        // head-tail 档：压缩串的"头 + 尾"拼接（只在压缩后仍超预算时才有意义）。
        String c = compact(abbrevTokens(n));
        if (cpLen(c) > budget) {
            l.add(cpHead(c, 2) + cpTail(c, 4));
            l.add(cpHead(c, 3) + cpTail(c, 3));
            l.add(cpHead(c, 2) + cpTail(c, 3));
            l.add(cpHead(c, 4) + cpTail(c, 2));
        }

        // cut 档：硬截（最后手段）。
        l.add(cpHead(c, budget));
        l.add(cpHead(n, budget));
        return l.done();
    }

    /** 默认预算下的阶梯（{@link #longCandidates(String, int, boolean)} 的便捷重载）。 */
    static List<String> longCandidates(String name) {
        return longCandidates(name, MAX_LABEL, false);
    }

    /**
     * 生成器 {@code candidates()} 的 Java 版：完整候选阶梯 = 状态词缀组合（若有）+ 原阶梯兜底，
     * 全部 ≤ {@code budget} 码点。<b>这是渲染路径与运行时兜底的唯一入口</b>（PLAN §5.6）。
     *
     * <p>例：{@code 熔融高速钢-G} → {@code 熔} + {@code 高速钢-G} = {@code 熔高速钢-G}（6 字，
     * 牌号完整）；基体预算 = budget − 状态字长度，所以 {@code 熔融铝合金-2090} →
     * {@code 熔} + {@code 铝2090} = {@code 熔铝2090}。组合候选被占满时仍回落到原阶梯
     * （宁可丑，也绝不返回会被渲染层截断的超长原名）。
     */
    static List<String> candidates(String name, int budget) {
        String n = name == null ? "" : name;
        Ladder l = new Ladder(budget);
        String[] st = splitStateAffix(n);
        if (st != null) {
            String base = st[0];
            boolean isPrefix = "1".equals(st[1]);
            // 基体在**完整预算**下的首选：用来判断"缩短预算是否把基体截断了"。
            List<String> bestFull = longCandidates(base, budget, true);
            for (int idx = 0; idx < 2; idx++) {
                String mk = idx == 0 ? st[2] : st[3];
                int room = budget - cpLen(mk);
                if (room < 2) {
                    continue;
                }
                List<String> cands = longCandidates(base, room, true);
                if (cands.isEmpty()) {
                    continue;
                }
                // 长写法（`熔融`）只在**它没有让基体降级**时使用：`铝合金-2090` 在 4 字预算
                // 下首选变成截断牌号的 `铝合20`，那就必须退到短写法 `熔`（⇒ `铝2090`）。
                if (idx == 0 && !bestFull.isEmpty() && !cands.get(0).equals(bestFull.get(0))) {
                    continue;
                }
                for (String c : cands) {
                    l.add(isPrefix ? mk + c : c + mk);
                }
            }
        }
        for (String c : longCandidates(n, budget, false)) {
            l.add(c);
        }
        return l.done();
    }

    /**
     * 生成器 {@code long_candidates} 内嵌的 {@code add()}：strip → 非空 / ≤ MAX_LABEL / 去重 /
     * BAD_CHARS 卫生检查 → 入列。<b>不做</b>功能字剥离，也<b>不做</b> CJK 混排检查（与旧 MDS 不同）。
     */
    private static void addLong(List<String> out, Set<String> seen, String raw) {
        if (raw == null) {
            return;
        }
        String s = raw.strip();
        if (s.isEmpty() || cpLen(s) > MAX_LABEL || seen.contains(s)) {
            return;
        }
        if (containsBadChar(s)) {
            return;
        }
        seen.add(s);
        out.add(s);
    }

    /**
     * 生成器的 {@code _abbrev_prefix()}：返回 (前缀简称, 剩余)；无可匹配前缀时返回 null。
     * 取<b>最长命中</b>（同长取表里靠前者）。
     */
    private static String[] abbrevPrefix(String name) {
        String bestPat = null;
        String bestShort = null;
        for (String[] pair : PREFIX_ABBREV) {
            String pat = pair[0];
            if (name.startsWith(pat) && (bestPat == null || pat.length() > bestPat.length())) {
                bestPat = pat;
                bestShort = pair[1];
            }
        }
        if (bestPat == null) {
            return null;
        }
        return new String[] {bestShort, name.substring(bestPat.length())};
    }

    /** 生成器的 {@code _abbrev_tokens()}：按表顺序对整串做<b>全部</b>字面替换。 */
    private static String abbrevTokens(String s) {
        String out = s;
        for (String[] pair : TOKEN_ABBREV) {
            if (out.contains(pair[0])) {
                out = out.replace(pair[0], pair[1]);
            }
        }
        return out;
    }

    /** 生成器的 {@code _compact()}：去掉不承载区分信息的助词与分隔符。 */
    // ------------------------------------------------- 码点计数（标签预算的单位）

    /**
     * 码点数（Python {@code len(s)} 的语义）。标签预算一律按<b>码点</b>算：渲染层
     * {@code LabelRenderer.buildLayout} 就是按码点切行，用 UTF-16 长度会把一个补充平面汉字
     * （{@code 𬭳} / {@code 𫓧} / {@code 𬭶}）算成 2 字，让本该原样的名字被无谓缩短。
     */
    private static int cpLen(String s) {
        return s == null ? 0 : s.codePointCount(0, s.length());
    }

    /** 前 {@code n} 个码点（Python {@code s[:n]}；n ≤ 0 时为空串）。 */
    private static String cpHead(String s, int n) {
        if (s == null || n <= 0) {
            return "";
        }
        int cps = s.codePointCount(0, s.length());
        if (n >= cps) {
            return s;
        }
        return s.substring(0, s.offsetByCodePoints(0, n));
    }

    /** 后 {@code n} 个码点（Python {@code s[-n:]}；n ≤ 0 时为空串）。 */
    private static String cpTail(String s, int n) {
        if (s == null || n <= 0) {
            return "";
        }
        int cps = s.codePointCount(0, s.length());
        if (n >= cps) {
            return s;
        }
        return s.substring(s.offsetByCodePoints(0, cps - n));
    }

    /** 生成器的 {@code _compact()}：去掉不承载区分信息的助词与分隔符。 */
    private static String compact(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (COMPACT_DROP.indexOf(ch) < 0) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 生成器 {@code tok.split("-")[-1]}：最后一段（无 '-' 时就是原串）。 */
    private static String lastDashSegment(String tok) {
        int i = tok.lastIndexOf('-');
        return i < 0 ? tok : tok.substring(i + 1);
    }

    /**
     * 无解标签的唯一化：优先名字本身，被占用则依次追加 2、3、…（绝不返回空串）。
     */
    private static String uniqueLabel(String base, Set<String> taken) {
        String s = base == null ? "" : base;
        if (s.isEmpty()) {
            s = "?";
        }
        if (!taken.contains(s)) {
            return s;
        }
        for (int i = 2; i < 10000; i++) {
            String cand = s + i;
            if (!taken.contains(cand)) {
                return cand;
            }
        }
        return s + "?";
    }

    // ------------------------------------------------- 阶段 3：显示截断救援（生成器 _rescue_truncation）

    /**
     * 把「完整标签互异、但截断到可见长度后同形」的名字重新命名。
     *
     * <p>渲染端几何上限 = {@code maxChars × maxLines}（默认 3 × 2 = {@link #MAX_LABEL} 字），
     * 超长标签会被静默截断 ⇒ 两条算法认为互异的长标签在屏幕上可能同形（历史实测两组：
     * {@code UHTC-X1000超高温复合材料} 与 {@code UHTC-X2000超高温复合材料} 都截成 {@code UHTC-X}；
     * {@code 哈斯特洛依合金-N} 与 {@code 哈斯特洛依合金-X} 都截成 {@code 哈斯特洛依合}）。
     * T1 下正常标签都 ≤ 6 字，只有"阶梯无解 ⇒ 退回全名"的极少数条目可能触发这一层。
     *
     * <p>只动真正撞车的那几名，其余标签原样保留（避免整表漂移）。
     * <b>与 {@code tools/gen_labels.py} 的 {@code _rescue_truncation} 同构，两侧必须同步修改。</b>
     *
     * @param labels 显示名 → 标签（原地修改）
     */
    private static void rescueTruncation(Map<String, String> labels) {
        Set<String> takenFull = new HashSet<>(labels.values());
        Set<String> takenVis = new HashSet<>();
        for (String lab : labels.values()) {
            takenVis.add(visible(lab));
        }
        Map<String, List<String>> groups = new HashMap<>();
        for (Map.Entry<String, String> e : labels.entrySet()) {
            groups.computeIfAbsent(visible(e.getValue()), x -> new ArrayList<>(2)).add(e.getKey());
        }
        List<String> groupKeys = new ArrayList<>(groups.keySet());
        groupKeys.sort(DistinctMds::compareCodePoints);
        for (String vis : groupKeys) {
            List<String> names = groups.get(vis);
            if (names.size() < 2) {
                continue;
            }
            names.sort(DistinctMds::compareCodePoints);
            for (String name : names) {
                String current = labels.get(name);
                if (current == null || cpLen(current) <= MAX_LABEL) {
                    continue;                       // 短标签就是它自己的可见形式，不动它
                }
                for (String cand : rescueCandidates(name)) {
                    if (takenFull.contains(cand) || takenVis.contains(visible(cand))) {
                        continue;
                    }
                    labels.put(name, cand);
                    takenFull.add(cand);
                    takenVis.add(visible(cand));
                    break;
                }
            }
        }
    }

    /** 标签**真正被画出来**的部分（生成器 {@code lab[:VIS]}，按码点）。 */
    private static String visible(String label) {
        return cpLen(label) <= MAX_LABEL ? label : cpHead(label, MAX_LABEL);
    }

    /**
     * 超长名的救援候选，优先带区分信息的那一段（生成器 {@code _rescue_candidates}）。
     *
     * <p>两条实测教训：<b>不能用牌号截断轮</b> —— 它会把 {@code UHTC-X1000} 压成
     * {@code UHT} + {@code 000}，恰好丢掉区分用的数字，而 {@code 000} 往往已被别的材料占用
     * （{@code T1000碳 → 000}）；救援层也<b>必须允许 CJK 与拉丁/数字混排</b>，因为
     * {@code 哈斯特洛依合金-N} 的唯一区分点就是后缀 {@code -N} / {@code -X}，通用候选的
     * "混排整条丢弃"规则在救援层过严（这里只保留 {@code BAD_CHARS} 检查）。
     */
    private static List<String> rescueCandidates(String name) {
        List<String> cands = new ArrayList<>(8);
        List<String> toks = new ArrayList<>(rawTokens(name));
        toks.sort((a, b) -> {
            int c = Integer.compare(cpLen(b), cpLen(a));
            return c != 0 ? c : compareCodePoints(a, b);
        });
        for (String t : toks) {                 // 1. 完整牌号的尾段带区分信息（X1000 / X2000）
            if (cpLen(t) > MAX_LABEL) {
                addRescue(cands, cpTail(t, MAX_LABEL));
            }
        }
        for (String t : toks) {
            if (cpLen(t) > 3) {
                addRescue(cands, cpTail(t, 3));
                addRescue(cands, cpHead(t, 3));
            }
        }
        if (cpLen(name) > MAX_LABEL) {          // 2. 头 + 尾：哈斯特洛依合金-N → `哈斯特-N`
            addRescue(cands, cpHead(name, 3) + cpTail(name, 2));
            addRescue(cands, cpHead(name, 3) + cpTail(name, 3));
            addRescue(cands, cpHead(name, 2) + cpTail(name, 4));
            addRescue(cands, cpHead(name, 4) + cpTail(name, 2));
        }
        for (String t : toks) {                 // 3. 短 token 本身（牌号、`50` 之类）
            addRescue(cands, t);
        }
        addRescue(cands, cpHead(name, MAX_LABEL));   // 4. 最后手段
        return cands;
    }

    /**
     * 救援候选清洗：剥首尾功能字、要求 {@code 2 ≤ len ≤ MAX_LABEL}、拒 {@code BAD_CHARS}。
     * 与通用 {@link #addLong} 的关键差别是<b>不</b>丢弃 CJK + 拉丁混排（见上方说明）。
     */
    private static void addRescue(List<String> out, String raw) {
        String s = stripFunc(raw);
        if (cpLen(s) < 2 || cpLen(s) > MAX_LABEL || containsBadChar(s) || out.contains(s)) {
            return;
        }
        out.add(s);
    }

    /**
     * 原始拉丁/数字串，<b>不做任何截断</b>（救援需要完整牌号，如 {@code UHTC-X1000}）。
     * 扫描规则与生成器 {@code _raw_tokens} / {@code _alnum_tokens} 的正则
     * {@code [A-Za-z0-9][A-Za-z0-9'\-]*} 一致，保持出现顺序。
     */
    private static List<String> rawTokens(String name) {
        List<String> out = new ArrayList<>(4);
        int i = 0;
        int n = name.length();
        while (i < n) {
            if (!isTokenStart(name.charAt(i))) {
                i++;
                continue;
            }
            int j = i + 1;
            while (j < n && isTokenPart(name.charAt(j))) {
                j++;
            }
            out.add(name.substring(i, j));
            i = j;
        }
        return out;
    }

    // ------------------------------------------------------------------ 字符工具

    /** 候选清洗：先去首尾空白，再反复剥掉首尾的功能字（生成器 rescue add() 的前两步）。 */
    private static String stripFunc(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String s = raw.strip();
        int from = 0;
        int to = s.length();
        while (from < to && FUNC_CHARS.indexOf(s.charAt(from)) >= 0) {
            from++;
        }
        while (to > from && FUNC_CHARS.indexOf(s.charAt(to - 1)) >= 0) {
            to--;
        }
        return s.substring(from, to);
    }

    private static boolean containsBadChar(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (BAD_CHARS.indexOf(s.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    /** ASCII 字母数字（生成器 {@code ch.isascii() and ch.isalnum()} 的 ASCII 半边）。 */
    private static boolean isAsciiAlnum(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    private static boolean isTokenStart(char c) {
        return isAsciiAlnum(c);
    }

    private static boolean isTokenPart(char c) {
        return isTokenStart(c) || c == '\'' || c == '-';
    }

    /**
     * 按 Unicode 码点比较（生成器用的是 Python str 排序）；仅补充平面字符与
     * {@link String#compareTo} 有别，其余情况与之一致。
     */
    private static int compareCodePoints(String a, String b) {
        int i = 0;
        int j = 0;
        int la = a.length();
        int lb = b.length();
        while (i < la && j < lb) {
            int ca = a.codePointAt(i);
            int cb = b.codePointAt(j);
            if (ca != cb) {
                return ca < cb ? -1 : 1;
            }
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(la - i, lb - j);
    }
}
