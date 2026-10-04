package com.fluidlabel;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AE2 侧启动自检（PLAN §6.7.B；与 {@link EmiSelfCheck} 同风格）。
 *
 * <p>一句话写进 {@code latest.log}，回答三个问题：
 * <ol>
 *   <li><b>目标方法还在不在</b>——AE2/GTCEu 换版本（整合包里是 GTO 补丁版）时最常见的变化就是
 *       签名漂移。签名对不上 ⇒ {@code require = 0} 会静默跳过，这里直接 WARN 点名
 *       「本注入点不会生效（AE2/GTCEu 版本变了？）」；</li>
 *   <li><b>注入器装没装</b>——Mixin 的 {@code @Inject} 会在<b>目标类</b>里生成
 *       {@code handler$…$fluidlabel$…} 形态的处理器方法，反射扫一遍即可确认
 *       （找不到只报"状态未知"，不报警：命名属 Mixin 内部约定，不影响结论）；</li>
 *   <li><b>四个探针的当前状态</b>（启动时通常是"待触发"）。</li>
 * </ol>
 *
 * <p><b>硬约束（PLAN §6.5 类引用隔离）</b>：本类<b>不</b> import {@code appeng.*} /
 * {@code com.gregtechceu.*} / {@code com.lowdragmc.*}，一律用<b>全限定名字符串 +
 * {@code Class.forName}</b>。这样缺 AE2/GTCEu 时本类仍可正常加载（与 {@link EmiSelfCheck}
 * 被 {@link FluidLabelMod} 反射调用的思路一致），也保证主类常量池干净。
 */
public final class Ae2SelfCheck {

    /** 注入点 3 的目标类（PLAN §6.2）。 */
    private static final String AE_KEY_RENDERING = "appeng.api.client.AEKeyRendering";
    /** 液体键类型（PLAN §6.2.1）。 */
    private static final String AE_FLUID_KEY = "appeng.api.stacks.AEFluidKey";
    /** 液体键的基类（方法参数与返回类型都要用到）。 */
    private static final String AE_KEY = "appeng.api.stacks.AEKey";
    /** 注入点 4 的目标类（PLAN §6.2，逐字来自 jar 条目清单）。 */
    private static final String GT_AE_FLUID_DISPLAY_WIDGET =
            "com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget";
    /** 注入点 5/6 的目标类（PLAN §7）：LDLib 的液体绘制原语。 */
    private static final String DRAWER_HELPER = "com.lowdragmc.lowdraglib.gui.util.DrawerHelper";
    /** 注入点 5/6 的参数类型（LDLib 自己的 FluidStack，不是 Forge 的）。 */
    private static final String LDLIB_FLUID_STACK = "com.lowdragmc.lowdraglib.side.fluid.FluidStack";

    /** 已就"类缺失"打过 WARN 的类名（同一个类只警告一次）。 */
    private static final Set<String> MISSING_WARNED = ConcurrentHashMap.newKeySet();

    private Ae2SelfCheck() {
    }

    /** 客户端启动时跑一次；由 {@code FluidLabelMod} 反射调用（AE2 或 GTCEu 存在时）。 */
    public static void run() {
        Class<?> aeKeyRendering = load(AE_KEY_RENDERING);
        Class<?> aeFluidKey = load(AE_FLUID_KEY);
        Class<?> aeKey = load(AE_KEY);
        Class<?> gtFluidWidget = load(GT_AE_FLUID_DISPLAY_WIDGET);

        // ① 注入点 3：AEKeyRendering#drawInGui(Minecraft, GuiGraphics, int, int, AEKey)
        Class<?>[] drawInGuiParams = params(
                "net.minecraft.client.Minecraft",
                "net.minecraft.client.gui.GuiGraphics",
                "int", "int", AE_KEY);
        if (drawInGuiParams != null) {
            verify("注入点3 AEKeyRendering#drawInGui", aeKeyRendering,
                    "drawInGui", "fluidlabel$overlayAe2", drawInGuiParams);
        }

        // ② AEFluidKey 的关键成员：is(AEKey) / getId() / getFluid()
        if (aeKey != null) {
            verify("AEFluidKey#is(AEKey)", aeFluidKey, "is", "fluidlabel$overlayAe2",
                    new Class<?>[] {aeKey});
        }
        verify("AEFluidKey#getId", aeFluidKey, "getId", "fluidlabel$overlayAe2", new Class<?>[0]);
        verify("AEFluidKey#getFluid", aeFluidKey, "getFluid", "fluidlabel$overlayAe2", new Class<?>[0]);

        // ③ 注入点 4：AEFluidDisplayWidget#drawInBackground(GuiGraphics, int, int, float)
        Class<?>[] drawInBackgroundParams = params(
                "net.minecraft.client.gui.GuiGraphics", "int", "int", "float");
        if (drawInBackgroundParams != null) {
            verify("注入点4 AEFluidDisplayWidget#drawInBackground", gtFluidWidget,
                    "drawInBackground", "fluidlabel$overlayGt", drawInBackgroundParams);
        }

        // ⑤ LDLib 侧（PLAN §7）：{@code DrawerHelper#drawFluidForGui} 的两个重载 —— GT/GTO
        //    机器界面的液体槽全部走这里（注入点 5/6）。放在本类而不是再开一个自检类：本类恰好
        //    "GTCEu 在时才被反射调用"，再加一个反射钩子没有额外信息量；LDLib 缺失时上面的
        //    load() 已经打过一行 WARN 说明原因。
        Class<?> drawerHelper = load(DRAWER_HELPER);
        Class<?> ldlFluidStack = load(LDLIB_FLUID_STACK);
        if (drawerHelper != null && ldlFluidStack != null) {
            verify("注入点5 DrawerHelper#drawFluidForGui(int 重载)", drawerHelper, "drawFluidForGui",
                    "fluidlabel$overlayLdlibTank",
                    new Class<?>[] {net.minecraft.client.gui.GuiGraphics.class, ldlFluidStack,
                            long.class, int.class, int.class, int.class, int.class});
            verify("注入点6 DrawerHelper#drawFluidForGui(float 重载)", drawerHelper, "drawFluidForGui",
                    "fluidlabel$overlayLdlibTankFloat",
                    new Class<?>[] {net.minecraft.client.gui.GuiGraphics.class, ldlFluidStack,
                            float.class, float.class, float.class, float.class});
        }

        // ⑥ 六个探针的当前状态（依赖 C 已把 InjectionProbe.FIRED 扩到 6 个站点，R-AE2-9）。
        FluidLabelMod.LOGGER.warn(
                "[fluidlabel] 探针：site1={} site2={} site3(AE2)={} site4(GT ME)={} site5(LDLib int)={} site6(LDLib float)={}"
                        + "（\"待触发\"=还没画到液体图标，进对应界面后应变为已生效）",
                probe(InjectionProbe.SITE_FLUID_STACK),
                probe(InjectionProbe.SITE_TANK_WIDGET),
                probe(InjectionProbe.SITE_AE2_DRAW_IN_GUI),
                probe(InjectionProbe.SITE_GT_FLUID_DISPLAY),
                probe(InjectionProbe.SITE_LDLIB_FLUID_TANK),
                probe(InjectionProbe.SITE_LDLIB_FLUID_TANK_FLOAT));
    }

    // ------------------------------------------------------------------ 内部

    private static String probe(int site) {
        return InjectionProbe.hasFired(site) ? "已生效" : "待触发";
    }

    /**
     * 反射确认目标方法存在（并顺带扫 handler）。
     *
     * <p>{@code owner} 或 {@code params} 为 null 时静默跳过：那说明前置的类加载已经失败并
     * 打过 WARN 了，重复报错只会刷屏。
     */
    private static void verify(String what, Class<?> owner, String name, String callback,
                               Class<?>... params) {
        if (owner == null || params == null || hasNull(params)) {
            return;
        }
        try {
            owner.getMethod(name, params);
            FluidLabelMod.LOGGER.warn("[fluidlabel] {} 目标在：{}.{}{} → {}",
                    what, owner.getSimpleName(), name, descriptor(params),
                    handlerState(owner, callback));
        } catch (NoSuchMethodException e) {
            FluidLabelMod.LOGGER.warn(
                    "[fluidlabel] {} 目标方法不存在：{}.{}{} —— 本注入点不会生效（AE2/GTCEu 版本变了？）",
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

    /** 按类名解析参数类型；任一缺失（或类加载失败）返回 null。 */
    private static Class<?>[] params(String... names) {
        Class<?>[] out = new Class<?>[names.length];
        for (int i = 0; i < names.length; i++) {
            Class<?> c = resolve(names[i]);
            if (c == null) {
                return null;
            }
            out[i] = c;
        }
        return out;
    }

    /**
     * 参数类型名 → {@link Class}：先查<b>基本类型</b>表，再交给 {@link #load}。
     *
     * <p><b>★ 教训（实机日志实证，本类初版的真 bug）</b>：初版把 {@code "int"} / {@code "float"}
     * 直接丢给 {@code Class.forName}，结果 {@code ClassNotFoundException: int} —— 日志里那行
     * 「自检：找不到类 int（AE2/GTCEu 未安装或版本变化）」是<b>假警报</b>，而它让
     * {@link #params} 返回 null ⇒ 注入点 3/4 的签名校验被<b>整体静默跳过</b>，
     * 把真正的问题（handler 少写 {@code static} 导致 mixin 装配失败）掩盖了。
     * 基本类型不是类名，必须单独查表。
     */
    private static Class<?> resolve(String name) {
        switch (name) {
            case "boolean":
                return boolean.class;
            case "byte":
                return byte.class;
            case "char":
                return char.class;
            case "short":
                return short.class;
            case "int":
                return int.class;
            case "long":
                return long.class;
            case "float":
                return float.class;
            case "double":
                return double.class;
            case "void":
                return void.class;
            default:
                return load(name);
        }
    }

    private static boolean hasNull(Class<?>[] params) {
        for (Class<?> c : params) {
            if (c == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * 加载一个类（<b>不初始化</b>）。失败返回 null —— 缺 AE2/GTCEu 是预期情形（软依赖），
     * 不是错误；同一个缺失类只打一次 WARN（不刷屏）。
     *
     * <p>类加载器用 {@link FluidLabelMod} 的：它和两个 AE2 mixin 的目标类都由
     * Forge 的 TransformingClassLoader 加载，因此这里拿到的是<b>已混入</b>的类
     * （handler 扫描才有意义）。
     */
    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, FluidLabelMod.class.getClassLoader());
        } catch (Throwable t) {
            if (MISSING_WARNED.add(name)) {
                FluidLabelMod.LOGGER.warn(
                        "[fluidlabel] 自检：找不到类 {}（AE2/GTCEu 未安装或版本变化）—— 对应注入点不会生效。",
                        name);
            }
            return null;
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
