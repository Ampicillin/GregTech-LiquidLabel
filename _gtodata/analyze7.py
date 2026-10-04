# -*- coding: utf-8 -*-
"""v7：全局稳定标签（对全部名字池唯一）——用 MDS。若可行，则渲染端无需配方上下文。"""
import json, os, re, collections, io, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report7.md")
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
LATIN = re.compile(r"[A-Za-z0-9][A-Za-z0-9\-\.]*")

# 索引：字符 -> 含该字符的名字列表（用于快速判断子序列）
bychar = collections.defaultdict(list)
for k, n in names.items():
    for ch in set(n):
        bychar[ch].append((k, n))

def is_subseq(s, t):
    it = iter(t)
    return all(ch in it for ch in s)

def uniq_glob(s, k):
    cand = bychar.get(s[0], [])
    for k2, m in cand:
        if k2 != k and is_subseq(s, m):
            return False
    return True

FUNC = set("的了之与和及·-")
def mds_global(k, n, maxlen=4):
    others_c = bychar
    if not any(k2 != k and is_subseq(n, m) for k2, m in bychar.get(n[0], [])):
        pass
    # 1 字符
    for i, ch in enumerate(n):
        if ch in FUNC:
            continue
        if uniq_glob(ch, k):
            return ch, 1, "1char"
    # 2 字符（连续优先）
    contig2 = [n[i:i + 2] for i in range(len(n) - 1)]
    for i, s in enumerate(contig2):
        if uniq_glob(s, k):
            return s, 2, "contig2"
    for a in range(len(n)):
        for b in range(a + 1, len(n)):
            s = n[a] + n[b]
            if uniq_glob(s, k):
                return s, 2, "subseq2"
    # 3 字符
    for i in range(len(n) - 2):
        s = n[i:i + 3]
        if uniq_glob(s, k):
            return s, 3, "contig3"
    for a in range(len(n)):
        for b in range(a + 1, len(n)):
            for c in range(b + 1, len(n)):
                s = n[a] + n[b] + n[c]
                if uniq_glob(s, k):
                    return s, 3, "subseq3"
    # 4 字符（连续）
    for i in range(len(n) - 3):
        s = n[i:i + 4]
        if uniq_glob(s, k):
            return s, 4, "contig4"
    return n, len(n), "full"

lens = collections.Counter(); mech = collections.Counter()
per = {}
for k, n in names.items():
    lab, L, m = mds_global(k, n)
    per[k] = lab
    lens[min(L, 9)] += 1; mech[m] += 1

P("# 全局稳定标签（对全部 %d 个名字唯一，MDS 规则）\n" % N)
cum = 0
P("| 标签长度 | 个数 | 覆盖率 |")
P("| --- | --- | --- |")
for ln in sorted(lens):
    cum += lens[ln]
    P("| %s 字符 | %d | %.1f%% |" % ("≥9" if ln == 9 else ln, lens[ln], 100 * cum / N))
P("\n机制: " + "、".join("%s=%d (%.1f%%)" % (a, b, 100 * b / N) for a, b in mech.most_common()))

P("\n## 用户举例\n")
for k, n in [("gtceu.severely_hydro_cracked_naphtha", None),
             ("gtceu.lightly_hydro_cracked_naphtha", None),
             ("gtceu.severely_steam_cracked_naphtha", None),
             ("gtceu.lightly_steam_cracked_naphtha", None),
             ("gtceu.naphtha", None), ("gtceu.sulfuric_naphtha", None),
             ("gtocore.cetane", None), ("gtceu.hydro_cracked_butane", None),
             ("gtceu.hydro_cracked_butene", None)]:
    hit = [kk for kk in names if "%s.%s" % kk == k]
    if hit:
        kk = hit[0]
        P("- %s = %s → 全局标签 `%s`" % (k, names[kk], per[kk]))

P("\n## 长度分布最长的 20 个（看最坏情况）\n")
worst = sorted(names.items(), key=lambda kv: -len(per[kv[0]]))[:20]
for k, n in worst:
    P("- %s → `%s` (%d 字)" % (n, per[k], len(per[k])))

with open(OUT, "w", encoding="utf-8") as fh:
    fh.write(buf.getvalue())
print("\nwritten:", OUT)
