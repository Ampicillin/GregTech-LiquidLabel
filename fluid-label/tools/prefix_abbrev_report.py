# -*- coding: utf-8 -*-
u"""长前缀分析报告（T1 用户要求：「你可以再分析其他长前缀并给出简称」）。

扫描语料里 >6 字的材料名，统计**反复出现的长前缀**，并给出：
  - 该前缀命中的名字条数
  - 生成器里的简称（PREFIX_ABBREV 命中）或"未收编"
  - 一个示例：名字 → 最终标签

输出 tools/prefix-abbrev.md（UTF-8）。
用法：python tools\prefix_abbrev_report.py --lang "…\_gtodata\assets"
"""
from __future__ import print_function

import argparse
import collections
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# 直接复用生成器里的常量与函数，保证报告与生成结果永远一致
from gen_labels import PREFIX_ABBREV, MAX_LABEL, collect, _abbrev_prefix  # noqa: E402

PROJECT = os.path.dirname(HERE)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--lang", default=os.path.join(os.path.dirname(PROJECT), u"_gtodata", u"assets"),
                    help=u"GT 语料 assets 目录（默认取工作区 ../_gtodata/assets）")
    ap.add_argument("--out", default=os.path.join(HERE, u"prefix-abbrev.md"))
    args = ap.parse_args()

    zh, _info, _en, _keys = collect(args.lang, [])
    names = sorted(set(zh.values()))
    if not names:
        # 踩过：默认路径写错 + 静默返回 ⇒ 用空表格覆盖掉好报告。
        print(u"[error] 没有收集到任何名字，检查 --lang（当前 %s）" % args.lang)
        return 3
    longs = [n for n in names if len(n) > MAX_LABEL]

    # 当前已生成的标签表（示例列显示**真实最终标签**，而不是只走前缀那一档）
    table = {}
    table_path = os.path.join(PROJECT, u"src", u"main", u"resources", u"fluidlabel_labels.json")
    if os.path.isfile(table_path):
        try:
            with io.open(table_path, "r", encoding="utf-8") as fh:
                table = json.load(fh).get("byDisplayName", {}) or {}
        except Exception as exc:
            print(u"[warn] 读不到标签表（%s），示例列退化为前缀档" % exc)

    # 前缀频次：取每个长名的前 2..8 字作为候选前缀，统计出现次数
    freq = collections.Counter()
    for n in longs:
        for k in range(2, min(8, len(n)) + 1):
            freq[n[:k]] += 1

    # 只保留"反复出现"(>=2) 且长度 >=3 的前缀，且不与更短的重复口径冲突
    cands = {p: c for p, c in freq.items() if c >= 2 and len(p) >= 3}
    # 去掉被更长前缀完全覆盖的短前缀（例如同时有 碳纤维- 与 碳纤维-聚）
    drops = set()
    for p in cands:
        for q in cands:
            if p != q and q.startswith(p) and cands[q] >= cands[p] * 0.8:
                drops.add(p)
                break
    for p in drops:
        cands.pop(p, None)

    rows = sorted(cands.items(), key=lambda kv: (-kv[1], -len(kv[0]), kv[0]))
    abbr = {pat: short for pat, short in PREFIX_ABBREV}

    out = []
    out.append(u"# 长前缀分析与简称表（T1）")
    out.append(u"")
    out.append(u"- 池：%d 个不同材料显示名，其中 >%d 字 **%d 条**。"
               % (len(names), MAX_LABEL, len(longs)))
    out.append(u"- 下表 = 在 >%d 字名字里**反复出现（≥2 次）且长度 ≥3** 的起始前缀，按条数排序。"
               % MAX_LABEL)
    out.append(u"- 「简称」列 = `tools/gen_labels.py` 的 `PREFIX_ABBREV` 里的收编结果；`—` = 未收编"
               u"（说明该前缀没有被反复用于长名前缀，或自动阶梯的其它档位已经够用）。")
    out.append(u"")
    out.append(u"| 前缀 | 条数 | 简称 | 示例（名字 → 标签） |")
    out.append(u"| --- | --- | --- | --- |")
    for p, c in rows:
        hit = None
        for pat, short in sorted(abbr.items(), key=lambda kv: -len(kv[0])):
            if p == pat or p.startswith(pat):
                hit = short
                break
        sample_name = None
        sample_label = None
        for n in longs:
            if n.startswith(p):
                sample_name = n
                if n in table:
                    sample_label = table[n]
                else:
                    prem, rest = _abbrev_prefix(n)
                    sample_label = (prem + rest) if prem else u"（自动阶梯其它档）"
                break
        out.append(u"| `%s` | %d | %s | %s → `%s` |"
                   % (p, c, (u"`%s`" % hit) if hit else u"—", sample_name, sample_label))
    out.append(u"")
    out.append(u"## 已收编的前缀全表（`PREFIX_ABBREV`）")
    out.append(u"")
    out.append(u"| 前缀 | 简称 | 长度 | 命名规律 |")
    out.append(u"| --- | --- | --- | --- |")
    for pat, short in sorted(PREFIX_ABBREV, key=lambda kv: (-len(kv[0]), kv[0])):
        out.append(u"| `%s` | `%s` | %d→%d | %s |"
                   % (pat, short, len(pat), len(short),
                      u"程度/工艺首字 + 尾字（用户样例风格）"
                      if pat.startswith(u"轻度") or pat.startswith(u"重度") else u"取独有词素"))
    out.append(u"")

    with io.open(args.out, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(u"\n".join(out))
    print(u"[ok] %s：长前缀 %d 条候选，已收编 %d 条"
          % (args.out, len(rows), len(PREFIX_ABBREV)))
    return 0


if __name__ == u"__main__":
    sys.exit(main())
