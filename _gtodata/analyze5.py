# -*- coding: utf-8 -*-
"""v5：语义化分层标签 —— 剥离"同屏所有名字共有的词"，只留判别部分，必要时换 2 行。"""
import json, os, re, collections, random, io, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report5.md")
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

# ---------------- 共有词剥离
def common_tokens(ns):
    """反复剥离"出现在所有名字里"的最长子串（长度>=2）。"""
    cur = list(ns); toks = []
    for _ in range(12):
        base = min(cur, key=len)
        best = None
        for ln in range(len(base), 1, -1):
            for st in range(len(base) - ln + 1):
                s = base[st:st + ln]
                if all(s in x for x in cur):
                    best = s; break
            if best: break
        if not best:
            break
        toks.append(best)
        cur = [x.replace(best, "", 1) for x in cur]
        if all(x == "" for x in cur):
            break
    return cur, toks

def latin_token(n):
    toks = [m.group(0).strip("-.") for m in LATIN.finditer(n)]
    toks = [t for t in toks if len(t) >= 2]
    return toks[-1] if toks else None

def tiered(S):
    """S = [(key, 名字)]，返回 {key: (标签, 层级)}。层级 1..5。"""
    ns = [n for _, n in S]
    cores, toks = common_tokens(ns)
    core = {k: c for (k, _), c in zip(S, cores)}
    out = {}
    for k, n in S:
        c = core[k]
        if len(n) <= 3:
            out[k] = [n, 1]
        elif c and len(c) <= 3:
            out[k] = [c, 2]
        elif c and len(c) <= 6:
            out[k] = [c, 3]                      # 2 行
        elif c:
            out[k] = [c[:3] + "…" + c[-1], 4]
        else:
            out[k] = [n[:3], 4]
    # 唯一性检查 -> 撞了就退化
    for _ in range(3):
        grp = collections.defaultdict(list)
        for k, v in out.items():
            grp[v[0]].append(k)
        bad = [k for v in grp.values() if len(v) > 1 for k in v]
        if not bad:
            break
        for k in bad:
            n = dict(S)[k]
            if len(n) <= 3 and out[k][0] != n:
                out[k] = [n, 1]
            else:
                tk = latin_token(n)
                if tk and len(tk) <= 6:
                    out[k] = [tk, 4]
                else:
                    out[k] = ["#%d" % (sorted(bad).index(k) + 1), 5]
    return out

# ---------------- 物质词分组（沿用 v4 定义）
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

P("# 语义化分层标签（剥离同屏共有词）\n")
P("混淆集合 = 共享物质尾缀的组；共 %d 组、%d 个名字\n"
  % (len(groups), sum(len(v) for v in groups.values())))

def report(tag, subsets):
    tier = collections.Counter(); lines = collections.Counter(); t = 0
    for S in subsets:
        res = tiered(S)
        for k, (lab, lv) in res.items():
            tier[lv] += 1; t += 1
            lines[lab.count("\n") + 1] += 1
    P("\n## %s（%d 个名字参与）\n" % (tag, t))
    P("| 层级 | 含义 | 占比 |")
    P("| --- | --- | --- |")
    mean = {1: "T1 全名（≤3字，一行）", 2: "T2 剥离共有词后的判别部分（≤3字，一行）",
            3: "T3 判别部分换 2 行（≤6字）", 4: "T4 压缩/牌号", 5: "T5 序号+悬停"}
    for lv in sorted(tier):
        P("| T%d | %s | %.1f%% |" % (lv, mean[lv], 100 * tier[lv] / t))
    P("\n单行/双行: " + "、".join("%d行=%.1f%%" % (a, 100 * b / t) for a, b in sorted(lines.items())))

report("A) 整组内（最保守）", [v for v in groups.values()])

random.seed(7)
for k in (3, 6):
    subs = []
    for v in groups.values():
        if len(v) >= k:
            for _ in range(3):
                subs.append(random.sample(v, k))
    report("B) 随机抽取 %d 个同族液体（≈配方一页）" % k, subs)

P("\n## C) 真实样例\n")
for g in ("石脑油", "柴油", "汽油", "纳米萃取树脂", "合金", "陶瓷"):
    if g not in groups:
        P("- 「%s」组不存在或只有 1 个成员" % g); continue
    v = sorted(groups[g], key=lambda x: x[1])
    res = tiered(v)
    P("- **%s** (%d)：" % (g, len(v)))
    for k, n in v[:14]:
        lab, lv = res[k]
        P("    - %s → `%s`  (T%d)" % (n, lab.replace("\n", "⏎"), lv))

# 用户举的那三/四个名字单独成组
P("\n## D) 用户举例的精确对照（把这几个名字放进同一屏）\n")
demo = [("x1", "重度加氢裂化石脑油"), ("x2", "轻度加氢裂化石脑油"),
        ("x3", "重度蒸汽裂化石脑油"), ("x4", "轻度蒸汽裂化石脑油"),
        ("x5", "石脑油"), ("x6", "含硫石脑油")]
res = tiered(demo)
for k, n in demo:
    lab, lv = res[k]
    P("- %s → `%s`  (T%d)" % (n, lab.replace("\n", "⏎"), lv))

with open(OUT, "w", encoding="utf-8") as fh:
    fh.write(buf.getvalue())
print("\nwritten:", OUT)
