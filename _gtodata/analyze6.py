# -*- coding: utf-8 -*-
"""v6：最小可区分子序列（MDS）—— 标签是子序列而非子串，所以"重加""轻蒸"这类缩写是合法的。"""
import json, os, re, collections, random, io, sys, itertools

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report6.md")
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

def is_subseq(s, t):
    it = iter(t)
    return all(ch in it for ch in s)

def max_run(idx):
    best = run = 1
    for a, b in zip(idx, idx[1:]):
        run = run + 1 if b == a + 1 else 1
        best = max(best, run)
    return best

def extend_contig(n, idx, others, cap):
    """把选中的子序列向右连续扩到最多 cap 个字符，只要仍然唯一。"""
    sel = list(idx)
    while len(sel) < cap and sel[-1] + 1 < len(n):
        cand = sel + [sel[-1] + 1]
        s = "".join(n[i] for i in cand)
        if any(is_subseq(s, o) for o in others):
            break
        sel = cand
    return "".join(n[i] for i in sel), tuple(sel)

def mds(n, others, maxlen=3, cap=3, minlen=1):
    """最小可区分子序列。返回 (标签, 长度, 连续段, 机制)。"""
    if not others:
        return n, len(n), len(n), "full"
    # n 的任意子序列都出现在别人里（n 本身是别人的子序列）-> 无解
    if any(is_subseq(n, o) for o in others):
        return None, None, None, "contained"
    frontier = [((i,), n[i]) for i in range(len(n))]
    for L in range(1, maxlen + 1):
        uniq = [(idx, s) for idx, s in frontier
                if all(not is_subseq(s, o) for o in others)]
        if uniq:
            # 优先连续段长、再靠前
            best = max(uniq, key=lambda c: (max_run(c[0]), -c[0][0]))
            if L < minlen or L < cap:
                lab, idx2 = extend_contig(n, best[0], others, cap)
                if len(lab) >= L:
                    return lab, len(lab), max_run(idx2), "subseq+ext"
            return best[1], L, max_run(best[0]), "subseq"
        nxt = {}
        for idx, s in frontier:
            for j in range(idx[-1] + 1, len(n)):
                s2 = s + n[j]
                k2 = idx + (j,)
                if s2 not in nxt or nxt[s2][0][-1] > j:
                    nxt[s2] = (k2, s2)
        frontier = list(nxt.values())
        if not frontier:
            break
    return None, None, None, "none"

# ---------- 物质词分组
sufcnt = collections.Counter()
for n in names.values():
    for ln in range(2, min(len(n), 6) + 1):
        sufcnt[n[-ln:]] += 1
def substance(n):
    best = None
    for ln in range(2, min(len(n), 6) + 1):
        s = n[-ln:]
        if 2 <= sufcnt[s] <= 40 and (best is None or len(s) > len(best)):
            best = s
    return best or (n[-2:] if len(n) >= 2 else n)
groups = collections.defaultdict(list)
for k, n in names.items():
    groups[substance(n)].append((k, n))
groups = {g: v for g, v in groups.items() if len(v) > 1}

def run(tag, subsets, show=None):
    lens = collections.Counter(); mech = collections.Counter(); t = 0
    for S in subsets:
        ns = [n for _, n in S]
        for k, n in S:
            others = [o for o in ns if o != n] or [o for k2, o in S if k2 != k]
            others = [o for k2, o in S if k2 != k]
            lab, L, run_, m = mds(n, others)
            t += 1
            if lab is None:
                mech[m] += 1
                # 回退：全名（≤4字）否则首个 3 字
                lab, L = (n, len(n)) if len(n) <= 4 else (n[:3], 3)
                lens[min(L, 8)] += 1
            else:
                mech[m] += 1; lens[min(L, 8)] += 1
    P("\n## %s（%d 个名字）\n" % (tag, t))
    cum = 0
    P("| 标签长度 | 个数 | 覆盖率 |")
    P("| --- | --- | --- |")
    for ln in sorted(lens):
        cum += lens[ln]
        P("| %s 字符 | %d | %.1f%% |" % (ln, lens[ln], 100 * cum / t))
    P("\n机制: " + "、".join("%s=%d (%.1f%%)" % (a, b, 100 * b / t) for a, b in mech.most_common()))

run("A) 整个物质词组内（最保守）", [v for v in groups.values()])
random.seed(11)
for k in (3, 6):
    subs = []
    for v in groups.values():
        if len(v) >= k:
            for _ in range(3):
                subs.append(random.sample(v, k))
    run("B) 随机 %d 个同族液体（≈配方一页）" % k, subs)

P("\n## C) 真实样例\n")
for g in ("石脑油", "汽油", "柴油", "纳米萃取树脂", "合金", "陶瓷"):
    if g not in groups:
        continue
    v = sorted(groups[g], key=lambda x: x[1])
    P("- **%s** (%d)：" % (g, len(v)))
    for k, n in v[:14]:
        others = [o for k2, o in v if k2 != k]
        lab, L, r_, m = mds(n, others)
        disp = lab if lab else ("（无解→%s）" % (n if len(n) <= 4 else n[:3]))
        P("    - %s → `%s`  (%d字, %s)" % (n, disp, L if L else len(n), m))
    if len(v) > 14:
        P("    - …共 %d 个" % len(v))

P("\n## D) 用户举例精确对照\n")
demo = [("x1", "重度加氢裂化石脑油"), ("x2", "轻度加氢裂化石脑油"),
        ("x3", "重度蒸汽裂化石脑油"), ("x4", "轻度蒸汽裂化石脑油"),
        ("x5", "石脑油"), ("x6", "含硫石脑油")]
ns = [n for _, n in demo]
for k, n in demo:
    others = [o for k2, o in demo if k2 != k]
    lab, L, r_, m = mds(n, others, maxlen=3, cap=3)
    P("- %s → `%s`  (%s)" % (n, lab if lab else "（无解→全名）", m))

with open(OUT, "w", encoding="utf-8") as fh:
    fh.write(buf.getvalue())
print("\nwritten:", OUT)
