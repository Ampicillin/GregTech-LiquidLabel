package com.fluidlabel;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.fluidlabel.compat.Ae2Compat;
import com.fluidlabel.compat.EmiCompat;
import com.fluidlabel.compat.LdlibFluidCompat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * 标签绘制层（PLAN §⑥ 绘制要求）：缩放、描边、对比度、换行、避让数量文字、状态复原。
 *
 * <p>被两个 Mixin 调用（PLAN §⑨.2 钉死的两个签名）：
 * <ul>
 *   <li>注入点 1：{@link #drawOverlay(GuiGraphics, Object, int, int, int, int, boolean)}
 *       —— 坐标就是图标左上角，宽高由 FluidEmiStack 语义固定为 16×16；</li>
 *   <li>注入点 2：{@link #drawOverlay(GuiGraphics, dev.emi.emi.api.widget.Bounds)}
 *       —— TankWidget 的坐标只能从 {@code getBounds()} 取。</li>
 * </ul>
 *
 * <p><b>渲染状态（A7）</b>：全程 {@code pushPose/popPose} 成对；自己设定的 shader color
 * 一定复原成 {@code (1,1,1,1)}；不用 {@code enableScissor}（避免 scissor 栈不平衡）。
 *
 * <p><b>z 序</b>：EMI 把液体画在调用者 pose 空间 +100 处，所以这里翻译 +150
 * （常量见 {@link #Z_ABOVE_FLUID}）。若实测标签被别的层盖住，调大该常量即可。
 *
 * <p><b>热路径纪律（PLAN §5.4）</b>：不调 {@code Component#getString()}（走
 * {@link LabelEngine} 的缓存）、不编译正则、不 {@code String.format}、不反射；
 * 文字布局（Font#width / 换行）按标签字符串缓存。
 */
public final class LabelRenderer {

    /**
     * 液体是在调用者 pose 空间里 z+100 画的（PLAN §④.1 字节码证据）；这里再抬 50，
     * 既压在流体与 tint 之上，又不至于盖住悬停时 EMI/原版叠加在 GUI 上的物品与 tooltip。
     * 实测若被别的层盖住，优先调大这个常量（不需要改结构）。
     */
    private static final float Z_ABOVE_FLUID = 150.0F;

    /** 描边偏移半径（GUI 空间，未缩放）。 */
    private static final float OUTLINE_OFFSET = 1.0F;

    /** 黑色描边 / 白字。 */
    private static final int COLOR_OUTLINE_DARK = 0xFF000000;
    private static final int COLOR_OUTLINE_LIGHT = 0xFFF0F0F0;
    private static final int COLOR_TEXT_LIGHT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DARK = 0xFF101010;

    /** 底板（半透明黑）。 */
    private static final int COLOR_BACKDROP = 0x80000000;

    /** 避开数量文字时，底部留出的像素（未缩放）。 */
    private static final float AMOUNT_RESERVE = 4.0F;

    /** 标签布局缓存上限；超过就整体清空（重建很便宜，比维护 LRU 划算）。 */
    private static final int LAYOUT_CACHE_LIMIT = 4096;

    private static final Map<String, Layout> LAYOUTS = new ConcurrentHashMap<>();

    /**
     * 去重时间窗（纳秒）：只用来挡"同一个图标在同一次调用栈里被连画两次"
     * （GT 的 {@code PhantomFluidWidget} 先 {@code super} 画真实流体、紧接着又画幽灵流体；
     * 这种重复相隔只有微秒级）。
     *
     * <p><b>为什么不用 {@code FrameContext.epoch()} 当"帧号"（实机缺陷，PLAN §8.2）</b>：
     * epoch 由客户端 tick（{@code ClientTickEvent.Phase.END}，20 Hz）推进，而渲染是 60+ fps
     * ⇒ 连续 3 帧里 epoch 完全相同。按 epoch 去重会把这 3 帧中的后 2 帧整体挡掉，
     * 标签以 20 Hz 亮灭（用户实测"一秒闪烁 10–20 次"）。用时间窗则：同一次调用栈的重复
     * 相隔 ≪ 2 ms 必被挡住，而相邻两帧至少隔 ~7 ms（144 fps）绝不会误挡。
     */
    private static final long DEDUPE_WINDOW_NANOS = 2_000_000L;

    /**
     * AE2 侧去重 memo（PLAN §6.4 第 3 点）：上一次真正画过的 (时间戳, x, y, key)。
     * 时间戳用 {@link #DEDUPE_WINDOW_NANOS} 判定"同一次调用栈"，不用 epoch。
     * 初值 0（而不是 {@code Long.MIN_VALUE}，避免 {@code now - 初值} 溢出成负数）：
     * nanoTime 早已跑过很久，首次调用的差值必然远超窗口。
     */
    private static volatile long memoNanos = 0L;
    private static volatile int memoX = Integer.MIN_VALUE;
    private static volatile int memoY = Integer.MIN_VALUE;
    private static volatile Object memoKey = null;

    private static volatile boolean firstFrameChecked = false;
    private static volatile boolean dumpWritten = false;

    private LabelRenderer() {
    }

    /**
     * 注入点 1（PLAN §④.1）：EMI 液体图标的 per-icon 绘制回调。
     *
     * @param emiStack    {@code FluidEmiStack} 实例（这里保持防御性 instanceof）
     * @param x           图标左上角 x
     * @param y           图标左上角 y
     * @param w           宽（FluidEmiStack 恒为 16）
     * @param h           高（恒为 16）
     * @param amountShown 该格是否还会画数量文字（{@code flags & RENDER_REMAINDER}）
     */
    public static void drawOverlay(GuiGraphics guiGraphics, Object emiStack, int x, int y, int w, int h,
                                   boolean amountShown) {
        if (!FluidLabelConfig.enabled()) {
            return; // A5：总开关关掉时第一行返回
        }
        if (guiGraphics == null || emiStack == null) {
            return;
        }
        if (!EmiCompat.isFluidStack(emiStack)) {
            return; // 非液体（物品/流体以外的 stack）一律不画
        }
        drawSlot(guiGraphics, emiStack, x, y, w, h, amountShown, false);
    }

    /**
     * 注入点 2（PLAN §④.2）：TankWidget 的储罐槽。
     *
     * <p>签名由 PLAN §⑨.2 钉死。{@code Bounds} 在 {@code dev.emi} 下，而契约要求本类承载
     * 这个重载 ⇒ 这是"允许引用 EMI 类型"的第三处（另两处在 mixin/ 与 compat/EmiCompat）。
     *
     * <p><b>已知限制</b>：{@code Bounds} 只是一个矩形值对象，不携带 widget 引用，
     * 因此本重载拿不到 {@code EmiStack}，只能"静默不画"。真正的储罐标签由
     * {@code TankWidgetMixin} 用 {@code @Shadow getStack()} 取到 stack 后走 7 参主路径。
     * 保留此重载是为了逐字满足契约签名，并保证缺 stack 时不抛异常（A6）。
     */
    public static void drawOverlay(GuiGraphics guiGraphics, dev.emi.emi.api.widget.Bounds bounds) {
        if (!FluidLabelConfig.enabled() || guiGraphics == null || bounds == null) {
            return;
        }
        if (!FluidLabelConfig.tankWidgets()) {
            return;
        }
        // 无 stack 可用：显式什么都不做（不要在这里反射拿 widget，PLAN 要求 `getBounds()` 走 @Shadow）。
    }

    /** 真正的绘制。 */
    private static void drawSlot(GuiGraphics guiGraphics, Object emiStack, int x, int y, int w, int h,
                                 boolean amountShown, boolean preferTop) {
        final int slotW = w > 0 ? w : 16;
        final int slotH = h > 0 ? h : 16;

        ResourceLocation id = EmiCompat.idOf(emiStack);
        if (id == null) {
            return;
        }
        if (EmiCompat.isEmptyFluid(emiStack)) {
            return; // 空流体：不画
        }

        if (FluidLabelConfig.showOnHoverOnly() && !isHovered(x, y, slotW, slotH)) {
            return;
        }

        // 记录"本帧见过这个液体"（DYNAMIC 档的池来源）。
        FrameContext.observed(id);

        if (FluidLabelConfig.debugLog()) {
            debugCheck();
        }

        String label = LabelEngine.labelFor(id, EmiCompat.displayNameOf(emiStack));
        if (label == null || label.isEmpty()) {
            return;
        }

        float scale = FluidLabelConfig.scale();
        if (scale <= 0.0F) {
            return;
        }

        // 配色（2026-10 用户要求统一为「黑字白包边」）：默认不再看 tint；只有 adaptiveTextColor=true
        // 时才按亮度切换（旧行为）。`&&` 短路 ⇒ 关闭时不取 tint，也不做亮度计算。
        boolean useLightText = FluidLabelConfig.adaptiveTextColor()
                && luminance(EmiCompat.tintOf(emiStack)) < 0.55F;

        // 绘制段抽成 drawLabel(...)：AE2 侧（drawOverlayAe2）与 EMI 侧共用同一套绘制，
        // 两条路径的输出因此逐像素一致（PLAN §6.4 第 5 点）。
        drawLabel(guiGraphics, label, useLightText, x, y, slotW, slotH, amountShown, preferTop);
    }

    /**
     * 绘制段（PLAN §6.4 第 5 点从 {@code drawSlot} 原样抽出，行为逐像素不变）：
     * 布局 → pushPose → z 抬升 → 居中 → 缩放 → 底板/8 向描边 → popPose + 状态复原。
     *
     * <p>两条调用方路径的差别只有"怎么拿到标签与对比色"：
     * <ul>
     *   <li>EMI：{@link #drawSlot}（{@code adaptiveTextColor=true} 时才用 {@code EmiCompat.tintOf} 判对比度）；</li>
     *   <li>AE2 / GT ME 部件：{@link #drawOverlayAe2}（{@code Ae2Compat.tintOf}）。</li>
     * </ul>
     *
     * @param useLightText true = 浅色字 + 深色描边；false = 深色字 + 浅色描边（默认，见 {@code adaptiveTextColor}）
     */
    private static void drawLabel(GuiGraphics guiGraphics, String label, boolean useLightText,
                                  int x, int y, int w, int h, boolean amountShown, boolean preferTop) {
        // 性能自计时（默认关闭，见 LabelPerf）：enabled() 为 false 时只多一次配置读。
        final long perfStart = LabelPerf.enabled() ? System.nanoTime() : 0L;
        final int slotW = w > 0 ? w : 16;
        final int slotH = h > 0 ? h : 16;

        float scale = FluidLabelConfig.scale();
        if (scale <= 0.0F) {
            return;
        }

        int maxChars = FluidLabelConfig.maxChars();
        int maxLines = FluidLabelConfig.maxLines();
        Layout layout = layoutOf(label, scale, maxChars, maxLines);
        if (layout.lines.isEmpty()) {
            return;
        }

        float textW = layout.width * scale;
        float textH = layout.height * scale;
        float drawX = x + (slotW - textW) / 2.0F;
        float drawY;
        boolean avoidBottom = preferTop || (amountShown && FluidLabelConfig.avoidAmountText());
        if (avoidBottom) {
            drawY = y + (slotH - textH) / 2.0F - AMOUNT_RESERVE / 2.0F;
        } else {
            drawY = y + (slotH - textH) / 2.0F;
        }
        // 别跑出槽外
        if (drawX < x) {
            drawX = x;
        }
        if (drawY < y) {
            drawY = y;
        }

        PoseStack pose = guiGraphics.pose();
        pose.pushPose();
        try {
            pose.translate(0.0F, 0.0F, Z_ABOVE_FLUID);
            pose.translate(drawX, drawY, 0.0F);
            pose.scale(scale, scale, 1.0F);

            Font font = Minecraft.getInstance().font;
            if (font == null) {
                return;
            }

            if (FluidLabelConfig.backdrop()) {
                guiGraphics.fill(-1, -1, layout.width + 1, layout.height + 1, COLOR_BACKDROP);
            }

            float lineY = 0.0F;
            for (String line : layout.lines) {
                if (!line.isEmpty()) {
                    float lineW = font.width(line);
                    float lineX = (layout.width - lineW) / 2.0F;
                    if (FluidLabelConfig.outline()) {
                        // 8 向描边：先画反色底，再画正色 —— 比底板更不挡液体（PLAN §⑥）。
                        int outlineColor = useLightText ? COLOR_OUTLINE_DARK : COLOR_OUTLINE_LIGHT;
                        drawStringNoShadow(guiGraphics, font, line, lineX - OUTLINE_OFFSET, lineY, outlineColor);
                        drawStringNoShadow(guiGraphics, font, line, lineX + OUTLINE_OFFSET, lineY, outlineColor);
                        drawStringNoShadow(guiGraphics, font, line, lineX, lineY - OUTLINE_OFFSET, outlineColor);
                        drawStringNoShadow(guiGraphics, font, line, lineX, lineY + OUTLINE_OFFSET, outlineColor);
                        drawStringNoShadow(guiGraphics, font, line, lineX - OUTLINE_OFFSET, lineY - OUTLINE_OFFSET, outlineColor);
                        drawStringNoShadow(guiGraphics, font, line, lineX + OUTLINE_OFFSET, lineY - OUTLINE_OFFSET, outlineColor);
                        drawStringNoShadow(guiGraphics, font, line, lineX - OUTLINE_OFFSET, lineY + OUTLINE_OFFSET, outlineColor);
                        drawStringNoShadow(guiGraphics, font, line, lineX + OUTLINE_OFFSET, lineY + OUTLINE_OFFSET, outlineColor);
                    }
                    drawStringNoShadow(guiGraphics, font, line, lineX, lineY,
                            useLightText ? COLOR_TEXT_LIGHT : COLOR_TEXT_DARK);
                }
                lineY += font.lineHeight;
            }
        } finally {
            pose.popPose();
            // A7：不污染 EMI 后续绘制。
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            if (perfStart != 0L) {
                LabelPerf.record(System.nanoTime() - perfStart);
            }
        }
    }

    /**
     * AE2 侧新入口（PLAN §6.4，签名钉死给 C）：AE2 界面与 GT 的 ME 部件界面共用。
     *
     * <p>与 EMI 侧（{@link #drawOverlay}）的差别只有"标签与 tint 从哪儿取"，绘制本身走同一个
     * {@link #drawLabel}。三条与 EMI 侧一致的纪律：
     * <ol>
     *   <li>第一行 {@code enabled} 短路（A5）；</li>
     *   <li>{@code aeKey} 是 {@code Object}，必须 {@code instanceof}/键型过滤 —— AE2 的物品键
     *       （{@code AEItemKey}）与 GT 侧 {@code GenericStack.what()} 的物品键都不能被画上液体标签；</li>
     *   <li>每帧只允许"建缓存"以外的 O(1) 操作（{@link com.fluidlabel.compat.Ae2Compat} 负责缓存）。</li>
     * </ol>
     *
     * @param aeKey       AE2 的液体键（{@code AEFluidKey}），传 {@code Object} 以隔离类型
     * @param x           图标左上角 x（AE2 是调用者传入；GT 侧是 {@code position.x + 1}）
     * @param y           图标左上角 y
     * @param w           图标宽（两处都是 16）
     * @param h           图标高（两处都是 16）
     * @param amountShown 是否避让数量文字。AE2 的 {@code FluidKeyRenderHandler} 不在图标内画数量、
     *                    GT 侧数量文字在图标<b>右侧</b>（{@code stackX + 20}）⇒ 两处都传 {@code false}
     */
    public static void drawOverlayAe2(GuiGraphics guiGraphics, Object aeKey, int x, int y, int w, int h,
                                      boolean amountShown) {
        if (!FluidLabelConfig.enabled()) {
            return;                                     // ① A5 短路，与 EMI 侧一致
        }
        if (guiGraphics == null || aeKey == null) {
            return;
        }
        if (!Ae2Compat.isFluidKey(aeKey)) {
            return;                                     // ② 只对液体键画（物品键一律 return）
        }
        // ③ 去重 memo：唯一现实的重复形态是"同一次调用栈里对同一个图标重复 drawInGui"
        //    （外层 wrapper + 内层实现）。不同图标不可能落在同一坐标，所以单条 memo 就够，
        //    且不会误伤"同一液体出现在不同槽位"（坐标不同）。O(1)、无分配。
        //    判据是"距上次绘制 < DEDUPE_WINDOW_NANOS"，**不是** epoch（见该常量的注释）。
        long now = System.nanoTime();
        if (now - memoNanos < DEDUPE_WINDOW_NANOS && x == memoX && y == memoY && aeKey == memoKey) {
            return;
        }
        // ④ 标签（per-key 缓存；未建缓存时在这里建一次）。`hasLabelFor` 是零分配的快路径
        //    （identity 命中）；它 miss 时再走 `hasLabelNow` 让 Ae2Compat 按 equals 建/命中缓存，
        //    避免对"没有标签的键"写下 memo 而挡住它后续的第一次建缓存。
        if (!Ae2Compat.hasLabelFor(aeKey) && !hasLabelNow(aeKey)) {
            return;
        }
        String label = Ae2Compat.labelOf(aeKey);
        if (label == null || label.isEmpty()) {
            return;
        }
        // ⑤ DYNAMIC 档一致性：AE2 屏与 EMI 侧栏同帧共存时，两边的标签必须来自同一个池
        //    （镜像 drawSlot 里对 FrameContext.observed 的调用，PLAN §6.3）。
        try {
            FrameContext.observed(parseId(Ae2Compat.idOf(aeKey)));
        } catch (Throwable ignored) {
            // 观测失败不影响绘制（下一帧重试）
        }
        // ⑥ 共用绘制段
        drawLabel(guiGraphics, label, useLightTextForAe2(aeKey),
                x, y, w > 0 ? w : 16, h > 0 ? h : 16, amountShown, false);
        memoNanos = now;
        memoX = x;
        memoY = y;
        memoKey = aeKey;
    }

    /**
     * LDLib 侧（注入点 5/6）的"同一次调用栈"去重 memo（判据见 {@link #DEDUPE_WINDOW_NANOS}，
     * 不能用 epoch：epoch 只有 20 Hz，会把 60 fps 里 2/3 的帧挡掉 ⇒ 标签 20 Hz 亮灭）。
     *
     * <p>键是<b>注册名字符串</b>而不是栈对象：GT 的 {@code TankWidget} 每帧都会
     * {@code FluidHelperImpl.toFluidStack(...)} new 一个新的 LDLib {@code FluidStack}
     * ⇒ identity 比较永远不命中，去重会失效；字符串比较没有这个陷阱（且注册名本身有缓存）。
     */
    private static volatile long memoLdlibNanos = 0L;
    private static volatile int memoLdlibX = Integer.MIN_VALUE;
    private static volatile int memoLdlibY = Integer.MIN_VALUE;
    private static volatile String memoLdlibId = null;

    /**
     * LDLib 侧新入口（PLAN §7.3）：GT/GTO 机器界面（以及 LDLib 原生控件）里的液体槽。
     *
     * <p>与 {@link #drawOverlayAe2} 的差别只有"标签与 tint 从哪儿取"，绘制本身走同一个
     * {@link #drawLabel} ⇒ 三条路径（EMI / AE2 / GT 机器）的输出<b>逐像素一致</b>。同样的纪律：
     * <ol>
     *   <li>第一行 {@code enabled} 短路（A5）；</li>
     *   <li>{@code ldlStack} 声明成 {@code Object} 并在内部做类型判定 —— 与 AE2 侧同理，
     *       调用方（mixin）与主类都不因此引用 LDLib 类型；</li>
     *   <li>热路径只准 O(1)：{@link com.fluidlabel.compat.LdlibFluidCompat} 负责缓存，
     *       本方法不做任何 {@code getString()} / {@code toString()}（{@code idOf} 本身是缓存命中路径）。</li>
     * </ol>
     *
     * @param ldlStack    LDLib 的 {@code com.lowdragmc.lowdraglib.side.fluid.FluidStack}
     * @param x           流体矩形左上角 x（调用者传入 = 槽位内缩 1px 后的坐标）
     * @param y           流体矩形左上角 y
     * @param w           流体矩形宽（像素）
     * @param h           流体矩形高（像素）
     * @param amountShown 是否避让数量文字。7 参重载（GT 机器槽）按 {@code amount > 0} 传 true；
     *                    LDLib 原生 TankWidget 的数量文字画在图标内部，传 false。
     */
    public static void drawOverlayLdlib(GuiGraphics guiGraphics, Object ldlStack, int x, int y, int w, int h,
                                        boolean amountShown) {
        if (!FluidLabelConfig.enabled()) {
            return;                                     // ① A5 短路，与 EMI / AE2 侧一致
        }
        if (guiGraphics == null || ldlStack == null) {
            return;
        }
        if (!LdlibFluidCompat.isFluid(ldlStack)) {
            return;                                     // ② 只对液体栈画
        }
        String idStr = LdlibFluidCompat.idOf(ldlStack);
        if (idStr == null || idStr.isEmpty()) {
            return;
        }
        // ③ 同一次调用栈去重：同一个图标可能被两条路径画两次（GT 的 PhantomFluidWidget 既自己
        //    画幽灵流体、又走 super 画真实流体），只保留第一次，避免描边叠粗。
        //    判据是"距上次绘制 < DEDUPE_WINDOW_NANOS"，**不能**用 epoch（20 Hz，会把
        //    60 fps 里 2/3 的帧挡掉 ⇒ 标签闪烁，见该常量的注释）。
        long now = System.nanoTime();
        if (now - memoLdlibNanos < DEDUPE_WINDOW_NANOS
                && x == memoLdlibX && y == memoLdlibY && idStr.equals(memoLdlibId)) {
            return;
        }
        String label = LdlibFluidCompat.labelOf(ldlStack);
        if (label == null || label.isEmpty()) {
            return;
        }
        // ④ DYNAMIC 档一致性：与 EMI/AE2 屏同帧共存时必须观测同一个液体池（PLAN §6.3 / §7.3）。
        try {
            FrameContext.observed(parseId(idStr));
        } catch (Throwable ignored) {
            // 观测失败不影响绘制（下一帧重试）
        }
        // ⑤ 共用绘制段
        drawLabel(guiGraphics, label, useLightTextForLdlib(ldlStack),
                x, y, w > 0 ? w : 16, h > 0 ? h : 16, amountShown, false);
        memoLdlibNanos = now;
        memoLdlibX = x;
        memoLdlibY = y;
        memoLdlibId = idStr;
    }

    /**
     * "现在有标签吗"的探测：{@link com.fluidlabel.compat.Ae2Compat#hasLabelFor} 的兜底。
     *
     * <p>存在的理由只有一个：{@code hasLabelFor} 用 identity 判定缓存命中，理论上可能出现
     * "缓存里是等价的另一个对象"的情形 —— 此时直接走一次 {@link com.fluidlabel.compat.Ae2Compat#labelOf}
     * 让它按 {@code equals} 命中并写入正确条目（代价只发生一次）。
     */
    private static boolean hasLabelNow(Object aeKey) {
        String label = Ae2Compat.labelOf(aeKey);
        return label != null && !label.isEmpty();
    }

    /**
     * AE2 液体键 → 是否用浅色字。
     *
     * <p>默认（{@code adaptiveTextColor=false}）**在取 tint 之前**就短路返回 {@code false}
     * = 深色字 + 浅色描边，三个界面（EMI / AE2 / GT）因此配色完全一致，且关闭时热路径
     * 不会去查 tint 缓存；只有开启自适应时才按亮度判断。
     */
    private static boolean useLightTextForAe2(Object aeKey) {
        return FluidLabelConfig.adaptiveTextColor() && luminance(Ae2Compat.tintOf(aeKey)) < 0.55F;
    }

    /** LDLib 液体栈 → 是否用浅色字（判据与 {@link #useLightTextForAe2} 相同，同样先短路）。 */
    private static boolean useLightTextForLdlib(Object ldlStack) {
        return FluidLabelConfig.adaptiveTextColor() && luminance(LdlibFluidCompat.tintOf(ldlStack)) < 0.55F;
    }

    /** 注册名字符串 → {@code ResourceLocation}（仅为 FrameContext 观测用）。 */
    private static ResourceLocation parseId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return ResourceLocation.tryParse(id);
    }

    /** 用 4 参 drawString（{@code dropShadow=false}），避免描边被阴影糊成一团。 */
    private static void drawStringNoShadow(GuiGraphics guiGraphics, Font font, String text,
                                           float x, float y, int color) {
        guiGraphics.drawString(font, text, Math.round(x), Math.round(y), color, false);
    }

    // ------------------------------------------------------------------ 布局

    private static Layout layoutOf(String label, float scale, int maxChars, int maxLines) {
        String key = label + '\u0001' + scale + '\u0001' + maxChars + '\u0001' + maxLines;
        Layout cached = LAYOUTS.get(key);
        if (cached != null) {
            return cached;
        }
        Layout built = buildLayout(label, maxChars, maxLines);
        if (LAYOUTS.size() > LAYOUT_CACHE_LIMIT) {
            LAYOUTS.clear();
        }
        LAYOUTS.put(key, built);
        return built;
    }

    /**
     * 换行：单行最多 {@code maxChars} 个"字符"（按 code point 计，避免把代理对劈开），
     * 最多 {@code maxLines} 行；超出部分整体丢弃（宁可短也不越界）。
     */
    private static Layout buildLayout(String label, int maxChars, int maxLines) {
        Font font = Minecraft.getInstance().font;
        List<String> lines = new ArrayList<>(maxLines);
        int perLine = Math.max(1, maxChars);
        int width = 0;
        int index = 0;
        int len = label.length();
        while (index < len && lines.size() < maxLines) {
            int count = 0;
            int end = index;
            while (end < len && count < perLine) {
                char c = label.charAt(end);
                end++;
                if (Character.isHighSurrogate(c) && end < len && Character.isLowSurrogate(label.charAt(end))) {
                    end++;
                }
                count++;
            }
            String line = label.substring(index, end);
            lines.add(line);
            if (font != null) {
                width = Math.max(width, font.width(line));
            } else {
                width = Math.max(width, perLine * 9);
            }
            index = end;
        }
        int lineHeight = font == null ? 9 : font.lineHeight;
        int height = lines.size() * lineHeight;
        return new Layout(lines, width, height);
    }

    // ------------------------------------------------------------------ 辅助

    /** 亮度：tint（ARGB）→ 0..1，用于自动对比度。 */
    private static float luminance(int argb) {
        if ((argb >>> 24) == 0) {
            return 0.0F; // 全透明 tint 视为"没有底色" ⇒ 用浅色文字
        }
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0.299F * r + 0.587F * g + 0.114F * b) / 255.0F;
    }

    /** 鼠标是否悬停在槽内（GUI 缩放空间）。 */
    private static boolean isHovered(int x, int y, int w, int h) {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getWindow() == null || mc.mouseHandler == null) {
                return true; // 取不到就当作"悬停"，宁多画不漏画
            }
            double guiScale = mc.getWindow().getGuiScale();
            double mx = mc.mouseHandler.xpos() / guiScale;
            double my = mc.mouseHandler.ypos() / guiScale;
            return mx >= x && mx < x + w && my >= y && my < y + h;
        } catch (Throwable t) {
            return true;
        }
    }

    /** 首帧自检 +（可选）一次性导出「注册名 → 显示名」。 */
    private static void debugCheck() {
        if (!firstFrameChecked) {
            firstFrameChecked = true;
            FluidLabelMod.LOGGER.info(
                    "[fluidlabel] 自检：enabled={} mode={} table={} 条 source={} scale={} maxChars={} maxLines={}",
                    FluidLabelConfig.enabled(), FluidLabelConfig.mode(), LabelTables.size(),
                    FluidLabelConfig.labelSource(), FluidLabelConfig.scale(),
                    FluidLabelConfig.maxChars(), FluidLabelConfig.maxLines());
        }
        if (FluidLabelConfig.dumpRegistry() && !dumpWritten) {
            dumpWritten = true;
            dumpRegistry();
        }
    }

    /** 把已见到的 (注册名 → 显示名) 写进 {@code config/fluidlabel-dump.json}。 */
    private static void dumpRegistry() {
        Path dir = FluidLabelConfig.configDir();
        if (dir == null) {
            Path aliases = FluidLabelConfig.aliasesFile();
            dir = aliases == null ? null : aliases.getParent();
        }
        if (dir == null) {
            FluidLabelMod.LOGGER.warn("[fluidlabel] dumpRegistry 失败：拿不到 config 目录");
            return;
        }
        Path out = dir.resolve("fluidlabel-dump.json");
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            Map<String, String> known = new LinkedHashMap<>(LabelEngine.knownNames());
            w.write("{\n  \"byRegistryName\": {\n");
            int i = 0;
            for (Map.Entry<String, String> e : known.entrySet()) {
                w.write("    \"" + escape(e.getKey()) + "\": \"" + escape(e.getValue()) + "\"");
                if (++i < known.size()) {
                    w.write(",");
                }
                w.write("\n");
            }
            w.write("  }\n}\n");
            FluidLabelMod.LOGGER.info("[fluidlabel] 已导出 {} 条注册名 → 显示名 到 {}",
                    known.size(), out);
        } catch (Throwable t) {
            FluidLabelMod.LOGGER.warn("[fluidlabel] dumpRegistry 写入失败", t);
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 一个标签的渲染布局（不可变）。 */
    private static final class Layout {
        final List<String> lines;
        final int width;
        final int height;

        Layout(List<String> lines, int width, int height) {
            this.lines = lines;
            this.width = width;
            this.height = height;
        }
    }
}
