# -*- coding: utf-8 -*-
"""gen_labels.py —— 离线生成 fluidlabel_labels.json（移植 _gtodata/analyze8.py 的 v8 算法）。

为什么必须离线预计算：EMI 的液体只有「注册名 + 显示名」两个可用信息（PLAN §5.1 已由 javap
坐实），要让标签在**任何界面**都稳定（档 A · STABLE），就得对**全池**（1900 条材料名）一次
求出"两两互异"的标签，这件事必须预先算好随 jar 发布。

用法：
    python tools/gen_labels.py
    python tools/gen_labels.py --lang <_gtodata/assets> --out src/main/resources/fluidlabel_labels.json

默认：lang 根目录按 <root>/<mod>/lang/<locale>.json 查找，覆盖 gtceu / gtocore × zh_cn / en_us。
数据源只取 `material.*` 键（全库只有 fluid.gtceu.potion 一个 fluid 键，PLAN §5.1）。
"""
import argparse
import datetime
import io
import itertools
import json
import os
import re
import sys

# 本机控制台默认 gbk，中文输出会炸 —— 必须在任何 print 之前处理（PLAN §⑤ 要求）。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

HERE = os.path.dirname(os.path.abspath(__file__))
PROJECT = os.path.dirname(HERE)

MODS = ("gtceu", "gtocore")
LOCALES = ("zh_cn", "en_us")

# 功能字：单字/双字候选里出现这些就丢弃，否则会产出「的钆纳」这种标签（PLAN §5.2 第 3 条）。
FUNC = set(u"的了之与和及，,、（）()[]·-")
# 低信息量连接/类别字：允许在「跳字」轮里被跳过（重度加氢裂化石脑油 → 重加氢）。
FILLER = set(u"的了之与和及其性度式级型类")
# 一旦出现在候选里就整条丢弃：路径/命名空间分隔符（Co/AC-AB → AC 尚可，"o/A" 不行）。
BAD_CHARS = set(u"/\\:;<>|\"'")

MAX_LEN = 3          # 「短名保全名」的长度上限（analyze8.py 的 maxlen）
NAME_FULL_LIMIT = 3  # 名字本身 ≤ 该长度时直接保全名（T1）
MAX_LABEL = 6        # 标签长度硬上限 = 2 行 × 3 字（PLAN §4.1 几何上限）
MAX_TIERS = 64       # 同步升级的最大轮次（候选数远小于此，仅作保险）
VIS = MAX_LABEL      # 渲染端**真正画出来**的字数上限（截断救援按它判互异）


# --------------------------------------------------------------------------- 工具


def log(*a):
    print(*a)
    try:
        sys.stdout.flush()
    except Exception:
        pass


def load_json(path):
    with io.open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def dump_json(path, obj):
    d = os.path.dirname(os.path.abspath(path))
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with io.open(path, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(json.dumps(obj, ensure_ascii=False, indent=1, sort_keys=True))
        fh.write(u"\n")


def safe_rel(path):
    try:
        return os.path.relpath(path, PROJECT).replace("\\", "/")
    except Exception:
        return path


# --------------------------------------------------------------------------- 算法


def _is_cjk(ch):
    return u"\u4e00" <= ch <= u"\u9fff"


def _has_cjk(s):
    return any(_is_cjk(c) for c in s)


def _tokens(name):
    """牌号 / 元素符号等拉丁数字串（ABS、DNA、507…）。"""
    toks = re.findall(r"[A-Za-z0-9][A-Za-z0-9'\-]*", name)
    ordered = sorted(toks, key=lambda x: (-len(x), x))
    out = []
    for t in ordered:
        if 2 <= len(t) <= 3:
            out.append(t)
    for t in ordered:
        if len(t) > 3:
            out.append(t[-3:])
            out.append(t[:3])
    return out


def _skip_filler(name, length):
    """保留首字、**只跳 FILLER 字**的 length 字子序列。

    这就是人做缩写的实际动作：`重度加氢裂化石脑油` → 丢「度」→ `重加氢`。
    必须真跳过至少一个字（连续情形交给前缀轮 / 滑窗轮，避免重复与乱序）。
    """
    first = name[0]
    rest = name[1:]
    out = []
    for idx in itertools.combinations(range(len(rest)), length - 1):
        idxset = set(idx)
        skipped = [rest[i] for i in range(len(rest)) if i not in idxset]
        if not skipped:
            continue
        if any(ch not in FILLER for ch in skipped):
            continue
        out.append(first + u"".join(rest[i] for i in idx))
    return out


def candidate_list(name):
    """一个显示名的候选标签，**按人类偏好降序**；下标即「升级轮次」。

    为什么不用 analyze8.py 的「长度最优」：1898 条名字一起求两两互异时，长度最优
    必然收敛到单字片段（石脑油→脑、十六烷→十、轻度加氢裂化石脑油→度加）——技术上
    互异，对玩家毫无用处。人给液体起缩写的动作依次是：整名（短名）→ 两字前缀 →
    保留首字并丢掉低信息量连接字 → 三字前缀 → 三字滑窗 → 牌号。
    """
    out = []
    seen = set()

    def add(s):
        s = s.strip()
        while s and s[0] in FUNC:
            s = s[1:]
        while s and s[-1] in FUNC:
            s = s[:-1]
        if len(s) < 2 or s in seen:
            return                       # 单字只允许出现在最后的兜底层
        if len(s) > MAX_LABEL:
            return                       # 超过 2 行 × 3 字就画不下，交给兜底层显示全名
        if all(ch in FUNC for ch in s):
            return
        if any(ch in BAD_CHARS for ch in s):
            return                       # Co/AC 这类带路径分隔符的片段没有可读性
        if _has_cjk(name) and not _has_cjk(s):
            return                       # 含 CJK 的名字只在牌号轮接受纯拉丁标签
        if _has_cjk(s) and any(not _is_cjk(c) for c in s):
            return                       # 数字/字母与汉字混排（2-四 / 4-乙）读不出来
        seen.add(s)
        out.append(s)

    n = len(name)
    if n <= MAX_LEN:
        add(name)                        # 短名直接用全名：石脑油 / 柴油 / 十六烷
    else:
        add(name[:2])                    # 轮 1：两字前缀（含硫石脑油 → 含硫）
        for c in _skip_filler(name, 3):
            add(c)                       # 轮 2：跳 FILLER 的三字（重度加氢… → 重加氢）
        add(name[:3])                    # 轮 3：三字前缀
        for off in range(1, n - 2):
            add(name[off:off + 3])       # 轮 4：三字滑窗（四氯乙烷 → 氯乙烷）
        for c in _skip_filler(name, 2):
            add(c)
        for off in range(1, n - 1):
            add(name[off:off + 2])       # 轮 5：两字滑窗
        # 轮 6：**首字 + 尾 2/3 字**。加氢裂化 / 蒸汽裂化 这类族里，同一个轮次上
        # 「温度」差异只在第 1 字、「产物」差异只在最后 2–3 字，纯窗口候选永远是
        # 同族同串（4 个产物提出同一个 `裂化石`，轻/重再各提出同一个），每组恒为 2
        # 而永不落定 —— 必须用「领头字 + 产物名」的组合一次把两个维度都带出来：
        # 轻度加氢裂化石脑油 → `轻脑油`、重度加氢裂化重燃油 → `重重燃油`。
        add(name[0] + name[-2:])
        add(name[0] + name[-3:])
        add(name[:2] + name[-2:])
        add(name[:2] + name[-3:])
        # 轮 7：**首字 + 一个中间字 + 产物首字**。上两轮仍留不住「工艺」这一维
        # （加氢 / 蒸汽 的差别在名字中段）：`轻重燃油` 与 `轻重燃油` 同串、
        # `轻石脑油` 在加氢与蒸汽之间也同串。三个维度各取一字才是这族的解：
        # 轻度加氢裂化石脑油 → `轻加石`、轻度蒸汽裂化石脑油 → `轻蒸石`。
        for off in range(1, n - 3):
            add(name[0] + name[off] + name[-3])
        for off in range(1, n - 4):
            add(name[0] + name[off:off + 2] + name[-3])
        # 牌号轮同样要过卫生检查：_tokens 的正则允许撇号/连字符，直接放行会让
        # `4-乙烯基-2,2'-联吡啶` 拿到 `2'-` 这种读不出来的标签（实测发生过）。
        for t in _tokens(name):
            t = t.strip("".join(FUNC))
            if len(t) < 2 or len(t) > MAX_LABEL or t in seen:
                continue
            if any(ch in BAD_CHARS for ch in t):
                continue
            if _has_cjk(t) and any(not _is_cjk(c) for c in t):
                continue
            seen.add(t)
            out.append(t)
    for s in (name,) + tuple(name):      # 兜底：全名 → 单字
        if s and s not in seen:
            seen.add(s)
            out.append(s)
    return out


# ---------------------------------------------------------------------------
# T1（2026-09 用户新规则，取代旧的"越短越好"取向）：
#   ① len(name) <= MAX_LABEL(6) ⇒ 标签 = 名字本身，**一字不改**（不截断、不简化）；
#   ② len(name) >  6            ⇒ "长前缀简称 + 核心压缩"把整条压到 <= 6 字；
#      前缀简称沿用用户给样例的风格：重度加氢裂化的 → 重氢裂、轻度蒸汽裂化的 → 轻蒸裂
#      （规律 = 程度首字 + 工艺关键字首字 + 尾字"裂"）；
#   ③ 自动阶梯给不出"<= 6 字且互异"的 ⇒ 进 leave-over 清单，交子代理人工写关键字。
# ---------------------------------------------------------------------------

# 长前缀 → 简称。匹配时取**最长命中**，所以顺序无所谓（代码里会按长度比）。
PREFIX_ABBREV = [
    (u"重度加氢裂化的", u"重氢裂"),
    (u"重度加氢裂化", u"重氢裂"),
    (u"轻度加氢裂化的", u"轻氢裂"),
    (u"轻度加氢裂化", u"轻氢裂"),
    (u"重度蒸汽裂化的", u"重蒸裂"),
    (u"重度蒸汽裂化", u"重蒸裂"),
    (u"轻度蒸汽裂化的", u"轻蒸裂"),
    (u"轻度蒸汽裂化", u"轻蒸裂"),
    (u"加氢裂化的", u"氢裂"),
    (u"加氢裂化", u"氢裂"),
    (u"蒸汽裂化的", u"蒸裂"),
    (u"蒸汽裂化", u"蒸裂"),
    (u"催化裂化的", u"催裂"),
    (u"催化裂化", u"催裂"),
    (u"溢满的", u"满"),
    (u"哈斯特洛依合金", u"哈氏"),
    (u"镍铬基合金-", u"镍铬"),
    (u"不锈钢-", u"不锈"),
    (u"钛合金-", u"钛合"),
    (u"铝合金-", u"铝合"),
    (u"钨合金-", u"钨合"),
    (u"碳纤维-", u"碳纤"),
    (u"二氧化钛纳米管", u"钛纳管"),
    (u"碳纳米管", u"纳管"),
    (u"氧化物弥散强化", u"弥散强"),
    (u"浓缩混合超能燃料", u"浓超燃"),
    (u"超能燃料", u"超燃"),
    (u"超重元素-", u"超重"),
    (u"碳化硅纤维增强", u"硅纤增"),
    (u"碳化硅纤维强化", u"硅纤强"),
    (u"碳化硅颗粒增强", u"硅颗增"),
    (u"氟碳镧铈稀土", u"氟镧铈"),
    (u"氟碳镧铈", u"氟镧铈"),
    (u"氯化稀土", u"氯稀土"),
    (u"独居石稀土", u"独居石"),
    (u"镧-富勒烯", u"镧富勒"),
    (u"多能性诱导", u"多能诱"),
    (u"高密度自由", u"高密自"),
    (u"间苯二甲", u"间苯二"),
    (u"二乙烯三胺", u"二乙烯"),
    (u"六甲基二硅", u"六甲硅"),
    (u"富集硅岩", u"富硅岩"),
    (u"超能硅岩", u"超硅岩"),
    (u"凯金化合物", u"凯金"),
    (u"火箭燃料", u"火箭燃"),
    (u"纳米金沉积", u"纳金沉"),
    (u"镀银的", u"银"),
    (u"回收的", u"回收"),
    (u"失效的", u"失效"),
    (u"激发的", u"激发"),
    (u"污浊的", u"浊"),
    (u"洁净的", u"洁净"),
    (u"纯净的", u"纯"),
    (u"稀释的", u"稀"),
    (u"浓缩的", u"浓"),
    (u"重新活化的", u"活化"),
]

# 词尾通用词 → 压缩写法（只在"前缀简称 + 原样剩余"仍然超 6 字时使用）
TOKEN_ABBREV = [
    (u"纳米萃取树脂", u"树脂"),
    (u"萃取树脂", u"树脂"),
    (u"复合材料", u"复材"),
    (u"混合物", u"混"),
    (u"溶液", u"液"),
    (u"浆液", u"浆"),
    (u"泡沫", u"泡"),
    (u"陶瓷", u"陶"),
    (u"催化剂", u"催化"),
    (u"共聚物", u"共聚"),
    (u"微珠", u"珠"),
    (u"纳米管", u"纳管"),
    (u"纳米", u"纳"),
    (u"纤维", u"纤"),
]

# 需要人工复核的档位：自动给的能用但不一定好看（纯牌号 / 首尾拼 / 硬截）
REVIEW_TIERS = (u"code", u"head-tail", u"cut")

# ---------------------------------------------------------------------------
# 状态词缀（2026-10 用户实测缺陷：`熔融高速钢-G` / `熔融铝合金-2090` 在界面里只剩
# `熔融高速钢-` / `熔融铝合金-`）。
#
# 根因：GT 的熔融/液态/气态流体名是**运行期用 lang 模板拼出来的**（`gtceu.fluid.molten`
# = `熔融%s`、`liquid_generic` = `液态%s`、`gas_generic` = `气态%s`、`plasma` =
# `%s等离子体`、`gas_vapor` = `%s蒸气`、`generic` = `%s`，见 tools/fluid_template_probe.py），
# 这些派生名**不在** fluidlabel_labels.json 里（表只收 material.* 字面键）⇒ 运行期走到
# "返回原名"的兜底 ⇒ 名字 7 码点 > 渲染预算 6（3 字 × 2 行）⇒ 第 3 行被 buildLayout 丢掉，
# 同族的 `熔融高速钢` / `-G` / `-S` 因此看起来完全一样。
#
# 解法：把「状态短字 + 基体标签」做成规则（生成器与 Java 运行时同一套），基体 = 去掉词缀
# 的材料名，其标签按**缩小后的预算**走同一阶梯，因此牌号照样被保住。
# 长写法在前：基体短时优先可读性（`熔融硫酸` 优于 `熔硫酸`）。
STATE_PREFIX = [
    (u"熔融", (u"熔融", u"熔")),
    (u"液态", (u"液态", u"液")),
    (u"气态", (u"气态", u"气")),
]
STATE_SUFFIX = [
    (u"等离子体", (u"等离子", u"浆")),   # 等离子体 = 电浆，取「浆」当状态字
    (u"蒸气", (u"蒸气", u"汽")),
]


def _split_state_affix(name):
    """把状态派生名拆成 [(基体名, 是否前缀, 状态字候选)]；不是派生名则返回 []。"""
    out = []
    for affix, markers in STATE_PREFIX:
        if name.startswith(affix) and len(name) > len(affix):
            out.append((name[len(affix):], True, markers))
    for affix, markers in STATE_SUFFIX:
        if name.endswith(affix) and len(name) > len(affix):
            out.append((name[:-len(affix)], False, markers))
    return out


def _abbrev_prefix(name):
    """返回 (前缀简称, 剩余)；无可匹配前缀时返回 (None, name)。取最长命中。"""
    best = None
    for pat, short in PREFIX_ABBREV:
        if name.startswith(pat) and (best is None or len(pat) > len(best[0])):
            best = (pat, short)
    if best is None:
        return None, name
    return best[1], name[len(best[0]):]


def _abbrev_tokens(s):
    out = s
    for pat, short in TOKEN_ABBREV:
        if pat in out:
            out = out.replace(pat, short)
    return out


def _compact(s):
    """去掉不承载区分信息的助词与分隔符（只做最后几档的压缩）。"""
    drop = set(u"的了之与和及·()（）[]{}<>-—_ '\"/\\,，、")
    return u"".join(ch for ch in s if ch not in drop)


def _alnum_tokens(s):
    """名字里的字母数字牌号（如 C-276 / X750 / Ti64 / 50）。"""
    return re.findall(r"[A-Za-z0-9][A-Za-z0-9'\-]*", s)


def _grade_split(name):
    """拆出（中文描述, 牌号, 原始 token）。

    牌号 = 含数字的最长拉丁串（去掉 `-`/`'`）：`铝合金-2090` → (`铝合金`, `2090`, `2090`)、
    `钛合金-Ti53311S` → (`钛合金`, `Ti53311S`, `Ti53311S`)、`FGM-TPS10热防护复合陶瓷` →
    (`热防护复合陶瓷`, `FGMTPS10`, `FGM-TPS10`)。
    纯字母的罗马数字后缀（`超能燃料 II`）与单个字母（`铍铝合金-F`）不算牌号 —— 它们没有
    「编号」语义，交给原阶梯处理。
    """
    toks = [t for t in _alnum_tokens(name) if any(ch.isdigit() for ch in t)]
    if not toks:
        return None
    tok = max(toks, key=lambda x: (len(x), x))
    g = tok.replace(u"-", u"").replace(u"'", u"")
    cjk = _compact(u"".join(ch for ch in name
                            if not (ch.isascii() and (ch.isalnum() or ch in u"-'"))))
    if len(g) < 2 or not cjk:
        return None
    return cjk, g, tok


def _grade_candidates(name, budget, add, allow_k1=False):
    """保牌号档（用户 2026-10 要求：牌号是重要区分，必须留下）。

    只做"严格更好"的两件事（第一版曾把 `不锈JBK7` 改成 `不JBK75`、`钛合Ti53` 改成
    `钛合金Ti5` —— 牌号字符是多了，但可读性下降，属于回归，已收紧）：
      ① 中文前缀简称 + **完整牌号**：`铝合金-2090` → `铝合2090`（也拦不住时见 ②）；
      ② 名字**以牌号结尾**时，中文头部 k 字 + **完整牌号**，k 从大到小
         （`结构钢-Q690` → `结构Q690`）。默认要求 k ≥ 2，避免 `不JBK75` 这种只剩一个
         汉字的形状；`allow_k1=True` 只给「状态词缀组合」用（那里已经花掉一个字，
         基体只剩 5 字预算，`熔融铝合金-2090` 必须给成 `熔铝2090` 而不是 `熔铝合209`）。
      ③ 牌号在名字**开头**（`CAS-9-蛋白` / `KH-550硅烷偶联剂`）时保持牌号在前：
         `CAS9蛋白`。

    牌号被裁的档（`不锈155p` / `钛合Ti53`）仍交给原 `code` / `prefix` 阶梯，保持既有观感。
    """
    sp = _grade_split(name)
    if sp is None:
        return
    cjk, g, tok = sp
    prem, _rest = _abbrev_prefix(name)
    if prem and len(prem) + len(g) <= budget:
        add(prem + g, u"grade")

    stripped = name.strip()
    if stripped.endswith(tok):                    # 牌号在尾：保牌号、裁中文
        room = budget - len(g)
        ks = list(range(min(len(cjk), room), 0, -1))
        if not allow_k1:
            ks = [k for k in ks if k >= 2]
        for k in ks:
            add(cjk[:k] + g, u"grade")
    elif stripped.startswith(tok):                # 牌号在头：牌号在前，中文尾部跟上
        room = budget - len(g)
        for k in range(min(len(cjk), room), 1, -1):
            add(g + cjk[:k], u"grade")


def long_candidates(name, budget=MAX_LABEL, allow_k1=False):
    """长名（> budget 码点）的候选阶梯：返回 [(候选, 档位)]，全部 <= budget。

    档位用于报告与人工复核：grade / prefix / tokens / code / head-tail / cut。
    `budget` / `allow_k1` 只在「状态词缀组合」里被改动（`熔融` + 基体），默认仍是
    2 行 × 3 字 = 6。
    """
    cands = []
    seen = set()

    def add(c, tier):
        if not c:
            return
        c = c.strip()
        if len(c) < 1 or len(c) > budget or c in seen:
            return
        if any(ch in BAD_CHARS for ch in c):
            return
        seen.add(c)
        cands.append((c, tier))

    prem, rest = _abbrev_prefix(name)

    # 保牌号档排在最前：牌号（2090 / Ti53311S / Q690）是玩家真正的区分点，中文可以压。
    _grade_candidates(name, budget, add, allow_k1)

    if prem:
        add(prem + rest, u"prefix")
        add(prem + _compact(rest), u"prefix")
        room = budget - len(prem)
        if room >= 1:
            add(prem + _compact(rest)[:room], u"prefix")
        for tok in _alnum_tokens(rest):
            add(prem + tok.replace(u"-", u""), u"prefix")
        add(prem + _abbrev_tokens(rest), u"prefix")

    add(_abbrev_tokens(name), u"tokens")
    add(_compact(_abbrev_tokens(name)), u"tokens")
    add(_abbrev_tokens(_compact(name)), u"tokens")

    # 牌号保留档：名字里的字母数字牌号（UHTC-X1000 / MAR-M200 / KH-550 / Ti64）是**人类真正的
    # 区分点**，可以压缩的是中文描述，所以这一档 = 牌号（<=budget）+ 尽可能多的中文核心。
    # 放在 head-tail 之前，避免把 `UHTC-X1000超高温复合材料` 压成 `UH高温复材` 这种丢牌号的形状。
    toks = _alnum_tokens(name)
    cjk = _compact(u"".join(ch for ch in name
                            if not (ch.isascii() and (ch.isalnum() or ch in u"-'"))))
    for tok in sorted(toks, key=lambda t: -len(t)):
        ct = tok.replace(u"-", u"").replace(u"'", u"")
        short = tok.split(u"-")[-1].replace(u"'", u"")
        for base in ([ct] if len(ct) <= budget else []) + [short]:
            if len(base) < 2 or len(base) > budget:
                continue
            room = budget - len(base)
            # 先试"牌号 + 中文核心"（信息量最大），再退到光牌号。
            if room >= 2 and cjk:
                add(base + cjk[:room], u"code")
                add(cjk[-room:] + base, u"code")
            add(base, u"code")

    c = _compact(_abbrev_tokens(name))
    if len(c) > budget:
        add(c[:2] + c[-4:], u"head-tail")
        add(c[:3] + c[-3:], u"head-tail")
        add(c[:2] + c[-3:], u"head-tail")
        add(c[:4] + c[-2:], u"head-tail")

    add(c[:budget], u"cut")
    add(name[:budget], u"cut")
    return cands


def candidates(name, budget=MAX_LABEL):
    """完整候选阶梯 = 状态词缀组合（若有）+ 原阶梯兜底；全部 <= budget 码点。

    状态派生名（`熔融高速钢-G` / `铝合金-2090等离子体`）的**首选**候选由基体算出，例如
    `熔融高速钢-G` → `熔` + `高速钢-G` = `熔高速钢-G`（6 字，牌号完整）；
    基体预算 = budget - 状态字长度，所以 `熔融铝合金-2090` → `熔` + `铝2090` = `熔铝2090`。
    组合候选被占满时仍回落到原阶梯（宁可丑也不能返回超长原名）。
    """
    out = []
    seen = set()

    def push(c, tier):
        if not c:
            return
        c = c.strip()
        if len(c) < 1 or len(c) > budget or c in seen:
            return
        if any(ch in BAD_CHARS for ch in c):
            return
        seen.add(c)
        out.append((c, tier))

    for base, is_prefix, markers in _split_state_affix(name):
        # 基体在**完整预算**下的首选标签：用来判断"缩短预算是否把基体截断了"。
        best_full = long_candidates(base, budget, allow_k1=True)
        last = len(markers) - 1
        for idx, mk in enumerate(markers):
            room = budget - len(mk)
            if room < 2:
                continue
            # allow_k1：状态字已经占掉一格，基体只剩 5 字预算。此时「整段牌号 + 一个字
            # 的中文」(`铝2090`) 比「中文简称 + 截断牌号」(`铝合209`) 更有用 —— 后者看起来
            # 像另一个编号（2090 显示成 209）。
            cands = long_candidates(base, room, allow_k1=True)
            if not cands:
                continue
            # 长写法（`熔融`）只在**它没有让基体降级**时使用：`铝合金-2090` 在 4 字预算下
            # 首选会变成截断牌号的 `铝合20`，那就必须退到短写法 `熔`（基体 5 字预算 ⇒
            # `铝2090`，牌号完整）。这一步是"宁可少画一个字，也不能丢牌号"的守门人。
            if idx != last and best_full and cands[0][0] != best_full[0][0]:
                continue
            for c, _tier in cands:
                push((mk + c) if is_prefix else (c + mk), u"state")

    for c, tier in long_candidates(name, budget):
        push(c, tier)
    return out


def assign_v2(names, reserved=None):
    """T1 新规则下的分配。

    返回 (labels, tier_of, leftovers)：
      labels    显示名 -> 标签（只含求到解的；无解的不在里面）
      tier_of   显示名 -> 档位（verbatim / prefix / tokens / head-tail / cut）
      leftovers 无解的显示名（已排序）
    """
    taken = set(v for v in (reserved or set()) if v)
    labels = {}
    tier_of = {}
    longs = []

    for n in names:
        if len(n) <= MAX_LABEL:
            labels[n] = n
            tier_of[n] = u"verbatim"
            taken.add(n)
        else:
            longs.append(n)

    left = []
    # 先短后长：短的长名更容易压进 6 字，先占位可避免被后来的差候选抢走好标签。
    for n in sorted(longs, key=lambda s: (len(s), s)):
        picked = None
        for cand, tier in candidates(n):
            if cand in taken:
                continue
            picked = (cand, tier)
            break
        if picked is None:
            left.append(n)
            continue
        labels[n] = picked[0]
        tier_of[n] = picked[1]
        taken.add(picked[0])

    return labels, tier_of, sorted(left)


def assign(names, reserved=None):
    """对需要互相区分的一组显示名分配互异标签。

    唯一性判据 = **标签两两互异**，而不是"标签不是别的名字的子序列" —— 后者会让
    26.4% 的名字退化成全名（PLAN §5.2 硬判据 1）。

    分配策略（三个阶段的顺序是刻意的，见 candidate_list 的说明）：
      阶段 0  短名（≤NAME_FULL_LIMIT）先拿自己的全名 —— 保证 `石脑油→石脑油`、
              `柴油→柴油`、`十六烷→十六烷`，而不是被长名的前缀轮抢走；
      阶段 1  **按轮次同步升级**：同一轮里，只有当某个候选只被一个还悬着的名字
              提出、且未被占用时才落定。这样 `重度加氢裂化石脑油` 与
              `轻度加氢裂化石脑油` 会在同一轮里一起从「前缀」升级到「跳字三字」，
              产出风格一致的 `重加氢` / `轻加氢`，而不是一个拿前缀、另一个拿滑窗；
      阶段 2  极端兜底（每一轮都撞满）：取第一个没被占用的候选，最后退化成全名。

    :param names: 显示名列表（顺序无关，函数内部排序，保证确定性）
    :param reserved: 已经被别处占用的标签（例如人工别名表）
    :return: (显示名 -> 标签, 机制计数 dict)
    """
    taken = dict()          # 标签 -> 名字（占用表）
    for r in reserved or ():
        taken[r] = u"<reserved>"
    per = {}
    mech = {}

    uniq = sorted(set(names))

    def record(name, label, key):
        taken[label] = name
        per[name] = label
        mech[key] = mech.get(key, 0) + 1

    # 阶段 0：短名直接用全名。
    pending = set()
    for name in uniq:
        if len(name) <= NAME_FULL_LIMIT:
            if name not in taken:
                record(name, name, u"全名")
            else:
                pending.add(name)          # 撞上已占用的标签，跟长名一起升级
        else:
            pending.add(name)

    lists = dict((n, candidate_list(n)) for n in pending)

    # 阶段 1：同步升级。
    for k in range(MAX_TIERS):
        if not pending:
            break
        proposed = {}
        for n in sorted(pending):
            lst = lists[n]
            if k < len(lst):
                proposed.setdefault(lst[k], []).append(n)
        if not proposed:
            break
        done = []
        for cand, members in sorted(proposed.items()):
            if len(members) == 1 and cand not in taken:
                n = members[0]
                record(n, cand, u"全名" if cand == n else (u"%d字" % len(cand)))
                done.append(n)
        for n in done:
            pending.discard(n)

    # 阶段 2：兜底。
    for n in sorted(pending):
        chosen = None
        for c in lists[n]:
            if c not in taken:
                chosen = c
                break
        if chosen is None:
            chosen = n
        record(n, chosen, u"兜底")
    return per, mech


def _raw_tokens(name):
    """原始拉丁/数字串，**不做任何截断**（救援需要完整牌号，如 `UHTC-X1000`）。"""
    return re.findall(r"[A-Za-z0-9][A-Za-z0-9'\-]*", name)


def _rescue_candidates(name):
    """超长名的救援候选，**优先带区分信息的那一段**。

    两条实测教训（见 tools/gen-report.md）：
    - 不能用 `_tokens()`：它会把 `UHTC-X1000` 压成 `UHT` + `000`，**恰好丢掉区分用的
      数字**（`X1000` / `X2000`），而且 `000` 往往已被别的材料占用（`T1000碳 → 000`）。
    - 救援层**必须允许 CJK 与拉丁/数字混排**：`哈斯特洛依合金-N` 的唯一区分点就是后缀
      `-N` / `-X`，`哈斯特金-N` 这类标签正是要的；通用候选的"混排整条丢弃"规则在救援
      层过严，只保留 `BAD_CHARS` 检查。
    """
    cands = []

    def add(s):
        s = s.strip()
        while s and s[0] in FUNC:
            s = s[1:]
        while s and s[-1] in FUNC:
            s = s[:-1]
        if not (2 <= len(s) <= VIS) or s in cands:
            return
        if any(c in BAD_CHARS for c in s):
            return
        cands.append(s)

    toks = sorted(_raw_tokens(name), key=lambda t: (-len(t), t))
    for t in toks:                       # 1. 完整牌号的尾段带区分信息
        if len(t) > VIS:
            add(t[-VIS:])
    for t in toks:
        if len(t) > 3:
            add(t[-3:])
            add(t[:3])
    if len(name) > VIS:                  # 2. 头 + 尾：哈斯特洛依合金-N → `哈斯特-N`
        add(name[:3] + name[-2:])
        add(name[:3] + name[-3:])
        add(name[:2] + name[-4:])
        add(name[:4] + name[-2:])
    for t in toks:                       # 3. 短 token 本身（牌号、`50` 之类）
        add(t)
    add(name[:VIS])                      # 4. 最后手段
    return cands


def _rescue_truncation(labels, reserved=None):
    """把「完整标签互异、但截断到可见长度后同形」的名字重新命名。

    渲染端几何上限 = maxChars × maxLines = 3 × 2 = 6 字（PLAN §4.1），超长标签会被
    静默截断 ⇒ 两条算法认为互异的长标签在屏幕上可能同形。互异性必须按**画出来的
    那 6 个字**判，不是按完整字符串判。这里只动真正撞车的那几名，其余标签原样保留
    （避免整表漂移，让新表与已发布表差异最小）。
    """
    out = dict(labels)
    taken_full = set(out.values()) | set(reserved or ())
    taken_vis = set(lab[:VIS] for lab in out.values())
    groups = {}
    for name, lab in out.items():
        groups.setdefault(lab[:VIS], []).append(name)
    for vis, names in sorted(groups.items()):
        if len(names) < 2:
            continue
        for name in sorted(names):
            if len(out[name]) <= VIS:
                continue                 # 短标签就是它自己的可见形式，不动它
            for cand in _rescue_candidates(name):
                if cand in taken_full or cand[:VIS] in taken_vis:
                    continue
                out[name] = cand
                taken_full.add(cand)
                taken_vis.add(cand[:VIS])
                break
    return out


def reg_names(modid, material):
    """给出一个材料的候选注册名。

    注：语言键里的材料名 ≠ 流体注册名（示例：键 `material.gtceu.glycolonitrile` 的中文是
    「乙腈」，而 gtocore 侧的「乙腈」键是 `material.gtocore.acetonitrile`）。因此这里对每个
    材料发射若干个候选 key，运行时用 `FluidEmiStack#getId().toString()` 精确命中其中之一即可；
    命中不了还有显示名索引与运行时兜底那两层（PLAN §5.3）。
    """
    out = [u"%s:%s" % (modid, material)]
    out.append(u"%s:%s_fluid" % (modid, material))
    return out


# --------------------------------------------------------------------------- 主流程


def collect(lang_root, explicit=None):
    """返回 (names, info, keys_info)。

    names      : {material_id: 中文显示名}
    info       : {material_id: {"modid":.., "material":.., "zh":.., "en":..}}
    keys_info  : 供报告使用的原始键统计
    """
    zh_names = {}
    info = {}
    en_names = {}
    srcs = []
    if explicit:
        srcs = list(explicit)
    else:
        for mod in MODS:
            for loc in LOCALES:
                p = os.path.join(lang_root, mod, "lang", loc + ".json")
                if os.path.isfile(p):
                    srcs.append(p)

    for path in srcs:
        base = os.path.basename(path)
        loc = base[:-5] if base.endswith(".json") else base
        parent = os.path.basename(os.path.dirname(os.path.dirname(path)))
        try:
            data = load_json(path)
        except Exception as exc:
            log(u"[warn] 读不了 %s：%s" % (path, exc))
            continue
        got_zh = 0
        got_en = 0
        for k, v in data.items():
            if not isinstance(v, str) or not v:
                continue
            p = k.split(".")
            if len(p) != 3 or p[0] != "material":
                continue
            material = p[2]
            modid = p[1]
            mid = u"%s/%s" % (modid, material)
            rec = info.setdefault(mid, {"modid": modid, "material": material})
            if loc.startswith("zh"):
                rec["zh"] = v
                zh_names[mid] = v
                got_zh += 1
            else:
                rec["en"] = v
                en_names[mid] = v
                got_en += 1
        log(u"[info] %s: material.* 命中 zh=%d en=%d" % (safe_rel(path), got_zh, got_en))

    keys_info = {"zh": len(zh_names), "en": len(en_names), "srcs": [safe_rel(s) for s in srcs]}
    return zh_names, info, en_names, keys_info


def main():
    ap = argparse.ArgumentParser(description=u"生成 fluidlabel_labels.json（v8 / MDS）")
    ap.add_argument("--lang", default=os.path.join(os.path.dirname(PROJECT), u"_gtodata", u"assets"),
                    help=u"语言文件根目录（内含 <mod>/lang/<locale>.json），或 4 个显式 json 路径"
                         u"（默认取工作区 ../_gtodata/assets）")
    ap.add_argument("--anchors", default=os.path.join(HERE, u"anchors.json"),
                    help=u"人工别名表（最高优先级），默认 tools/anchors.json（可以不存在）")
    ap.add_argument("--out", default=os.path.join(PROJECT, u"src", u"main", u"resources",
                                                 u"fluidlabel_labels.json"))
    ap.add_argument("--report", default=os.path.join(HERE, u"gen-report.md"))
    args = ap.parse_args()

    explicit = []
    if args.lang and os.path.isfile(args.lang):
        explicit.append(args.lang)
    elif args.lang and os.path.isdir(args.lang):
        explicit = []
    else:
        log(u"[error] --lang 不是有效路径：%s" % args.lang)
        return 2

    anchors = {}
    anchor_rejected = []
    if args.anchors and os.path.isfile(args.anchors):
        try:
            raw = load_json(args.anchors)
            if isinstance(raw, dict):
                inner = raw.get("byDisplayName") if isinstance(raw.get("byDisplayName"), dict) else raw
                for k, v in inner.items():
                    if not isinstance(v, str) or not v:
                        continue
                    # 渲染侧每行 maxChars 字、最多 maxLines 行 ⇒ 标签必须 <= MAX_LABEL 个码点，
                    # 否则会被 buildLayout 截掉尾巴（人工表也不例外，2026-10 踩过：12 条超长）。
                    if len(v) > MAX_LABEL or any(c in BAD_CHARS for c in v):
                        anchor_rejected.append((k, v))
                        continue
                    anchors[k] = v
                for k, v in anchor_rejected:
                    log(u"[warn] anchors 被拒（须 1..%d 码点且不含 %s）：%s => %s"
                        % (MAX_LABEL, BAD_CHARS, k, v))
            log(u"[info] anchors: %d 条（拒绝 %d 条）" % (len(anchors), len(anchor_rejected)))
        except Exception as exc:
            log(u"[warn] anchors 读取失败，按空处理：%s" % exc)
    else:
        log(u"[info] anchors 不存在（%s），按空处理" % args.anchors)

    zh_names, info, en_names, keys_info = collect(args.lang, explicit)
    if not zh_names:
        log(u"[error] 没有收集到任何 material.* 中文名，检查 --lang")
        return 3

    # 显示名撞名的条目无法在运行时分清 ⇒ 必须合并成一组、给同一个标签（PLAN §5.1）。
    by_display = {}
    for mid, name in zh_names.items():
        by_display.setdefault(name, []).append(mid)
    conflict_groups = {k: v for k, v in by_display.items() if len(v) > 1}

    # 标签只对"不同显示名"求互异；anchors 的人工标签视为已占用，避免自动标签与之撞车。
    reserved = set(v for v in anchors.values() if isinstance(v, str) and v)
    # T1 新规则：≤6 字名字原样；>6 字名字压到 ≤6 字；压不进去的进 leave-over 清单。
    per_name, tier_of, leftovers = assign_v2(list(by_display.keys()), reserved)

    # 兜底复核：新规则下所有标签都 ≤ 6 字，这一步等价于"完整字符串两两互异"。
    per_name = _rescue_truncation(per_name, reserved)

    mech = {}
    for _t in tier_of.values():
        mech[_t] = mech.get(_t, 0) + 1

    # 人工复核清单：① 自动阶梯无解；② 自动解但档位可疑（首尾拼 / 硬截）。
    review = sorted(n for n in per_name if tier_of.get(n) in REVIEW_TIERS)

    by_display_out = {}
    for name, mids in by_display.items():
        label = anchors.get(name) or per_name.get(name) or name
        by_display_out[name] = label

    # 硬校验：不同显示名的标签必须两两互异。两条规则会互相撞车 ——「≤6 字原样」与
    # 「anchors 最高优先级」（2026-10 实测撞了 2 组：电子级硅、钇钡铜氧化物）。
    _owner = {}
    for _n, _l in by_display_out.items():
        _owner.setdefault(_l, []).append(_n)
    collisions = sorted((v, ks) for v, ks in _owner.items() if len(ks) > 1)
    for _v, _ks in collisions:
        log(u"[error] 标签冲突：`%s` <= %s" % (_v, u" | ".join(_ks)))

    by_registry = {}
    dup_registered = 0
    for mid, name in zh_names.items():
        rec = info.get(mid, {})
        modid = rec.get("modid") or mid.split("/")[0]
        material = rec.get("material") or mid.split("/")[-1]
        label = by_display_out[name]
        for reg in reg_names(modid, material):
            if reg in by_registry:
                dup_registered += 1
            by_registry[reg] = label

    total = len(by_display_out)
    lens = {}
    for label in by_display_out.values():
        lens[len(label)] = lens.get(len(label), 0) + 1
    le2 = sum(v for k, v in lens.items() if k <= 2)
    le3 = sum(v for k, v in lens.items() if k <= 3)
    le6 = sum(v for k, v in lens.items() if k <= MAX_LABEL)
    over3 = sum(v for k, v in lens.items() if k > 3)
    over6 = sum(v for k, v in lens.items() if k > MAX_LABEL)

    out_obj = {
        "version": 2,
        "generatedAt": datetime.datetime.now().strftime("%Y-%m-%dT%H:%M:%S"),
        "generator": "gen_labels.py v2 (MDS per analyze8.py)",
        "counts": {
            "materials": len(zh_names),
            "displayNames": total,
            "registryNames": len(by_registry),
            "anchors": len(anchors),
            "conflictGroups": len(conflict_groups),
        },
        "byDisplayName": by_display_out,
        "byRegistryName": by_registry,
        "conflicts": 0,   # 同显示名已合并为一组并共享标签，故"不同名的标签"两两互异
    }
    dump_json(args.out, out_obj)

    # ---------------------------------------------- 随 jar 出厂的人工锚点（供 DYNAMIC 档使用）
    # 理由：STABLE 档直接把 anchors 烘进上面的 byDisplayName；而 DYNAMIC 档在运行时重算标签，
    # 读不到这个构建期文件 ⇒ 把锚点原样带进 resources，让两档行为一致（PLAN §0.4/§5.3）。
    anchors_res = os.path.join(PROJECT, u"src", u"main", u"resources", u"fluidlabel_anchors.json")
    dump_json(anchors_res, {
        "version": 1,
        "generatedAt": out_obj["generatedAt"],
        "note": u"由 tools/gen_labels.py 从 tools/anchors.json 导出；DYNAMIC 档的最高优先级覆盖层",
        "counts": {"anchors": len(anchors)},
        "byDisplayName": dict((k, v) for k, v in anchors.items()),
    })

    # ---------------------------------------------- T1 人工清单（交付子代理）
    long_names = sorted(n for n in by_display if len(n) > MAX_LABEL)
    owner = {}
    for _n, _l in by_display_out.items():
        owner.setdefault(_l, []).append(_n)

    lo = []
    lo.append(u"# fluidlabel 标签人工复核清单（T1 规则）")
    lo.append(u"")
    lo.append(u"- 规则：**≤%d 字的名字原样显示，一字不改**；>%d 字的压到 ≤%d 字。" % (MAX_LABEL, MAX_LABEL, MAX_LABEL))
    lo.append(u"- 池大小：%d 个不同显示名；其中 >%d 字 **%d 条**。" % (total, MAX_LABEL, len(long_names)))
    lo.append(u"- 无解（自动阶梯给不出 ≤%d 字且互异）**%d 条** → 见 §1，请手写关键字。" % (MAX_LABEL, len(leftovers)))
    lo.append(u"- 可疑档位（首尾拼 `head-tail` / 硬截 `cut`，能看但不一定好看）**%d 条** → 见 §2，抽查即可。" % len(review))
    lo.append(u"")
    lo.append(u"> 写回方式：把结果填进 `%s` 的 `byDisplayName`（生成器最高优先级、且会预留防撞）。"
              % safe_rel(args.anchors))
    lo.append(u"")
    lo.append(u"## §0 标签冲突（必须修，否则导出非零退出）—— %d 组" % len(collisions))
    lo.append(u"")
    for _v, _ks in collisions:
        lo.append(u"- `%s` <= %s" % (_v, u" | ".join(_ks)))
    lo.append(u"")
    lo.append(u"> 这类冲突几乎总是 anchor 的值正好等于另一条短名的原样标签；短名必须原样，改 anchor。")
    lo.append(u"")
    lo.append(u"## §1 无解：请为每条手写「简单易分辨的关键字」（≤%d 字）" % MAX_LABEL)
    lo.append(u"")
    if not leftovers:
        lo.append(u"- 无")
    for n in leftovers:
        lo.append(u"### %s（%d 字）" % (n, len(n)))
        lo.append(u"")
        lo.append(u"- 自动阶梯给出的候选（`← 已被占用` 表示该候选已归别的名字）：")
        for cand, tier in long_candidates(n):
            ow = owner.get(cand)
            lo.append(u"  - `%s` [%s]%s" % (cand, tier,
                      (u" ← 已被「%s」占用" % u"」/「".join(ow)) if ow else u""))
        if not long_candidates(n):
            lo.append(u"  - （阶梯为空：连 `cut` 档都过不了 `BAD_CHARS` 卫生检查）")
        lo.append(u"- 需要你写：`%s` = ?" % n)
        lo.append(u"")
    lo.append(u"## §2 可疑档位：抽查，不好看就手写替换")
    lo.append(u"")
    if not review:
        lo.append(u"- 无")
    for n in review:
        lo.append(u"- %s（%d 字）→ `%s` [%s]" % (n, len(n), by_display_out.get(n), tier_of.get(n)))
    lo.append(u"")
    lo.append(u"## §3 参考：全部 >%d 字的名字与自动标签" % MAX_LABEL)
    lo.append(u"")
    for n in long_names:
        lo.append(u"- %s（%d 字）→ `%s` [%s]" % (n, len(n), by_display_out.get(n), tier_of.get(n, u"?")))
    lo.append(u"")
    with io.open(os.path.join(HERE, u"gen-leftovers.txt"), "w",
                 encoding="utf-8", newline="\n") as fh:
        fh.write(u"\n".join(lo))

    # ------------------------------------------------------------------ 报告
    report = []
    report.append(u"# fluidlabel gen-report")
    report.append(u"")
    report.append(u"- 生成时间：%s" % out_obj["generatedAt"])
    report.append(u"- 输入：%s" % u", ".join(keys_info["srcs"]))
    report.append(u"- material.* 键：zh=%d / en=%d" % (keys_info["zh"], keys_info["en"]))
    report.append(u"- 池大小（不同显示名）：**%d**（材料条目 %d）" % (total, len(zh_names)))
    report.append(u"- byRegistryName：%d 条（%d 个候选键与主键重复，已去重）"
                  % (len(by_registry), dup_registered))
    report.append(u"- anchors：%d 条（人工关键字，最高优先级）" % len(anchors))
    if anchor_rejected:
        report.append(u"- anchors 被拒：**%d 条**（超长或含禁用字符）：%s"
                      % (len(anchor_rejected), u"；".join(u"%s => %s" % kv for kv in anchor_rejected)))
    report.append(u"- conflicts：0（同显示名合并为一组，共享标签）")
    report.append(u"")
    report.append(u"## 标签长度分布（T1 规则：≤6 字原样显示；>6 字压到 ≤6 字）")
    report.append(u"")
    report.append(u"| 长度 | 个数 | 累计占比 |")
    report.append(u"| --- | --- | --- |")
    cum = 0
    for k in sorted(lens):
        cum += lens[k]
        report.append(u"| %d 字 | %d | %.1f%% |" % (k, lens[k], 100.0 * cum / total))
    report.append(u"")
    report.append(u"- ≤2 字：%d / %d = **%.1f%%**" % (le2, total, 100.0 * le2 / total))
    report.append(u"- ≤3 字：%d / %d = **%.1f%%**" % (le3, total, 100.0 * le3 / total))
    report.append(u"- ≤6 字：%d / %d = **%.1f%%**" % (le6, total, 100.0 * le6 / total))
    report.append(u"- >6 字：%d 个（T1 规则下必须为 0，除非被 anchors 人为写长）" % over6)
    report.append(u"- 档位：%s" % u"、".join(
        u"%s=%d (%.1f%%)" % (k, mech[k], 100.0 * mech[k] / total)
        for k in sorted(mech, key=lambda x: -mech[x])))
    report.append(u"- 无解（自动阶梯给不出 ≤%d 字且互异）：**%d 条**" % (MAX_LABEL, len(leftovers)))
    report.append(u"- 可疑档位需人工抽查：**%d 条**（清单见 `tools/gen-leftovers.txt`）" % len(review))
    report.append(u"")
    report.append(u"## 撞名组（运行时分不清 ⇒ 强制同一标签）")
    report.append(u"")
    if conflict_groups:
        for name, mids in sorted(conflict_groups.items()):
            report.append(u"- 「%s」→ `%s`，条目：%s" % (name, by_display_out[name], u", ".join(mids)))
    else:
        report.append(u"- 无")
    report.append(u"")
    report.append(u"## 用户举例回归（T1 规则下的确定值：短名原样、长名「前缀简称 + 核心」）")
    report.append(u"")
    report.append(u"说明：`重度加氢裂化的`→`重氢裂`、`轻度蒸汽裂化的`→`轻蒸裂`，前缀 3 字 + "
                  u"核心 3 字 = 恰好 6 字，所以这一族应当**逐条精确命中**期望值。")
    report.append(u"")
    expect = {
        u"重度加氢裂化石脑油": u"重氢裂石脑油",
        u"轻度加氢裂化石脑油": u"轻氢裂石脑油",
        u"重度蒸汽裂化石脑油": u"重蒸裂石脑油",
        u"轻度蒸汽裂化石脑油": u"轻蒸裂石脑油",
        u"石脑油": u"石脑油",
        u"含硫石脑油": u"含硫石脑油",
        u"柴油": u"柴油",
        u"生物柴油": u"生物柴油",
        u"十六烷": u"十六烷",
        u"二氧化钛": u"二氧化钛",
    }
    for name, want in expect.items():
        got = by_display_out.get(name)
        if got is None:
            report.append(u"- %s：**不在池里**（期望 `%s`）" % (name, want))
        else:
            flag = u"OK" if got == want else u"与期望不同"
            report.append(u"- %s → `%s`（期望 `%s`，%s）" % (name, got, want, flag))
    report.append(u"")
    report.append(u"## 同族一致性（`裂化` 族 —— 最难的一族，必须全部互异且风格一致）")
    report.append(u"")
    fam = sorted((n, by_display_out.get(n)) for n in by_display.keys() if u"裂化" in n)
    for name, label in fam:
        report.append(u"- %s → `%s`" % (name, label))
    report.append(u"")
    report.append(u"- 族内 %d 条，标签互异数 **%d**/==条数**%s**"
                  % (len(fam), len(set(l for _, l in fam)),
                     u"" if len(set(l for _, l in fam)) == len(fam) else u"（!!! 有重复）"))
    all_labels = list(by_display_out.values())
    report.append(u"- 全池 %d 个标签，互异数 **%d**（必须相等）"
                  % (len(all_labels), len(set(all_labels))))
    report.append(u"")
    report.append(u"## 最丑 100 条（标签长度最长 / 退化到全名，供人工决定是否加 anchors）")
    report.append(u"")
    worst = sorted(by_display_out.items(), key=lambda kv: (-len(kv[1]), -len(kv[0]), kv[0]))[:100]
    for name, label in worst:
        report.append(u"- %s → `%s`" % (name, label))
    report.append(u"")

    d = os.path.dirname(os.path.abspath(args.report))
    if d and not os.path.isdir(d):
        os.makedirs(d)
    with io.open(args.report, "w", encoding="utf-8", newline="\n") as fh:
        fh.write(u"\n".join(report))

    log(u"[ok] 写出 %s" % safe_rel(args.out))
    log(u"     显示名 %d 条 / 注册名 %d 条 / conflicts=0" % (total, len(by_registry)))
    log(u"     ≤2 字 %.1f%% / ≤3 字 %.1f%% / ≤6 字 %.1f%% / >6 字 %d 个"
        % (100.0 * le2 / total, 100.0 * le3 / total, 100.0 * le6 / total, over6))
    log(u"     无解 %d 条 / 可疑档位 %d 条 → %s"
        % (len(leftovers), len(review), safe_rel(os.path.join(HERE, u"gen-leftovers.txt"))))
    log(u"[ok] 写出报告 %s" % safe_rel(args.report))
    log(u"[ok] 写出随包锚点 %s（%d 条，DYNAMIC 档用）"
        % (safe_rel(anchors_res), len(anchors)))
    if collisions:
        log(u"[error] %d 组标签冲突未解决，导出视为失败（见 %s §0）"
            % (len(collisions), safe_rel(os.path.join(HERE, u"gen-leftovers.txt"))))
        return 4
    return 0


if __name__ == "__main__":
    sys.exit(main())
