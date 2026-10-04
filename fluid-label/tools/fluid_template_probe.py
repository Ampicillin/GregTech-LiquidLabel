# -*- coding: utf-8 -*-
"""探查 GT 的"流体名模板"与"带牌号的材料名"库存。

问题：`熔融高速钢-G` / `熔融铝合金-2090` 这类名字**不在**预计算表里，
因为它们是运行时用 lang 模板拼出来的（`gtceu.fluid.molten` = `熔融%s`），
而生成器只收 `material.*` 的字面值。

输出：_diag/fluid_templates.txt
"""
import io
import json
import os
import re

BASE = r"D:\Program Files (x86)\DeepseekHarness\workspace\_gtodata\assets"
OUT = r"D:\Program Files (x86)\DeepseekHarness\workspace\_diag\fluid_templates.txt"
GRADE = re.compile(u"^(.+?)[-_ ]?([A-Za-z0-9][A-Za-z0-9._\\-]*)$")


def main():
    lines = []
    mats = {}
    for mod in ("gtceu", "gtocore"):
        p = os.path.join(BASE, mod, "lang", "zh_cn.json")
        with io.open(p, encoding="utf-8") as fh:
            d = json.load(fh)
        lines.append("== %s" % mod)
        fl = sorted((k, v) for k, v in d.items()
                    if k.startswith("gtceu.fluid") or k.startswith("fluid."))
        lines.append("-- 键以 gtceu.fluid / fluid. 开头：%d 条" % len(fl))
        for k, v in fl:
            lines.append("     %-46s => %s" % (k, v))
        for k, v in d.items():
            if k.startswith("material.") and isinstance(v, str):
                mats.setdefault(v, k)
        lines.append("")

    names = sorted(mats)
    lines.append("== 材料显示名 %d 条" % len(names))
    with_grade = [n for n in names if GRADE.match(n) and len(GRADE.match(n).group(2)) > 0
                  and re.search(u"[A-Za-z0-9]", GRADE.match(n).group(2))]
    lines.append(u"-- 能拆出尾牌号的：%d 条（前 60）" % len(with_grade))
    for n in with_grade[:60]:
        m = GRADE.match(n)
        lines.append("     %-30s cjk=%s grade=%s" % (n, m.group(1), m.group(2)))

    long_grade = [n for n in with_grade if len(n) > 6]
    lines.append("-- 其中 >6 码点（必须简化）：%d 条" % len(long_grade))
    for n in long_grade:
        m = GRADE.match(n)
        lines.append("     %-30s cjk=%s(%d) grade=%s(%d)"
                     % (n, m.group(1), len(m.group(1)), m.group(2), len(m.group(2))))

    # 派生名规模估算
    tmpl = []
    for mod in ("gtceu", "gtocore"):
        p = os.path.join(BASE, mod, "lang", "zh_cn.json")
        with io.open(p, encoding="utf-8") as fh:
            d = json.load(fh)
        for k, v in d.items():
            if isinstance(v, str) and v.count("%s") == 1 and (k.startswith("gtceu.fluid")):
                tmpl.append((k, v))
    lines.append("")
    lines.append("== 单占位符流体模板 %d 条" % len(tmpl))
    for k, v in sorted(tmpl):
        lines.append("     %-46s => %s   （派生规模 = 材料数 %d）" % (k, v, len(names)))

    with io.open(OUT, "w", encoding="utf-8") as fh:
        fh.write(u"\n".join(lines))
    print("wrote %s (%d lines)" % (OUT, len(lines)))


if __name__ == "__main__":
    main()
