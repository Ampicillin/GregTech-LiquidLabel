package com.fluidlabel.compat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fluidlabel.FluidLabelConfig;
import com.fluidlabel.FluidLabelMod;
import com.fluidlabel.LabelEngine;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;

/**
 * LDLib 侧适配（PLAN §7）：把 {@code com.lowdragmc.lowdraglib.side.fluid.FluidStack}
 * 归一到「注册名 + 显示名 + 标签 + tint」，供注入点 5/6
 * （{@code com.lowdragmc.lowdraglib.gui.util.DrawerHelper#drawFluidForGui}）使用。
 *
 * <p>与 {@link Ae2Compat} 同构的纪律：
 * <ul>
 *   <li><b>建缓存</b>（首次见到某个液体）才允许调 {@code getDisplayName()} /
 *       {@code ResourceLocation#toString()}；每帧每槽路径只做一次 {@code ConcurrentHashMap}
 *       命中，热路径上零 {@code Component#getString()}、零 {@code toString()}、零正则/反射
 *       （PLAN §5.6）；</li>
 *   <li>任何异常都降级为安全默认值（不画标签 / 白色 tint），只打一行 WARN（A6）。</li>
 * </ul>
 *
 * <p><b>★ 缓存键用 {@code net.minecraft.world.level.material.Fluid}，不用 FluidStack</b>：
 * GT 的 {@code TankWidget} 每帧都会 {@code FluidHelperImpl.toFluidStack(...)} new 一个新的
 * LDLib 栈对象，用栈对象当键等于每帧重建缓存（也就等于每帧调 {@code getDisplayName()}）。
 *
 * <p><b>已知取舍</b>：显示名按「液体」缓存，忽略 NBT 差异。GT 的显示名只由液体本身决定
 * （{@code gtceu.fluid.molten = 熔融%s} 这类模板的实参是材料名，不含 NBT），因此这是安全的；
 * 反过来，注册名与显示名都不会因为 NBT 而变。
 */
public final class LdlibFluidCompat {

    /**
     * 液体 → 静态信息。
     *
     * <p>用 {@code ConcurrentHashMap}：渲染路径要无锁读取。淘汰策略与
     * {@code LabelRenderer.LAYOUTS} / {@link Ae2Compat} 一致 —— 超过上限就<b>整体清空</b>
     * （重建很便宜，比维护 LRU 划算，也免掉每次读都要加锁）。
     */
    private static final Map<Fluid, Info> INFOS = new ConcurrentHashMap<>();

    /** 缓存上限（整合包的液体种类上千，4096 足够且不会漏）。 */
    private static final int CACHE_LIMIT = 4096;

    /** 建缓存的互斥锁：保证同一个液体不会并发走两次 {@code getDisplayName()}。 */
    private static final Object BUILD_LOCK = new Object();

    /** 已就「LDLib 侧调用失败」打过一次 WARN，不刷屏。 */
    private static volatile boolean warned = false;

    private LdlibFluidCompat() {
    }

    // ------------------------------------------------------------------ 类型判定

    /**
     * 是不是一个"画得出来"的液体栈。
     *
     * <p>{@code DrawerHelper#drawFluidForGui} 对空栈会提前 return（我们注入在 RETURN，
     * 每个出口都会回调一次），所以这里必须把空栈挡掉，否则会给"什么都没画的格子"贴标签。
     */
    public static boolean isFluid(Object stack) {
        if (stack == null) {
            return false;
        }
        try {
            if (!(stack instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack fluidStack)) {
                return false;
            }
            Fluid fluid = fluidStack.getFluid();
            return fluid != null
                    && fluid != net.minecraft.world.level.material.Fluids.EMPTY
                    && !fluidStack.isEmpty();
        } catch (Throwable t) {
            warnOnce("isFluid", t);
            return false;
        }
    }

    // ------------------------------------------------------------------ 标签 / tint

    /**
     * 注册名（{@code "gtceu:sulfuric_acid"}）；取不到返回 {@code null}。
     *
     * <p>与 EMI 侧 {@code FluidEmiStack#getId()}、AE2 侧 {@code AEFluidKey#getId()} 同源
     * （都是 {@code ForgeRegistries.FLUIDS.getKey(fluid)}）⇒ 三条路径的标签天然一致（PLAN §7.2）。
     */
    public static String idOf(Object stack) {
        Info info = infoOf(stack);
        return info == null ? null : info.id;
    }

    /** 该液体的显示名；取不到返回 {@code null}（自检用，不要放在热路径上）。 */
    public static String displayNameOf(Object stack) {
        Info info = infoOf(stack);
        return info == null ? null : info.name;
    }

    /** 最终标签（≤6 码点的中文短标签）。空串 = "没有标签，不要画"。 */
    public static String labelOf(Object stack) {
        if (!FluidLabelConfig.enabled()) {
            return "";                                   // A5 短路，不触缓存
        }
        Info info = infoOf(stack);
        return info == null ? "" : info.label;
    }

    /**
     * 图标底色 tint（ARGB，仅 {@code adaptiveTextColor=true} 时用于判对比度）；取不到/异常时返回
     * {@code 0xFFFFFFFF}（白）⇒ 自适应档下为浅色字 + 黑描边，是安全默认。
     *
     * <p>同一液体的 tint 是常量，按液体缓存一次；本方法只会在"首次见到该液体"时付一次代价，
     * 之后是纯 Map 命中（tint 存在 {@link Info} 里，连 Map 都不用再查第二次）。
     */
    public static int tintOf(Object stack) {
        Info info = infoOf(stack);
        if (info == null) {
            return 0xFFFFFFFF;
        }
        if (!info.tintResolved) {
            info.tint = resolveTint(info);
            info.tintResolved = true;
        }
        return info.tint;
    }

    // ------------------------------------------------------------------ 缓存

    /** 清空缓存；调用时机：配置加载/重载（{@code FluidLabelMod.onConfigEvent}）。 */
    public static void clearCaches() {
        INFOS.clear();
    }

    /** 当前缓存条目数（自检/调试用）。 */
    public static int cacheSize() {
        return INFOS.size();
    }

    // ------------------------------------------------------------------ 内部

    private static Info infoOf(Object stack) {
        if (stack == null) {
            return null;
        }
        try {
            if (!(stack instanceof com.lowdragmc.lowdraglib.side.fluid.FluidStack fluidStack)) {
                return null;
            }
            Fluid fluid = fluidStack.getFluid();
            if (fluid == null) {
                return null;
            }
            Info cached = INFOS.get(fluid);
            if (cached != null) {
                return cached;
            }
            synchronized (BUILD_LOCK) {
                Info again = INFOS.get(fluid);
                if (again != null) {
                    return again;
                }
                Info built = build(fluid, fluidStack);
                if (INFOS.size() > CACHE_LIMIT) {
                    INFOS.clear();                       // 超限整体清空（与 Ae2Compat 同策略）
                }
                INFOS.put(fluid, built);
                return built;
            }
        } catch (Throwable t) {
            warnOnce("infoOf", t);
            return null;
        }
    }

    /** 建缓存：这里是全类唯一允许出现 {@code getString()} / {@code toString()} 的地方。 */
    private static Info build(Fluid fluid, com.lowdragmc.lowdraglib.side.fluid.FluidStack sample) {
        String id = null;
        String name = null;
        try {
            ResourceLocation rl = net.minecraftforge.registries.ForgeRegistries.FLUIDS.getKey(fluid);
            id = rl == null ? null : rl.toString();
            name = sample.getDisplayName().getString();
        } catch (Throwable t) {
            warnOnce("build", t);
            return new Info(fluid, id, name, "");
        }
        String label = "";
        try {
            if (name != null && !name.isEmpty()) {
                label = LabelEngine.labelFor(parseId(id), net.minecraft.network.chat.Component.literal(name));
            }
        } catch (Throwable t) {
            warnOnce("labelFor", t);
        }
        return new Info(fluid, id, name, label == null ? "" : label);
    }

    /**
     * tint 解析（只在首次见到该液体时调用一次）。
     *
     * <p>与 {@link Ae2Compat#tintOf(Object)} 同一套 Forge 1.20.1 用法：
     * {@code IClientFluidTypeExtensions.of(fluid).getTintColor(FluidStack)}。
     * 这里的 {@code FluidStack} 是一次性构造的（每个液体一次，不在热路径上）。
     */
    private static int resolveTint(Info info) {
        try {
            return net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions
                    .of(info.fluid)
                    .getTintColor(new net.minecraftforge.fluids.FluidStack(info.fluid, 1));
        } catch (Throwable t) {
            warnOnce("tintOf", t);
            return 0xFFFFFFFF;
        }
    }

    /** 注册名字符串 → {@code ResourceLocation}（只在建缓存那一次调用）。 */
    private static ResourceLocation parseId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        try {
            return ResourceLocation.tryParse(id);
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
                "[fluidlabel] LDLib 侧调用失败（{}）：后续同类异常静默处理，标签功能降级不影响游戏。", where, t);
    }

    /**
     * 一种液体的静态信息（不可变 id/name/label；tint 惰性解析）。
     *
     * <p>用普通类而不是 {@code record}：tint 必须在"首次真正要用"时才算（建缓存路径上
     * 不应该碰 {@code IClientFluidTypeExtensions}）。
     */
    private static final class Info {
        final Fluid fluid;
        final String id;
        final String name;
        final String label;
        volatile int tint = 0xFFFFFFFF;
        volatile boolean tintResolved = false;

        Info(Fluid fluid, String id, String name, String label) {
            this.fluid = fluid;
            this.id = id;
            this.name = name;
            this.label = label == null ? "" : label;
        }
    }
}
