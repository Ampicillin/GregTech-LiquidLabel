# -*- coding: utf-8 -*-
"""v2：改进版标签算法 —— 优先拉丁/数字词元，再退回唯一子串；输出到 UTF-8 文件。"""
import json, os, re, collections, random, io, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report.md")
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass
buf = io.StringIO()
def P(*a):
    print(*a)
    print(*a, file=buf)

def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)

mat = {}
for mod in ("gtceu", "gtocore"):
    for field, fname in (("zh", "zh_cn.json"), ("en", "en_us.json")):
        for k, v in load(os.path.join(BASE, mod, "lang", fname)).items():
            p = k.split(".")
            if k.startswith("material.") and len(p) == 3:
                mat.setdefault((p[1], p[2]), {})[field] = v
names = {k: v["zh"] for k, v in mat.items() if v.get("zh")}

LATIN = re.compile(r"[A-Za-z0-9][A-Za-z0-9\-\.]*")

def latin_token(n):
    """取名字里的拉丁/数字词元（钛合金-TC11 -> TC11）。"""
    toks = [m.group(0).strip("-.") for m in LATIN.finditer(n)]
    toks = [t for t in toks if len(t) >= 2]
    if not toks:
        return None
    # 取最后一个（通常是牌号/编号）
    return toks[-1]

def shortest_unique_sub(n, others, budget=8):
    for ln in range(1, min(len(n), budget) + 1):
        for st in range(len(n) - ln, -1, -1):
            sub = n[st:st + ln]
            if all(sub not in o for o in others):
                return sub
    return None

def label_for(n, others):
    """返回 (标签, 机制, 行数, 字符数)。"""
    # 0) 只有自己 / 全局唯一名 → 完整名
    if not others:
        return n, "full", 1, len(n)
    # 1) 拉丁/数字词元唯一
    tok = latin_token(n)
    if tok and all(tok not in o for o in others):
        return tok, "latin-token", 1, len(tok)
    # 2) 尾部唯一子串（物质名在尾部）
    for ln in range(1, min(len(n), 6) + 1):
        sub = n[-ln:]
        if all(sub not in o for o in others):
            return sub, "unique-tail", 1, ln
    # 3) 任意位置最短唯一子串
    sub = shortest_unique_sub(n, others)
    if sub:
        return sub, "shortest-unique", 1, len(sub)
    # 4) 退化：两行（前半行 + 后半行各取核心）
    if len(n) >= 4:
        return n[:2] + "\n" + n[-2:], "two-line", 2, 4
    return n, "FAILED", 1, len(n)

# ------------------------------------------------------------ 1) 固定截断
P("## 1) 固定截断方案的全局唯一性（分母 = %d 个材料名）\n" % len(names))
P("| 方案 | 不同标签 | 冲突组 | 落在冲突中的名字 | 占比 | 最大组 |")
P("| --- | --- | --- | --- | --- | --- |")
def fixed(fn, label):
    g = collections.defaultdict(list)
    for k, n in names.items():
        g[fn(n)].append(k)
    coll = [v for v in g.values() if len(v) > 1]
    ic = sum(len(v) for v in coll)
    P("| %s | %d | %d | %d | %.0f%% | %d |"
      % (label, len(g), len(coll), ic, 100 * ic / len(names), max(len(v) for v in g.values())))
fixed(lambda n: n[0], "首字")
fixed(lambda n: n[:2], "前2字")
fixed(lambda n: n[-2:], "后2字")
fixed(lambda n: n[-3:], "后3字")
fixed(lambda n: n[-4:], "后4字")
fixed(lambda n: n[:1] + n[-1], "首字+尾字")
fixed(lambda n: n[:1] + n[-2:], "首字+后2字")

# ------------------------------------------------------------ 2) 族内消歧
P("\n## 2) 族内消歧（只在同族、即\"看起来一样\"的名字之间要求唯一）\n")
fam = collections.defaultdict(list)
for k, n in names.items():
    fam[n[:3]].append((k, n))
multi = {p: v for p, v in fam.items() if len(v) > 1}
P("共享前 3 字的族: %d 个，覆盖 %d/%d 个名字 (%.0f%%)\n"
  % (len(multi), sum(len(v) for v in multi.values()), len(names),
     100 * sum(len(v) for v in multi.values()) / len(names)))
P("> 注：这是**保守**估计 —— 真实配方一页里只会同时出现其中几个，而非整族。\n")

lens = collections.Counter(); srcs = collections.Counter(); lines = collections.Counter()
examples = []
for p, mem in multi.items():
    for k, n in mem:
        others = [o for kk, o in mem if kk != k]
        lab, src, nl, cl = label_for(n, others)
        lens[cl] += 1; srcs[src] += 1; lines[nl] += 1
        if p in [x[1] for x in sorted(((len(v), pp) for pp, v in multi.items()), reverse=True)[:14]]:
            examples.append((p, n, lab.replace("\n", "⏎")))

tot = sum(lens.values())
P("参与消歧的名字: %d 个\n" % tot)
P("| 预算 | 可唯一区分的名字 | 覆盖率 |")
P("| --- | --- | --- |")
cum = 0
for ln in sorted(lens):
    cum += lens[ln]
    P("| ≤%d 字符 | %d/%d | %.1f%% |" % (ln, cum, tot, 100 * cum / tot))
P("\n机制分布: " + "、".join("%s=%d" % (k, v) for k, v in srcs.most_common()))
P("行数分布: " + "、".join("%d行=%d" % (k, v) for k, v in sorted(lines.items())))

P("\n## 3) 典型族的实际标注效果\n")
seen = set()
for p, n, lab in examples:
    if p in seen:
        continue
    seen.add(p)
    mem = multi[p]
    pairs = []
    for k, nn in sorted(mem, key=lambda x: x[1]):
        others = [o for kk, o in mem if kk != k]
        l2 = label_for(nn, others)[0].replace("\n", "⏎")
        pairs.append("%s→`%s`" % (nn, l2))
    P("- **[%s]** %s" % (p, "  ".join(pairs[:10])))

P("\n## 4) 其它维度\n")
eng = sum(1 for n in names.values() if LATIN.search(n))
P("- 名字里含拉丁字母/数字的: %d/%d (%.0f%%) —— 这些几乎总能直接用牌号当标签（TC11 / X750 / 718）"
  % (eng, len(names), 100 * eng / len(names)))
longid = sum(1 for k in names if len(k[1]) > 14)
P("- 注册名长度 >14 字符的: %d (%.0f%%) —— 不适合直接当标签" % (longid, 100 * longid / len(names)))
P("- 名字长度分布: 中位 %d 字，均值 %.1f，最长 %d 字，>6 字的占 %.0f%%"
  % (sorted(len(n) for n in names.values())[len(names) // 2],
     sum(len(n) for n in names.values()) / len(names),
     max(len(n) for n in names.values()),
     100 * sum(1 for n in names.values() if len(n) > 6) / len(names)))

with open(OUT, "w", encoding="utf-8") as f:
    f.write(buf.getvalue())
print("\nwritten:", OUT)
