package com.fluidlabel.mixin;

import appeng.api.stacks.AEKey;
import com.fluidlabel.InjectionProbe;
import com.fluidlabel.LabelRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入点 3（PLAN §6.2 / §6.2.2）：AE2 的 {@code appeng.api.client.AEKeyRendering#drawInGui}。
 *
 * <p>覆盖类 A（AE2 全部界面）：ME 存储/合成/样板终端网格、合成确认表、网络状态、信息条、
 * 内容 Toast、存储元件 tooltip、GTO {@code gto_ae} 设施管理屏。ME 网格的调度点是
 * {@code gto_ae.hooks.gui.menu.IRepoSlot#renderSlot} → {@code invokestatic AEKeyRendering.drawInGui}。
 *
 * <p><b>几何（Lead 实测钉死，§6.2.4）</b>：调用者传进来的 {@code (x, y)} 就是<b>图标左上角</b>，
 * 图标 16×16（{@code FluidKeyRenderHandler.drawInGui} = {@code Blitter.dest(x, y, 16, 16)}）
 * ⇒ 不需要任何槽位几何 API，标签与图标天然同 pose 同坐标系。
 *
 * <p><b>★ handler 必须是 {@code static}（实机日志实证的致命缺陷）</b>：目标
 * {@code drawInGui} 是 <b>static</b> 方法（javap：{@code public static void drawInGui(...)}），
 * 而 Mixin 要求 <b>handler 的 static 修饰符与目标一致</b>。写成实例方法时装配期直接抛
 * {@code InvalidInjectionException: 'static' modifier of handler method does not match target}
 * —— 注意这是 <b>WARN 而非崩溃</b>，游戏照常启动、界面一根标签都不画，
 * 且 {@code require = 0} 让注入失败完全静默（唯一线索就是那行 WARN）。对照：注入点 4 的目标
 * {@code AEFluidDisplayWidget#drawInBackground} 是实例方法，其 handler 就必须<b>非</b> static。
 *
 * <p><b>约束</b>：{@code remap = false}（模组类，不做 SRG 重映射，PLAN §④.4）；
 * {@code at = @At("RETURN")}（<b>禁止</b> {@code TAIL}，见 §⓪.1 的教训：多出口方法
 * {@code TAIL} 只匹配最后一个 {@code RETURN}，会静默漏掉真实路径）；
 * {@code require = 0}（目标漂移时降级，绝不崩游戏）。
 *
 * <p><b>本类只声明注入点与方法签名，方法体留给 C</b>（PLAN §6.8 归属表）。
 */
@Mixin(value = appeng.api.client.AEKeyRendering.class, remap = false)
public class AeKeyRenderingMixin {

    @Inject(
        method = "drawInGui(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/GuiGraphics;IILappeng/api/stacks/AEKey;)V",
        at = @At("RETURN"),
        require = 0
    )
    private static void fluidlabel$overlayAe2(Minecraft mc, GuiGraphics g, int x, int y, AEKey key,
                                              CallbackInfo ci) {
        // 探针放在最前面：只要回调被调用就说明注入器装配成功（与 EMI 侧两个 mixin 的约定一致，
        // 见 InjectionProbe 的说明 —— `require = 0` 的失配是完全静默的）。
        InjectionProbe.fired(InjectionProbe.SITE_AE2_DRAW_IN_GUI, "AEKeyRendering#drawInGui");
        try {
            // (x, y) 就是图标左上角、图标 16×16（PLAN §6.2.4 ①④）；
            // amountShown = false：AE2 的 FluidKeyRenderHandler 不在图标内画数量。
            LabelRenderer.drawOverlayAe2(g, key, x, y, 16, 16, false);
        } catch (Throwable t) {
            // 静默：标签是增强，任何异常都不该影响 AE2 的绘制（A6 / R-AE2-10）。
        }
    }
}
