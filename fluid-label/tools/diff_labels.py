# -*- coding: utf-8 -*-
"""diff_labels.py —— 对比两版标签表，只关心「中文名 + 拉丁数字牌号」族的变化。

用法：
    python tools/diff_labels.py <before.json> <after.json>

输出：变化条数、牌号完整度统计（变化前/后各保留了多少牌号字符）、逐条差异。
"""
import io
import json
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def load(path):
    with io.open(path, "r", encoding="utf-8") as fh:
        obj = json.load(fh)
    return obj.get("byDisplayName", obj)


def grade(name):
    toks = [t for t in re.findall(r"[A-Za-z0-9][A-Za-z0-9'\-]*", name)
            if any(ch.isdigit() for ch in t)]
    if not toks:
        return u""
    t = max(toks, key=lambda x: (len(x), x))
    return t.replace(u"-", u"").replace(u"'", u"")


def kept(label, g):
    """标签里按顺序命中了牌号的多少个字符（贪心顺序匹配）。"""
    i = 0
    for ch in label:
        if i < len(g) and ch == g[i]:
            i += 1
    return i


def main():
    before = load(sys.argv[1])
    after = load(sys.argv[2])
    keys = sorted(set(before) | set(after))
    changed = [(k, before.get(k), after.get(k)) for k in keys if before.get(k) != after.get(k)]
    print(u"显示名 %d → %d；变化 %d 条" % (len(before), len(after), len(changed)))

    worse, better, same = [], [], []
    for k, b, a in changed:
        g = grade(k)
        if not g:
            same.append((k, b, a))
            continue
        kb, ka = kept(b or u"", g), kept(a or u"", g)
        if ka > kb:
            better.append((k, b, a, g, kb, ka))
        elif ka < kb:
            worse.append((k, b, a, g, kb, ka))
        else:
            same.append((k, b, a))

    print(u"  牌号族：变好 %d / 变差 %d / 持平 %d" % (len(better), len(worse), len(same)))
    print(u"")
    print(u"## 变好")
    for k, b, a, g, kb, ka in better:
        print(u"  %s [%s] %s(%d) → %s(%d)" % (k, g, b, kb, a, ka))
    print(u"")
    print(u"## 变差")
    for k, b, a, g, kb, ka in worse:
        print(u"  %s [%s] %s(%d) → %s(%d)" % (k, g, b, kb, a, ka))
    print(u"")
    print(u"## 非牌号族 / 持平")
    for k, b, a in same:
        print(u"  %s: %s → %s" % (k, b, a))
    return 0


if __name__ == "__main__":
    sys.exit(main())
