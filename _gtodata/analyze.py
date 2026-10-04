# -*- coding: utf-8 -*-
"""对 GTO 0.5.6 实际 lang 数据做"液体图标标注"可辨识性分析。
数据来源：gtocore-forge-1.20.1-0.5.6-beta.jar 内嵌
  assets/gtceu/lang/{zh_cn,en_us}.json   (GTCEu 26.7.3)
  assets/gtocore/lang/{zh_cn,en_us}.json (GTO 0.5.6)
"""
import json, os, re, collections

BASE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")

def load(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)

mat = {}  # (ns, mid) -> {'zh':..,'en':..}
for mod in ("gtceu", "gtocore"):
    for field, fname in (("zh", "zh_cn.json"), ("en", "en_us.json")):
        p = os.path.join(BASE, mod, "lang", fname)
        for k, v in load(p).items():
            if not k.startswith("material."):
                continue
            parts = k.split(".")
            if len(parts) != 3:
                continue
            mat.setdefault((parts[1], parts[2]), {})[field] = v

names = {k: v["zh"] for k, v in mat.items() if v.get("zh")}
print("=" * 78)
print("材料名池（= 液体显示名的超集）")
print("=" * 78)
print("material.* 键总数(含 en):", len(mat))
print("有中文名的材料     :", len(names))
print("  其中 gtceu       :", sum(1 for k in names if k[0] == "gtceu"))
print("  其中 gtocore     :", sum(1 for k in names if k[0] == "gtocore"))
L = [len(n) for n in names.values()]
print("中文名长度: min=%d  中位=%.0f  mean=%.1f  max=%d  >6字的=%d (%.0f%%)"
      % (min(L), sorted(L)[len(L) // 2], sum(L) / len(L), max(L),
         sum(1 for x in L if x > 6), 100 * sum(1 for x in L if x > 6) / len(L)))

# ---------------------------------------------------------------- 族结构
print()
print("=" * 78)
print("1) 前缀族：名字共享前缀 => 前缀截断必然失败")
print("=" * 78)
fam = collections.defaultdict(list)
for k, n in names.items():
    fam[n[:3]].append(n)
big = sorted(((len(v), p, v) for p, v in fam.items() if len(v) > 1), reverse=True)
print("共享前3字的族数: %d 个族覆盖 %d 个名字 (%.0f%%)"
      % (len(big), sum(b[0] for b in big), 100 * sum(b[0] for b in big) / len(names)))
print("最大的 8 个族:")
for cnt, p, v in big[:8]:
    print("   [%s] %2d 个: %s" % (p, cnt, "、".join(sorted(v)[:6]) + ("…" if cnt > 6 else "")))

print()
print("--- 用户举的例子在真实数据里长这样 ---")
for mid in ("severely_hydro_cracked_naphtha", "lightly_hydro_cracked_naphtha",
            "severely_steam_cracked_naphtha", "lightly_steam_cracked_naphtha",
            "naphtha", "cetane", "hydro_cracked_butane", "hydro_cracked_butene"):
    hit = {k: v for k, v in names.items() if k[1] == mid}
    for k, v in hit.items():
        print("   %-46s -> %s  (en: %s)" % (".".join(k), v, mat[k].get("en", "?")))

# ---------------------------------------------------------------- 各种方案
print()
print("=" * 78)
print("2) 固定截断方案：全局唯一性统计（分母 = %d 个名字）" % len(names))
print("=" * 78)
def uniq_stats(labels):
    g = collections.defaultdict(list)
    for k, lab in labels.items():
        g[lab].append(k)
    coll = {lab: v for lab, v in g.items() if len(v) > 1}
    in_coll = sum(len(v) for v in coll.values())
    worst = max((len(v) for v in g.values()), default=0)
    return len(g), len(coll), in_coll, worst

def scheme(fn, label):
    labels = {k: fn(n) for k, n in names.items()}
    d, nc, ic, w = uniq_stats(labels)
    print("   %-26s 不同标签=%4d  冲突组=%4d  落在冲突里的名字=%4d (%.0f%%)  最大组=%d"
          % (label, d, nc, ic, 100 * ic / len(names), w))
    return labels

scheme(lambda n: n[0], "首字")
scheme(lambda n: n[:2], "前2字")
scheme(lambda n: n[-1], "尾字")
scheme(lambda n: n[-2:], "后2字")
scheme(lambda n: n[-3:], "后3字")
scheme(lambda n: n[-4:], "后4字")
scheme(lambda n: n[:1] + n[-1], "首字+尾字")
scheme(lambda n: n[:1] + n[-2:], "首字+后2字")

# ---------------------------------------------------------------- 族内自适应
print()
print("=" * 78)
print("3) 族内消歧：只在'看起来一样'的名字之间要求唯一（这才是真正的需求）")
print("=" * 78)

def family_of(n):
    """把名字切成 修饰语 + 主干：用真实数据里高频的过程词表剥离。"""
    return n[:3]

def distinguishing(n, others, budget=6):
    """返回 (标签, 来源)。优先取'尾部唯一子串'（物质名在尾部），
    失败再退回'任意位置最短唯一子串'，再失败用位置序号。"""
    if not others:
        return n[-3:] if len(n) > 3 else n, "tail3"
    # 1) 尾部唯一子串
    for ln in range(1, min(len(n), budget) + 1):
        sub = n[-ln:]
        if all(sub not in o for o in others):
            return sub, "unique-tail"
    # 2) 最短唯一子串（任意位置，优先靠后）
    best = None
    for ln in range(1, min(len(n), budget) + 1):
        for st in range(len(n) - ln, -1, -1):
            sub = n[st:st + ln]
            if all(sub not in o for o in others):
                best = sub
                break
        if best:
            break
    if best:
        return best, "shortest-unique"
    return n[:2], "FAILED"

fam = collections.defaultdict(list)
for k, n in names.items():
    fam[family_of(n)].append((k, n))

lens = collections.Counter()
srcs = collections.Counter()
rows = []
for fkey, members in fam.items():
    if len(members) < 2:
        continue
    for k, n in members:
        others = [o for kk, o in members if kk != k]
        lab, src = distinguishing(n, others)
        lens[len(lab)] += 1
        srcs[src] += 1
        rows.append((fkey, n, lab, src))

print("参与族内消歧的名字: %d 个（属于 %d 个多成员族）"
      % (len(rows), len({r[0] for r in rows})))
print("标签长度分布:", dict(sorted(lens.items())))
print("标签来源    :", dict(srcs))
tot = sum(lens.values())
cum = 0
for ln in sorted(lens):
    cum += lens[ln]
    print("   <=%d 字符可覆盖 %4d/%d = %.1f%%" % (ln, cum, tot, 100 * cum / tot))

print()
print("每个族的消歧效果示例（前 10 个最大的族）:")
for cnt, p, v in big[:10]:
    mem = [(k, n) for k, n in names.items() if n[:3] == p]
    if len(mem) < 2:
        continue
    out = []
    for k, n in mem[:8]:
        others = [o for kk, o in mem if kk != k]
        lab, src = distinguishing(n, others)
        out.append("%s→%s" % (n, lab))
    print("   [%s] %s" % (p, "  ".join(out)))

# ---------------------------------------------------------------- 变体：化学式
print()
print("=" * 78)
print("4) 备选维度：注册名（英文 id）能否当稳定标签？")
print("=" * 78)
eng = sum(1 for n in names.values() if re.search(r"[A-Za-z]", n))
print("含拉丁字母/数字的中文名: %d/%d (%.0f%%)" % (eng, len(names), 100 * eng / len(names)))
longid = sum(1 for k in names if len(k[1]) > 14)
print("注册名长度 >14 字符的: %d (%.0f%%)  -> 不适合直接当标签" % (longid, 100 * longid / len(names)))
