# -*- coding: utf-8 -*-
"""表结构小查询：熔融前缀名 / hssg 注册名 / 高速钢相关标签。

用法：python tools\\diag_table.py
"""
import io
import json
import os
import sys

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
out = io.open(1, "w", encoding="utf-8", errors="replace", closefd=False)


def p(*a):
    out.write(u" ".join(a) + u"\n")


path = os.path.join(PROJECT, u"src", u"main", u"resources", u"fluidlabel_labels.json")
t = json.load(io.open(path, encoding="utf-8"))
bd = t[u"byDisplayName"]
br = t[u"byRegistryName"]

p(u"byDisplayName=%d byRegistryName=%d conflicts=%s" % (len(bd), len(br), t.get(u"conflicts")))
p(u"counts=%s" % (t.get(u"counts"),))
p(u"")
p(u"--- 显示名里含「熔融」的条数：%d" % sum(1 for k in bd if u"熔融" in k))
for k, v in sorted(bd.items()):
    if u"熔融" in k:
        p(u"    %r -> %r" % (k, v))
p(u"")
p(u"--- 显示名里含「液态」「气态」「等离子体」的条数：%d/%d/%d" % (
    sum(1 for k in bd if u"液态" in k),
    sum(1 for k in bd if u"气态" in k),
    sum(1 for k in bd if u"等离子体" in k)))
p(u"")
p(u"--- 注册名里含 hssg 的：")
for k, v in sorted(br.items()):
    if u"hssg" in k:
        p(u"    %s -> %r" % (k, v))
p(u"")
p(u"--- 显示名或标签里含「高速钢」的：")
for k, v in sorted(bd.items()):
    if u"高速钢" in k or u"高速钢" in v:
        p(u"    %r -> %r" % (k, v))
p(u"")
p(u"--- 标签等于 熔融高速钢- 的显示名：%r" % ([k for k, v in bd.items() if v == u"熔融高速钢-"],))
p(u"--- 标签里含 熔融 的：")
for k, v in sorted(bd.items()):
    if u"熔融" in v:
        p(u"    %r -> %r" % (k, v))
p(u"")
p(u"--- 池中长度 7 的前 30 个名字及其标签：")
i = 0
for k, v in sorted(bd.items(), key=lambda kv: (len(kv[0]), kv[0])):
    if len(k) == 7:
        p(u"    %r -> %r" % (k, v))
        i += 1
        if i >= 30:
            break

# 用生成器自己的阶梯解释一个组合名
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_labels as G  # noqa: E402

p(u"")
p(u"--- 组合名阶梯演示（熔融 + 材料名）")
for base in [u"高速钢-G", u"高速钢-E", u"高速钢-S", u"不锈钢", u"钛"]:
    composed = u"熔融" + base
    p(u"    %r（%d 码点）" % (composed, len(composed)))
    p(u"        _compact = %r" % (G._compact(composed),))
    for c, tier in G.long_candidates(composed):
        p(u"        %-8s %r" % (tier, c))
