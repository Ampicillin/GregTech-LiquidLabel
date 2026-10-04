# -*- coding: utf-8 -*-
"""v3：全局稳定标签（同一个液体在任何配方里都显示同一标签）+ 边界情况。"""
import json, os, re, collections, io, sys

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "report3.md")
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
LATIN = re.compile(r"[A-Za-z0-9][A-Za-z0-9\-\.]*")
N = len(names)

# ---- 全局唯一性判定：对每个名字，找出"最短的、在整个池子里唯一的"后缀/子串/牌号
def latin_token(n):
    toks = [m.group(0).strip("-.") for m in LATIN.finditer(n)]
    toks = [t for t in toks if len(t) >= 2]
    return toks[-1] if toks else None

# 建索引：所有长度<=12 的子串 -> 出现的名字集合（只看需要消歧的名字，控制内存）
pool = collections.defaultdict(set)
for k, n in names.items():
    for ln in range(1, min(len(n), 12) + 1):
        for st in range(len(n) - ln + 1):
            pool[n[st:st + ln]].add(k)

def global_label(k, n):
    """返回 (label, mechanism, chars, lines)。唯一性针对全部 1900 个名字。"""
    others_has = lambda s: len(pool[s] - {k}) > 0
    # 0) 名字本身全局唯一 -> 直接全名
    if not others_has(n):
        return n, "full-name", len(n), 1
    # 1) 牌号（拉丁/数字）唯一
    tok = latin_token(n)
    if tok and not others_has(tok):
        return tok, "latin-token", len(tok), 1
    # 2) 最短唯一后缀
    for ln in range(1, min(len(n), 8) + 1):
        s = n[-ln:]
        if not others_has(s):
            return s, "unique-tail", ln, 1
    # 3) 任意位置最短唯一子串
    for ln in range(1, min(len(n), 8) + 1):
        for st in range(len(n) - ln, -1, -1):
            s = n[st:st + ln]
            if not others_has(s):
                return s, "unique-sub", ln, 1
    # 4) 两行：拆成两个片段，要求"两片段组合"唯一（用第一个片段做组键近似）
    if len(n) >= 4:
        a, b = n[:len(n) // 2], n[len(n) // 2:]
        if not others_has(a) or not others_has(b):
            return a + "\n" + b, "two-line", max(len(a), len(b)), 2
    return n, "giveup", len(n), 1

lens = collections.Counter(); src = collections.Counter(); lines = collections.Counter()
per = {}
for k, n in names.items():
    lab, s, c, nl = global_label(k, n)
    per[k] = lab
    lens[min(c, 13)] += 1; src[s] += 1; lines[nl] += 1

P("# 全局稳定标签：唯一性针对全部 %d 个材料名\n" % N)
P("| 预算 | 可唯一区分的名字 | 覆盖率 |")
P("| --- | --- | --- |")
cum = 0
for ln in sorted(lens):
    cum += lens[ln]
    tag = "≥13" if ln == 13 else ("≤%d" % ln)
    P("| %s 字符 | %d/%d | %.1f%% |" % (tag, cum, N, 100 * cum / N))
P("\n机制分布: " + "、".join("%s=%d" % (a, b) for a, b in src.most_common()))
P("行数分布: " + "、".join("%d行=%d" % (a, b) for a, b in sorted(lines.items())))

# ---- 关键：只统计"需要标注时不许退化成全名"的部分
need = {k: n for k, n in names.items() if len(n) > 3}
P("\n在所有 %d 个名字里，长度≤3 字（一行放得下、无需处理）的有 %d 个 (%.0f%%)；"
  "需要缩短的有 %d 个。\n" % (N, N - len(need), 100 * (N - len(need)) / N, len(need)))
sub = collections.Counter()
for k in need:
    lab = per[k]
    c = max(len(x) for x in lab.split("\n"))
    sub[min(c, 13)] += 1
P("**在被缩短的 %d 个名字中**，标签长度分布：" % len(need))
c2 = 0
for ln in sorted(sub):
    c2 += sub[ln]
    tag = "≥13" if ln == 13 else ("≤%d" % ln)
    P("- %s 字符：%d (累计 %.1f%%)" % (tag, sub[ln], 100 * c2 / len(need)))

# ---- 边界：同名不同液体
dup = collections.defaultdict(list)
for (mod, path), v in mat.items():
    if v.get("zh"):
        dup[v["zh"]].append("%s:%s" % (mod, path))
dups = {n: ks for n, ks in dup.items() if len(ks) > 1}
P("\n## 边界情况：中文名完全相同、只能靠颜色/位置区分\n")
P("同名组数: %d，涉及 %d 个 material 键（占 %.1f%%）"
  % (len(dups), sum(len(v) for v in dups.values()),
     100 * sum(len(v) for v in dups.values()) / N))
for n, ks in sorted(dups.items(), key=lambda x: -len(x[1]))[:12]:
    P("- `%s` × %d: %s" % (n, len(ks), "、".join(ks[:6])))

# ---- 用户举例：石脑油 / 十六烷 家族全貌
P("\n## 用户举例相关家族（真实数据）\n")
for kw in ("石脑油", "十六烷", "辛烷", "汽油"):
    hits = [(k, n) for k, n in names.items() if kw in n]
    if not hits:
        continue
    P("含「%s」的 %d 个：" % (kw, len(hits)))
    for k, n in sorted(hits, key=lambda x: x[1]):
        P("  - `%s` = %s → 标签 `%s`" % ("%s.%s" % k, n, per[k].replace("\n", "⏎")))

with open(OUT, "w", encoding="utf-8") as f:
    f.write(buf.getvalue())
print("\nwritten:", OUT)
