# -*- coding: utf-8 -*-
"""v8：标签两两互异（而非"与所有名字都不构成子序列"）。贪心分配 + 语义优先候选。"""
import json, os, re, collections, io, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report8.md")
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass
buf = io.StringIO()
def P(*a):
    print(*a); print(*a, file=buf)

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
N = len(names)

FUNC = set("的了之与和及，,、（）()[]·-")
def candidates(n, maxlen=4):
    """按"短优先、连续优先、靠前优先"排序的候选标签。"""
    out = []
    for L in range(1, maxlen + 1):
        contig = [n[i:i + L] for i in range(len(n) - L + 1)]
        sub = []
        if L >= 2:
            for idx in __import__("itertools").combinations(range(len(n)), L):
                if idx[-1] - idx[0] + 1 == L:      # 连续，已在 contig
                    continue
                sub.append("".join(n[i] for i in idx))
        for s in contig + sub:
            if any(ch in FUNC for ch in s) and L <= 2:
                continue
            out.append((L, s))
    return out

taken = {}
order = sorted(names.items(), key=lambda kv: (len(kv[1]), kv[1]))
lens = collections.Counter(); mech = collections.Counter(); per = {}
for k, n in order:
    chosen = None
    for L, s in candidates(n, maxlen=(3 if len(n) > 3 else len(n))):
        if s not in taken:
            chosen, cl = s, L
            break
    if chosen is None:
        chosen, cl = n, len(n)
    taken[chosen] = k
    per[k] = chosen
    lens[min(cl, 9)] += 1
    mech["full" if chosen == n else ("%d字" % cl)] += 1

P("# 全局稳定标签（标签两两互异），池 = %d 个材料名\n" % N)
cum = 0
P("| 标签长度 | 个数 | 覆盖率 |")
P("| --- | --- | --- |")
for ln in sorted(lens):
    cum += lens[ln]
    P("| %s 字符 | %d | %.1f%% |" % ("≥9" if ln == 9 else ln, lens[ln], 100 * cum / N))
P("\n机制: " + "、".join("%s=%d (%.1f%%)" % (a, b, 100 * b / N) for a, b in mech.most_common()))

P("\n## 用户举例\n")
ks = ["gtceu.severely_hydro_cracked_naphtha", "gtceu.lightly_hydro_cracked_naphtha",
      "gtceu.severely_steam_cracked_naphtha", "gtceu.lightly_steam_cracked_naphtha",
      "gtceu.naphtha", "gtceu.sulfuric_naphtha", "gtocore.cetane",
      "gtceu.hydro_cracked_butane", "gtceu.hydro_cracked_butene",
      "gtceu.hydro_cracked_butadiene", "gtceu.gasoline", "gtceu.high_octane_gasoline",
      "gtceu.diesel", "gtceu.bio_diesel", "gtceu.octane", "gtceu.cetane_boosted_diesel"]
for s in ks:
    hit = [kk for kk in names if "%s.%s" % kk == s]
    if hit:
        kk = hit[0]
        P("- %s → `%s`" % (names[kk], per[kk]))

P("\n## 随机 40 个的标签抽样\n")
import random
random.seed(3)
for k in random.sample(list(names), 40):
    P("- %s → `%s`" % (names[k], per[k]))

P("\n## 需要 5 字以上的最坏情况\n")
worst = [(n, per[k]) for k, n in names.items() if len(per[k]) >= 5]
P("共 %d 个 (%.1f%%)，其中前 15：" % (len(worst), 100 * len(worst) / N))
for n, lab in sorted(worst, key=lambda x: -len(x[1]))[:15]:
    P("- %s → `%s`" % (n, lab))

with open(OUT, "w", encoding="utf-8") as fh:
    fh.write(buf.getvalue())
print("\nwritten:", OUT)
