package com.fluidlabel;

/**
 * 渲染耗时的自计时（默认关闭；只在 {@link FluidLabelConfig#debugLog()} 打开时工作）。
 *
 * <p>存在的理由：{@link LabelRenderer#drawLabel} 的成本大头是 {@code GuiGraphics.drawString}
 * （默认 8 向描边 + 正色 = 每行 9 次绘制，最多 2 行 = 每槽每帧最多 18 次），
 * 这部分只能在实机里量。纯逻辑部分（查表、memo、去重、DYNAMIC 重建）已由
 * {@code tools/parity/PerfProbe.java} 离线量化（见 {@code tools/perf-baseline.txt}）。
 *
 * <p><b>成本纪律</b>：{@link #enabled()} 为 false 时，调用点只做一次
 * {@code FluidLabelConfig.debugLog()} 判断（Forge 配置读，纳秒级），不取时间戳、不计数。
 * 因此默认配置下本类对热路径零影响。
 *
 * <p><b>线程模型</b>：只在客户端渲染线程调用，字段不加锁；日志每秒最多一行（WARN 级，
 * 与探针一致 —— INFO 在本工程实机日志里曾整段丢失过，见 PLAN §6.10）。
 */
public final class LabelPerf {

    /** 统计窗口：1 秒。 */
    private static final long WINDOW_NANOS = 1_000_000_000L;

    private static long windowStart;
    private static long windowNanos;
    private static long windowMaxNanos;
    private static int windowSlots;

    private LabelPerf() {
    }

    /** 是否开启（= debugLog 开关；默认 false）。 */
    public static boolean enabled() {
        return FluidLabelConfig.debugLog();
    }

    /**
     * 记录一个标签槽的绘制耗时。
     *
     * @param nanos {@link #enabled()} 为 true 时由调用点测得的纳秒数；为 0 表示未测量
     */
    public static void record(long nanos) {
        if (nanos <= 0L) {
            return;
        }
        long now = System.nanoTime();
        if (windowStart == 0L) {
            windowStart = now;
        }
        windowNanos += nanos;
        windowSlots++;
        if (nanos > windowMaxNanos) {
            windowMaxNanos = nanos;
        }
        if (now - windowStart >= WINDOW_NANOS) {
            flush(now);
        }
    }

    private static void flush(long now) {
        double seconds = (now - windowStart) / 1e9;
        if (seconds <= 0.0) {
            seconds = 1.0;
        }
        double avgUs = windowNanos / 1000.0 / Math.max(1, windowSlots);
        double totalMsPerSec = windowNanos / 1e6 / seconds;
        FluidLabelMod.LOGGER.warn(
                "[fluidlabel] 标签渲染耗时：{}/s 个标签槽、平均 {} µs/槽（含描边绘制）、合计 {} ms/s、单槽峰值 {} µs"
                        + " —— 关掉 debugLog 即停（对照：把 enabled 设为 false 可看纯基线）",
                Math.round(windowSlots / seconds), String.format("%.1f", avgUs),
                String.format("%.2f", totalMsPerSec), String.valueOf(windowMaxNanos / 1000));
        windowStart = now;
        windowNanos = 0L;
        windowMaxNanos = 0L;
        windowSlots = 0;
    }
}
