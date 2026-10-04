package com.fluidlabel;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * 标签引擎：三层来源 + 两档语义（PLAN §5.3）。
 *
 * <pre>
 * 1. 外部人工别名表   config/fluidlabel-aliases.json      （显示名为键，最高优先级）
 * 2. 内置预计算表     jar 内 /fluidlabel_labels.json      （byRegistryName / byDisplayName 双索引）
 * 3. 运行时规则兜底   DistinctMds（任意模组都可用，无条件生效）
 * </pre>
 *
 * <p>档位：
 * <ul>
 *   <li>STABLE（默认）：兜底 MDS 的池 = 内置表全部显示名（1900 条）⇒ 跨界面恒定。</li>
 *   <li>DYNAMIC：池 = {@link FrameContext} 上一帧已发布的液体集合 ⇒ 标签更短更贴切。</li>
 * </ul>
 *
 * <p>性能（PLAN §5.4）：渲染热路径只做 HashMap 查表 / 集合命中，禁止 {@code Component#getString()}、
 * 正则、{@code String.format}、反射；{@code displayName.getString()} 的结果在
 * {@link #nameCache} 里按 ResourceLocation 缓存一次。
 */
public final class LabelEngine {

    /** 注册名 → 显示名字符串（跨帧缓存；避开每帧 Component#getString）。 */
    private static final Map<String, String> NAME_CACHE = new ConcurrentHashMap<>();
    /** STABLE 档「规则兜底」的结果（池=全表，只算一次）。 */
    private static volatile Map<String, String> stableFallback = null;
    /** DYNAMIC 档最近一次已发布的标签快照。 */
    private static volatile Map<String, String> dynamicSnapshot = java.util.Collections.emptyMap();
    /** DYNAMIC 快照对应的 epoch。 */
    private static volatile long dynamicEpoch = -1L;
    /** 构建 dynamicSnapshot 时的重入保护。 */
    private static final Object DYNAMIC_LOCK = new Object();
    /** 已就"表不可用"打过日志。 */
    private static volatile boolean warnedNoTable = false;
    /** 池外名字的预算内兜底标签（键 = 注册名，缺则显示名键）；跨帧稳定。 */
    private static final Map<String, String> EXTRA_LABELS = new ConcurrentHashMap<>();
    /** {@link #EXTRA_LABELS} 的占用集合（惰性建立）。 */
    private static volatile java.util.Set<String> extraTaken = null;
    /** {@link #EXTRA_LABELS} 上限（防病态整合包把内存撑爆）。 */
    private static final int MAX_EXTRA_LABELS = 8192;

    private LabelEngine() {
    }

    /**
     * 取一个液体的标签。
     *
     * @param id          注册名（{@code FluidEmiStack#getId()}）；可为 null
     * @param displayName EMI/原版翻译后的显示名（{@code FluidEmiStack#getName()}）；可为 null
     * @return 非空标签；空串表示"什么都不该画"
     */
    public static String labelFor(ResourceLocation id, Component displayName) {
        String idStr = id == null ? null : id.toString();

        String name = null;
        if (idStr != null) {
            name = NAME_CACHE.get(idStr);
        }
        if (name == null) {
            name = safeName(displayName, id);
            if (idStr != null) {
                NAME_CACHE.put(idStr, name);
            }
        }
        if (name.isEmpty()) {
            return "";
        }

        // DYNAMIC 档：优先用本帧已发布的互异标签（O(1) 查表）。
        if (FluidLabelConfig.mode() == FluidLabelConfig.Mode.DYNAMIC) {
            String dyn = dynamicLabel(idStr, name);
            if (!dyn.isEmpty()) {
                return dyn;
            }
        }

        // 别名表（最高优先级）。
        String label = LabelTables.aliasLabel(name);
        if (label != null && !label.isEmpty()) {
            return label;
        }

        FluidLabelConfig.LabelSource source = FluidLabelConfig.labelSource();
        if (source != FluidLabelConfig.LabelSource.RULE_ONLY) {
            if (idStr != null) {
                label = LabelTables.labelByRegistryName(idStr);
                if (label != null && !label.isEmpty()) {
                    return label;
                }
            }
            label = LabelTables.labelByDisplayName(name);
            if (label != null && !label.isEmpty()) {
                return label;
            }
            if (source == FluidLabelConfig.LabelSource.TABLE_ONLY) {
                return ""; // 只信表：查不到就不画
            }
        }

        // 第 3 层：运行时规则兜底（无条件可用，保证功能不会失效）。
        return ruleFallback(idStr, name);
    }

    /** DYNAMIC 档查表；epoch 变更时先重建快照。 */
    private static String dynamicLabel(String idStr, String name) {
        ensureDynamicSnapshot();
        if (idStr != null) {
            String hit = dynamicSnapshot.get(idStr);
            if (hit != null) {
                return hit;
            }
        }
        String byName = dynamicSnapshot.get(nameKey(name));
        return byName == null ? "" : byName;
    }

    /**
     * 用「上一帧已发布的液体集合」重算一套互异标签。每帧最多一次，绘制路径不再重算。
     */
    private static void ensureDynamicSnapshot() {
        long epoch = FrameContext.epoch();
        if (epoch == dynamicEpoch) {
            return;
        }
        synchronized (DYNAMIC_LOCK) {
            if (epoch == dynamicEpoch) {
                return;
            }
            Map<String, String> snapshot = FrameContext.publishedLabels();
            if (snapshot.isEmpty()) {
                Map<String, String> nameByKey = new LinkedHashMap<>();
                for (ResourceLocation rl : FrameContext.currentFrame()) {
                    if (rl == null) {
                        continue;
                    }
                    String idStr = rl.toString();
                    String nm = NAME_CACHE.get(idStr);
                    nameByKey.put(idStr, nm == null ? guessDisplayName(rl) : nm);
                }
                if (!nameByKey.isEmpty()) {
                    Map<String, String> assigned = DistinctMds.assignLabels(nameByKey);
                    assigned = applyBuiltinAnchors(assigned, nameByKey);
                    Map<String, String> byId = new LinkedHashMap<>(assigned);
                    // 同时按显示名建一份索引（NAME_CACHE 可能还没填上）。
                    // 注意：键前缀 "\u0000" 保证显示名键不会与注册名键相撞。
                    for (Map.Entry<String, String> e : nameByKey.entrySet()) {
                        String label = assigned.get(e.getKey());
                        if (label != null) {
                            byId.put(nameKey(e.getValue()), label);
                        }
                    }
                    assigned = java.util.Collections.unmodifiableMap(byId);
                    snapshot = assigned;
                }
                // 无论集合是否为空都发布一次，避免每帧重算。
                FrameContext.publishLabels(snapshot);
            }
            dynamicSnapshot = snapshot;
            dynamicEpoch = epoch;
        }
    }

    /**
     * DYNAMIC 档也遵守人工锚点（PLAN §0.4/§5.3）：把随包锚点叠加到本帧重算结果上。
     *
     * <p>为什么需要：DYNAMIC 档不查预计算表，若不叠加这层，同一个液体在 STABLE/DYNAMIC
     * 两档下会显示不同标签（人工挑过的关键字消失）。
     *
     * <p>实现放在 {@link DistinctMds#applyAnchors} —— 纯函数、不依赖 Minecraft 类型，
     * 因此可用 {@code tools/parity/DynamicAnchorCheck.java} 离线测试（含冲突消解）。
     */
    private static Map<String, String> applyBuiltinAnchors(Map<String, String> assigned,
                                                           Map<String, String> nameByKey) {
        return DistinctMds.applyAnchors(assigned, nameByKey, LabelTables.builtinAnchors());
    }

    /** STABLE 档的规则兜底：池 = 内置表全部显示名（只算一次，之后 O(1)）。 */
    private static String ruleFallback(String idStr, String name) {
        if (!LabelTables.tableAvailable() && !warnedNoTable) {
            warnedNoTable = true;
            if (FluidLabelConfig.debugLog()) {
                FluidLabelMod.LOGGER.info(
                        "[fluidlabel] 内置标签表不可用，全部标题走运行时规则兜底。");
            }
        }
        Map<String, String> cache = stableFallback;
        if (cache == null) {
            synchronized (DYNAMIC_LOCK) {
                cache = stableFallback;
                if (cache == null) {
                    cache = buildStableFallback();
                    stableFallback = cache;
                }
            }
        }
        String hit = idStr == null ? null : cache.get(idStr);
        if (hit == null) {
            hit = cache.get(nameKey(name));
        }
        if (hit != null) {
            return hit;
        }
        return extraSafeLabel(idStr, name);
    }

    /**
     * 兜底里的兜底：池（冻结于首次构建）里没有这个名字时，<b>绝不能返回原名</b> ——
     * 超过 6 码点的原名会被渲染层按 3 字 × 2 行截断成同形，用户 2026-10 报的
     * {@code 熔融铝合金-} / {@code 熔融高速钢-} 正是这条老路径造成的。
     *
     * <p>用与生成器同一套阶梯（{@link DistinctMds#labelForName}）挑一个预算内、当前未被
     * 占用的标签，并惰性缓存 ⇒ 同一液体跨帧稳定。
     */
    private static String extraSafeLabel(String idStr, String name) {
        String key = idStr == null ? nameKey(name) : idStr;
        String cached = EXTRA_LABELS.get(key);
        if (cached != null) {
            return cached;
        }
        synchronized (DYNAMIC_LOCK) {
            cached = EXTRA_LABELS.get(key);
            if (cached != null) {
                return cached;
            }
            Set<String> taken = extraTaken;
            if (taken == null) {
                taken = new HashSet<>();
                Map<String, String> base = stableFallback;
                if (base != null) {
                    taken.addAll(base.values());
                }
                extraTaken = taken;
            }
            String label = DistinctMds.labelForName(name, taken);
            taken.add(label);
            if (EXTRA_LABELS.size() < MAX_EXTRA_LABELS) {
                EXTRA_LABELS.put(key, label);
            }
            return label;
        }
    }

    /** 池 = 全表显示名（STABLE）+ 已缓存显示名 + 本帧已发布集合；结果同时按注册名与显示名索引。 */
    private static Map<String, String> buildStableFallback() {
        Map<String, String> nameByKey = new LinkedHashMap<>();
        for (String display : LabelTables.displayPool()) {
            nameByKey.put(nameKey(display), display);
        }
        for (Map.Entry<String, String> e : NAME_CACHE.entrySet()) {
            String display = e.getValue();
            if (display != null && !display.isEmpty()) {
                nameByKey.putIfAbsent(nameKey(display), display);
            }
        }
        // 本帧已发布的集合（动态档刚渲染过的那些）也要进池，否则最早几帧只有极少数名字、
        // 标签会与后续帧不一致。
        for (ResourceLocation rl : FrameContext.currentFrame()) {
            if (rl == null) {
                continue;
            }
            String idStr = rl.toString();
            String nm = NAME_CACHE.get(idStr);
            String display = nm == null ? guessDisplayName(rl) : nm;
            if (!display.isEmpty()) {
                nameByKey.putIfAbsent(nameKey(display), display);
            }
        }
        if (nameByKey.isEmpty()) {
            return java.util.Collections.emptyMap();
        }
        Map<String, String> assigned = DistinctMds.assignLabels(nameByKey);
        Map<String, String> out = new LinkedHashMap<>(assigned);
        // 注册名索引：借用泛化的 key 约定（以 "\u0000" 前缀区分，避免与显示名撞键）
        for (Map.Entry<String, String> e : NAME_CACHE.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            String byDisplay = assigned.get(nameKey(e.getValue()));
            if (byDisplay != null) {
                out.put(e.getKey(), byDisplay);
            }
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    /**
     * 清空全部缓存（配置重载 / 别名表变更后调用）。
     * Signature 由 PLAN §⑨.2 钉死。
     */
    public static void invalidateCache() {
        NAME_CACHE.clear();
        stableFallback = null;
        dynamicSnapshot = java.util.Collections.emptyMap();
        dynamicEpoch = -1L;
        FrameContext.invalidateLabelsOnly();
    }

    /** 追加一条「注册名 → 显示名」映射（给 dumpRegistry 自检用）。 */
    public static void rememberName(ResourceLocation id, Component displayName) {
        if (id == null) {
            return;
        }
        // 热路径（每个液体槽每帧都会调到这里，20–200 槽 × 60fps）：先查缓存再碰
        // Component#getString()，否则 enabled=false 时也照跑字符串化（v-review 风险 2）。
        String key = id.toString();
        if (NAME_CACHE.containsKey(key)) {
            return;
        }
        String name = safeName(displayName, id);
        if (!name.isEmpty()) {
            NAME_CACHE.putIfAbsent(key, name);
        }
    }

    /** 已缓存的 (注册名 → 显示名) 快照。 */
    public static Map<String, String> knownNames() {
        return NAME_CACHE;
    }

    /** 把显示名当键时的规范形式（避免与注册名键混淆）。 */
    private static String nameKey(String display) {
        return display == null ? "" : "\u0000" + display;
    }

    private static String safeName(Component displayName, ResourceLocation id) {
        try {
            if (displayName != null) {
                String s = displayName.getString();
                if (s != null && !s.isEmpty()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
            // 翻译层出问题也不能崩渲染
        }
        return id == null ? "" : guessDisplayName(id);
    }

    /** 没有显示名时的兜底：用注册名的 path 段（比全 null 好，至少能区分）。 */
    private static String guessDisplayName(ResourceLocation id) {
        try {
            String path = id.getPath();
            return path == null ? "" : path;
        } catch (Throwable t) {
            return "";
        }
    }
}
