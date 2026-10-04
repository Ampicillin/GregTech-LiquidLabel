# -*- coding: utf-8 -*-
"""按子串查一族名字 → 标签（排查"牌号丢失/被截断"类问题的第一工具）。

用法：
    python tools/family_probe.py 熔融高速钢 熔融铝合金
    python tools/family_probe.py --anchors 熔融        # 同时显示 anchors 里的覆盖值
"""
from __future__ import print_function

import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TABLE = os.path.join(ROOT, "src", "main", "resources", "fluidlabel_labels.json")
ANCHORS = os.path.join(HERE, "anchors.json")


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    show_anchors = "--anchors" in sys.argv
    if not args:
        print(u"用法：python tools/family_probe.py <子串> [更多子串...]")
        return 2
    with io.open(TABLE, encoding="utf-8") as fh:
        table = json.load(fh)
    labels = table["byDisplayName"]
    by_reg = table.get("byRegistryName", {})
    anchors = {}
    if show_anchors and os.path.isfile(ANCHORS):
        with io.open(ANCHORS, encoding="utf-8") as fh:
            anchors = json.load(fh).get("byDisplayName", {})

    for probe in args:
        print(u"\n## %s" % probe)
        hits = sorted(n for n in labels if probe in n)
        if not hits:
            print(u"   （无命中）")
            continue
        for n in hits:
            lab = labels[n]
            regs = [r for r, v in by_reg.items() if v == lab]
            mark = u" [anchor]" if n in anchors else u""
            # 渲染侧：maxChars=3 × maxLines=2 ⇒ 只会画出前 6 个码点
            shown = lab[:6]
            flag = u"  ← 超出 6 码点，渲染会截断" if len(lab) > 6 else u""
            print(u"   %-28s len=%d  name_len=%d  label=`%s`（渲染=`%s`）%s%s"
                  % (n, len(lab), len(n), lab, shown, mark, flag))
            if regs:
                print(u"        registry: %s" % u", ".join(sorted(regs)[:4]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
