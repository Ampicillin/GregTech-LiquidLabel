# -*- coding: utf-8 -*-
"""derived_check.py —— 校验「状态词缀派生名」的标签（用户 2026-10 报的缺陷）。

背景（PLAN §5.6）：GT 的流体显示名是**运行期用 lang 模板拼出来的**：
    gtceu.fluid.molten       熔融%s
    gtceu.fluid.liquid_generic 液态%s
    gtceu.fluid.gas_generic  气态%s
    gtceu.fluid.plasma       %s等离子体
    gtceu.fluid.gas_vapor    %s蒸气
    gtceu.fluid.generic      %s          （恒等：就是材料名本身）
于是 `熔融高速钢-G`（7 码点）这类名字**不在** fluidlabel_labels.json 里（表只收
`material.*` 字面键），运行时只能靠规则。修复前的规则是「查不到就返回原名」⇒ 7 > 6
（3 字 × 2 行）⇒ buildLayout 丢掉第三行 ⇒ 同族的 `熔融高速钢` / `-G` / `-S` 长得一模一样。

本脚本按**与 Java 运行时同一套算法**（`gen_labels.candidates`）离线模拟：
  1. 每个派生名的首选候选必须 ≤ 6 码点（硬门禁：否则屏幕上又会被截断）；
  2. 派生名之间两两互异（硬门禁）；
  3. 派生标签与预计算表里的标签也必须互异 —— 撞了就用下一个候选（运行时同样处理），
     全都撞满才算失败。

用法：
    python tools/derived_check.py                       # 用 src/main/resources 里的表
    python tools/derived_check.py --table <path> --limit 40
退出码：0 = 全部通过；1 = 有硬失败。
"""
import argparse
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)
sys.path.insert(0, HERE)

import gen_labels as G  # noqa: E402

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

TEMPLATES = [
    (u"gtceu.fluid.molten", u"熔融%s", u"prefix"),
    (u"gtceu.fluid.liquid_generic", u"液态%s", u"prefix"),
    (u"gtceu.fluid.gas_generic", u"气态%s", u"prefix"),
    (u"gtceu.fluid.plasma", u"%s等离子体", u"suffix"),
    (u"gtceu.fluid.gas_vapor", u"%s蒸气", u"suffix"),
]

# 用户实测报出来的那几条（必须逐条给出牌号保留的结果）
USER_CASES = [
    u"熔融高速钢",
    u"熔融高速钢-G",
    u"熔融高速钢-S",
    u"熔融高速钢-E",
    u"熔融铝合金-2090",
    u"熔融铝合金-7050",
    u"熔融不锈钢-316",
    u"熔融钛合金-Ti53311S",
    u"液态硫酸",
    u"气态硫酸",
    u"熔融钙盐",
    u"硫酸蒸气",
    u"铁等离子体",
    u"铝合金-2090等离子体",
]


def load_table(path):
    with io.open(path, "r", encoding="utf-8") as fh:
        obj = json.load(fh)
    return obj.get("byDisplayName", {}), obj.get("byRegistryName", {})


def derive(template, base):
    return template.replace(u"%s", base)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--table", default=os.path.join(PROJECT, u"src", u"main", u"resources",
                                                    u"fluidlabel_labels.json"))
    ap.add_argument("--limit", type=int, default=25, help=u"每类问题最多打印几条")
    args = ap.parse_args()

    by_display, by_registry = load_table(args.table)
    if not by_display:
        print(u"[error] 表里没有 byDisplayName：%s" % args.table)
        return 1

    table_labels = set(by_display.values()) | set(by_registry.values())
    # 标签 → 拥有它的名字集合。派生名若与表内某条目**同名**，共用同一个标签不算撞车
    # （运行时先查表，拿到的是同一个标签）。
    owner = {}
    for src in (by_display, by_registry):
        for n, l in src.items():
            owner.setdefault(l, set()).add(n)
    print(u"表：显示名 %d 条 / 注册名 %d 条 / 标签互异数 %d"
          % (len(by_display), len(by_registry), len(set(by_display.values()))))

    # 基体集合 = 显示名本身 + 「去掉尾牌号」的名字。后者必要：GT 融合流体的基体是**材料名**，
    # 而 `高速钢` 这种基体本身不在表里（表里只有 `高速钢-E/G/S`），不补就会漏掉
    # `熔融高速钢` 这类真实派生名。
    bases = set(by_display)
    for n in by_display:
        gs = G._grade_split(n)
        if gs and gs[0]:
            bases.add(gs[0])
        # 尾部拉丁/编号 token 一律去掉（`高速钢-G` → `高速钢`、`超能燃料 II` → `超能燃料`、
        # `耐热铬铁合金 MA-956` → `耐热铬铁合金`）：`_grade_split` 只认含数字的牌号，
        # 纯字母牌号（`-G`/`II`）会被它放过，导致 `熔融高速钢` 这类真实派生名漏检。
        stripped = re.sub(u"[\\s\\-][A-Za-z0-9'\\-β]*$", u"", n)
        if stripped and stripped != n:
            bases.add(stripped)
    bases = sorted(bases)
    print(u"基体（含去牌号变体）：%d 条" % len(bases))

    taken = set(table_labels)
    made = {}                 # 派生名 -> 标签
    order = []                # 与运行时一致的分配顺序（供 Java 侧 ParityCheck 复算）
    needed_ladder = 0
    no_solution = []
    table_collisions = []

    for _key, tmpl, _kind in TEMPLATES:
        for base in bases:
            name = derive(tmpl, base)
            if len(name) <= G.MAX_LABEL:
                label = name          # 短派生名原样（与 T1 规则一致）
            else:
                label = None
                needed_ladder += 1
                for cand, _tier in G.candidates(name):
                    if cand in taken:
                        continue
                    label = cand
                    break
                if label is None:
                    no_solution.append(name)
                    continue
            if label in table_labels and name not in owner.get(label, ()):
                table_collisions.append((name, label))
            taken.add(label)
            made[name] = label
            order.append((name, label))

    dup = {}
    for n, l in made.items():
        dup.setdefault(l, []).append(n)
    dups = sorted((l, ns) for l, ns in dup.items() if len(ns) > 1)
    too_long = sorted((n, l) for n, l in made.items() if len(l) > G.MAX_LABEL)

    print(u"派生名 %d 条（走阶梯 %d 条；原样 %d 条）"
          % (len(made) + len(no_solution), needed_ladder, len(made) - needed_ladder))
    print(u"")
    print(u"## 用户实测样例")
    for n in USER_CASES:
        l = made.get(n)
        print(u"  %-22s → %s" % (n, (u"`%s`（%d 字）" % (l, len(l))) if l else u"**不在派生集里**"))
    print(u"")
    print(u"## 硬门禁")
    print(u"  ≤6 码点          : %s" % (u"PASS" if not too_long else u"FAIL（%d 条）" % len(too_long)))
    print(u"  派生名两两互异   : %s" % (u"PASS" if not dups else u"FAIL（%d 组）" % len(dups)))
    print(u"  阶梯有解         : %s" % (u"PASS" if not no_solution else u"FAIL（%d 条）" % len(no_solution)))
    print(u"  与表标签撞车     : %s" % (u"PASS" if not table_collisions else u"FAIL（%d 条）" % len(table_collisions)))
    for n, l in too_long[:args.limit]:
        print(u"    [超长] %s → %s（%d 字）" % (n, l, len(l)))
    for l, ns in dups[:args.limit]:
        print(u"    [重复] `%s` ← %s" % (l, u" | ".join(ns[:6])))
    for n in no_solution[:args.limit]:
        print(u"    [无解] %s" % n)
    for n, l in table_collisions[:args.limit]:
        print(u"    [撞表] %s → `%s`（表里已有同名标签）" % (n, l))

    bad = bool(too_long or dups or no_solution or table_collisions)

    dump = os.path.join(PROJECT, u"..", u"_diag", u"derived_replay.txt")
    try:
        with io.open(dump, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(u"# 供 Java 侧 DerivedLabelCheck 复算：首字母段名 TAB 参数…\n")
            fh.write(u"# 语义：运行时先查预计算表（resolve 段），查不到才走 ruleFallback（derived 段）。\n")
            for l in sorted(table_labels):
                fh.write(u"seed\t%s\n" % l)
            for n in sorted(by_display):
                fh.write(u"resolve\t%s\n" % n)
            for n, l in order:
                fh.write(u"derived\t%s\t%s\n" % (n, l))
        print(u"[ok] 写成 %s（seed %d / resolve %d / derived %d）"
              % (os.path.normpath(dump), len(table_labels), len(by_display), len(order)))
    except Exception as exc:            # pragma: no cover
        print(u"[warn] 写 %s 失败：%s" % (dump, exc))

    print(u"")
    print(u"VERDICT: %s" % (u"FAIL" if bad else u"ALL PASS"))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
