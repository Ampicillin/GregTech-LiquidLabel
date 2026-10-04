package com.fluidlabel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Fluid Label 客户端配置（PLAN §⑥，共 15 项）。
 *
 * <p>配置文件固定为 {@code config/fluidlabel-client.toml}（由 {@code FluidLabelMod}
 * 显式传文件名注册，保证与 PLAN 一致）。
 *
 * <p>所有 getter 都是静态的、无副作用的；{@code SPEC}/{@code spec_} 必须保持 public
 * static final，否则 Forge 注册配置时会失败。
 */
public final class FluidLabelConfig {

    /** 标签档位（PLAN §5.3）。 */
    public enum Mode {
        /** 档 A：标签来自离线预计算表，跨界面恒定（默认）。 */
        STABLE,
        /** 档 B：只用本帧出现过的液体集合重算标签。 */
        DYNAMIC
    }

    /** 标签来源策略（PLAN §⑥ labelSource）。 */
    public enum LabelSource {
        /** 先查表，查不到再用运行时规则兜底（默认）。 */
        TABLE_THEN_RULE,
        /** 完全跳过内置表，只用运行时规则。 */
        RULE_ONLY,
        /** 只用内置表，查不到就不画。 */
        TABLE_ONLY
    }

    /** 别名表路径（相对 {@code config/}）。 */
    public static final String DEFAULT_ALIASES_FILE = "fluidlabel-aliases.json";

    public static final ForgeConfigSpec SPEC;

    private static final ForgeConfigSpec.BooleanValue ENABLED;
    private static final ForgeConfigSpec.EnumValue<Mode> MODE;
    private static final ForgeConfigSpec.DoubleValue SCALE;
    private static final ForgeConfigSpec.IntValue MAX_CHARS;
    private static final ForgeConfigSpec.IntValue MAX_LINES;
    private static final ForgeConfigSpec.BooleanValue ADAPTIVE_TEXT_COLOR;
    private static final ForgeConfigSpec.BooleanValue OUTLINE;
    private static final ForgeConfigSpec.BooleanValue BACKDROP;
    private static final ForgeConfigSpec.BooleanValue SHOW_ON_HOVER_ONLY;
    private static final ForgeConfigSpec.BooleanValue AVOID_AMOUNT_TEXT;
    private static final ForgeConfigSpec.EnumValue<LabelSource> LABEL_SOURCE;
    private static final ForgeConfigSpec.BooleanValue TANK_WIDGETS;
    private static final ForgeConfigSpec.ConfigValue<String> ALIASES_FILE;
    private static final ForgeConfigSpec.BooleanValue DEBUG_LOG;
    private static final ForgeConfigSpec.BooleanValue DUMP_REGISTRY;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("Fluid Label - 在 EMI 液体图标上叠加可区分的中文短标签（纯客户端）").push("general");
        ENABLED = b.comment("总开关；false 时渲染路径第一行直接返回。").define("enabled", true);
        MODE = b.comment("STABLE=跨界面恒定（预计算表）；DYNAMIC=按本帧出现的液体重算。")
                .defineEnum("mode", Mode.STABLE);
        LABEL_SOURCE = b.comment("TABLE_THEN_RULE=先查表再兜底；RULE_ONLY=只用运行时规则；TABLE_ONLY=只用表。")
                .defineEnum("labelSource", LabelSource.TABLE_THEN_RULE);
        b.pop();

        b.comment("文字外观").push("appearance");
        SCALE = b.comment("文字缩放；0.5 -> 汉字约 4.5px，一行 3 字。")
                .defineInRange("scale", 0.5D, 0.3D, 1.0D);
        MAX_CHARS = b.comment("单行最大字数；超出则切两行。")
                .defineInRange("maxChars", 3, 1, 6);
        MAX_LINES = b.comment("最多行数（1 或 2）。")
                .defineInRange("maxLines", 2, 1, 2);
        // 2026-10 用户要求：NEI/EMI 页是「黑字白包边」，AE2/GT 却随液体变色 ⇒ 统一为黑字白包边。
        // 注意这里**换过键名**（原 autoContrast）：旧配置文件里已写入的 autoContrast=true 会继续生效，
        // 换名才能让老配置拿到新默认值（false）。旧键成为孤儿行，留在 toml 里无害、可手工删除。
        ADAPTIVE_TEXT_COLOR = b.comment(
                        "文字配色。false（默认）= 所有界面统一「深色字 + 浅色描边」（与 EMI 页一致）；"
                                + "true = 按液体 tint 亮度自动切换深色字/浅色字（旧行为，AE2/GT 会随液体变色）。")
                .define("adaptiveTextColor", false);
        OUTLINE = b.comment("描边（8 向先画反色）——比底板更不挡液体。")
                .define("outline", true);
        BACKDROP = b.comment("半透明底板（0x80000000）。")
                .define("backdrop", false);
        b.pop();

        b.comment("行为").push("behavior");
        SHOW_ON_HOVER_ONLY = b.comment("true 时仅当鼠标悬停在该槽上才画标签。")
                .define("showOnHoverOnly", false);
        AVOID_AMOUNT_TEXT = b.comment("避开槽底的液体数量文字（EMI 用 RENDER_REMAINDER 画）。")
                .define("avoidAmountText", true);
        TANK_WIDGETS = b.comment("是否也给储罐槽（TankWidget）贴标签。")
                .define("tankWidgets", true);
        ALIASES_FILE = b.comment("相对 config/ 的别名表文件名（人工覆盖，最高优先级）。")
                .define("aliasesFile", DEFAULT_ALIASES_FILE);
        b.pop();

        b.comment("调试").push("debug");
        DEBUG_LOG = b.comment("首帧自检 + 命中/未命中日志。")
                .define("debugLog", false);
        DUMP_REGISTRY = b.comment("一次性导出「注册名 -> 显示名」到 config/fluidlabel-dump.json。")
                .define("dumpRegistry", false);
        b.pop();

        SPEC = b.build();
    }

    private FluidLabelConfig() {
    }

    public static boolean enabled() {
        return ENABLED.get();
    }

    public static Mode mode() {
        return MODE.get();
    }

    /** 文字缩放（float，便于直接喂给 PoseStack.scale）。 */
    public static float scale() {
        return SCALE.get().floatValue();
    }

    public static int maxChars() {
        return MAX_CHARS.get();
    }

    public static int maxLines() {
        return MAX_LINES.get();
    }

    /**
     * 是否按液体 tint 自适应文字颜色。
     *
     * <p>{@code false}（默认）= 所有界面统一「深色字 + 浅色描边」；{@code true} = 按 tint 亮度在
     * 深色字/浅色字之间切换（旧行为）。
     */
    public static boolean adaptiveTextColor() {
        return ADAPTIVE_TEXT_COLOR.get();
    }

    public static boolean outline() {
        return OUTLINE.get();
    }

    public static boolean backdrop() {
        return BACKDROP.get();
    }

    public static boolean showOnHoverOnly() {
        return SHOW_ON_HOVER_ONLY.get();
    }

    public static boolean avoidAmountText() {
        return AVOID_AMOUNT_TEXT.get();
    }

    public static LabelSource labelSource() {
        return LABEL_SOURCE.get();
    }

    public static boolean tankWidgets() {
        return TANK_WIDGETS.get();
    }

    public static boolean debugLog() {
        return DEBUG_LOG.get();
    }

    public static boolean dumpRegistry() {
        return DUMP_REGISTRY.get();
    }

    /** 别名表绝对路径（相对 {@code config/} 解析）。 */
    public static Path aliasesFile() {
        String name = ALIASES_FILE.get();
        if (name == null || name.isBlank()) {
            name = DEFAULT_ALIASES_FILE;
        }
        try {
            return FMLPaths.CONFIGDIR.get().resolve(name);
        } catch (Throwable t) {
            // 配置系统还没起来（或非 Forge 环境）时的保守兜底。
            return Paths.get("config").resolve(name);
        }
    }

    /** 若已加载，返回 config 目录；否则返回 null。 */
    public static Path configDir() {
        try {
            Path dir = FMLPaths.CONFIGDIR.get();
            if (dir != null && Files.isDirectory(dir)) {
                return dir;
            }
        } catch (Throwable ignored) {
            // 忽略：调用方按 null 处理
        }
        return null;
    }
}
