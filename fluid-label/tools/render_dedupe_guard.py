#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""渲染去重守卫（回归闸门，PLAN §8.2）。

实机缺陷：标签在 GT 机器里以 20 Hz 亮灭（用户实测"一秒闪烁 10–20 次"）。
根因：`LabelRenderer` 的两处"同一次调用栈去重"memo 用 `FrameContext.epoch()` 当帧号，
而 epoch 由客户端 tick（`ClientTickEvent.Phase.END`，20 Hz）推进 —— 渲染 60+ fps 时
连续 3 帧 epoch 相同 ⇒ 后 2 帧整帧被挡掉。

这个脚本防止该写法回归：
  1. `LabelRenderer.java` 必须存在 `DEDUPE_WINDOW_NANOS` 且必须用 `System.nanoTime()`；
  2. `LabelRenderer.java` 不得再出现 `FrameContext.epoch()` / `FrameContext.endFrame()`；
  3. 两个 overlay 入口（AE2 / LDLib）都必须引用该时间窗常量。

另含两批纪律检查：
  - 渲染自计时（PLAN §9）：默认配置下不得进入计时分支；
  - 配色统一（PLAN §10）：`adaptiveTextColor` 默认 false、`LabelRenderer` 不再出现 `autoContrast`、
    两个入口共用 `FluidLabelConfig.adaptiveTextColor()` 判据。

用法：python tools\\render_dedupe_guard.py
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
RENDERER = os.path.join(PROJECT, "src", "main", "java", "com", "fluidlabel", "LabelRenderer.java")

failures = []
checks = 0


def check(ok, msg):
    global checks
    checks += 1
    print(("  PASS  " if ok else "  FAIL  ") + msg)
    if not ok:
        failures.append(msg)


def main():
    print("=== render_dedupe_guard: %s ===" % RENDERER)
    if not os.path.isfile(RENDERER):
        print("  FAIL  找不到 %s" % RENDERER)
        return 1
    with open(RENDERER, encoding="utf-8") as fh:
        src = fh.read()

    check("DEDUPE_WINDOW_NANOS" in src, "存在 DEDUPE_WINDOW_NANOS 常量")
    check("System.nanoTime()" in src, "使用 System.nanoTime() 作为判据")

    # 注释里提到 epoch 是允许的（javadoc 要解释为什么不这么写）⇒ 先剥掉块注释与行注释
    code = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    code = re.sub(r"//[^\n]*", "", code)
    bad_epoch = [i + 1 for i, line in enumerate(code.splitlines())
                 if re.search(r"FrameContext\.(epoch|endFrame)\s*\(", line)]
    check(not bad_epoch,
          "LabelRenderer.java 代码里不引用 FrameContext.epoch()/endFrame()（命中行：%s）" % (bad_epoch or "无"))

    # 两个入口必须各自引用时间窗（一段 handler 里出现在 nanoTime 之后）
    for entry in ("drawOverlayAe2", "drawOverlayLdlib"):
        m = re.search(r"public static void %s\(.*?\n    \}" % entry, src, re.S)
        check(m is not None and "DEDUPE_WINDOW_NANOS" in m.group(0),
              "%s 里用时间窗去重" % entry)

    # epoch 仍必须是 DYNAMIC 快照的版本号（不能被误删）
    engine = os.path.join(PROJECT, "src", "main", "java", "com", "fluidlabel", "LabelEngine.java")
    with open(engine, encoding="utf-8") as fh:
        eng = fh.read()
    check("FrameContext.epoch()" in eng, "LabelEngine 仍用 epoch 做 DYNAMIC 快照版本号")

    # 渲染自计时的零成本纪律（PLAN §9）：默认配置下不得进入计时分支
    check("LabelPerf.enabled() ? System.nanoTime() : 0L" in src,
          "drawLabel 只在 LabelPerf.enabled() 时取时间戳")
    perf_record = [i + 1 for i, line in enumerate(code.splitlines()) if "LabelPerf.record" in line]
    guarded = all(
        "perfStart != 0L" in code.splitlines()[i - 2] or "perfStart != 0L" in code.splitlines()[i - 3]
        for i in perf_record
    )
    check(bool(perf_record) and guarded,
          "LabelPerf.record 全部在 if (perfStart != 0L) 保护下（命中行：%s）" % (perf_record or "无"))
    perf = os.path.join(PROJECT, "src", "main", "java", "com", "fluidlabel", "LabelPerf.java")
    with open(perf, encoding="utf-8") as fh:
        perfsrc = fh.read()
    check("FluidLabelConfig.debugLog()" in perfsrc, "LabelPerf.enabled() 由 debugLog 控制（默认 false）")
    check("if (nanos <= 0L)" in perfsrc, "LabelPerf.record 对未测量的样本（0）直接返回")
    check(("WINDOW_NANOS" in perfsrc) and ("%.2f" in perfsrc),
          "LabelPerf 按秒级窗口聚合、每秒最多一行日志")

    # 配色统一（PLAN §10，用户 m01954）：默认必须是「深色字 + 浅色描边」，且三条路径同一条判据
    cfg = os.path.join(PROJECT, "src", "main", "java", "com", "fluidlabel", "FluidLabelConfig.java")
    with open(cfg, encoding="utf-8") as fh:
        cfgsrc = fh.read()
    check(re.search(r'\.define\("adaptiveTextColor",\s*false\)', cfgsrc) is not None,
          "adaptiveTextColor 默认 false（统一深色字 + 浅色描边）")
    bad_contrast = [i + 1 for i, line in enumerate(code.splitlines()) if "autoContrast" in line]
    check(not bad_contrast,
          "LabelRenderer 代码里不再出现 autoContrast（命中行：%s）" % (bad_contrast or "无"))
    adaptive_calls = code.count("FluidLabelConfig.adaptiveTextColor()")
    check(adaptive_calls >= 3,
          "三条路径（EMI / AE2 / LDLib）都经 FluidLabelConfig.adaptiveTextColor() 取开关（命中 %d 处）" % adaptive_calls)
    eager = [i + 1 for i, line in enumerate(code.splitlines())
             if re.search(r"useLightTextFor\([^)]*tintOf\s*\(", line)]
    check(not eager,
          "关闭自适应时不先取 tint（不允许 useLightTextFor(tintOf(...)) 这种急切求值，命中行：%s）"
          % (eager or "无"))
    check(("useLightTextForAe2(aeKey)" in src) and ("useLightTextForLdlib(ldlStack)" in src),
          "AE2 / LDLib 两条路径改用按键短路取色的 helper")

    print("=== checks=%d failures=%d ===" % (checks, len(failures)))
    if failures:
        print("VERDICT: FAILED")
        for f in failures:
            print("  - " + f)
        return 1
    print("VERDICT: ALL PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
