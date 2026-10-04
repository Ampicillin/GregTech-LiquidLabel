package com.fluidlabel.compat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * EMI 类引用隔离层（PLAN §⑨.2 第 4 条）。
 *
 * <p>本类与 {@code mixin/} 是两个<b>唯一</b>允许出现 {@code dev.emi.*} 类型的地方；
 * 其余类（LabelEngine / LabelRenderer / FrameContext …）不得 import EMI，这样"缺 EMI"
 * 时只有 Mixin 层受影响，其余代码可以照常加载。
 *
 * <p>另一重隔离：所有面向 EMI 的入口参数/返回值都是 {@code Object} 或 JDK/原版类型，
 * 不把 EMI 类型泄漏到调用方的签名里。
 *
 * <p>所有探测方法都必须"安静失效"：EMI 任意版本导致的问题只能让标签不画，绝不能崩（A6）。
 */
public final class EmiCompat {

    /** 已知的液体容器类名（1.20.1 是 {@code net.minecraft.world.level.material.Fluid}）。 */
    private static final String FLUID_CLASS = "net.minecraft.world.level.material.Fluid";
    /** 已知的空流体字段名（{@code Fluids.EMPTY}）。 */
    private static final String FLUIDS_CLASS = "net.minecraft.world.level.material.Fluids";

    /** 液态 stack 的 tint 缓存（ARGB），EMI 每个 stack 的 tint 是常量，缓存一次即可。 */
    private static final Map<String, Integer> TINT_CACHE = new ConcurrentHashMap<>();

    /** {@code EmiStack#getTint()} 反射句柄；null 表示该 API 不可用。 */
    private static volatile Method getTintMethod;
    private static volatile boolean getTintResolved = false;
    private static volatile Method getKeyMethod;
    private static volatile boolean getKeyResolved = false;
    private static volatile Object emptyFluid;
    private static volatile boolean emptyFluidResolved = false;

    private EmiCompat() {
    }

    /** 是不是 EMI 的液体 stack（注入点 1 的类型其实已是真实类，这里保持防御）。 */
    public static boolean isFluidStack(Object emiStack) {
        if (emiStack == null) {
            return false;
        }
        try {
            return emiStack instanceof dev.emi.emi.api.stack.FluidEmiStack;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 任意 EmiStack / 液体 stack 的注册名；取不到返回 null。 */
    public static ResourceLocation idOf(Object emiStack) {
        if (emiStack instanceof dev.emi.emi.api.stack.EmiStack) {
            try {
                return ((dev.emi.emi.api.stack.EmiStack) emiStack).getId();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /** 任意 EmiStack 的显示名（EMI/原版翻译后的名字）。 */
    public static Component displayNameOf(Object emiStack) {
        if (emiStack instanceof dev.emi.emi.api.stack.EmiStack) {
            try {
                return ((dev.emi.emi.api.stack.EmiStack) emiStack).getName();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    /**
     * {@code EmiStack#getTint()} 的隔离封装，返回 ARGB。取不到时返回 {@code 0xFFFFFFFF}
     * （白色）；EMI 侧因此恒为「深色字 + 浅色描边」，与默认配色（adaptiveTextColor=false）一致。
     *
     * <p>用反射而不是直接调用：EMI 1.1.x 的 tint API 在 4 参/带 index 的重载之间有过变动，
     * 反射能让签名不匹配时安静退化为白色，而不是编译不过或运行期 {@code NoSuchMethodError}。
     */
    public static int tintOf(Object emiStack) {
        ResourceLocation id = idOf(emiStack);
        if (id != null) {
            Integer cached = TINT_CACHE.get(id.toString());
            if (cached != null) {
                return cached;
            }
        }
        int tint = 0xFFFFFFFF;
        try {
            Method m = resolveGetTint();
            if (m != null) {
                Object v = m.invoke(emiStack);
                if (v instanceof Integer) {
                    tint = (Integer) v;
                }
            }
        } catch (Throwable t) {
            tint = 0xFFFFFFFF;
        }
        if (id != null) {
            TINT_CACHE.put(id.toString(), tint);
        }
        return tint;
    }

    /** stack 的 key 是不是"空流体"（{@code Fluids.EMPTY}）。取不到时返回 false（不拦）。 */
    public static boolean isEmptyFluid(Object emiStack) {
        Object key = keyOf(emiStack);
        if (key == null) {
            return false;
        }
        Object empty = emptyFluid();
        return empty != null && empty == key;
    }

    /** {@code EmiStack#getKey()}（液体 stack 上就是 Fluid 对象）。 */
    public static Object keyOf(Object emiStack) {
        if (!(emiStack instanceof dev.emi.emi.api.stack.EmiStack)) {
            return null;
        }
        try {
            Method m = resolveGetKey();
            if (m == null) {
                return null;
            }
            return m.invoke(emiStack);
        } catch (Throwable t) {
            return null;
        }
    }

    /** tint 缓存条目数（调试用）。 */
    public static int tintCacheSize() {
        return TINT_CACHE.size();
    }

    // ------------------------------------------------------------------ 内部

    private static Method resolveGetTint() {
        if (!getTintResolved) {
            synchronized (EmiCompat.class) {
                if (!getTintResolved) {
                    Method found = null;
                    try {
                        found = dev.emi.emi.api.stack.EmiStack.class.getMethod("getTint");
                    } catch (Throwable ignored) {
                        // EMI 版本差异：安静退化
                    }
                    getTintMethod = found;
                    getTintResolved = true;
                }
            }
        }
        return getTintMethod;
    }

    private static Method resolveGetKey() {
        if (!getKeyResolved) {
            synchronized (EmiCompat.class) {
                if (!getKeyResolved) {
                    Method found = null;
                    try {
                        found = dev.emi.emi.api.stack.EmiStack.class.getMethod("getKey");
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    getKeyMethod = found;
                    getKeyResolved = true;
                }
            }
        }
        return getKeyMethod;
    }

    private static Object emptyFluid() {
        if (!emptyFluidResolved) {
            synchronized (EmiCompat.class) {
                if (!emptyFluidResolved) {
                    Object found = null;
                    try {
                        Class<?> fluids = Class.forName(FLUIDS_CLASS);
                        java.lang.reflect.Field f = fluids.getField("EMPTY");
                        Object v = f.get(null);
                        if (v != null && Class.forName(FLUID_CLASS).isInstance(v)) {
                            found = v;
                        }
                    } catch (Throwable ignored) {
                        // 忽略
                    }
                    emptyFluid = found;
                    emptyFluidResolved = true;
                }
            }
        }
        return emptyFluid;
    }
}
