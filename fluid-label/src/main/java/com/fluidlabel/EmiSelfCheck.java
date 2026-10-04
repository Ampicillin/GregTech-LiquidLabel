package com.fluidlabel;

import dev.emi.emi.api.stack.FluidEmiStack;
import dev.emi.emi.api.widget.TankWidget;

import java.lang.reflect.Method;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 注入点自检（客户端启动时执行一次，PLAN §④.1/§④.2 的回归防线）。
 *
 * <p>检查两件事，一句话写进 {@code latest.log}：
 * <ol>
 *   <li><b>目标方法还在不在</b>——EMI 换版本时最常见的变化就是签名漂移。
 *       方法签名对不上 ⇒ {@code require = 0} 会静默跳过，这里直接 WARN 点名。</li>
 *   <li><b>注入器装没装</b>——Mixin 的 {@code @Inject} 会在<b>目标类</b>里生成
 *       {@code handler$…$fluidlabel$…} 形态的处理器方法，反射扫一遍即可确认
 *       （找不到只报"状态未知"，不报警——命名属 Mixin 内部约定，不影响结论）。</li>
 * </ol>
 *
 * <p><b>本类只被 {@link FluidLabelMod} 用反射加载</b>（EMI 存在时），
 * 这样 {@code FluidLabelMod} 自身不引用任何 {@code dev.emi.*} 类型，
 * 缺 EMI 的主类仍可正常加载（PLAN §A6）。
 */
public final class EmiSelfCheck {

    private EmiSelfCheck() {
    }

    public static void run() {
        verify("注入点1 FluidEmiStack#render", FluidEmiStack.class, "render", "fluidlabel$overlay",
                GuiGraphics.class, int.class, int.class, float.class, int.class);
        verify("注入点2 TankWidget#drawStack", TankWidget.class, "drawStack", "fluidlabel$overlayTank",
                GuiGraphics.class, int.class, int.class, float.class);
        FluidLabelMod.LOGGER.warn(
                "[fluidlabel] 探针：site1={} site2={}（\"待触发\"=还没画到液体图标，进配方界面后应变为已生效）",
                InjectionProbe.hasFired(InjectionProbe.SITE_FLUID_STACK) ? "已生效" : "待触发",
                InjectionProbe.hasFired(InjectionProbe.SITE_TANK_WIDGET) ? "已生效" : "待触发");
    }

    private static void verify(String what, Class<?> owner, String name, String callback,
                               Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            FluidLabelMod.LOGGER.warn("[fluidlabel] {} 目标在：{}.{}{} → {}",
                    what, owner.getSimpleName(), name, descriptor(params),
                    handlerState(owner, callback));
        } catch (NoSuchMethodException e) {
            FluidLabelMod.LOGGER.warn(
                    "[fluidlabel] {} 目标方法不存在：{}.{}{} —— 本注入点不会生效（EMI 版本变了？）",
                    what, owner.getSimpleName(), name, descriptor(params));
        } catch (Throwable t) {
            FluidLabelMod.LOGGER.warn("[fluidlabel] {} 自检失败（忽略）", what, t);
        }
    }

    /** 目标类里是否有 Mixin 生成的 handler（{@code handler$…$} + 我们的回调名）。 */
    private static String handlerState(Class<?> owner, String callback) {
        try {
            for (Method m : owner.getDeclaredMethods()) {
                String n = m.getName();
                if (n.startsWith("handler$") && n.contains(callback)) {
                    return "注入器已装配";
                }
            }
            return "注入器状态未知（未匹配到 handler$" + callback + "）";
        } catch (Throwable t) {
            return "注入器状态未知";
        }
    }

    private static String descriptor(Class<?>... params) {
        StringBuilder sb = new StringBuilder("(");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(params[i].getSimpleName());
        }
        return sb.append(')').toString();
    }
}
