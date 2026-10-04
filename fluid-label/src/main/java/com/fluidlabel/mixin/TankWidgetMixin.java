package com.fluidlabel.mixin;

import com.fluidlabel.FluidLabelConfig;
import com.fluidlabel.InjectionProbe;
import com.fluidlabel.LabelEngine;
import com.fluidlabel.LabelRenderer;

import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.SlotWidget;
import dev.emi.emi.api.widget.TankWidget;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 注入点 2（PLAN §④.2）：GT 的液体储罐槽 {@code TankWidget#drawStack}。
 *
 * <p>为什么必需：{@code TankWidget} 自己 {@code new FluidEmiStack(...)} 后直接调
 * {@code EmiAgnos.renderFluid(9 参)}，<b>从不</b>调用 {@code FluidEmiStack#render}
 * ⇒ 注入点 1 覆盖不到储罐槽。而扫 gtceu.jar 可知 {@code TankWidget}/{@code SlotWidget}
 * 各只被 {@code com/gregtechceu/gtceu/integration/emi/recipe/GTEmiRecipe} 引用，
 * 也就是说这就是 GT 液体槽的主路径。
 *
 * <p>坐标：{@code drawStack} 的两个 int 是 <b>mouseX/mouseY</b>，不是坐标
 * ⇒ 必须用 {@code getBounds()}（字节码证明 TankWidget 自己就是这么算的，
 * {@code renderFluid} 收到的 posX/posY 与这套坐标系一致）。
 *
 * <p>为什么这里**不用** {@code @Shadow}：{@code getBounds()} 是 {@code TankWidget} 自己
 * 覆写的方法，而 {@code getStack()} 是从父类 {@code SlotWidget} 继承的
 * （javap 实证：{@code public dev.emi.emi.api.stack.EmiIngredient getStack();}，
 * 返回类型是 {@code EmiIngredient} 而<b>不是</b> {@code EmiStack}）。继承成员的
 * {@code @Shadow} 一旦名字/返回类型对不上，失败的是<b>整个 mixin 类</b>
 * ——{@code require = 0} 挡不住这一步，症状是储罐槽彻底没有标签。改成显式转型后，
 * 编译期与运行时都没有歧义：混入后的实例本身就是 {@code TankWidget}。
 *
 * <p><b>为什么必须是 {@code @At("RETURN")} 而不是 {@code @At("TAIL")}</b>（2026-09 实测修复）：
 * {@code TankWidget#drawStack} 的字节码里有<b>两个</b> {@code RETURN}——offset 307（外层
 * 循环 {@code level >= fill} 的正常结束，即"槽里真的有液体、填充画完"）与 offset 311
 * （{@code getEmiStacks()} 为空、或没有任何 stack 的 key 是 {@code Fluid} 的空槽）。
 * Mixin 的 {@code TAIL} 只匹配方法里<b>最后一个</b> {@code RETURN}（=311），而真实液体槽
 * 走的是 307 ⇒ 回调永不执行；{@code require = 0} 又让这个失配完全静默。
 * {@code RETURN} 匹配方法内所有 {@code RETURN}，两类出口都会调用本回调。
 *
 * <p>与注入点 1 一样：{@code remap = false} + {@code require = 0}，
 * 不使用 {@code @Redirect}/{@code @Overwrite}（与 GT 自身 Mixin 共存，PLAN §④.4）。
 */
@Mixin(value = TankWidget.class, remap = false)
public abstract class TankWidgetMixin {

    /** 混入后的 this 就是 TankWidget（进而就是 SlotWidget），显式转型取父类的 public 方法。 */
    private SlotWidget fluidlabel$widget() {
        return (SlotWidget) (Object) this;
    }

    @Inject(
        method = "drawStack(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",
        at = @At("RETURN"),
        require = 0
    )
    private void fluidlabel$overlayTank(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta,
                                       CallbackInfo ci) {
        // 探针放在门控之前：只要回调被调用就说明注入器装配成功（见 InjectionProbe 的说明）。
        InjectionProbe.fired(InjectionProbe.SITE_TANK_WIDGET, "TankWidget#drawStack");
        // 注意：mouseX/mouseY 在此无用（TankWidget 自己也不用），位置一律走 getBounds()。
        try {
            if (!FluidLabelConfig.enabled() || !FluidLabelConfig.tankWidgets()) {
                return; // 总开关 / 储罐槽开关（第一行短路）
            }
            EmiIngredient ingredient = fluidlabel$widget().getStack();
            if (!(ingredient instanceof EmiStack stack)) {
                return; // 储罐里未必装液体（物品储罐不画）
            }
            // 先把 (注册名 → 显示名) 记下来，让 DYNAMIC 档与 dumpRegistry 有完整信息。
            LabelEngine.rememberName(stack.getId(), stack.getName());
            Bounds bounds = fluidlabel$widget().getBounds();
            LabelRenderer.drawOverlay(guiGraphics, stack, bounds.x(), bounds.y(),
                                      bounds.width(), bounds.height(), true);
        } catch (Throwable t) {
            // 静默：标签是增强，任何异常都不该影响 EMI 的绘制（A6）。
        }
    }
}
