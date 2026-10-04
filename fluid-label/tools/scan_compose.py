# -*- coding: utf-8 -*-
"""扫描语料 lang 文件里的"运行时拼装"模板（值里含 %s 的），以及各种连字符码点。

用法：
    python tools\\scan_compose.py
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)

out = io.open(1, "w", encoding="utf-8", errors="replace", closefd=False)


def p(*a):
    out.write(u" ".join(a) + u"\n")


def show(s):
    return u"".join((ch if ord(ch) < 0x2000 else u"") + u"[U+%04X]" % ord(ch) for ch in s)


def main():
    lang_root = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(PROJECT), u"_gtodata", u"assets")
    files = []
    for root, _dirs, names in os.walk(lang_root):
        for n in names:
            if n.endswith(u".json"):
                files.append(os.path.join(root, n))

    p(u"=== 含 %%s 的模板（按文件）===")
    for path in sorted(files):
        data = json.load(io.open(path, encoding="utf-8"))
        hits = [(k, v) for k, v in data.items() if isinstance(v, str) and u"%s" in v]
        if not hits:
            continue
        p(u"--- %s（%d 条）" % (os.path.relpath(path, lang_root), len(hits)))
        for k, v in sorted(hits)[:40]:
            p(u"    %-52s %r" % (k, v))
        if len(hits) > 40:
            p(u"    … 另有 %d 条" % (len(hits) - 40))

    p(u"")
    p(u"=== material.* 里带非 ASCII 连字符的名字 ===")
    weird = u"\u2010\u2011\u2012\u2013\u2014\u2015\u2212\uff0d\u30fc\uff5e"
    for path in sorted(files):
        data = json.load(io.open(path, encoding="utf-8"))
        for k, v in sorted(data.items()):
            if not isinstance(v, str) or not k.startswith(u"material."):
                continue
            if any(ch in weird for ch in v):
                p(u"    %-46s %r  %s" % (k, v, show(v)))

    p(u"")
    p(u"=== 关注的几个材料名（原始码点）===")
    want = [u"material.gtceu.hssg", u"material.gtceu.hsse", u"material.gtceu.hsss",
            u"material.gtceu.hss", u"material.gtceu.hss_g"]
    for path in sorted(files):
        data = json.load(io.open(path, encoding="utf-8"))
        for k in want:
            if k in data:
                p(u"    %-40s %r  %s" % (k, data[k], show(data[k])))


main()
