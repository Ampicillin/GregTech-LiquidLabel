# -*- coding: utf-8 -*-
"""探查 GT 语料里"熔融/热/等离子体"等状态前缀流体的实际 lang 键与取值。

目的：解释为什么 `熔融高速钢-G` / `熔融铝合金-2090` 这类名字**不在** fluidlabel 的
1898 条显示名池里（生成器只收 `material.*` 键）。
"""
import io
import json
import os
import re

BASE = r"D:\Program Files (x86)\DeepseekHarness\workspace\_gtodata\assets"
OUT = r"D:\Program Files (x86)\DeepseekHarness\workspace\_diag\lang_molten.txt"


def main():
    lines = []
    for mod in ("gtceu", "gtocore"):
        p = os.path.join(BASE, mod, "lang", "zh_cn.json")
        if not os.path.isfile(p):
            lines.append("!! missing %s" % p)
            continue
        with io.open(p, encoding="utf-8") as fh:
            d = json.load(fh)
        lines.append("== %s  keys=%d" % (mod, len(d)))

        for probe in (u"熔融", u"等离子", u"热气", u"滚烫"):
            hits = [(k, v) for k, v in d.items() if isinstance(v, str) and probe in v]
            lines.append("-- 值含 %s：%d 条" % (probe, len(hits)))
            for k, v in hits[:20]:
                lines.append("     %-60s => %s" % (k, v))

        molten = [(k, v) for k, v in d.items() if "molten" in k.lower()]
        lines.append("-- 键含 molten：%d 条" % len(molten))
        for k, v in molten[:40]:
            lines.append("     %-60s => %s" % (k, v))

        # 键前缀分布（谁在提供流体名？）
        pref = {}
        for k in d:
            head = k.split(".")[0]
            pref[head] = pref.get(head, 0) + 1
        lines.append("-- 键首段分布 top20：%s"
                     % ", ".join("%s=%d" % kv for kv in
                                 sorted(pref.items(), key=lambda kv: -kv[1])[:20]))

        # 是否真有 material.* 键取值为 熔融X
        mats = {k: v for k, v in d.items() if k.startswith("material.") and isinstance(v, str)}
        lines.append("-- material.* 键 %d 条；其中值含 熔融 的：%d"
                     % (len(mats), len([1 for v in mats.values() if u"熔融" in v])))

        # 找 高速钢 / 铝合金 相关的所有键
        for probe in (u"高速钢", u"铝合金"):
            rel = [(k, v) for k, v in d.items() if isinstance(v, str) and probe in v]
            lines.append("-- 值含 %s：%d 条（前 20）" % (probe, len(rel)))
            for k, v in rel[:20]:
                lines.append("     %-60s => %s" % (k, v))
        lines.append("")

    with io.open(OUT, "w", encoding="utf-8") as fh:
        fh.write(u"\n".join(lines))
    print("wrote %s (%d lines)" % (OUT, len(lines)))


if __name__ == "__main__":
    main()
