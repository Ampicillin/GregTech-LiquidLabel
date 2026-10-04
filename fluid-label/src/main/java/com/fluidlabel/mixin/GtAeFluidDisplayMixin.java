package com.fluidlabel.mixin;

import com.fluidlabel.InjectionProbe;

import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入点 4（PLAN §6.2 / §7.4）：GTCEu 的
 * {@code com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget#drawInBackground}。
 *
 * <p><b>★ PLAN §7 起：本类只保留存活探针，不再画标签。</b>绘制已由注入点 5
 * （{@code DrawerHelper#drawFluidForGui} 的 7 参重载）接管 —— Lead javap 实证该控件画图标时
 * 就是调 {@code DrawerHelper.drawFluidForGui(GuiGraphics, FluidStack, long, int, int, int, int)}，
 * 传 {@code bipush 16 bipush 16}、x/y = {@code position.x + 1 / position.y + 1}
 * （与这里原先硬编码的几何逐字一致）。两条路径同时画 ⇒ 同一个图标被叠两次标签，
 * 描边会变粗（视觉上像加粗了字体），所以这里必须撤掉渲染调用。
 *
 * <p>保留探针的理由：它是"注入点 4 的目标方法还在、且注入器确实装配上了"的唯一运行时证据
 * （{@code require = 0} 的失配是静默的）。探针站点号仍是 4。
 *
 * <p><b>注入点 4 的其余设计约束</b>（原样保留，任何"以后要在这里补回渲染"的人都必须先读）：
 * <ul>
 *   <li>{@code @Shadow} 只能针对目标类<b>自身</b>声明的成员；且本 mixin
 *       <b>不声明父类</b> —— LDLib 的 {@code Widget} 没有无参构造器（PLAN §6.2.2）；</li>
 *   <li>{@code remap = false}、{@code at = @At("RETURN")}、{@code require = 0}
 *       （§⓪.1 禁 {@code TAIL}）；</li>
 *   <li>handler 必须是<b>实例方法</b>：目标 {@code drawInBackground} 是实例方法
 *       （对照注入点 5/6 的 static 要求，见 {@link DrawerHelperFluidMixin}）。</li>
 * </ul>
 */
@Mixin(value = com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget.class, remap = false)
public class GtAeFluidDisplayMixin {

    @Inject(
        method = "drawInBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At("RETURN"),
        require = 0
    )
    private void fluidlabel$overlayGt(GuiGraphics g, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        // 只留探针：回调被调用 = 该注入点装配成功（渲染已交给注入点 5）。
        InjectionProbe.fired(InjectionProbe.SITE_GT_FLUID_DISPLAY,
                "GT AEFluidDisplayWidget#drawInBackground（渲染已移交注入点5）");
    }
}
