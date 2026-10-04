# -*- coding: utf-8 -*-
"""Final audit of src/main/resources/fluidlabel_labels.json (T1 rules).

Verdicts printed as PASS/FAIL lines so the run can be quoted verbatim.

Rules checked (PLAN ⓪.2 / fluid-label-README.md):
  R1 every label is 1..6 code points (renderer budget: maxChars x maxLines)
  R2 labels are pairwise distinct across all 1898 display names
  R3 a display name of <= 6 code points keeps its name verbatim as the label
  R4 conflicts list is empty (same display name => same label)
  R5 byRegistryName maps 3800 registry names onto existing labels
  R6 the samples the user hand-checked resolve to the agreed labels

Usage: python tools/audit_table.py
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
TABLE = os.path.join(ROOT, "src", "main", "resources", "fluidlabel_labels.json")

EXPECT = [
    (u"重度加氢裂化石脑油", u"重氢裂石脑油"),
    (u"轻度加氢裂化石脑油", u"轻氢裂石脑油"),
    (u"重度蒸汽裂化石脑油", u"重蒸裂石脑油"),
    (u"轻度蒸汽裂化石脑油", u"轻蒸裂石脑油"),
    (u"石脑油", u"石脑油"),
    (u"含硫石脑油", u"含硫石脑油"),
    (u"柴油", u"柴油"),
    (u"生物柴油", u"生物柴油"),
    (u"十六烷", u"十六烷"),
    (u"二氧化钛", u"二氧化钛"),
]

fails = []


def check(ok, msg):
    print((u"PASS  " if ok else u"FAIL  ") + msg)
    if not ok:
        fails.append(msg)


def main():
    raw = json.load(io.open(TABLE, encoding="utf-8"))
    by_display = raw[u"byDisplayName"]
    by_reg = raw[u"byRegistryName"]
    print(u"table %s" % TABLE)
    print(u"size=%d bytes  byDisplayName=%d  byRegistryName=%d  conflicts=%d"
          % (os.path.getsize(TABLE), len(by_display), len(by_reg), len(raw.get(u"conflicts") or [])))

    bad = [(k, v) for k, v in by_display.items() if not (1 <= len(v) <= 6)]
    check(not bad, u"R1 all labels within 1..6 code points (violations=%d %s)" % (len(bad), bad[:5]))

    seen = {}
    for k, v in by_display.items():
        seen.setdefault(v, []).append(k)
    dup = {v: ks for v, ks in seen.items() if len(ks) > 1}
    check(not dup, u"R2 labels pairwise distinct (%d/%d distinct, dup=%s)"
          % (len(seen), len(by_display), list(dup.items())[:3]))

    changed = [(k, v) for k, v in by_display.items() if len(k) <= 6 and k != v]
    check(not changed, u"R3 names <=6 code points stay verbatim (violations=%d %s)"
          % (len(changed), changed[:5]))

    check(not raw.get(u"conflicts"), u"R4 conflicts empty")

    label_set = set(by_display.values())
    orphan = [(k, v) for k, v in by_reg.items() if v not in label_set]
    check(not orphan, u"R5 every registry label comes from the display-name table (violations=%d %s)"
          % (len(orphan), orphan[:5]))

    wrong = [(n, by_display.get(n), w) for n, w in EXPECT if by_display.get(n) != w]
    check(not wrong, u"R6 user samples (violations=%d %s)" % (len(wrong), wrong))

    lens = {}
    for v in by_display.values():
        lens[len(v)] = lens.get(len(v), 0) + 1
    print(u"length distribution: " + u", ".join(u"%d=%d" % (k, lens[k]) for k in sorted(lens)))
    print(u"\nVERDICT: %s" % (u"ALL PASS" if not fails else u"%d FAILURE(S)" % len(fails)))
    return 0 if not fails else 1


if __name__ == "__main__":
    sys.exit(main())
