# -*- coding: utf-8 -*-
"""单个名字的标签诊断：语料里的原始码点、候选阶梯、表里的最终标签、占用者。

用法：
    python tools\\diag_name.py 熔融高速钢
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import gen_labels as G  # noqa: E402

out = io.open(1, "w", encoding="utf-8", errors="replace", closefd=False)


def p(*a):
    out.write(u" ".join(a) + u"\n")


def show(s):
    return u"".join(u"%s(U+%04X)" % (ch, ord(ch)) for ch in s)


def main():
    needle = sys.argv[1] if len(sys.argv) > 1 else u"熔融高速钢"
    lang_root = sys.argv[2] if len(sys.argv) > 2 else os.path.join(
        os.path.dirname(PROJECT), u"_gtodata", u"assets")

    names, info, en_names, keys_info = G.collect(lang_root)

    p(u"=== 语料里含「%s」的名字 ===" % needle)
    hits = sorted(n for n in set(names.values()) if needle in n)
    for n in hits:
        mids = sorted(m for m, v in names.items() if v == n)
        p(u"- %r  len=%d  %s" % (n, len(n), show(n)))
        p(u"    %s" % (u", ".join(mids),))

    table_path = os.path.join(PROJECT, u"src", u"main", u"resources", u"fluidlabel_labels.json")
    table = G.load_json(table_path)
    by_display = table.get(u"byDisplayName", {})
    by_reg = table.get(u"byRegistryName", {})

    p(u"")
    p(u"=== 表里这些名字的标签 ===")
    for n in hits:
        p(u"- %r -> %r" % (n, by_display.get(n)))
    p(u"")
    p(u"=== 注册名 → 标签（前 20 条）===")
    shown = 0
    for k, v in sorted(by_reg.items()):
        if any(h in (by_display.get(by_display.get(k, u""), u""), v) for h in hits) or \
           any(h in v for h in hits):
            p(u"- %s -> %s" % (k, v))
            shown += 1
            if shown > 20:
                break

    # 占用者反查：谁拿了某个候选
    owner = {}
    for n, lab in by_display.items():
        owner.setdefault(lab, []).append(n)

    p(u"")
    p(u"=== 候选阶梯 ===")
    for n in hits:
        p(u"--- %r（%d 码点）" % (n, len(n)))
        for c, tier in G.long_candidates(n):
            occ = owner.get(c, [])
            mark = u"  ← 已归 %r" % (occ[0],) if occ and occ[0] != n else u""
            p(u"    %-10s %r  len=%d%s" % (tier, c, len(c), mark))
        comp = G._compact(n)
        p(u"    _compact   = %r len=%d" % (comp, len(comp)))
        p(u"    前缀简称   = %r" % (G._abbrev_prefix(n),))
        p(u"    词尾压缩   = %r" % (G._abbrev_tokens(n),))

    p(u"")
    p(u"=== 池中长度 7 且以「%s」开头的名字 ===" % needle)
    for n in sorted(set(names.values())):
        if n.startswith(needle) and len(n) > G.MAX_LABEL:
            p(u"- %r len=%d -> %r" % (n, len(n), by_display.get(n)))


main()
