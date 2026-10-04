package com.fluidlabel;

import com.fluidlabel.compat.Ae2Compat;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * Fluid Label —— EMI 液体图标中文短标签（纯客户端）。
 *
 * <p>modId 钉死为 {@code fluidlabel}（PLAN §③ / §④.5），不要改成 kebab-case。
 *
 * <p>本类只做五件事：
 * <ol>
 *   <li>注册客户端配置（固定文件名 {@code fluidlabel-client.toml}，PLAN §⑥）；</li>
 *   <li>配置加载/重载时清缓存（PLAN §⑥）；</li>
 *   <li>提示/短路 —— 缺 EMI / 缺 AE2 / 缺 GTCEu 时都"安静失效"，绝不抛异常（A6）；</li>
 *   <li>启动时跑一次 AE2 侧自检（{@link Ae2SelfCheck}，反射加载，PLAN §6.7.B）；</li>
 *   <li>启动时跑一次注入点自检（{@link EmiSelfCheck}，反射加载）。</li>
 * </ol>
 * 真实绘制在 mixin + {@link LabelRenderer}，本类<b>不</b>引用任何 {@code dev.emi.*} /
 * {@code appeng.*} / {@code com.gregtechceu.*} / {@code com.lowdragmc.*} 类型，
 * 以保证这些模组缺失时主类仍能加载（PLAN §6.5 的常量池校验）。
 */
@Mod(FluidLabelMod.MODID)
public class FluidLabelMod {

    public static final String MODID = "fluidlabel";

    /** 配置文件固定名（PLAN §⑥：显式指定以保证固定）。 */
    public static final String CONFIG_FILE_NAME = "fluidlabel-client.toml";

    public static final Logger LOGGER = LogUtils.getLogger();

    /** 是否检测到 EMI（仅用于日志；渲染层另有 instanceof 防御）。 */
    public static final boolean EMI_PRESENT = detectEmi();

    /**
     * 是否检测到 AE2（PLAN §6.5；仅用于日志与自检触发 —— 渲染层另有键型防御）。
     *
     * <p>双保险：modId 判定 + 类存在性判定。整合包里 AE2 是 GTO 补丁版，jarJar 内嵌，
     * 但仍会注册自己的 modId；类名判定用来兜住"modId 变化/被改名"的情形。
     */
    public static final boolean AE2_PRESENT = detectAe2();

    /** 是否检测到 GTCEu（PLAN §6.5；同上，仅日志与自检触发）。 */
    public static final boolean GTCEU_PRESENT = detectGtceu();

    public FluidLabelMod() {
        boolean emi = ModList.get().isLoaded("emi");

        // 1) 配置永远注册 —— 这样"缺 EMI"时玩家仍能看到配置项与一行明确提示。
        try {
            ModLoadingContext.get().registerConfig(
                    ModConfig.Type.CLIENT, FluidLabelConfig.SPEC, CONFIG_FILE_NAME);
        } catch (Throwable t) {
            LOGGER.warn("[fluidlabel] 注册客户端配置失败（继续运行，配置走默认值）", t);
        }

        // 2) 配置加载/重载后清缓存。
        try {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(FluidLabelMod::onConfigEvent);
        } catch (Throwable t) {
            LOGGER.warn("[fluidlabel] 注册配置事件监听失败（改配置需重启生效）", t);
        }

        // 3) 帧末清理：让 DYNAMIC 档"本帧集合"有明确边界（PLAN §5.3）。
        try {
            MinecraftForge.EVENT_BUS.addListener(FluidLabelMod::onClientTick);
        } catch (Throwable t) {
            LOGGER.warn("[fluidlabel] 注册 tick 监听失败（DYNAMIC 档退化为时间窗判定）", t);
        }

        // 标签表与 AE2 侧的生命周期都独立于 EMI：AE2 / GT 的 ME 部件界面同样要用这张表。
        // ★ 用 warn 不用 info：23:01/23:21 两轮实机会话里本模组的 INFO 行全部缺失
        //   （原因未查明），这行是"构造器确实跑到了这里"的锚点，不能赌 INFO 落盘（见 InjectionProbe）。
        LOGGER.warn("[fluidlabel] loaded (client-side fluid label overlay).");
        LabelTables.reload();

        // 4) AE2 / GT 的 ME 部件界面侧启动自检（PLAN §6.7.B）。
        //    只做诊断，不影响渲染；反射调用保证本类不引用 appeng.* / gregtechceu.* 类型
        //    （jar 常量池校验：FluidLabelMod.class 不含这三家的包名）。
        if (AE2_PRESENT || GTCEU_PRESENT) {
            try {
                Class.forName("com.fluidlabel.Ae2SelfCheck").getMethod("run").invoke(null);
            } catch (Throwable t) {
                LOGGER.warn("[fluidlabel] AE2 侧自检未执行（不影响渲染）", t);
            }
        } else {
            LOGGER.info("[fluidlabel] AE2 / GTCEu not found - ME-side labels stay inactive.");
        }

        if (!emi) {
            LOGGER.info("[fluidlabel] EMI not found - fluid label overlay stays inactive.");
            return;
        }

        // 5) 注入点自检（反射加载，保证本类不引用 dev.emi.* —— 见 EmiSelfCheck 的说明）。
        try {
            Class.forName("com.fluidlabel.EmiSelfCheck").getMethod("run").invoke(null);
        } catch (Throwable t) {
            LOGGER.warn("[fluidlabel] 注入点自检未执行（不影响渲染）", t);
        }
    }

    /** 客户端 tick 结束时收束本帧的液体集合（DYNAMIC 档用）。 */
    private static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            FrameContext.endFrame();
            LabelTables.refreshAliasesIfChanged();
        }
    }

    /** 配置加载/重载 → 清缓存 + 重读表（PLAN §⑥ / §6.3）。 */
    private static void onConfigEvent(ModConfigEvent event) {
        LabelEngine.invalidateCache();
        LabelTables.reload();
        FrameContext.invalidate();
        // AE2 侧的 per-key 缓存（标签/显示名/tint）也要清，否则配置改完 AE2 屏上还是旧标签。
        Ae2Compat.clearCaches();
        LOGGER.info("[fluidlabel] 配置已{}，缓存已清空。",
                event instanceof ModConfigEvent.Reloading ? "重载" : "加载");
    }

    /** 只做存在性探测；不加载 EMI 的任何类（避免类加载隔离问题）。 */
    private static boolean detectEmi() {
        try {
            return ModList.get().isLoaded("emi");
        } catch (Throwable t) {
            return false;
        }
    }

    /** AE2 存在性：modId 判定 + 类存在性判定双保险（PLAN §6.5）。 */
    private static boolean detectAe2() {
        try {
            return ModList.get().isLoaded("ae2")
                    || classPresent("appeng.api.client.AEKeyRendering");
        } catch (Throwable t) {
            return false;
        }
    }

    /** GTCEu 存在性：modId 判定 + 类存在性判定双保险（PLAN §6.5）。 */
    private static boolean detectGtceu() {
        try {
            return ModList.get().isLoaded("gtceu")
                    || classPresent("com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 类存在性探测（PLAN §6.5）。
     *
     * <p>{@code initialize = false}：只问"这个类在不在"，不触发它的静态初始化（避免在
     * 模组构造期就把别人的类初始化掉）。类名是本方法的<b>字符串参数</b>，不会进入本类的
     * 常量池（常量池只出现字符串 "appeng.api.client.AEKeyRendering"，
     * 这满足 §6.5 的 {@code FluidLabelMod.class} 校验要求）。
     */
    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, FluidLabelMod.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
