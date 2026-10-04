package com.fluidlabel.mixin;

import com.fluidlabel.InjectionProbe;
import com.fluidlabel.LabelRenderer;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入点 5 / 6（PLAN §7）：LDLib 的液体绘制原语
 * {@code com.lowdragmc.lowdraglib.gui.util.DrawerHelper#drawFluidForGui} 的两个重载。
 *
 * <p><b>为什么注入"绘制原语"而不是逐个控件</b>（Lead 实测常量池扫描的结论，PLAN §7.1）：
 * <ul>
 *   <li>GT/GTO 机器界面的每一个液体槽最终都落到这两个方法之一 —— GT 自己的
 *       {@code com.gregtechceu.gtceu.api.gui.widget.TankWidget}、
 *       {@code PhantomFluidWidget}、ME 侧 {@code AEFluidDisplayWidget}、
 *       GTO 的 {@code AEFluidConfigSlotWidget}、LDLib 的
 *       {@code com.lowdragmc.lowdraglib.gui.widget.TankWidget} 全部在内；</li>
 *   <li>调用面极小且可枚举：扫三个 jar 的常量池，{@code drawFluidForGui} 只出现在
 *       gtceu 4 个类、ldlib 2 个类、gtocore 1 个类里 ⇒ 不存在"未知调用者把标签画到
 *       奇怪位置"的爆炸半径（PLAN §7.1 表）；</li>
 *   <li>不需要为每个控件写一个 mixin，也<b>不需要 extends 任何 LDLib 类</b>
 *       （避开 {@code Widget} 没有无参构造器那个坑，PLAN §6.2.2）。</li>
 * </ul>
 *
 * <p><b>★ 两个 handler 必须是 {@code static}</b>：两个目标方法都是 {@code public static void}
 * （{@code javap} 逐字实证）。Mixin 要求 handler 与目标的 static 性一致 —— 不一致的报错是
 * 「{@code 'static' modifier of handler method does not match target}」，而且在
 * {@code require = 0} 下<b>只是 WARN、游戏照常跑</b>（AE2 侧 23:01 那轮就是这么静默失败的，
 * PLAN §6.10）。这是本类最容易踩的坑。
 *
 * <p><b>几何（PLAN §7.2）</b>：两个重载的坐标参数都是"流体实际绘制矩形"的左上角与宽高，
 * 单位像素：
 * <ul>
 *   <li>7 参重载：{@code (x, y, w, h)} 是<b>整个槽的内缩矩形</b> —— GT 的
 *       {@code TankWidget} 传 {@code (position.x+1, position.y+1, size.width-2, size.height-2)}
 *       （字节码实证），{@code AEFluidDisplayWidget} 传 {@code (stackX, stackY, 16, 16)}；</li>
 *   <li>5 参 float 重载：{@code (x, y, w, h)} 是<b>填充之后的切片</b> —— LDLib 的
 *       {@code TankWidget} 传 {@code (x + drawnU*innerW, y + drawnV*innerH, innerW*drawnWidth,
 *       innerH*drawnHeight)}（字节码 offset 267–302 实证）。填充比例低时这个矩形会退化成
 *       细条 ⇒ 用 {@link #MIN_SLOT} 挡掉，宁可不画也不把字压在槽外。</li>
 * </ul>
 *
 * <p><b>⚠ 已知限制</b>：LDLib 原生 {@code TankWidget}（模块化 UI / 部分 Phantom 控件）在
 * 填充比例较低时拿不到"整个槽"的矩形，因此那一类控件在低填充时<b>不显示标签</b>。
 * GT 机器界面本身走的是 7 参重载（整槽矩形），不受此限制。
 *
 * <p><b>约束</b>：{@code remap = false}（LDLib 不混淆、名字即运行时名）；{@code at = @At("RETURN")}
 * + {@code require = 0}（§⓪.1：禁 {@code TAIL}；绘制完成后叠加才不会挡液体本身）。
 */
@Mixin(value = com.lowdragmc.lowdraglib.gui.util.DrawerHelper.class, remap = false)
public class DrawerHelperFluidMixin {

    /**
     * 小于这个边长（像素）的矩形不贴标签。
     *
     * <p>两个作用：① 挡掉 float 重载在低填充时给出的细条矩形；② 挡掉把液体当"进度条底纹"
     * 画的小矩形（这类矩形贴字必然压到别的控件上）。GT 机器槽的内缩矩形通常是 14–16px，
     * 10 这个阈值只牺牲"本身就小于一个字符格"的极端小槽。
     */
    private static final int MIN_SLOT = 10;

    /**
     * 7 参重载：{@code (GuiGraphics, FluidStack, long amount, int x, int y, int w, int h)}。
     *
     * <p>{@code amount} 只用来判断"槽里是否有量"（→ 是否需要避让数量文字），不参与定位。
     */
    @Inject(
        method = "drawFluidForGui(Lnet/minecraft/client/gui/GuiGraphics;Lcom/lowdragmc/lowdraglib/side/fluid/FluidStack;JIIII)V",
        at = @At("RETURN"),
        require = 0,
        remap = false
    )
    private static void fluidlabel$overlayLdlibTank(GuiGraphics guiGraphics,
                                                   com.lowdragmc.lowdraglib.side.fluid.FluidStack stack,
                                                   long amount, int x, int y, int w, int h, CallbackInfo ci) {
        // 探针放在最前面：回调被调用 = 注入器装配成功（`require = 0` 的失配是静默的）。
        InjectionProbe.fired(InjectionProbe.SITE_LDLIB_FLUID_TANK,
                "DrawerHelper#drawFluidForGui(GuiGraphics, FluidStack, long, int×4)");
        if (w < MIN_SLOT || h < MIN_SLOT) {
            return;                                     // 进度条底纹 / 极端小槽：不贴
        }
        try {
            LabelRenderer.drawOverlayLdlib(guiGraphics, stack, x, y, w, h, amount > 0L);
        } catch (Throwable t) {
            // 静默：LDLib 的绘制链路上任何异常都不该被这里放大（A6 / R-AE2-10）。
        }
    }

    /**
     * 5 参 float 重载：{@code (GuiGraphics, FluidStack, float x, float y, float w, float h)}。
     *
     * <p>矩形是"填充后的切片"（见类 javadoc）—— 低填充时会被 {@link #MIN_SLOT} 挡掉。
     */
    @Inject(
        method = "drawFluidForGui(Lnet/minecraft/client/gui/GuiGraphics;Lcom/lowdragmc/lowdraglib/side/fluid/FluidStack;FFFF)V",
        at = @At("RETURN"),
        require = 0,
        remap = false
    )
    private static void fluidlabel$overlayLdlibTankFloat(GuiGraphics guiGraphics,
                                                        com.lowdragmc.lowdraglib.side.fluid.FluidStack stack,
                                                        float x, float y, float w, float h, CallbackInfo ci) {
        InjectionProbe.fired(InjectionProbe.SITE_LDLIB_FLUID_TANK_FLOAT,
                "DrawerHelper#drawFluidForGui(GuiGraphics, FluidStack, float×4)");
        int ix = Math.round(x);
        int iy = Math.round(y);
        int iw = Math.round(w);
        int ih = Math.round(h);
        if (iw < MIN_SLOT || ih < MIN_SLOT) {
            return;                                     // 低填充切片：不贴（见类 javadoc 的已知限制）
        }
        try {
            LabelRenderer.drawOverlayLdlib(guiGraphics, stack, ix, iy, iw, ih, false);
        } catch (Throwable t) {
            // 静默（同上）
        }
    }
}
