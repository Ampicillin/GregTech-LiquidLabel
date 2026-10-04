# -*- coding: utf-8 -*-
"""Validate tools/anchors.json against the generated label table.

Checks:
  1. value length in [1, 6] (code points)
  2. values contain no BAD_CHARS
  3. values are pairwise distinct
  4. every anchor key exists verbatim in fluidlabel_labels.json -> byDisplayName
  5. how many anchor keys would be trimmed-mismatched (key with surrounding whitespace)

Usage: python tools/check_anchors.py
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ANCHORS = os.path.join(HERE, "anchors.json")
TABLE = os.path.join(ROOT, "src", "main", "resources", "fluidlabel_labels.json")
BAD_CHARS = u"/\\:;<>|\"'"

sys.path.insert(0, HERE)
try:
    from gen_labels import BAD_CHARS as GEN_BAD
except Exception:
    GEN_BAD = BAD_CHARS


def main():
    anchors = json.load(io.open(ANCHORS, encoding="utf-8"))["byDisplayName"]
    table = json.load(io.open(TABLE, encoding="utf-8"))
    names = set(table["byDisplayName"].keys())
    labels = table["byDisplayName"]

    print("anchors: %d   table names: %d   table entries: %d"
          % (len(anchors), len(names), len(labels)))

    long_vals = [(k, v) for k, v in anchors.items() if not (1 <= len(v) <= 6)]
    print("FAIL value length out of [1,6]: %d %s" % (len(long_vals), long_vals[:10]))

    bad = [(k, v) for k, v in anchors.items() if any(c in v for c in GEN_BAD)]
    print("FAIL values with BAD_CHARS: %d %s" % (len(bad), bad[:10]))

    from collections import Counter
    dup = [v for v, n in Counter(anchors.values()).items() if n > 1]
    print("FAIL duplicate values: %d %s" % (len(dup), dup))

    missing = [k for k in anchors if k not in names]
    print("FAIL anchor keys absent from table: %d" % len(missing))
    for k in missing[:20]:
        print("   %r" % (k,))

    stripped = [k for k in anchors if k != k.strip()]
    print("WARN keys whose name has surrounding whitespace: %d %s" % (len(stripped), stripped))

    # do the anchors actually appear in the table values?
    applied = sum(1 for k, v in anchors.items() if labels.get(k) == v)
    print("anchors applied verbatim in table: %d/%d" % (applied, len(anchors)))
    notapplied = [(k, anchors[k], labels.get(k)) for k in anchors if labels.get(k) != anchors[k]]
    for k, want, got in notapplied[:20]:
        print("   MISMATCH %r want=%r got=%r" % (k, want, got))


if __name__ == "__main__":
    main()
