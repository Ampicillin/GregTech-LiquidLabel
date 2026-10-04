package com.fluidlabel;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.resources.ResourceLocation;

/**
 * DYNAMIC 档（PLAN §5.3「同屏动态」）的帧上下文：记录本帧通过两个注入点绘制过的液体集合，
 * 并对该集合缓存一份"互异标签"快照。
 *
 * <p><b>关键设计：标签必须由「上一帧已发布的集合」算出，而不是由正在构建的集合算出。</b>
 * 原因：注入点在绘制时被逐个调用（第一个液体被画时，本帧其余液体还没出现），若用"正在
 * 构建的集合"求 MDS，先画的标签与后画的不是同一套解，同屏会出现撞车。因此这里做
 * live/published 双缓冲：
 * <ul>
 *   <li>{@link #observed(ResourceLocation)} 写入 live 集合，并在跨越"帧窗口"时把 live
 *       快照成 published、清空 live、epoch++；</li>
 *   <li>{@link #currentFrame()} 只读 published 快照；</li>
 *   <li>{@link #endFrame()} 立即把 live 发布（供宿主在帧末主动调用）。</li>
 * </ul>
 *
 * <p><b>帧窗口的判定</b>：本类不使用 Forge 的 tick 事件（保持零事件注册、零 API 风险），
 * 而是用 {@code System.nanoTime()} 的静默间隔（{@link #FRAME_GAP_NANOS} = 40ms）判定。
 * 依据：同一帧内 EMI 会连续绘制几十个槽，间隔远小于 1ms；而两帧之间至少隔着一次
 * 「tick + 渲染」循环。40ms 是 25fps 的安全余量，既不会把一帧切碎，也不会推迟超过一帧。
 *
 * <p>线程模型：全部状态只在客户端渲染线程读写，用 {@code synchronized} 串行化即可。
 */
public final class FrameContext {

    /** 帧间静默阈值：超过它就认为进入了新的一帧（40ms ≈ 25fps 的余量）。 */
    public static final long FRAME_GAP_NANOS = 40_000_000L;

    private static final Object LOCK = new Object();

    /** 已发布的快照（只读方可见）。 */
    private static volatile Set<ResourceLocation> published = Collections.emptySet();
    /** 正在构建的快照。 */
    private static Set<ResourceLocation> live = new LinkedHashSet<>();
    /** 已发布快照的编号；每次滚动 +1，供 LabelEngine 做缓存键。 */
    private static final AtomicLong EPOCH = new AtomicLong();
    /** 本帧每个液体的标签缓存（key = 注册名，value = 标签）。 */
    private static volatile Map<String, String> publishedLabels = Collections.emptyMap();
    private static volatile long lastObserveNanos = 0L;

    private FrameContext() {
    }

    /**
     * 记录"本帧绘制过这个液体"（在两个注入点里调用）。
     *
     * <p>调用频率 = 每槽每帧一次，所以这里只做最便宜的事：集合写入 + 一次 nanoTime 比较。
     */
    public static void observed(ResourceLocation id) {
        if (id == null) {
            return;
        }
        long now = System.nanoTime();
        synchronized (LOCK) {
            if (lastObserveNanos != 0L && now - lastObserveNanos > FRAME_GAP_NANOS) {
                roll();
            }
            lastObserveNanos = now;
            live.add(id);
        }
    }

    /**
     * 主动结束本帧：把 live 立即发布出去，并开启新的 live。
     * （宿主若接上明确的帧末事件，可调用本方法让标签提前一帧生效。）
     */
    public static void endFrame() {
        synchronized (LOCK) {
            roll();
        }
    }

    /** 当前已发布的液体集合（不可变快照，可直接喂给 DistinctMds）。 */
    public static Set<ResourceLocation> currentFrame() {
        return published;
    }

    /** 已发布集合的编号；-1 表示还没有任何发布。 */
    public static long epoch() {
        return EPOCH.get();
    }

    /** 当前已发布的「注册名 → 标签」缓存（不可变）。 */
    public static Map<String, String> publishedLabels() {
        return publishedLabels;
    }

    /**
     * 覆盖已发布的标签缓存。由 {@link LabelEngine} 在集合变更后调用一次
     * （每帧最多一次），之后绘制路径就是 O(1) 查表。
     */
    public static void publishLabels(Map<String, String> labels) {
        publishedLabels = labels == null ? Collections.<String, String>emptyMap() : labels;
    }

    /** 令缓存失效（配置重载 / 换档位时调用）。 */
    public static void invalidate() {
        synchronized (LOCK) {
            published = Collections.emptySet();
            live = new LinkedHashSet<>();
            publishedLabels = Collections.emptyMap();
            lastObserveNanos = 0L;
        }
    }

    /**
     * 清空缓存但**保留**已发布集合（换档位 / 改缩放时用）：
     * 集合不变 ⇒ 标签应当重算，但"当前可见液体"这个事实不该丢。
     */
    public static void invalidateLabelsOnly() {
        publishedLabels = Collections.emptyMap();
    }

    /** 内部滚动：live → published，然后开一个新的 live。调用者持锁。 */
    private static void roll() {
        // 即使 live 为空也推进 epoch：epoch 就是"画面上这套标签属于哪一帧"的版本号。
        published = live.isEmpty()
                ? Collections.<ResourceLocation>emptySet()
                : Collections.unmodifiableSet(new LinkedHashSet<>(live));
        publishedLabels = Collections.emptyMap();
        EPOCH.incrementAndGet();
        live = new LinkedHashSet<>();
        lastObserveNanos = 0L;
    }

    /** 仅供自检/调试：live 集合的大小。 */
    public static int liveSize() {
        synchronized (LOCK) {
            return live.size();
        }
    }
}
