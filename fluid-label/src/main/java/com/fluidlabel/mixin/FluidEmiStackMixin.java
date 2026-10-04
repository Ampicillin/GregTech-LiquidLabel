package com.fluidlabel.mixin;

import com.fluidlabel.InjectionProbe;
import com.fluidlabel.LabelEngine;
import com.fluidlabel.LabelRenderer;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.FluidEmiStack;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入点 1（PLAN §④.1）：EMI 液体图标的唯一 per-icon 绘制调用。
 *
 * <p>覆盖范围（实证）：{@code SlotWidget#drawStack} 居中算出真实坐标后调用本方法；
 * {@code StackBatcher#render} 的两处 {@code invokeinterface EmiIngredient.render} 也会落到这里
 * ⇒ 配方槽 + 侧栏列表都走本注入点。
 *
 * <p>约束（PLAN §④.1 结论 1/3）：
 * <ul>
 *   <li>{@code x}/{@code y} <b>就是图标左上角坐标</b>（流体只是被额外 z+100 绘制）
 *       ⇒ 在 TAIL 处同 pose 内再抬 z 即可精确落在图标上；</li>
 *   <li>{@code flags & RENDER_ICON} 是图标门控，只画数量文字的场景必须直接 return；</li>
 *   <li>{@code flags & RENDER_REMAINDER} 表示同格还会画数量文字 ⇒ 传 amountShown=true。</li>
 * </ul>
 *
 * <p>路线钉死：{@code remap = false}（模组类，不做 SRG 重映射）+ {@code require = 0}
 * （目标漂移时降级，绝不崩游戏）+ 不生成 refmap（描述符里只有类名，无成员名）。
 */
@Mixin(value = FluidEmiStack.class, remap = false)
public abstract class FluidEmiStackMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIFI)V",
        at = @At("TAIL"),
        require = 0
    )
    private void fluidlabel$overlay(GuiGraphics guiGraphics, int x, int y, float delta, int flags, CallbackInfo ci) {
        // 探针放在门控之前：只要回调被调用就说明注入器装配成功（见 InjectionProbe 的说明）。
        InjectionProbe.fired(InjectionProbe.SITE_FLUID_STACK, "FluidEmiStack#render");
        if ((flags & EmiIngredient.RENDER_ICON) == 0) {
            return; // 没画图标 ⇒ 不叠标签（否则会在纯数量文字场景重复绘制）
        }
        try {
            FluidEmiStack self = (FluidEmiStack) (Object) this;
            // 先把 (注册名 → 显示名) 记下来：DYNAMIC 档的池与 dumpRegistry 都依赖它。
            LabelEngine.rememberName(self.getId(), self.getName());
            LabelRenderer.drawOverlay(guiGraphics, self, x, y, 16, 16,
                    (flags & EmiIngredient.RENDER_REMAINDER) != 0);
        } catch (Throwable t) {
            // 静默：标签是增强，异常绝不冒泡到 EMI 的渲染循环（A6/A7）。
        }
    }
}
