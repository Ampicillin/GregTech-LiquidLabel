package com.fluidlabel.compat;

import java.util.Map;

import com.fluidlabel.FluidLabelConfig;
import com.fluidlabel.FluidLabelMod;
import com.fluidlabel.LabelEngine;

/**
 * AE2 类引用隔离层（PLAN §6.3 / §6.5；与 {@link EmiCompat} 同思路）。
 *
 * <p>这一层做三件事：
 * <ol>
 *   <li><b>类型隔离</b>：所有 {@code appeng.*} 引用只出现在本类（与 {@code mixin/}、
 *       {@code Ae2SelfCheck} 并列）。对外入口的参数/返回值全是 {@code Object} / JDK / 原版
 *       类型，{@code LabelRenderer} 因此不需要 import 任何 AE2 类型；</li>
 *   <li><b>标签来源与 EMI 侧同源</b>：{@link #labelOf(Object)} 只调
 *       {@link LabelEngine#labelFor}，<b>不引入任何新规则</b>（PLAN §6.3）；</li>
 *   <li><b>热路径缓存</b>：{@code getId().toString()} / {@code Component#getString()} /
 *       tint 解析都只允许发生在"首次见到这个键"那一次，之后 O(1) 查 Map
 *       （PLAN §5.6 与 §6.3）。</li>
 * </ol>
 *
 * <p><b>安静失效（A6 / R-AE2-10）</b>：AE2 任意版本导致的问题只能让标签不画，
 * 绝不能崩 —— 每个方法都包 {@code try/catch} 并返回安全默认值
 * （{@code false} / {@code null} / {@code ""} / {@code 0xFFFFFFFF}）。
 * 首次异常打一行 WARN，之后不再重复（不刷屏）。
 *
 * <p>为什么缓存键直接用 {@code AEFluidKey} 对象：该类是 {@code final} 且实现了
 * {@code equals/hashCode}（AE2 侧 {@code hashCode} 在构造期算好），把它当 Map 键既能命中
 * "同一液体换 NBT/温度"这类同键场景，又比每次拼 {@code ResourceLocation#toString()} 便宜。
 */
public final class Ae2Compat {

    /** 缓存条目上限；超过就整体清空（与 {@code LabelRenderer.LAYOUTS} 同策略）。 */
    public static final int CACHE_LIMIT = 4096;

    /**
     * AE2 键 → 该键的静态信息（注册名 / 显示名 / 最终标签 / tint）。
     *
     * <p>用 {@code ConcurrentHashMap} 而不是 access-order 的 {@code LinkedHashMap}：
     * 渲染路径要无锁读取（{@code LinkedHashMap.get()} 会改内部链表，并发读会损坏结构）。
     * 淘汰策略与 {@code LabelRenderer.LAYOUTS} 一致：超过上限就<b>整体清空</b>
     * （重建很便宜，比维护 LRU 划算，也免掉每次读都要加锁）。
     */
    private static final Map<Object, KeyInfo> INFOS = new java.util.concurrent.ConcurrentHashMap<>();

    /** 建缓存的互斥锁：保证同一个键不会并发走两次 {@code getDisplayName()}/{@code toString()}。 */
    private static final Object BUILD_LOCK = new Object();

    /** 已就"AE2 侧调用失败"打过一次 WARN（不刷屏，PLAN §6.3）。 */
    private static volatile boolean warned = false;

    private Ae2Compat() {
    }

    // ------------------------------------------------------------------ 类型判定

    /**
     * 是不是 AE2 的<b>液体</b>键（{@code AEFluidKey}）。
     *
     * <p>非液体键一律不画标签（AE2 的物品键是 {@code AEItemKey}；GT 侧
     * {@code GenericStack.what()} 也可能是物品键）。
     *
     * <p>注意用 {@code AEFluidKey.is(AEKey)} 而不是
     * {@code AEFluidKey#of(FluidStack)}：后者对空栈返回 {@code null}（R-AE2-6），
     * 且这里根本不需要构造任何东西。
     */
    public static boolean isFluidKey(Object key) {
        if (key == null) {
            return false;
        }
        try {
            return key instanceof appeng.api.stacks.AEKey aeKey
                    && appeng.api.stacks.AEFluidKey.is(aeKey);
        } catch (Throwable t) {
            warnOnce("isFluidKey", t);
            return false;
        }
    }

    /**
     * 液体键的注册名（{@code "gtceu:severely_hydro_cracked_naphtha"}）。
     *
     * <p>与 EMI 侧 {@code FluidEmiStack#getId()} 同源（都是
     * {@code BuiltInRegistries.FLUID.getKey(fluid)}），所以两侧的标签天然一致。
     *
     * <p>这里<b>不</b>缓存：本方法只被"建缓存那一次"和 DYNAMIC 档的观测调用，
     * 不是每帧每槽路径（每帧路径走 {@link #labelOf(Object)} 的 Map 命中）。
     *
     * @return 注册名；取不到返回 {@code null}（{@code null} = "没有注册名"，调用方应跳过）
     */
    public static String idOf(Object key) {
        if (key == null) {
            return null;
        }
        try {
            if (key instanceof appeng.api.stacks.AEFluidKey fluidKey) {
                return fluidKey.getId().toString();
            }
            return null;
        } catch (Throwable t) {
            warnOnce("idOf", t);
            return null;
        }
    }

    // ------------------------------------------------------------------ 标签 / 显示名

    /**
     * 最终标签（已是中文短标签，≤6 码点）。空串 = "没有标签，不要画"。
     *
     * <p><b>构造期</b>（每键一次）取显示名 + 调 {@link LabelEngine#labelFor}；
     * <b>命中后</b>是纯 Map 查表，热路径上没有 {@code Component#getString()}、
     * 没有 {@code ResourceLocation#toString()}、没有正则/反射（PLAN §5.6）。
     */
    public static String labelOf(Object key) {
        if (key == null) {
            return "";
        }
        if (!FluidLabelConfig.enabled()) {
            return "";                                              // A5 短路，不触缓存
        }
        KeyInfo info = infoOf(key);
        return info == null ? "" : info.label;
    }

    /**
     * 该键的显示名（AE2/原版翻译后的名字）。取不到返回 {@code null}。
     *
     * <p>与 {@link #labelOf(Object)} 一样走 per-key 缓存；AE2 侧
     * {@code AEKeyRendering#getDisplayName} 每次调用都会分配一个 {@code Component}，
     * 绝不能在每帧每槽路径上直接调。
     */
    public static String displayNameOf(Object key) {
        if (key == null) {
            return null;
        }
        KeyInfo info = infoOf(key);
        return info == null ? null : info.name;
    }

    /**
     * 该键是否已经有可画的标签（供 {@code LabelRenderer} 的渲染前短路用）。
     *
     * <p>用 <b>identity</b>（{@code ==}）而不是 {@code equals} 比较缓存命中：本方法在
     * 热路径上被调用，而不命中时返回 {@code false} 只是让调用方早退一次，语义完全安全。
     */
    public static boolean hasLabelFor(Object key) {
        if (key == null) {
            return false;
        }
        KeyInfo info = INFOS.get(key);
        return info != null && key == info.key && !info.label.isEmpty();
    }

    /** 该键的显示名字符串；{@code null} 表示取不到（自检用，不要放在热路径上）。 */
    public static String debugIdOf(Object key) {
        KeyInfo info = INFOS.get(key);
        return info == null ? null : info.id;
    }

    // ------------------------------------------------------------------ tint

    /**
     * 图标底色 tint（ARGB，仅 {@code adaptiveTextColor=true} 时用于判对比度）。取不到/异常时返回
     * {@code 0xFFFFFFFF}（白）⇒ 自适应档下为浅色字 + 黑描边，是安全默认（PLAN §6.4 第 6 点）。
     *
     * <p>Forge 1.20.1 的常规做法：{@code IClientFluidTypeExtensions.of(FluidStack).getTintColor()}。
     * 同一液体的 tint 是常量，按 key 缓存一次即可；本方法只会在"首次见到该键"时付一次代价。
     */
    public static int tintOf(Object key) {
        if (key == null) {
            return 0xFFFFFFFF;
        }
        KeyInfo info = infoOf(key);
        if (info == null) {
            return 0xFFFFFFFF;
        }
        if (!info.tintResolved) {
            info.tint = resolveTint(key);
            info.tintResolved = true;
        }
        return info.tint;
    }

    // ------------------------------------------------------------------ 缓存

    /**
     * 清空本类的所有缓存。
     *
     * <p>调用时机：配置加载/重载（{@code FluidLabelMod.onConfigEvent}）。幂等：
     * 缓存本来就空时也只是清一遍。
     */
    public static void clearCaches() {
        INFOS.clear();
    }

    /** 当前缓存条目数（自检/调试用）。 */
    public static int cacheSize() {
        return INFOS.size();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 取（必要时建立）该键的静态信息。
     *
     * <p>失败时也写入一条"空信息"：热路径上不能因为一个坏键就每帧都重试
     * （重试意味着每帧 {@code getDisplayName} + {@code toString}）。真正的过滤在
     * {@link #isFluidKey(Object)} —— 非液体键根本不会走到这里。
     */
    private static KeyInfo infoOf(Object key) {
        KeyInfo cached = INFOS.get(key);
        if (cached != null && key == cached.key) {
            return cached;
        }
        synchronized (BUILD_LOCK) {
            KeyInfo again = INFOS.get(key);
            if (again != null && key == again.key) {
                return again;
            }
            KeyInfo built = build(key);
            if (INFOS.size() > CACHE_LIMIT) {
                INFOS.clear();                          // 超限整体清空（与 LabelRenderer.LAYOUTS 同策略）
            }
            INFOS.put(key, built);
            return built;
        }
    }

    /** 建缓存：这里是全类唯一允许出现 {@code getString()} / {@code toString()} 的地方。 */
    private static KeyInfo build(Object key) {
        String id = null;
        String name = null;
        try {
            appeng.api.stacks.AEKey aeKey = (appeng.api.stacks.AEKey) key;
            net.minecraft.resources.ResourceLocation rl = aeKey.getId();
            id = rl == null ? null : rl.toString();      // 每个键只 toString 一次（§5.6）
            name = aeKey.getDisplayName().getString();
        } catch (Throwable t) {
            warnOnce("buildKeyInfo", t);
            return new KeyInfo(key, id, name, "");
        }
        String label = "";
        try {
            if (name != null && !name.isEmpty()) {
                label = LabelEngine.labelFor(parseId(id), net.minecraft.network.chat.Component.literal(name));
            }
        } catch (Throwable t) {
            warnOnce("labelFor", t);
        }
        return new KeyInfo(key, id, name, label == null ? "" : label);
    }

    /**
     * tint 解析（只在首次见到该键时调用一次）。
     *
     * <p>整体 {@code try/catch}：Forge 的 fluid-type 扩展在"没有注册 IFluidType"或
     * "客户端还没初始化"时都可能抛异常，一律退化 {@code 0xFFFFFFFF}。
     */
    private static int resolveTint(Object key) {
        try {
            if (!(key instanceof appeng.api.stacks.AEFluidKey fluidKey)) {
                return 0xFFFFFFFF;
            }
            net.minecraftforge.fluids.FluidStack stack = fluidKey.toStack(1);
            if (stack == null || stack.isEmpty()) {
                return 0xFFFFFFFF;                     // 不用 AEFluidKey#of(FluidStack)：空栈返回 null
            }
            return net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions
                    .of(stack.getFluid()).getTintColor(stack);
        } catch (Throwable t) {
            warnOnce("tintOf", t);
            return 0xFFFFFFFF;
        }
    }

    /** 注册名字符串 → {@code ResourceLocation}（只在建缓存那一次调用）。 */
    private static net.minecraft.resources.ResourceLocation parseId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return net.minecraft.resources.ResourceLocation.tryParse(id);
        } catch (Throwable t) {
            warnOnce("parseId", t);
            return null;
        }
    }

    /** 首次异常打一行 WARN（不刷屏）；后续同类异常只返回安全默认值。 */
    private static void warnOnce(String where, Throwable t) {
        if (warned) {
            return;
        }
        warned = true;
        FluidLabelMod.LOGGER.warn(
                "[fluidlabel] AE2 侧调用失败（{}）：后续同类异常静默处理，标签功能降级不影响游戏。", where, t);
    }

    /**
     * 一个液体键的静态信息（不可变 id/name/label；tint 惰性解析）。
     *
     * <p>用普通类而不是 {@code record}：{@code record} 的字段是 {@code final}，
     * 而 tint 必须在"首次真正要用"时才算（建缓存路径上不应该碰
     * {@code IClientFluidTypeExtensions}）。
     */
    private static final class KeyInfo {
        final Object key;
        final String id;
        final String name;
        final String label;
        volatile int tint = 0xFFFFFFFF;
        volatile boolean tintResolved = false;

        KeyInfo(Object key, String id, String name, String label) {
            this.key = key;
            this.id = id;
            this.name = name;
            this.label = label == null ? "" : label;
        }
    }
}
