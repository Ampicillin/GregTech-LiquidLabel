# -*- coding: utf-8 -*-
"""One-off fixup for tools/anchors.json (2026-10).

The sub-agent wrote 12 anchors whose value exceeded MAX_LABEL (6) code points, which the
renderer would silently truncate (buildLayout: maxChars per line x maxLines = 6 code points).
Four of them were redundant with the auto label, so they are dropped; the other eight are
rewritten to <= 6 code points while keeping the discriminating information.

Run: python tools/fix_anchors.py
"""
import io
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
PATH = os.path.join(HERE, "anchors.json")

DROP = [
    u"CFC-SIC1500碳纤维强化复合材料",      # auto "CF强化复材" (5) is already clearer
    u"KH-550硅烷偶联剂",                  # auto "KH550" (5)
    u"UHTC-X1000超高温复合材料",            # auto "X1000" (5); "\u590d\u6750" adds no区分度 and wraps to 2 lines
    u"UHTC-X2000超高温复合材料",
]

REPLACE = {
    u"GRCop-84合金": u"GRC84",                    # was GRCop84 (7)
    u"MAR-M200特种钢": u"MAR200",                  # was MARM200 (7)
    u"N-甲基邻苯二甲酰亚胺": u"N甲基酰亚胺",              # was 甲基邻苯酰亚胺 (7)
    u"P-507（2-乙基己基膦酸单-2-乙基己基酯）": u"P507萃取",    # was P507萃取剂 (7)
    u"硅酸乙酯(TEOS)": u"硅酸乙酯",                   # was TEOS硅酸酯 (7)
    u"结构钢-Q690": u"Q690钢",                     # was Q690结构钢 (7)
    u"耐热铬铁合金 MA-956": u"耐热铬956",              # was 耐热铬铁956 (7)
    u"马氏体时效钢-300": u"马氏体300",                 # was 马氏体钢300 (7)
    # 第二轮（audit_table.py R2 抓到）：这两条 anchor 的值撞上了别的显示名的原样标签。
    # 短名必须原样（用户规则 R3），所以改的是 anchor。
    u"酸洗后的电子级硅": u"酸洗电子硅",                 # was 电子级硅 == 短名「电子级硅」
    u"精致钇-钡-铜氧化混合物": u"钇钡铜氧",               # was 钇钡铜氧化物 == 短名「钇钡铜氧化物」
}


def main():
    raw = json.load(io.open(PATH, encoding="utf-8"))
    a = raw["byDisplayName"]
    before = len(a)
    for k in DROP:
        a.pop(k, None)
    for k, v in REPLACE.items():
        assert k in a, k
        a[k] = v
    over = [(k, v) for k, v in a.items() if not (1 <= len(v) <= 6)]
    dup = {}
    for k, v in a.items():
        dup.setdefault(v, []).append(k)
    dup = {v: ks for v, ks in dup.items() if len(ks) > 1}
    print(u"anchors %d -> %d ; overlong=%d %s ; dup=%s" % (before, len(a), len(over), over, dup))
    io.open(PATH, "w", encoding="utf-8").write(
        json.dumps({u"byDisplayName": a}, ensure_ascii=False, indent=2) + u"\n")


if __name__ == "__main__":
    main()
