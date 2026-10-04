# -*- coding: utf-8 -*-
"""Build _diag/anchor_fix.txt: the overlong anchors plus family context, for manual fixing."""
import io
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(os.path.dirname(ROOT), "_diag", "anchor_fix.txt")

anchors = json.load(io.open(os.path.join(HERE, "anchors.json"), encoding="utf-8"))["byDisplayName"]
table = json.load(io.open(os.path.join(ROOT, "src", "main", "resources", "fluidlabel_labels.json"),
                          encoding="utf-8"))
names = list(table["byDisplayName"].keys())
labels = table["byDisplayName"]

KEYS = [k for k, v in anchors.items() if len(v) > 6]

lines = []
lines.append(u"# overlong anchors (value > 6 code points) : %d" % len(KEYS))
for k in KEYS:
    v = anchors[k]
    lines.append(u"\n%s\n    value=%s  len=%d  auto=%s" % (k, v, len(v), labels.get(k)))

lines.append(u"\n\n# family context")
probes = [u"SIC", u"MAR-M", u"邻苯", u"TEOS", u"Q690", u"MA-956", u"GRCop", u"KH-", u"UHTC",
          u"P-507", u"铬铁", u"不锈钢", u"超高温复合材料"]
for p in probes:
    lines.append(u"\n## probe %s" % p)
    for n in sorted(names):
        if p.lower() in n.lower():
            lines.append(u"    %-42s -> %s" % (n, labels.get(n)))

lines.append(u"\n\n# value prefixes shared with existing labels (collision hints)")
for k in KEYS:
    v = anchors[k]
    pref = v[:2]
    hits = [u"%s=>%s" % (n, labels[n]) for n in names
            if labels.get(n) and labels[n].startswith(pref) and labels[n] != v]
    if hits:
        lines.append(u"\n%s (%s) prefix %s used by: %s" % (k, v, pref, u", ".join(hits[:12])))

io.open(OUT, "w", encoding="utf-8").write(u"\n".join(lines))
print("wrote %s (%d lines)" % (OUT, len(lines)))
