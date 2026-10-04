package com.fluidlabel;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 注入点存活探针：每个注入点<b>首次</b>被调用时打印一行 WARN。
 *
 * <p>为什么需要它：{@code @Inject(..., require = 0)} 在目标方法签名漂移或
 * {@code @At} 匹配不到时是<b>完全静默</b>的——T2 的真实故障（
 * {@code TankWidget#drawStack} 有两个 RETURN、{@code TAIL} 只匹配最后一个）
 * 就是这样藏了整轮测试。有了这一行，玩家只需看 {@code latest.log}：
 * <ul>
 *   <li>看到「注入点2 已生效」⇒ 储罐槽回调确实被调用（T2 修复生效）；</li>
 *   <li>打开了配方界面却没有这行 ⇒ 注入器没装配上（EMI 版本变了），排查方向立刻明确。</li>
 * </ul>
 *
 * <p><b>★ 为什么用 {@code warn} 而不是 {@code info}</b>（2026-10 实机日志教训）：
 * 有两轮会话（{@code debug-2.log.gz} 23:01 / {@code latest.log} 23:21）里，本模组
 * <b>所有</b> INFO 行——包括构造器里无条件执行的那行 {@code loaded}——都在日志中缺失，
 * 而同一次会话里 WARN（自检假警报那行）却完整保留；原因至今未查明（已排除全局 INFO 过滤、
 * Mixin 装配失败、handler 抛异常）。注入点探针是"沉默失败"的唯一对策，<b>不能</b>再依赖
 * INFO 一定能落盘 ⇒ 提到 WARN。代价可控：每个注入点一生只打一行。
 *
 * <p>计数器放在本类而<b>不是</b> mixin 里，避免往目标类（{@code TankWidget}/
 * {@code FluidEmiStack}/{@code DrawerHelper}）合并静态字段。
 *
 * <p><b>站点号</b>：1 = {@code FluidEmiStack#render}、2 = {@code TankWidget#drawStack}、
 * 3 = {@code AEKeyRendering#drawInGui}（AE2 侧，§6.2 注入点 3）、
 * 4 = {@code AEFluidDisplayWidget#drawInBackground}（GT 的 ME 部件界面，§6.2 注入点 4）、
 * 5 = {@code DrawerHelper#drawFluidForGui(…long,int,int,int,int)}（GT/GTO 机器界面液体槽，§7）、
 * 6 = {@code DrawerHelper#drawFluidForGui(…float,float,float,float)}（LDLib 原生 TankWidget，§7）。
 * 新增站点必须同时扩容 {@link #FIRED}（见 R-AE2-9）。
 */
public final class InjectionProbe {

    /** 1 = FluidEmiStack#render（与 PLAN §④.1 一致）。 */
    public static final int SITE_FLUID_STACK = 1;

    /** 2 = TankWidget#drawStack。 */
    public static final int SITE_TANK_WIDGET = 2;

    /**
     * 3 = {@code appeng.api.client.AEKeyRendering#drawInGui}（PLAN §6.2 注入点 3）。
     *
     * <p>覆盖类 A（AE2 全部界面 + GTO {@code gto_ae} 设施屏）。
     */
    public static final int SITE_AE2_DRAW_IN_GUI = 3;

    /**
     * 4 = {@code com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget
     * #drawInBackground}（PLAN §6.2 注入点 4）。
     *
     * <p>覆盖类 B（GT 的 ME 流体仓 / 库存总线 / 输出仓等 LDLib 界面）。
     * <b>§7 起本点只做存活探针</b>，绘制由注入点 5 接管（避免同一图标双描边）。
     */
    public static final int SITE_GT_FLUID_DISPLAY = 4;

    /**
     * 5 = {@code com.lowdragmc.lowdraglib.gui.util.DrawerHelper#drawFluidForGui}
     * 的 7 参重载 {@code (GuiGraphics, FluidStack, long, int, int, int, int)}（PLAN §7）。
     *
     * <p>覆盖 GT/GTO 机器界面的液体槽（GT 自己的 {@code api.gui.widget.TankWidget}、
     * {@code PhantomFluidWidget}、ME 流体显示控件、{@code AEFluidConfigSlotWidget} 等）。
     */
    public static final int SITE_LDLIB_FLUID_TANK = 5;

    /**
     * 6 = 同一个 {@code DrawerHelper#drawFluidForGui} 的 5 参 float 重载
     * {@code (GuiGraphics, FluidStack, float, float, float, float)}（PLAN §7）。
     *
     * <p>LDLib 原生 {@code com.lowdragmc.lowdraglib.gui.widget.TankWidget} 走这个重载。
     */
    public static final int SITE_LDLIB_FLUID_TANK_FLOAT = 6;

    /**
     * 长度必须 = <b>最大站点号 + 1</b>（1–6 ⇒ 7）。
     *
     * <p>⚠ R-AE2-9：{@link #fired(int, String)} 对越界站点是<b>静默 return</b>（不打日志），
     * 所以新增注入点忘了扩容的后果是"注入点明明生效了、日志里却什么都没有"，把排查带进沟里
     * ——这正是本数组必须跟着站点号一起改的原因。
     */
    private static final AtomicBoolean[] FIRED = {
        new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean(),
        new AtomicBoolean(), new AtomicBoolean(), new AtomicBoolean()
    };

    private InjectionProbe() {
    }

    /** 注入点首次触发时打印一次；之后为无副作用的 CAS 读。 */
    public static void fired(int site, String detail) {
        if (site < 1 || site > FIRED.length) {
            return;
        }
        if (FIRED[site - 1].compareAndSet(false, true)) {
            FluidLabelMod.LOGGER.warn("[fluidlabel] 注入点{} 已生效：{}", site, detail);
        }
    }

    /** 供自检/调试查询：该注入点本轮是否已经触发过。 */
    public static boolean hasFired(int site) {
        return site >= 1 && site <= FIRED.length && FIRED[site - 1].get();
    }
}
