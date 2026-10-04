# -*- coding: utf-8 -*-
"""v4：正确的"混淆集合"定义 = 共享同一物质尾缀的名字；并在"配方规模"子集上测标签长度。"""
import json, os, re, collections, random, io, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report4.md")
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

# ---------------------------------------------------------------- 物质尾缀表
sufcnt = collections.Counter()
for n in names.values():
    for ln in range(2, min(len(n), 6) + 1):
        sufcnt[n[-ln:]] += 1
def substance(n):
    """名字的"物质词" = 最长且被 2..40 个名字共用的尾缀（贪心取最长）。"""
    best = None
    for ln in range(2, min(len(n), 6) + 1):
        s = n[-ln:]
        if 2 <= sufcnt[s] <= 40 and (best is None or len(s) > len(best)):
            best = s
    return best or n[-2:] if len(n) >= 2 else n

groups = collections.defaultdict(list)
for k, n in names.items():
    groups[substance(n)].append((k, n))
groups = {g: v for g, v in groups.items() if len(v) > 1}
covered = sum(len(v) for v in groups.values())
P("# 混淆集合定义：共享同一物质尾缀\n")
P("物质词组: %d 个，覆盖 %d/%d 个名字 (%.0f%%)\n" % (len(groups), covered, N, 100 * covered / N))
P("最大的 12 个物质词组：")
for g, v in sorted(groups.items(), key=lambda x: -len(x[1]))[:12]:
    P("- **%s** (%d): %s" % (g, len(v), "、".join(n for _, n in sorted(v, key=lambda x: x[1])[:14])))

# ---------------------------------------------------------------- 最短可区分标签
def shortest_distinguishing(n, others, budget=14):
    if not others:
        return None, 0
    for ln in range(1, min(len(n), budget) + 1):
        for st in range(len(n) - ln, -1, -1):
            s = n[st:st + ln]
            if all(s not in o for o in others):
                return s, ln
    return None, None  # 完全无法区分（同名/子串关系）

def latin_token(n):
    toks = [m.group(0).strip("-.") for m in LATIN.finditer(n)]
    toks = [t for t in toks if len(t) >= 2]
    return toks[-1] if toks else None

def label(n, others):
    """优先牌号，其次最短唯一子串。返回 (label, chars, need_two_lines)。"""
    if not others:
        return n, len(n), len(n) > 5
    tok = latin_token(n)
    if tok and all(tok not in o for o in others):
        return tok, len(tok), False
    s, ln = shortest_distinguishing(n, others)
    if s is None:
        return None, None, None
    return s, ln, ln > 5

# ---------- A) 整组内区分
P("\n## A) 在整个物质词组内区分（最保守）\n")
cnt = collections.Counter(); fails = 0; tot = 0
for g, v in groups.items():
    for k, n in v:
        others = [o for kk, o in v if kk != k]
        lab, c, two = label(n, others)
        tot += 1
        if lab is None:
            fails += 1
        else:
            cnt[min(c, 13)] += 1
cum = 0
P("| 预算 | 名字数 | 覆盖率 |")
P("| --- | --- | --- |")
for ln in sorted(cnt):
    cum += cnt[ln]
    tag = "≥13" if ln == 13 else ("≤%d" % ln)
    P("| %s 字符 | %d/%d | %.1f%% |" % (tag, cnt[ln], tot, 100 * cum / tot))
P("\n完全无法用子串区分（需序号回退）: %d/%d (%.1f%%)\n" % (fails, tot, 100 * fails / tot))

# ---------- B) 配方规模子集模拟
P("\n## B) 按「一个配方里同时出现的液体数」抽样（这才是真实场景）\n")
random.seed(42)
P("| 同屏液体数 k | 需要 ≤2 字 | ≤3 字 | ≤4 字 | 无解需序号 |")
P("| --- | --- | --- | --- | --- |")
for k in (2, 3, 4, 6, 8):
    agg = collections.Counter(); f = 0; t = 0
    for g, v in groups.items():
        if len(v) < k:
            continue
        for _ in range(4):
            sub = random.sample(v, k)
            for kk, n in sub:
                others = [o for k2, o in sub if k2 != kk]
                lab, c, two = label(n, others)
                t += 1
                if lab is None:
                    f += 1
                else:
                    agg[min(c, 13)] += 1
    c2 = c3 = c4 = 0
    for ln, cc in agg.items():
        if ln <= 2: c2 += cc
        if ln <= 3: c3 += cc
        if ln <= 4: c4 += cc
    P("| %d | %.1f%% | %.1f%% | %.1f%% | %.1f%% |"
      % (k, 100 * c2 / t, 100 * c3 / t, 100 * c4 / t, 100 * f / t))

# ---------- C) 用户举例的组
P("\n## C) 用户举例：石脑油组与烷烃组\n")
for g in ("石脑油", "十六烷", "柴油", "汽油"):
    if g not in groups:
        P("- 「%s」不在物质词表中（组内只有 1 个）" % g); continue
    v = sorted(groups[g], key=lambda x: x[1])
    P("- **%s** (%d)：" % (g, len(v)))
    for k, n in v:
        others = [o for k2, o in v if k2 != k]
        lab, c, two = label(n, others)
        P("    - %s → `%s`" % (n, (lab if lab else "（无解，用序号）") + ("" if not two else " (需2行)")))

with open(OUT, "w", encoding="utf-8") as fh:
    fh.write(buf.getvalue())
print("\nwritten:", OUT)
