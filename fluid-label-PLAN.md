# PLAN —— EMI 液体图标中文短标签（纯客户端，MC 1.20.1 + Forge）

> 交付物：一个可丢进 `C:\Users\18409\AppData\Roaming\PrismLauncher\instances\GregTech.Odyssey-0.5.6-beta\minecraft\mods\` 的 reobf 后 jar。
> 本文件是 B（骨架）/ C（内容）/ D（审查+构建）三级的唯一接口契约。
> **v2 修订说明**：用户答复（"以EMI为主（本整合包用户可使用界面以EMI为主）"，m00440）后，把目标从 JEI 改为 **EMI**。
> v1（JEI 版）的全部 JEI 章节作废，代之以本文件 ④ 的两处 EMI 注入点 —— **证据全部来自本机安装版 jar 的 `javap -c` 反汇编，非源码推断、非记忆**。

---

## ⓪ v3 修订记录（T1 标签规则 + T2 配方槽不显示，用户 m00934 实测反馈）

### 0.1 T2 —— 配方槽（蒸馏塔等所有 GT 机器）看不到标签：已修
**根因（字节码实证）**：`dev.emi.emi.api.widget.TankWidget#drawStack(Lnet/minecraft/client/gui/GuiGraphics;IIF)V`
**有两个 RETURN**：
- offset `307` = 正常路径（槽里有液体、双层 16px 循环画完填充）；
- offset `311` = 空槽路径（`getEmiStacks()` 空 / 没有 `Fluid` key）。

Mixin 的 `@At("TAIL")` 只匹配**最后一个** RETURN ⇒ 只对应 311，而真实储罐走 307 ⇒ `TankWidgetMixin` 的回调**从不执行**；又因 `require = 0`，注入点不匹配完全静默。侧栏（注入点 1）走 `FluidEmiStack#render`，该方法**只有 1 个 RETURN**（offset 67），所以侧栏一直正常 —— 与现象完全吻合。
**修法**：`TankWidgetMixin` 的 `at = @At("TAIL")` → **`at = @At("RETURN")`**（匹配方法内所有 RETURN），`require = 0`、`remap = false` 不变。
**回归提醒**：任何 `@Inject` 到"可能有多个 return"的方法，都必须用 `RETURN` 而不是 `TAIL`；新增注入点前先用 `javap -c` 数 RETURN 的数量。

### 0.2 T1 —— 标签规则从"越短越好"改为"≤6 字不改、>6 字才简化"
用户原话（m00934）：6 个汉字可以较好显示；**少于六字则无需任何截断与简化**；长前缀按 `重度加氢裂化的→重氢裂`、`轻度蒸汽裂化的→轻蒸裂` 的风格给简称；压不进 6 字的交子代理人工写关键字；最后审一遍无冲突。
落地（Python 生成器 `tools/gen_labels.py` 的 `assign_v2` + Java 侧 `DistinctMds` 同构镜像）：
1. `len(name) ≤ 6` ⇒ 标签 = 名字本身（`verbatim`，一字不改）；实测 **1476 / 1898 = 77.8%**；
2. `len(name) > 6` ⇒ 候选阶梯：`prefix缩写+剩余` → `+去助词` → `+截断` → `+字母数字牌号` → `+词尾压缩` → 整条词尾压缩/去助词 → `code`（保留牌号） → `head-tail` → `cut`；
3. 分配顺序 = 先短后长，取第一个未被占用的候选 ⇒ 池内标签两两互异；
4. 自动结果不好看的（`code`/`head-tail`/`cut` 档）**列清单交子代理人工写关键字**，写进 `tools/anchors.json` 的 `byDisplayName`（最高优先级别名层）。
**验收标准相应调整**：A2 的"≤3 汉字"改为 **"≤6 汉字，且 ≤6 字的材料名必须原样显示"**。

### 0.3 人工关键字层（anchors）+ 两道硬闸门（2026-10 补，实测踩过坑）
**`tools/anchors.json`**（`{"byDisplayName": {...}}`，现 **83** 条）= 子代理逐条过目 206 条可疑标签后手写的关键字，最高优先级；生成器先把它当成"已占用"再跑自动阶梯，因此不会与自动标签撞车。
**坑 1 —— 锚点自身超长**：子代理写的 12 条值超过 6 个码点（如 `SIC1500复材`=9、`GRCop84`=7）。渲染侧预算 = `maxChars(3) × maxLines(2)` = **6 个码点**，超出的会被 `buildLayout` **静默截断**。⇒ 生成器现在**拒收**超长/含禁用字符的锚点并打 `[warn]`；其中 4 条（`CFC-SIC1500…`、`KH-550硅烷偶联剂`、`UHTC-X1000/X2000…`）与自动标签等价（`CF强化复材`/`KH550`/`X1000`/`X2000`）已删除，8 条改写为 ≤6 码点（`GRC84`、`MAR200`、`N甲基酰亚胺`、`P507萃取`、`硅酸乙酯`、`Q690钢`、`耐热铬956`、`马氏体300`）。改写脚本：`tools/fix_anchors.py`（幂等）。
**坑 2 —— 锚点撞上"短名原样"**：`酸洗后的电子级硅→电子级硅`、`精致钇-钡-铜氧化混合物→钇钡铜氧化物` 两条的值正好等于另一条**短名的原样标签**，被 `tools/audit_table.py` 的 R2 抓到。⇒ 生成器新增**硬校验**：任何两两冲突直接 `[error]` 列出、写进 `tools/gen-leftovers.txt` **§0**，并以 **exit 4** 结束（导出即失败）。两条锚点已改为 `酸洗电子硅`、`钇钡铜氧`。
**验收脚本**：`tools/audit_table.py`（R1 全部 ≤6 码点 / R2 1898 条两两互异 / R3 ≤6 字名字原样 / R4 conflicts 空 / R5 注册名标签均来自显示名表 / R6 用户手验样例），当前 **ALL PASS**。
**同构性**：`tools/parity/ParityCheck` 现在接**第二个可选参数**（`tools/anchors.json`）并把被人工覆盖的条目从算法比对里剔除 —— 结果 **1814/1815 完全一致**；唯一偏差是已知的非 BMP 名 `𬭳掺杂的纳米管`（Python 数码点=6 原样，Java 数 UTF-16=7 走简化 ⇒ `𬭳掺杂纳管`），可接受。
**已知局限**：只有一条 —— 非 BMP 名（`𬭳掺杂的纳米管`）在 DYNAMIC 档会与表差一个虚字（Python 数码点、Java 数 UTF-16）；`mode=STABLE`（默认）读表，无此问题。**DYNAMIC 档不含 anchors 的局限已闭合**（见 §0.5）。
**构建/部署**：`mc_gradle build` = BUILD SUCCESSFUL；当前产物 `build\libs\fluid-label-0.1.0.jar` = **102,450 字节**、sha256 `8366CB0DCED418BCDF177D8FA829A4C55E2755640BF8CB945AC34C2C57584285`（含 §0.4 探针 + §0.5 DYNAMIC 锚点叠加层），内嵌 `fluidlabel_labels.json` = 253,455 字节 / sha256 `CF5B8FDF878C8481698026FC316B5B17DAD6EAC1848D2F0E986DB3285D4056BB`、`fluidlabel_anchors.json` = 4,691 字节 / sha256 `61590307C111E608014152B5960FFC331AF19FE78BFC0E3D11946CD0915250B9`（两者均与工作区文件**逐字节相同**；表 1898 条 / 注册名 3800 / anchors 83 / conflicts 0），已复制进实例 `minecraft\mods\`，两侧 sha 一致；`TankWidgetMixin.class` 常量池含 `RETURN`、不含 `TAIL`（T2 修复随此 jar 出厂）。

### 0.4 注入点探针 + 启动自检（2026-10 补：把"沉默失败"变成日志可查）

**动机**：`@Inject(..., require = 0)` 的失配是**完全静默**的，T2 的故障就是这样藏了整轮实测。⇒ 现在每个注入点都有可观测证据，且**不依赖玩家肉眼看图标**。

**`com.fluidlabel.InjectionProbe`**（新类）：两个 `AtomicBoolean` 计数器 + `fired(int site, String detail)` / `hasFired(int site)`；`SITE_FLUID_STACK = 1`、`SITE_TANK_WIDGET = 2`。两个 mixin 的**回调第一行**（在 `flags` 门控/开关判断**之前**）调用 `fired(...)`，首次触发打一行 INFO：
```
[fluidlabel] 注入点1 已生效：FluidEmiStack#render
[fluidlabel] 注入点2 已生效：TankWidget#drawStack
```
刻意**不**受 `debugLog` 开关控制（它就是沉默失败的对策）；计数器放在本类而非 mixin 里，避免往 `TankWidget`/`FluidEmiStack` 合并静态字段。

**`com.fluidlabel.EmiSelfCheck`**（新类，启动时跑一次）：① 用反射确认两个目标方法**签名仍存在**（`FluidEmiStack#render(GuiGraphics,int,int,float,int)`、`TankWidget#drawStack(GuiGraphics,int,int,float)`），不存在则 WARN 点名"本注入点不会生效（EMI 版本变了？）"；② 扫目标类的 `getDeclaredMethods()`，找 Mixin 生成的 `handler$…$fluidlabel$…` 处理器，得到"注入器已装配 / 状态未知"；③ 打印两个探针的当前状态（启动时通常是"待触发"）。
**接线方式**：`FluidLabelMod` 构造器在 `emi` 分支里用 `Class.forName("com.fluidlabel.EmiSelfCheck").getMethod("run").invoke(null)` 反射调用 —— 保住"主类不引用任何 `dev.emi.*` 类型"这条约束（jar 内校验：`FluidLabelMod.class` 常量池 **无** `dev/emi`，仅有 `EmiSelfCheck` 字符串）。

**排查口诀**：进了配方界面看 `latest.log` —— 有「注入点2 已生效」= 储罐槽回调真的在跑；没有 = 注入器没装配（先看 `EmiSelfCheck` 那两行 WARN）。

**校验**：`build\libs\fluid-label-0.1.0.jar` 内含 `com/fluidlabel/{InjectionProbe,EmiSelfCheck}.class`；`FluidEmiStackMixin.class` 引用 `InjectionProbe`；`TankWidgetMixin.class` 仍含 `RETURN`、不含 `TAIL`；未打包任何 `dev/emi` 条目。

### 0.5 DYNAMIC 档也遵守人工锚点（2026-10 补：闭合 §0.3 遗留的局限）

**问题**：anchors（§0.3，83 条人工关键字）只进了预计算表 ⇒ `mode=DYNAMIC`（运行时规则重算，不查表）会把人工挑过的关键字丢掉，同一个液体在两个档位下显示不同标签。

**落地**：
- `tools/gen_labels.py` 除主表外**再写一份随包锚点** `src\main\resources\fluidlabel_anchors.json`（`{version, generatedAt, note, counts:{anchors}, byDisplayName}`，4,691 字节 / 83 条）。
- `LabelTables` 新增 `ANCHORS_RESOURCE_PATH = "/fluidlabel_anchors.json"`、字段 `builtinAnchors`、访问器 `builtinAnchors()`、`loadBuiltinAnchors()`（`MiniJson` 解析，失败/缺失返回空表并 WARN，`debugLog` 行会打印「随包锚点 N 条」）。
- `DistinctMds.applyAnchors(assigned, nameByKey, anchors)` —— **纯函数、不依赖 Minecraft 类型**（所以能离线测）：锚点值最高优先；其余条目若标签落在锚点值集合里，用 `labelForName(name, taken)` 就地重算以保住"不同显示名两两互异"；**同一个显示名的多个键（身份键 + `assignLabels` 补写的显示名键）复用一个重算结果**（memo），否则按名查询与按键查询会给出不同标签。
- `LabelEngine.ensureDynamicSnapshot()` 在 `DistinctMds.assignLabels(nameByKey)` 之后调用薄封装 `applyBuiltinAnchors(assigned, nameByKey)` → `DistinctMds.applyAnchors(..., LabelTables.builtinAnchors())`。

**离线验收**：`tools/parity/DynamicAnchorCheck.java`（只依赖 JDK + `DistinctMds`）四个 case 全绿 —— ① 锚点覆盖注册名键**与**显示名键；② 未命中锚点者标签不变；③ 与锚点值撞车的条目被重算且"不同显示名两两互异"；④ 真实语料（1898 名 + 83 锚点）重算后互异、锚点 **83/83** 生效。
```
javac -encoding UTF-8 -d out src\main\java\com\fluidlabel\DistinctMds.java tools\parity\DynamicAnchorCheck.java
java '-Dfile.encoding=UTF-8' -cp out com.fluidlabel.DynamicAnchorCheck      → VERDICT: ALL PASS
```
**教训**：`edit` 时 `old_string` 少了结尾换行会把两行粘成一行（本轮在 `assignLabels`/`applyAnchors` 上各犯一次，靠 read 复查发现）——改完关键文件务必 read 复查该处。

---

## ① 目标与验收标准

### 1.1 目标
在 EMI 配方/物品列表界面里，把**每一个液体图标（16×16）**叠加上一个**可区分的中文短标签**（默认 ≤6 汉字，最多两行 —— 见 ⓪.2 的 T1 规则），让人不必依赖颜色就能分辨 GT 化工配方里成百上千种液体；悬停时 EMI 原本的全名 tooltip 保持不变。

两个档位，配置项切换（用户要求两档都做、可 A/B）：
- **档 A · STABLE（跨配方稳定，默认）**：标签来自离线预计算表（1900 条 GT 材料名池），同一液体在任何界面显示同一标签，便于记忆。
- **档 B · DYNAMIC（同屏动态）**：只用**当前一帧画面里出现过的液体集合**重算标签，标签更短更贴切，但会随界面变化。

### 1.2 验收标准（逐条可验证）
| # | 标准 | 验证方式 |
| --- | --- | --- |
| A1 | 模组能在目标实例里正常加载，不崩，出现在模组列表 | 启动实例看模组列表 + `logs/latest.log` 无 `Mixin apply failed` |
| A2 | 打开任意 GT 化工配方（如"加氢裂化"），液体槽上出现 **≤6 汉字**标签，同一屏内**互不相同**；材料名本身 ≤6 字时**必须原样显示** | 实机截图 |
| A2b | 储罐槽（蒸馏塔等所有 GT 机器的 EMI 配方界面）同样出现标签 —— 由 `@At("RETURN")` 保证（见 ⓪.1） | 实机截图 |
| A3 | 悬停液体槽，tooltip 仍为**完整中文名 + 数量**（EMI 原行为未破坏） | 实机悬停 |
| A4 | `mode=DYNAMIC` 时同一液体在不同界面标签可变；`mode=STABLE` 时跨界面恒定 | 两个界面截图对比 |
| A5 | `enabled=false` 完全不绘制（渲染路径第一行返回，无额外开销） | 实机 |
| A6 | 缺 EMI 时安静失效：不崩、日志一行提示 | 临时移走 EMI 的 jar 启动 |
| A7 | 渲染状态完整复原（pose/shaderColor/blend），不污染 EMI 后续绘制 | 实机截图 + D 代码审查 |
| A8 | `mc_gradle build` 通过，产物在 `build/libs/`，reobf 后可加载 | D 执行 |

### 1.3 明确不做（范围外）
- 不改 GTCEu / GTOCore / EMI 的任何一行代码、任何资源。
- 不做服务端逻辑；不注册网络包；不注册任何内容（0 个 item/block/fluid）。
- 不动涂色、不动 tint（会破坏 GT 本身用颜色编码的信息：GT 有 `FluidEmiStackMixin` 已参与）。
- **不做 JEI 适配**（用户以 EMI 为主；JEI 留作 P2 备忘）、不做 1.21+/26.x 移植、不做图案（pattern）模式（P2）。

---

## ② 版本矩阵与构建链

### 2.1 版本矩阵（全部已核实）
| 项 | 值 | 状态/来源 |
| --- | --- | --- |
| MC | 1.20.1 | 目标实例固定（`mmc-pack.json` 已读） |
| 平台 | Forge（`javafml`） | 目标实例运行时 **47.4.20** |
| 年代 | **现代线 1.18.2–1.20.4：JDK 17 + ForgeGradle 6 + Gradle 8.x + `META-INF/mods.toml`** | 技能库年代表 |
| JDK | **17**（`JAVA_HOME=D:\Program Files (x86)\jdk17`；PATH 上是 jdk25，Gradle 不看 PATH） | 已核实 |
| Gradle wrapper | **8.8**（脚手架钉选） | 技能库钉选值 |
| ForgeGradle | **6.0.54**（脚手架钉选） | 技能库钉选值 |
| 编译用 Forge | 脚手架给的 **47.3.0** 保留即可；如需对齐运行时 → `1.20.1-47.4.5`（maven 1.20.1 线最高） | 见风险 R2 |
| **EMI（编译依赖）** | **`libs/emi-1.1.24+1.20.1+forge.jar`（从实例拷入，`compileOnly files(...)`）** | 已确认实例内该 jar 存在（1,121,750 字节）。**不用 maven**：`maven.terraformersmc.com` 会 302 到 `repo.sleeping.town`，本机不可达（已验证：cross-origin redirect + fetch failed） |
| EMI（运行时） | 同版本，实例内 `emi-1.1.24+1.20.1+forge.jar` | 已确认 |
| GTCEu | 26.7.3（JarJar 内嵌于 `gtocore-forge-1.20.1-0.5.6-beta.jar`） | 已核实：抽出 `META-INF/jarjar/gtceu-1.20.1-forge-1.20.1-26.7.3.jar` = 11,682,928 字节 |
| 本机 JDK 8 | 无（只有 JRE）→ **不影响本任务** | 已核实 |

### 2.2 build.gradle 关键片段（**只有 D 能改 build.gradle**）
```groovy
repositories {
    maven { name = "Forge"; url = "https://maven.minecraftforge.net" }
    mavenCentral()
}

dependencies {
    // EMI 只编译期可见：用**实例里那一颗**生产 jar，保证字节级 ABI 一致。
    // 绝不能 runtimeOnly / 不能打包进产物（EMI 由用户自己装）。
    compileOnly files("libs/emi-1.1.24+1.20.1+forge.jar")
}

// Mixin 接线：jar manifest（本实例 115/172 个含 mixin 的模组都走这条路，实证见 ④.4）
jar {
    manifest {
        attributes([
            "MixinConfigs": "fluidlabel.mixins.json",
            "Specification-Title": "fluidlabel",
            "Implementation-Title": "${project.name}",
            "Implementation-Version": "${project.version}"
        ])
    }
}
```
> **不加 mixin 注解处理器、不生成 refmap**。理由与证据见 ④.4 —— 本方案的两个注入目标都是 **模组类**，且它们的描述符里只有类名（无 SRG 成员名），所以 `remap = false` + 无 refmap 即可工作。

### 2.3 为什么必须走 Mixin
EMI **没有**给第三方"在别人图标上叠加绘制"的公开扩展点：
- `EmiIngredient` / `EmiStack` 是 API，但**只能被子类覆写**（要自己 new 一个 stack 类型），无法替换 EMI 内部已有 stack 的绘制。
- `EmiRenderHelper` / `WidgetGroup` 是渲染实现层，不是注册点。
- 唯一的"外部可见的每个液体绘制调用"就是 `FluidEmiStack#render` 与 `TankWidget#drawStack`（见 ④）。
⇒ 必须 Mixin（**用户已明确接受客户端 Mixin**，m00244）。

---

## ③ 工程结构（文件树 + 职责 + 归属）

模组信息（钉死，B/C/D 都不得更改）：
- **modId = `fluidlabel`**，主类 `com.fluidlabel.FluidLabelMod`，包根 `com.fluidlabel`
- mixin config 文件名 = `fluidlabel.mixins.json`（必须在 jar 根，与 manifest 里的名字逐字符一致）

```
fluid-label/                                     ← mc_scaffold 目标目录（必须保持空直到 B 执行）
├─ gradlew / gradlew.bat / gradle/wrapper/*      [B 脚手架生成]
├─ build.gradle / gradle.properties / settings.gradle   [B 生成 → D 按 §2.2 增补]
├─ libs/emi-1.1.24+1.20.1+forge.jar              [B 从实例拷贝，128 字节误差内一致]
├─ README.md                                     [B 生成 → C 补使用说明]
├─ PLAN.md                                       [宿主复制本文件]
└─ src/main/
   ├─ java/com/fluidlabel/
   │  ├─ FluidLabelMod.java              [B 骨架]  @Mod("fluidlabel")，注册 CLIENT 配置，ModList 判 EMI
   │  ├─ FluidLabelConfig.java           [C 内容]  ForgeConfigSpec（Type.CLIENT），键表见 ⑥
   │  ├─ LabelEngine.java                [C 内容]  STABLE/DYNAMIC 两档 + 缓存；对外 labelFor(...)
   │  ├─ LabelTables.java                [C 内容]  读内置 labels.json + 别名表 + 显示名索引
   │  ├─ DistinctMds.java                [C 内容]  最小可区分子序列（T1–T5 分层回退，移植 analyze8.py）
   │  ├─ LabelRenderer.java              [C 内容]  绘制：缩放、描边、对比度、换行、避让数量文字
   │  ├─ FrameContext.java               [C 内容]  DYNAMIC 档：本帧出现过的液体集合 + 逐帧缓存
   │  ├─ mixin/
   │  │  ├─ FluidEmiStackMixin.java      [C 内容]  ★ 注入点 1（覆盖槽位 + 侧栏 + 一切直接 render）
   │  │  └─ TankWidgetMixin.java         [C 内容]  ★ 注入点 2（覆盖 GT 的液体储罐槽，绕过点 1）
   │  └─ compat/EmiCompat.java           [C 内容]  EMI 类引用隔离（缺失时安静失效）
   └─ resources/
      ├─ fluidlabel.mixins.json         [B 建（含 1 个空壳）→ C 补第二个]
      ├─ META-INF/mods.toml             [B 生成 → C 补 emi 软依赖条目]
      ├─ assets/fluidlabel/lang/{zh_cn,en_us}.json   [C] 仅配置项翻译
      └─ fluidlabel_labels.json         [C 由脚本生成，≥1900 条]
tools/                                             [C 内容，不进 jar]
├─ gen_labels.py    离线生成 labels.json（移植 analyze8.py 的 v8 算法）
├─ anchors.json     别名覆盖表模板（默认空对象；用户选了"先看纯自动效果"）
└─ gen-report.md    生成报告（覆盖率 / 最丑 100 条 / 冲突数）
```

### 3.1 文件归属表（**防两个代理写同一文件**）

| 文件 | B（骨架） | C（内容） | D（审查/构建） |
| --- | --- | --- | --- |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/*` | 生成（脚手架） | — | 只读 |
| `build.gradle` | 生成基础版（不改） | **不得改** | 按 §2.2 增补 `libs` 依赖 + `jar.manifest` |
| `gradle.properties`, `settings.gradle` | 生成 | 不得改 | 只读 |
| `libs/emi-1.1.24+1.20.1+forge.jar` | 从实例拷贝 | — | 校验大小 |
| `src/main/java/com/fluidlabel/FluidLabelMod.java` | 生成最小 `@Mod` 类 | **改这一个**（注册配置 + 短路） | 审查 |
| `src/main/java/com/fluidlabel/mixin/FluidEmiStackMixin.java` | 生成空壳（`@Mixin` + 1 个空 `@Inject`） | 重写为完整实现 | 审查 |
| `src/main/java/com/fluidlabel/mixin/TankWidgetMixin.java` | **不建** | 新建 | 审查 |
| `src/main/resources/fluidlabel.mixins.json` | 生成（只含 `FluidEmiStackMixin`，`required:false`） | 加第二项 | 审查 |
| `src/main/resources/META-INF/mods.toml` | 生成 | 加 `[[dependencies.fluidlabel]] modId="emi"` | 审查 |
| `.../{FluidLabelConfig,LabelEngine,LabelTables,DistinctMds,LabelRenderer,FrameContext,compat/EmiCompat}.java` | 不建 | 全部新建 | 审查 |
| `src/main/resources/fluidlabel_labels.json` | 不建 | 生成 | 校验条数 |
| `tools/*` | 不建 | 全部 | — |
| `docs/BUILD-REQUESTS.md` | 不建 | 需要改 build 时写这里 | 执行 |

> **硬规则**：`build.gradle` 只有 D 能改。C 若发现 build 需要改，写 `docs/BUILD-REQUESTS.md` 让 D 执行。

---

## ④ Mixin 注入点（**字节码证据，已坐实**）

证据来源：对实例内**生产 jar** 执行 `javap -c -p -cp <emi jar> <class>`。

### 4.1 注入点 1：`FluidEmiStack#render`（主注入点）

**目标（逐字来自 javap）**
```
dev.emi.emi.api.stack.FluidEmiStack extends dev.emi.emi.api.stack.EmiStack
  public void render(net.minecraft.client.gui.GuiGraphics, int, int, float, int);
  public java.lang.Object getKey();          // 实测返回 Fluid（见下）
  public net.minecraft.resources.ResourceLocation getId();
  public net.minecraft.network.chat.Component getName();
```
**方法体（javap -c 关键路径）**
```
0:  invokestatic  dev/emi/emi/runtime/EmiDrawContext.wrap:(Lnet/minecraft/client/gui/GuiGraphics;)Ldev/emi/emi/runtime/EmiDrawContext;
6:  iload 5; iconst_1; iand; ifeq 48          ← flags & RENDER_ICON(1) 才画图标
15: invokevirtual EmiDrawContext.push:()V
20: invokevirtual EmiDrawContext.matrices:()Lcom/mojang/blaze3d/vertex/PoseStack;
23: fconst_0, fconst_0, ldc 100.0f; invokevirtual PoseStack.m_252880_:(FFF)V   ← z+100
30: iload_2, iload_3, fload 4; invokestatic dev/emi/emi/platform/EmiAgnos.renderFluid:(Ldev/emi/emi/api/stack/FluidEmiStack;Lcom/mojang/blaze3d/vertex/PoseStack;IIF)V
43: invokevirtual EmiDrawContext.pop:()V
48: iload 5; bipush 8; iand; ifeq 67          ← flags & RENDER_REMAINDER(8) 才画数量文字
```
**结论（三条，全部是设计约束）**
1. **`x`(iload_2)/`y`(iload_3) 参数就是图标的绘制坐标**，流体在"调用者的 pose 空间"里以 (x,y) 为左上角绘制，只是额外 `z+100`。
   ⇒ 注入 `@At("TAIL")` 后在**同一 pose** 里 `translate(0,0,200)` 再 `drawString(x+…, y+…)` 即精确落在图标上。**不需要任何槽位几何 API**（这正是 JEI 路线做不到而 EMI 路线能做的原因）。
2. `push`/`pop` 成对 ⇒ **TAIL 处 pose 已复原**，注入安全。
3. `flags & 1`（`RENDER_ICON`）是图标门控 ⇒ **只在 `(flags & RENDER_ICON) != 0` 时叠标签**，否则会在只画数量文字的场景重复画。`flags & 8`（`RENDER_REMAINDER`）表示同槽还会画数量文字 ⇒ 传给绘制的 `avoidBottom` = `true`。

**常量（`javap -constants` 实测）**：`RENDER_ICON = 1`、`RENDER_AMOUNT = 2`、`RENDER_INGREDIENT = 4`、`RENDER_REMAINDER = 8`。
`EmiIngredient` 的 4 参 `default render(...)` 内部以 `iconst_m1`（= 全部标志位）转发给 5 参版 ⇒ **任何走 4 参入口的调用最终都会进入 `FluidEmiStack#render`**。

**覆盖面（实证）**
- `SlotWidget#drawStack`：`getBounds()` → `(w-16)/2`、`(h-16)/2` 居中 → `getStack().render(guiGraphics, bounds.x()+pad, bounds.y()+pad, delta, flags)` ⇒ **槽位里的液体走注入点 1**，且拿到的是真实坐标。
- `StackBatcher#render(EmiIngredient, GuiGraphics, int, int, float[, int])` 内部两处 `invokeinterface EmiIngredient.render:(Lnet/minecraft/client/gui/GuiGraphics;IIFI)V` ⇒ **侧栏物品/液体列表也走注入点 1**（若某帧走的是烘焙后的 VertexBuffer 而不再调用 render，则侧栏无标签 —— 属可接受的降级，见 R9）。
- ⚠ `SlotWidget#drawStack` 的 int 参数是 **mouseX/mouseY 而不是坐标**（字节码里它完全不用这两个参数，只用 `getBounds()`）⇒ 若将来要 hook `SlotWidget`，坐标必须取 `getBounds()`。

```java
package com.fluidlabel.mixin;

import com.fluidlabel.LabelRenderer;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.FluidEmiStack;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = FluidEmiStack.class, remap = false)   // 模组类，不做 SRG 重映射
public abstract class FluidEmiStackMixin {

    @Inject(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIFI)V",
        at = @At("TAIL"),
        require = 0                                    // 目标漂移时降级，绝不崩游戏
    )
    private void fluidlabel$overlay(GuiGraphics guiGraphics, int x, int y, float delta, int flags, CallbackInfo ci) {
        if ((flags & EmiIngredient.RENDER_ICON) == 0) return;
        LabelRenderer.drawOverlay(guiGraphics, (FluidEmiStack) (Object) this,
                                  x, y, 16, 16, (flags & EmiIngredient.RENDER_REMAINDER) != 0);
    }
}
```

### 4.2 注入点 2：`TankWidget#drawStack`（必需，注入点 1 覆盖不到）

**为什么必需（javap 证据）**
```
public class dev.emi.emi.api.widget.TankWidget extends dev.emi.emi.api.widget.SlotWidget
  public void drawStack(net.minecraft.client.gui.GuiGraphics, int, int, float);
    Code:
       1: invokevirtual getBounds:()Ldev/emi/emi/api/widget/Bounds;
       8: invokevirtual Bounds.width:()I ; 11: bipush 16; 13: isub; 14: iconst_2; 15: idiv
      20: invokevirtual Bounds.height:()I; 23: bipush 16; 25: isub; 26: iconst_2; 27: idiv
      44: invokevirtual EmiStack.getKey:()Ljava/lang/Object;
      51: instanceof Fluid; 59: checkcast Fluid
      64: new FluidEmiStack; 72: EmiStack.getNbt(); 77: EmiIngredient.getAmount(); 82: invokespecial FluidEmiStack."<init>":(Fluid;CompoundTag;J)V
      89: invokestatic dev/emi/emi/platform/EmiAgnos.isFloatyFluid:(Ldev/emi/emi/api/stack/FluidEmiStack;)Z
     232: invokevirtual GuiGraphics.m_280168_:()Lcom/mojang/blaze3d/vertex/PoseStack;
     253: invokestatic dev/emi/emi/platform/EmiAgnos.renderFluid:(Ldev/emi/emi/api/stack/FluidEmiStack;Lcom/mojang/blaze3d/vertex/PoseStack;IIFIIII)V
```
⇒ `TankWidget` **自己 `new FluidEmiStack(...)` 然后直接调 `EmiAgnos.renderFluid(9 参)`，从不调用 `FluidEmiStack#render`** ⇒ 注入点 1 对储罐槽无效。
**并且这是 GT 液体槽的主路径**：扫 `gtceu.jar` 全部 class，`dev/emi/emi/api/widget/TankWidget` 与 `SlotWidget` 各只被 **1 个类**引用 —— `com/gregtechceu/gtceu/integration/emi/recipe/GTEmiRecipe.class`。

**坐标来源**：`drawStack` 的 int 参数同样**不是坐标**（是 mouseX/mouseY）⇒ 必须用 `getBounds()`（`x()/y()/width()/height()`），且 `renderFluid` 收到的 posX/posY 就是这套坐标系 ⇒ 在同一 pose 里用 `getBounds()` 画文字即对齐。

```java
package com.fluidlabel.mixin;

import com.fluidlabel.LabelRenderer;
import dev.emi.emi.api.widget.Bounds;
import dev.emi.emi.api.widget.TankWidget;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = TankWidget.class, remap = false)
public abstract class TankWidgetMixin extends SlotWidgetMixin {

    @Shadow public abstract Bounds getBounds();

    @Inject(method = "drawStack(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("RETURN"), require = 0)
    private void fluidlabel$overlayTank(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        LabelRenderer.drawOverlay(guiGraphics, (Object) this, getBounds());
    }
}
```
> 实现细节留给 C：`LabelRenderer` 需要一个 `Bounds` 重载，或由 mixin 传 `bounds.x(), bounds.y(), bounds.width(), bounds.height()`。
> `SlotWidgetMixin`（若需要继承 `getStack()`）由 C 决定；**保持 `getBounds()` 用 `@Shadow` 取，不要反射**。
> ⚠️ **`at` 必须是 `RETURN` 而不是 `TAIL`**：`drawStack` 有 2 个 RETURN（307 正常液体路径 / 311 空槽路径），TAIL 只匹配最后一个 ⇒ 真实储罐的回调永不执行（这就是 T2 的症状，详见 ⓪.1）。

### 4.3 mixin config（`src/main/resources/fluidlabel.mixins.json`）
```json
{
  "required": false,
  "minVersion": "0.8",
  "package": "com.fluidlabel.mixin",
  "compatibilityLevel": "JAVA_17",
  "injectors": { "defaultRequire": 0 },
  "client": [
    "FluidEmiStackMixin",
    "TankWidgetMixin"
  ]
}
```
- `"client"` 而非 `"mixins"`：EMI 与渲染都是客户端独占。
- `"required": false`：缺 EMI 时只 WARN，不崩（配合 A6）。每个 `@Inject` 都 `require = 0`。
- **不写 `"refmap"` 字段、不生成 refmap**（见 4.4）。

### 4.4 Mixin 接线与 refmap：**路线 X 已由实证坐实（不再是假设）**

**接线实证（扫实例 `mods/` 全部 172 个 jar）**：**115 个 jar 含 `*.mixins.json`**；抽查前 14 个里 **13 个用 jar manifest 的 `MixinConfigs: <name>.mixins.json`**，仅 `BadOptimizations-2.4.1-1.20.1.jar` 用 `mods.toml` 的 `[[mixins]]`（且不带 refmap）。真实模组普遍在 jar 根投放 `<name>.refmap.json`。**逐字样本**（`AE2ToggleableViewCell-1.20.1-1.1.0.jar` 的 `ae2_toggleable_view_cell.mixins.json`）：
```json
{ "required": true, "minVersion": "0.8", "package": "io.github.lapis256.ae2_toggleable_view_cell.mixin",
  "refmap": "ae2_toggleable_view_cell.refmap.json", "compatibilityLevel": "JAVA_17",
  "server": [], "injectors": { "defaultRequire": 1 }, "mixins": [ "MixinRestrictedInputSlot", "MixinMEStorageMenu" ] }
```
> Forge 1.20.1 的 `mods.toml` 规格里**没有** mixins 章节（官方文档已核）。故用 **manifest `MixinConfigs`** 作为唯一注册方式。

**为什么本方案不需要 refmap（关键，v1 里被标为"最高风险假设"，现已坐实）**
- refmap 的作用：把注解里的**原版 MC 成员名**从 mojmap 翻成 SRG。
- 本方案两个目标的**描述符里只有类名，没有成员名**：
  `render(Lnet/minecraft/client/gui/GuiGraphics;IIFI)V` / `drawStack(Lnet/minecraft/client/gui/GuiGraphics;IIF)V`
- **实证**：EMI 生产 jar 自己的字节码里，MC **类名是可读的**（`net/minecraft/client/gui/GuiGraphics`），而**成员名是 SRG**（`GuiGraphics.m_280168_()`、`PoseStack.m_252880_()`）。⇒ 类名在两种映射下同名，描述符原样可用。
- 因此：`remap = false` + **不生成 refmap** 即可正确注入；**我们的方法体里调用 MC 方法**（`pose()`、`drawString(...)`）由 ForgeGradle 的 reobf 步骤统一翻成 SRG，与 Mixin 无关。
- **若实测失败**（日志 `Target method not found`）再切路线 Y（加 `org.spongepowered:mixin:0.8.5:processor` + `-AoutRefMapFile=...`），并把 `"refmap"` 字段写回 config。R4 给出判据。

> **与 GT 自身 Mixin 共存**：GTCEu 已有 `com/gregtechceu/gtceu/core/mixins/emi/FluidEmiStackMixin.class`（GT 也混入了 `FluidEmiStack`）。我们只用 `@Inject(TAIL)`、**绝不用 `@Redirect`/`@Overwrite`**、不改参数、不改返回 ⇒ 共存安全（R10）。

### 4.5 mods.toml 关键字段（B 生成，C 补依赖）
```toml
modLoader="javafml"
loaderVersion="[47,)"
license="MIT"

[[mods]]
modId="fluidlabel"
version="${file.jarVersion}"
displayName="Fluid Label"
description='''为 EMI 液体图标叠加可区分的中文短标签（纯客户端）。'''
authors="…"
displayTest="IGNORE_SERVER_VERSION"      # 纯客户端：服务端有无本模组都不影响联机

[[dependencies.fluidlabel]]
  modId="forge"
  mandatory=true
  versionRange="[47,)"
  ordering="NONE"
  side="BOTH"

[[dependencies.fluidlabel]]
  modId="minecraft"
  mandatory=true
  versionRange="[1.20.1,1.21)"
  ordering="NONE"
  side="BOTH"

# ★ EMI 是软依赖：缺失时必须安静失效，绝不能 mandatory=true
[[dependencies.fluidlabel]]
  modId="emi"
  mandatory=false
  versionRange="[1.1,)"
  ordering="AFTER"
  side="CLIENT"
```
- **不写 `clientSideOnly`**：该字段是 1.20.x 后期加入（[PR #9804](https://github.com/MinecraftForge/MinecraftForge/pull/9804)），写错会让整个 `mods.toml` 解析失败、模组直接不加载。用 `displayTest="IGNORE_SERVER_VERSION"` 达到同等效果且所有 47.x 都认。
- 代码侧检测：`net.minecraftforge.fml.ModList.get().isLoaded("emi")`（**不用** `@Optional`，那是 1.12 时代做法）。
- 双保险：`"required": false`（mixin config）+ `FluidLabelMod` 构造函数里 `if (!ModList.get().isLoaded("emi")) { LOGGER.info(...); return; }`。

---

## ⑤ 标签引擎

### 5.1 数据来源（离线，构建期）
**输入（已在本机）**
| 来源 | 绝对路径 | 用途 |
| --- | --- | --- |
| gtceu 中文名 | `_gtodata\assets\gtceu\lang\zh_cn.json` | `material.*` → 中文名 |
| gtceu 英文名 | `_gtodata\assets\gtceu\lang\en_us.json` | 英文名索引 + 拉丁/数字词元 |
| gtocore 中文名 | `_gtodata\assets\gtocore\lang\zh_cn.json` | 同上（gtocore 侧材料更多：1247 条） |
| gtocore 英文名 | `_gtodata\assets\gtocore\lang\en_us.json` | 同上 |
| 算法参考实现 | `_gtodata\analyze1.py` … `analyze8.py`、`report1..8.md` | **v8 是最终算法**；`report8.md` 的 1900 条池是本方案的回归基线 |

**输出**：`src/main/resources/fluidlabel_labels.json`

**✅ 运行时能拿到什么（已由 javap 坐实，这决定了索引怎么建）**
`FluidEmiStack` 公开 `getId()`（`ResourceLocation`）与 `getName()`（`Component` = **EMI/原版翻译后的显示名**）。
⇒ 运行时**不需要**访问 `BuiltInRegistries.FLUID`、不需要构造 `FluidStack`、不需要 Forge fluid API。索引直接按这两样建：
- **主索引 = 显示名**（zh_cn；en_us 一并写入，供英文客户端）
- **次索引 = 注册名**（`namespace:path`），`getId().toString()` 精确命中
- 两者都不中 → 规则兜底（⑤.3 第 3 层）

**语言键结构（已核实）**：`gtceu/zh_cn` 5474 键（`material.*` **653**）、`gtocore/zh_cn` 8793 键（`material.*` **1247**）⇒ 1900 条材料名池。`fluid.*` 键极少（gtceu 侧仅 `fluid.gtceu.potion`；gtocore 侧 15 条自定义流体）⇒ **材料液体名字来自 `material.*`**，生成脚本两者都收。

**显示名撞名的实测规模**：全 1900 条里**只有 2 组 / 4 个键**完全相同 —— `乙腈`×2（`gtceu:glycolonitrile`、`gtocore:acetonitrile`）、`金属残渣`×2（`gtocore:metal_residue`、`gtocore:metallic_residues`）⇒ 主索引的撞名必须处理：**同显示名的条目合并为一组，标签给同一组分配互异标签**（第 5 层 T5 序号兜底留给运行时极端情况）。

**输出 JSON 结构（固定，C 按此解析）**
```json
{
  "version": 2,
  "generatedAt": "2026-…",
  "generator": "gen_labels.py v2 (MDS per analyze8.py)",
  "byDisplayName": { "重度加氢裂化石脑油": "重加氢", "石脑油": "石脑油", "十六烷": "十六烷" },
  "byRegistryName": { "gtceu:severely_hydro_cracked_naphtha": "重加氢" },
  "conflicts": 0
}
```
> `byDisplayName` 里若两个键同名 → 生成脚本必须**把它们作为一组**求互异标签，并（因为运行时分不清）在报告里列出，供人工决定是否加别名。数量级：2 组，可忽略。

**生成脚本接口（写给 C）**
```
tools/gen_labels.py
  --lang <dir 或 4 个显式 json 路径>   # gtceu/gtocore × zh_cn/en_us
  --anchors tools/anchors.json        # 人工别名（最高优先级，默认 {}）
  --out src/main/resources/fluidlabel_labels.json
  --report tools/gen-report.md        # 覆盖率 / 最丑 100 条 / 撞名组
```
**产物校验**：`byDisplayName` 条数 ≥ 1900、`conflicts == 0`；对同一池重跑必须复现 `report8.md` 的基线 **≤2 字 99.9% / ≤3 字 100%**。

### 5.2 算法（移植 `analyze8.py`，**两条硬判据不许"优化"掉**）
对需要区分的集合 `S`（STABLE 档 `S` = 全池 1900；DYNAMIC 档 `S` = 本帧集合）：
```
T1  名字本身 ≤ maxNameLen（默认 4）字        → 用全名（石脑油 / 汽油 / 柴油 / 十六烷）
T2  集合内存在共享"物质词"                   → 用物质词当标签（含硫石脑油 → 含硫）
T3  物质词冲突                               → 判别部分的最小可区分子序列
T4  仍冲突                                   → 末尾补 1 字 或 改用拉丁/数字牌号（TC11 / X750 / 718）
T5  显示名完全相同（实测仅 2 组）            → 序号 #1/#2，全名留 tooltip
```
**硬判据**
1. **唯一性判据 = 标签之间两两互异**，**不是**"标签不是别的名字的子序列"。
   用"子序列唯一"判据 → **26.4%** 退化到全名（反例：`加氢裂化丁烯` 的候选 `丁烯` 是 `加氢裂化丁二烯` 的子序列，但不构成歧义）；改成"两两互异" → 1900 条 **100%** 拿到 ≤3 字标签。
2. **标签取子序列而非连续子串**。人类缩写就是取子序列（`重度加氢`→`重加`，在名字里不连续）。用子串规则有 **17.8%** 数学上无解（`轻度加氢裂化石脑油` 的任意子串都出现在同屏别的名字里）。
3. **候选优先连续段、其次子序列；排除功能字**（`的/了/之/与/和/及/标点`），否则产出 `的钆纳`。
4. **目标函数语义优先**：纯最短会产出 `石脑油→脑`、`十六烷→十`、`二氧化铪→化铪`（语义反转）。**T1 保全名 + T2 剥离共有词是"好懂"的主要来源，不能省。**
5. 拉丁/数字词元优先：9% 的名字含拉丁/数字，直接拿牌号当标签（`钛合金-TC11`→`TC11`）。

**已实测成效（`_gtodata/report8.md`，池 = 1900）**：1 字 41.3%、≤2 字 99.9%、≤3 字 100%；需 5 字以上 = **0 个**。
需区分集合规模的影响（`analyze6`）：整族 1152 条 → ≤3 字 96.4%；随机 3 个同族（≈一屏）→ ≤3 字 98.0%；随机 6 个 → **100%**。
**用户举例的实测结果（生成器实跑，2026-09 复核）**：重度加氢裂化石脑油→`重加石`、轻度加氢裂化石脑油→`轻加石`、重度蒸汽裂化石脑油→`重蒸石`、轻度蒸汽裂化石脑油→`轻蒸石`、加氢裂化丁烯→`加丁烯`、蒸汽裂化丁烯→`蒸丁烯`、石脑油→`石脑油`、柴油→`柴油`、含硫石脑油→`含硫石`、生物柴油→`生物柴`。（早期 `重加氢`/`重蒸汽` 是 v6 时代的样例，**以 `tools/gen-report.md` 为准**。）

#### 5.2.1 显示截断救援（2026-09 补，Python / Java 双侧同构）

渲染端几何上限 = `maxChars × maxLines`（默认 3 × 2 = 6 字），**超过 6 字的标签会被渲染端静默截断**
⇒ "完整标签字符串两两互异"**不够**，必须按**画出来的那 6 个字**判互异。实测 2 组同形，正是本模组
要消灭的症状：

| 名字 | 旧标签 | 截断后 | 结果 |
| --- | --- | --- | --- |
| `UHTC-X1000超高温复合材料` | 全名 | `UHTC-X` | 与下一行同形 |
| `UHTC-X2000超高温复合材料` | 全名 | `UHTC-X` | ↑ |
| `哈斯特洛依合金-N` | 全名 | `哈斯特洛依合` | 与下一行同形 |
| `哈斯特洛依合金-X` | 全名 | `哈斯特洛依合` | ↑ |

- 实现：`tools/gen_labels.py` 的 `_rescue_truncation` / `_rescue_candidates` 与 `DistinctMds` 的
  `rescueTruncation` / `rescueCandidates` / `visible`，**两侧必须同步修改**（同构性由
  `tools/parity/ParityCheck` 守）。
- 语义：按 `visible(label) = label[:6]` 分组，组内成员数 > 1 时只重命名**长度 > 6** 的那些名
  （短标签本来就是它自己的可见形式，不动它）；候选不得与 `taken_full` / `taken_vis` 冲突。
- 救援候选**必须**用原始拉丁串（`_raw_tokens` / `rawTokens`，正则 `[A-Za-z0-9][A-Za-z0-9'\-]*`），
  **不能**用 `_tokens()` / `latinTokens()`：后者会把 `UHTC-X1000` 压成 `UHT` + `000`，恰好**丢掉区分
  用的数字**，而 `000` 往往已被别的材料占用（实测 `T1000碳 → 000`）。
- 救援层**必须允许 CJK 与拉丁/数字混排**（`哈斯特金-N` 正是要的形态），只保留 `BAD_CHARS` 检查；
  通用候选的"混排整条丢弃"规则在救援层过严。
- 验收：全池 1898 条按 `visible` 分组**无任何组员数 > 1**；本轮仅 4 条标签被改名
  （`X1000` / `X2000` / `哈斯特-N` / `哈斯特-X`），其余标签与救援前逐条一致（不乱动全表）。

### 5.3 运行时三层来源 + 两档语义
```
1. 外部别名覆盖表（人工，最高优先级；用户选了"先看纯自动效果"，故默认文件为空）
   config/fluidlabel-aliases.json    { "重度加氢裂化石脑油": "重加氢" }
2. 内置预计算表（构建期产出，随 jar 发布）    byDisplayName → byRegistryName
3. 通用兜底（任意模组，无条件可用）
   命中不了 → 用 getName() 的显示名现场跑 MDS（池 = 本帧集合；STABLE 档用全池）
```
| | STABLE（默认） | DYNAMIC |
| --- | --- | --- |
| 池 `S` | 内置表全池（1900） | **本帧集合**（`FrameContext`） |
| 跨界面恒定 | **是** | 否 |
| 成本 | O(1) 查表 | 每帧一次 MDS（集合通常 <100，需缓存） |
| 标签长度 | 多为 2–3 字 | 通常更短更贴切 |
| 额外注入点 | 无（两个注入点都要） | 无（**同一套注入点**，见下） |

**DYNAMIC 档的实现（重要，且是 v2 相对 v1 的简化）**
- 集合 = **本帧**通过两个注入点绘制过的液体（`FrameContext` 里的 `Set<Fluid>` / `Set<ResourceLocation>`）。
- 帧末清空（`ClientTickEvent.Post` 或自增帧计数器）。
- **不需要任何"配方级"钩子**。理由：槽位与侧栏同屏共存，任何"当前可见"的严格子集在唯一性上都是不完整的；用本帧实际绘制过的集合，是**语义正确**的定义（"你此刻看到的所有液体标签互不相同"），而且它天然是唯一性约束的一个超集 —— 只会让标签偶尔多一个字，**永远不会撞车**。
- 与 STABLE 的真实差别：池从 1900 收缩到几十~几百 ⇒ 标签更短、更贴切。

### 5.4 保牌号档 + 状态词缀组合（2026-10 补，用户实测驱动）

**症状（用户 2026-10 实测）**：`熔融高速钢-G` / `熔融高速钢-S` / `熔融铝合金-2090` / `熔融铝合金-7050`
在界面里全部显示成同族的第一个样子（`熔融高速钢-` / `熔融铝合金-`），牌号消失。

**根因（不是"原汉字少于 6 字"）**：
1. GT 的熔融/气态/液态名是**运行期用 lang 模板拼出来的**，不是独立字面键 ——
   `gtceu.fluid.molten = 熔融%s`、`liquid_generic = 液态%s`、`gas_generic = 气态%s`、
   `plasma = %s等离子体`、`gas_vapor = %s蒸气`、`generic = %s`（6 个单占位符模板）。
   派生名 `熔融高速钢-G`（7 码点）**根本不在** 1898 条预计算表里。
2. 查表 miss ⇒ 旧 `LabelEngine.ruleFallback` 走到 `return name`（返回超长原名）
   ⇒ 渲染端预算 `maxChars(3) × maxLines(2) = 6` 码点，按 3 字切行、第 3 行丢弃
   ⇒ `熔融高` + `速钢-`（读作 `熔融高速钢-`）、`熔融铝` + `合金-`（读作 `熔融铝合金-`）。
   同族的 `-G` / `-S` 因此看起来完全一样 —— 正是本模组要消灭的症状。

**三条修复（Python `tools/gen_labels.py` 与 Java `DistinctMds` 双侧同构）**：
1. **状态词缀组合层**：`STATE_PREFIX = [(熔融→熔融/熔), (液态→液态/液), (气态→气态/气)]`、
   `STATE_SUFFIX = [(等离子体→等离子/浆), (蒸气→蒸气/汽)]`。派生名标签 = `状态短字 + 基体标签`
   （基体 = 去掉词缀后的材料名，按 `budget − 状态字长` 收缩）。
2. **守门逻辑**：长写法（`熔融`）只在"缩短预算没有让基体首选降级"时使用，否则退短写法。
   反例：`熔融铝合金-2090` 用 `熔融` 时基体预算只剩 4 ⇒ 首选变截断牌号的 `铝合20`；退到 `熔` 得 `熔铝2090`。
3. **保牌号档（`gradeCandidates`）**：牌号 = **含数字的最长拉丁串**（去 `-`/`'`；纯字母罗马数字与单字母不算）。
   只做"严格更好"的三件事：① 中文前缀简称 + 完整牌号（`铝合金-2090 → 铝合2090`）；
   ② 牌号在尾 ⇒ 中文头部 k 字 + 完整牌号（k ≥ 2，状态组合允许 k = 1）；
   ③ 牌号在头 ⇒ 牌号 + 中文头部 k 字（`CAS-9-蛋白 → CAS9蛋白`）。
   **第一版教训（已撤销 `grade-cut` 档）**：无条件"中文头部 + 完整牌号"+"保中文裁牌号"
   产生 15 条变化，其中 `不锈155p→不155ph`、`不锈JBK7→不JBK75`、`钛合Ti53→钛合金Ti5` 均为**回归**
   ⇒ 结论：**牌号字符数多 ≠ 更好**；可读性（≥2 个连续汉字、牌号保持原名顺序）优先。
   收紧后全表只变 2 条：`哈斯特洛依合金-N75: 哈氏-N75 → 哈氏N75`、`-X78: 哈氏-X78 → 哈氏X78`。
4. **兜底不得返回超长原名**：`DistinctMds.labelForName` 改为预算内选择（阶梯 + `uniqueWithinBudget`）；
   另加 `LabelEngine.extraSafeLabel`（`EXTRA_LABELS` + `extraTaken`，上限 8192）——
   池（冻结于首次构建）miss 时也**绝不**返回原名，改为预算内挑一个未被占用的标签并惰性缓存。

**验收（离线，全绿）**：`tools/derived_check.py` 模拟 5 个模板 × 词库 = **9695 条派生名**
（走阶梯 4954 / 原样 4741），四条硬门禁全 PASS；用户样例：
`熔融高速钢 → 熔融高速钢`、`熔融高速钢-G/-S/-E → 熔高速钢-G/-S/-E`、
`熔融铝合金-2090 → 熔铝2090`、`-7050 → 熔铝7050`、`熔融不锈钢-316 → 熔不锈316`、
`铝合金-2090等离子体 → 铝2090浆`。

### 5.5 码点预算（**长度单位，2026-10 修正**）

标签预算与"画出来的可见长度"一律按**码点**计（`LabelRenderer.buildLayout` 本来就是按码点切行，
`Character.isHighSurrogate` 跳过低位代理）。旧 `DistinctMds` 用 UTF-16 `String.length()`，
一个补充平面汉字（`𬭳` / `𫓧` / `𬭶`）被算成 2 字 ⇒ 本该原样的名字被无谓缩短
（`熔融三氯𫓧烷` 只剩 6 码点却变成 `熔三氯𫓧烷`；`氯化𬭶等离子体` 被压成 `氯化𬭶浆`）。
修法：`DistinctMds` 新增 `cpLen` / `cpHead` / `cpTail`，所有影响**长度判定与切片**的位置改用它们
（ASCII/CJK 字符循环保持原样）。副作用是好的：`ParityCheck` 的历史唯一偏差
（`𬭳掺杂的纳米管`）消失，**1814/1815 → 1815/1815**。

### 5.6 缓存与性能（**必须遵守**）
- 缓存键 = `(ResourceLocation, mode, frameEpoch)`；DYNAMIC 档每帧只算一次，绘制时 O(1) 查。
- **渲染路径禁止**：`Component#getString()`（**先取一次缓存**，不要每帧每槽调）、正则、`String.format`、反射。
- `Font#width` 结果必须缓存（0.5 缩放下每个标签一次调用，槽多时累积）。
- 短路：`enabled=false` → Mixin 方法第一行 return；`(flags & RENDER_ICON)==0` → return；`Fluids.EMPTY` → return。

---

## ⑥ 配置项表

配置文件：**`config/fluidlabel-client.toml`**（`ModConfig.Type.CLIENT`，显式指定文件名以保证固定）。
注册：`ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, FluidLabelConfig.SPEC, "fluidlabel-client.toml");`
`ModConfigEvent.Loading`/`Reloading` 注册到 **mod 事件总线** → 重载后清缓存。

| 键 | 类型 | 默认 | 取值 | 作用 |
| --- | --- | --- | --- | --- |
| `enabled` | boolean | `true` | — | 总开关；false 时渲染路径第一行返回 |
| `mode` | enum | `STABLE` | `STABLE` \| `DYNAMIC` | 档 A / 档 B |
| `scale` | double | `0.5` | `0.3`–`1.0` | 文字缩放；0.5 → 汉字 4.5px，一行 3 字 |
| `maxChars` | int | `3` | `1`–`6` | 单行最大字数；超出切两行 |
| `maxLines` | int | `2` | `1`–`2` | 最多两行 |
| `autoContrast` | boolean | `true` | — | 按液体 tint 亮度自动选黑字/白字 |
| `outline` | boolean | `true` | — | 描边（8 向偏移先画黑）—— 比底板更不挡液体 |
| `backdrop` | boolean | `false` | — | 半透明底板（`guiGraphics.fill`，ARGB `0x80000000`） |
| `showOnHoverOnly` | boolean | `false` | — | true 时只在悬停该槽时画 |
| `avoidAmountText` | boolean | `true` | — | 避开槽底的液体数量文字（EMI 用 `RENDER_REMAINDER` 画） |
| `labelSource` | enum | `TABLE_THEN_RULE` | `TABLE_THEN_RULE` \| `RULE_ONLY` \| `TABLE_ONLY` | 是否允许运行时兜底规则 |
| `tankWidgets` | boolean | `true` | — | 是否也给储罐槽（`TankWidget`）贴标签（关掉可 A/B 对比） |
| `aliasesFile` | String | `fluidlabel-aliases.json` | 路径 | 相对 `config/` 的别名表 |
| `debugLog` | boolean | `false` | — | 首帧自检 + 命中/未命中 |
| `dumpRegistry` | boolean | `false` | — | 一次性导出「注册名 → 显示名」到 `config/fluidlabel-dump.json` |

> `mode` 用 `defineEnum`；`scale` 用 `defineInRange`；键名一律 lowerCamelCase（Forge 惯例）。

---

## ⑦ 分阶段里程碑

| 里程碑 | 内容 | 通过标准 | 执行者 |
| --- | --- | --- | --- |
| **M1 骨架能编译** | `mc_scaffold` 产出项目；拷入 `libs/emi-…jar`；D 加 `compileOnly files(...)` + `jar.manifest`；`mods.toml` 补 `displayTest` + `emi mandatory=false`；`FluidEmiStackMixin` 空壳（方法体只打一行日志） | `mc_gradle build` 通过；jar 内有 `fluidlabel.mixins.json` 且 manifest 有 `MixinConfigs` | B + D |
| **M2 静态标签可见** | `LabelTables`（双索引）+ `LabelEngine`（三层优先级）+ `LabelRenderer`（缩放/描边/对比度）+ `gen_labels.py` 产出 labels.json；配置 `enabled/scale/maxChars/autoContrast/outline` | 打开 GT 化工配方，液体槽出现 ≤3 汉字标签、同屏互不相同；tooltip 仍为全名 | C + D |
| **M3 储罐槽 + 动态档** | `TankWidgetMixin`；`FrameContext` + `mode` 配置；`DistinctMds` 运行时可用 | 储罐槽也有标签且不与槽位标签撞车；`DYNAMIC` 与 `STABLE` 实机可辨差异 | C + D |
| **M4 配置与别名表** | 剩余配置键；别名表热路径；`dumpRegistry` 导出 | 改别名表重进游戏生效；导出文件可用于校正索引 | C |
| **M5 进实例验证** | reobf jar 复制到 `...\GregTech.Odyssey-0.5.6-beta\minecraft\mods\`；启动；A1–A8 逐条验收；截图 | A1–A8 全过 | D + 宿主/用户 |
| **M6（可选 P2）** | 图案叠加层（WCAG G111）、JEI 适配、别名表 GUI | — | 后续 |

> 后两个阶段（AE2 侧、GT/GTO 机器液体槽）沿用同一套 M1–M5 流程各重跑一遍：骨架（mixin + `compat` 桩）→ 内容 → 构建 → 进实例验证。
> 实施记录见 §6.9 / §6.10 与 §7.7；三阶段共用同一个 `LabelEngine`，没有新增配置项。

**M1 的 mc_gradle 任务与超时（D 用）**
```
1) clean
2) build --stacktrace      ← 首次要下载 Forge/FG 依赖 + 反编译，可能 10–30 分钟
3) jar
超时：冷启动 ≥1800s；增量 ≥600s。失败先判断是否只是依赖下载超时，同一错误两次才算真失败。
产物：build/libs/fluid-label-<version>.jar
```

---

## ⑧ 风险与未验证项

| # | 风险 | 影响 | 等级 | 缓解 / 验证 |
| --- | --- | --- | --- | --- |
| **R1** | ~~注入点描述符未坐实~~ | — | **已消除** | v1 的 R1 是"未 javap"，v2 已用本机生产 jar 反汇编坐实两个描述符（④.1/④.2） |
| **R2** | 编译 Forge 47.3.0/47.4.5 vs 运行时 **47.4.20**（该版本不在 maven 47.x 列表，来源不明） | 若 47.4.20 有破坏性改动 ⇒ 运行期 `NoSuchMethodError` | 中 | 只用 `@Mod`/`ModConfigSpec`/`ModLoadingContext`/`ModList` 这些 47.x 稳定面；`versionRange="[47,)"` 不设上限；若启动崩再对齐版本 |
| **R3** | 运行时拿到的是 `ResourceLocation` + 显示名，索引键规则若与实际不符 | 命中率低 ⇒ 退化到兜底 | 中 | 三层来源里**第 3 层无条件可用**（显示名现场跑 MDS）⇒ 功能不会失效，只影响标签质量；`dumpRegistry` 可导出实况校正 |
| **R4** | 无 refmap 路线（route X）失败 | 注入失败 ⇒ 无标签 | 中→低 | ④.4 已用"EMI 生产字节码里类名可读、成员名为 SRG"实证支撑；D 阶段看日志判据（`Target method not found`）→ 切路线 Y（加 mixin AP + `refmap` 字段） |
| **R5** | `clientSideOnly` / `[[mixins]]` 在 47.4.x 的解析 | TOML 解析异常 ⇒ 模组不加载 | 中 | **两个都不写**；用 manifest `MixinConfigs` + `displayTest` |
| **R6** | 侧栏（`StackBatcher`）若走烘焙 VertexBuffer 不再调 `render` | 侧栏无标签 | 低 | 属可接受降级（配方槽是主目标）；实机确认，必要时把 `StackBatcher` 也纳入（P2） |
| **R7** | `FluidEmiStack#getName()` 在 GT 材料液体上返回的是否就是 `material.*` 的中文名 | 主索引（显示名）命中率下降 | 中 | 实机 + `dumpRegistry` 导出真实 (id, name) 对；`byRegistryName` 作为第二索引；兜底规则仍在 |
| **R8** | 储罐槽的 `getBounds()` 坐标系与 `renderFluid` 收到的 posX/posY 是否严格同一套 | 储罐标签错位 | 中 | 同一 pose 内取 `getBounds()`（字节码证明 TankWidget 自己就是这么画的）；实机校验；配置项 `tankWidgets` 可关掉 |
| **R9** | 与其它 172 个模组/GT 自身 Mixin 冲突（GT 已有 `FluidEmiStackMixin`） | 注入被覆盖 | 低 | 只用 `@Inject(TAIL)`，不用 `@Redirect`/`@Overwrite`；不改参数不改返回 |
| **R10** | 渲染状态污染（EMI 在液体绘制后接着画数量文字/tooltip） | 文字变色或消失 | 中 | `pushPose/popPose` 成对；结束后 `RenderSystem.setShaderColor(1,1,1,1)` + `disableBlend()` 复原；D 逐条审查 |
| **R11** | 中文标签可读性（`石脑油→脑` 这类） | 功能可用但难懂 | 中（产品） | T1 保全名 + T2 剥离共有词是主要来源；`gen-report.md` 输出"最丑 100 条"供人工过目（用户选了先看纯自动效果，别名表默认空） |
| **R12** | 数量文字（`1000L`）与标签重叠 | 互相遮挡 | 低 | `avoidAmountText=true`：标签贴上/居中，底部留 4px（`flags & RENDER_REMAINDER` 时必开） |

**环境风险（已核实）**
- 本机**没有 JDK 8**（`D:\Program Files (x86)\java8` 只有 JRE）—— 本任务用 JDK 17，不受影响。
- PATH 上的 `java` 是 JDK 25；ForgeGradle 6 / Gradle 8.8 通过 `JAVA_HOME`（=jdk17）取 JDK，**不要**为此改 wrapper/toolchain。
- `maven.terraformersmc.com` 在本机不可达（302 → `repo.sleeping.town` 后 fetch failed）⇒ EMI 依赖走 `libs/` 本地 jar，**不要**试 maven。
- 构建**必须**通过 `mc_gradle`（D 执行），禁止直接调 `gradlew`。

---

## ⑨ 给 B / C / D 的接口约定

### 9.1 给 B（骨架代理）：只做这些，做完即止
1. `mc_scaffold` 在 **`D:\Program Files (x86)\DeepseekHarness\workspace\fluid-label`** 生成 **forge / 1.20.1 / client-only** 骨架（modId=`fluidlabel`，package=`com.fluidlabel`，name=`fluid-label`）。
2. **不要**手写 `build.gradle` 的依赖/仓库/manifest（留给 D）。不要建 `settings.gradle.kts`。
3. 拷入依赖：`C:\Users\18409\AppData\Roaming\PrismLauncher\instances\GregTech.Odyssey-0.5.6-beta\minecraft\mods\emi-1.1.24+1.20.1+forge.jar` → **`fluid-label\libs\emi-1.1.24+1.20.1+forge.jar`**（目录不存在则创建；大小应为 1,121,750 字节）。
4. 建这些文件（其他一律不建）：
   - `src/main/java/com/fluidlabel/FluidLabelMod.java` —— 最小 `@Mod("fluidlabel")` 类，构造函数体留空 + 一行 `LOGGER.info`。
   - `src/main/java/com/fluidlabel/mixin/FluidEmiStackMixin.java` —— **空壳**：`@Mixin(value = dev.emi.emi.api.stack.FluidEmiStack.class, remap = false)` + `@Inject(method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIFI)V", at = @At("TAIL"), require = 0)`，方法体 `{}`。**不加**其它逻辑。
   - `src/main/resources/fluidlabel.mixins.json` —— 按 ④.3，但 `"client"` **只含 `FluidEmiStackMixin`**，`"required": false`。
   - `src/main/resources/META-INF/mods.toml` —— 按 ④.5（含 `displayTest="IGNORE_SERVER_VERSION"` 与 `emi` 软依赖）。
   - `README.md` 骨架。
5. **禁止**：建 `TankWidgetMixin`、建 `fluidlabel_labels.json`、建 `tools/`、改 `build.gradle`、建 `FrameContext`/`LabelEngine` 等 C 的文件。
6. 完成后回报：文件清单 + **绝对路径** + 脚手架钉选的**确切版本号**（Forge / wrapper / FG），供 D 改 build.gradle。

### 9.2 给 C（内容代理）：只做这些
1. **新建**：`FluidLabelConfig`、`LabelEngine`、`LabelTables`、`DistinctMds`、`LabelRenderer`、`FrameContext`、`compat/EmiCompat`、`mixin/TankWidgetMixin`、`tools/gen_labels.py`、`tools/anchors.json`、`tools/gen-report.md`、`src/main/resources/fluidlabel_labels.json`。
2. **改**（只许改这些已存在文件）：`FluidLabelMod.java`（加配置注册 + EMI 缺失短路）、`mixin/FluidEmiStackMixin.java`（空壳 → 真实调用）、`fluidlabel.mixins.json`（`client` 加 `TankWidgetMixin`）、`META-INF/mods.toml`（如需）。
3. **方法签名契约（逐字一致）**
   ```java
   // com.fluidlabel.FluidLabelConfig
   public static final ForgeConfigSpec SPEC;
   public static boolean enabled();
   public static Mode mode();                    // enum Mode { STABLE, DYNAMIC }
   public static float scale();
   public static int maxChars();
   public static int maxLines();
   public static boolean autoContrast();
   public static boolean outline();
   public static boolean backdrop();
   public static boolean showOnHoverOnly();
   public static boolean avoidAmountText();
   public static boolean tankWidgets();
   public static java.nio.file.Path aliasesFile();

   // com.fluidlabel.LabelRenderer        ← 被两个 Mixin 调用
   public static void drawOverlay(net.minecraft.client.gui.GuiGraphics guiGraphics,
                                  Object emiStack, int x, int y, int w, int h, boolean amountShown);
   public static void drawOverlay(net.minecraft.client.gui.GuiGraphics guiGraphics,
                                  dev.emi.emi.api.widget.Bounds bounds);   // TankWidget 用

   // com.fluidlabel.LabelEngine
   public static String labelFor(net.minecraft.resources.ResourceLocation id,
                                 net.minecraft.network.chat.Component displayName);
   public static void invalidateCache();

   // com.fluidlabel.FrameContext          ← DYNAMIC 档
   public static void observed(net.minecraft.resources.ResourceLocation id);
   public static void endFrame();
   public static java.util.Set<net.minecraft.resources.ResourceLocation> currentFrame();

   // com.fluidlabel.LabelTables
   public static void reload();
   ```
4. **必须实现的防御**（否则 A2/A6/A7 会挂）
   - `drawOverlay` 第一行 `if (!FluidLabelConfig.enabled()) return;`
   - `emiStack` **必须**先 `instanceof FluidEmiStack` 判定（注入点 1 的类型是擦除后的真实类，但保持防御）；非液体 → return。
   - 所有 `dev.emi.*` 类型引用**只允许**出现在 `mixin/` 与 `compat/EmiCompat.java` 里；其余类不得 import EMI（缺 EMI 时只有 Mixin 层受影响）。
   - 两个 `@Mixin` 都要 `remap = false`；所有 `@Inject` 都要 `require = 0`。
   - **不要**调用 `EmiIngredient#getEmiStacks()` 之类会分配对象的方法在每帧热路径上做多余工作。
5. **算法实现**：`DistinctMds.java` 移植 `_gtodata/analyze8.py` 的 v8 规则（两两互异 + 子序列候选 + 语义分层 T1–T5），并以 `report8.md` 统计作回归基线（≤2 字 99.9% / ≤3 字 100%）。
6. **产物校验**：`byDisplayName` ≥ 1900 且 `conflicts == 0`。

### 9.3 给 D（审查 + 构建）
1. 按 ②.2 改 `build.gradle`（**唯一有权改的人**）：加 `compileOnly files("libs/emi-1.1.24+1.20.1+forge.jar")`、`jar.manifest` 的 `MixinConfigs`。
2. 按 ⑦ 的 M1 跑 `mc_gradle`（`clean` → `build --stacktrace` → `jar`），超时按 ⑦。
3. 校验产物：`build/libs/*.jar` 内含 `fluidlabel.mixins.json`、`META-INF/mods.toml`、两个 mixin class；`unzip -p` 看 manifest 有 `MixinConfigs`；**确认 EMI 的类没有被打进产物**。
4. 修复小错误（编译错误、明显笔误）；**架构性改动**（换注入点、改算法判据）必须回到 PLAN，不得就地改。
5. 回报：验证报告（构建输出、产物内容、失败项与修法）。

## ⑩ §6 —— AE2 侧液体标签（第二阶段：AE2 全部界面 + GT 的 ME 部件界面）

> **编号说明**：本文件顶层已有 ⑥（配置项表），故本阶段排为第 10 个顶层章节，内部小节按委派要求写 §6.1–§6.8。
> **合并状态**：已由 Lead 追加到本文件末尾（原片段 `fluid-label-PLAN-6-AE2.md` 可删）。§6.2.3 的 javap 复核清单已由 Lead 用本机 shell 执行，结论见 **§6.2.4**。
> **范围**：只覆盖 **AE2 界面** 与 **GT 的 ME 部件界面**；EMI/JEI 侧已完成（§④），本阶段**不得改动其行为**。

---

### 6.0 证据来源与"未能复现"声明（先说清楚哪些是实测、哪些是源码、哪些待核实）

| 结论 | 来源 | 等级 |
| --- | --- | --- |
| `appeng/api/client/AEKeyRendering#drawInGui(Minecraft, GuiGraphics, int, int, AEKey)`、`getDisplayName(AEKey)`、`getTooltip(AEKey)`、`getOrThrow(AEKeyType)`、`register(...)` 存在 | 【宿主 javap】委派 m00001 第 3 条 + 本轮【源码 forge/v15.4.11】逐条对上 | 已复核 |
| `AEFluidKey`：`is(AEKey)` / `of(FluidStack)` / `getFluid()` / `toStack(int)` / `getId()` | 【宿主 javap】第 2 条 + 本轮【源码 forge/v15.4.11】 | 已复核 |
| `InitStackRenderHandlers$FluidKeyRenderHandler.drawInGui` = `Blitter.dest(x,y,16,16)` ⇒ 图标 = (x,y) 处 16×16 | 【宿主 javap】第 4 条（本轮**无法复现**：无 shell） | 采信宿主 |
| GTO 的 `gto_ae/hooks/gui/menu/IRepoSlot#renderSlot` 字节码含 `invokestatic appeng/api/client/AEKeyRendering.drawInGui` ⇒ ME 终端网格走该调度点 | 【宿主 javap】第 5 条（本轮**无法复现**） | 采信宿主 |
| 另有 12 个 AE2 类引用 `AEKeyRendering`（`ContentToast`/`FinishedJobToast`/`PendingCraftingJobs`/`AbstractTableRenderer`/`CraftConfirmTableRenderer`/`CraftingStatusTableRenderer`/`NetworkStatusScreen`/`InfoBar$StackWidget`/`StorageCellClientTooltipComponent`/`GuiGraphicsHooks`/`gto_ae…FacilityManagementScreen`） | 【宿主 javap】第 6 条 | 采信宿主 |
| GT 侧类 FQCN：`com/gregtechceu/gtceu/integration/ae2/gui/widget/list/{AEFluidDisplayWidget, AEItemDisplayWidget, AEListGridWidget, AEListGridWidget$Fluid, AEListGridWidget$Item}` | 本工作区 `_gtodata\_gtceu_entries.txt:8970-8997`（jar 条目清单，**逐字**） | 已复核 |
| `AEFluidDisplayWidget#drawInBackground` 在 `(position.x+1, position.y+1)` 画 16×16 流体图标 | 【源码 GTCEu tag `1.20.1-1.4.5-build_1169`】URL 见 6.2.1 | 已复核（上游线） |
| 整合包内 AE2=**15.267.4（GTO 补丁）**、GTCEu=**26.7.3（GTO 补丁）** | `_gtocore_entries.txt:8-15`（jarJar 内嵌清单） | 已复核 |
| 上述两个补丁版**是否仍与上游同形**（尤其 RETURN 数量、`stackX/stackY` 计算） | 本轮无 shell，**无法验证** | **待核实 → §6.2.3 的 D 复核清单** |

**网络通道（给后续复查用）**：`raw.githubusercontent.com` 在本机 `fetch failed`；`github.com` 当日 HTTP 503；**唯一成功通道 = 加前缀 `https://ghproxy.net/`**，例如
`https://ghproxy.net/https://raw.githubusercontent.com/AppliedEnergistics/Applied-Energistics-2/forge/v15.4.11/src/main/java/appeng/api/client/AEKeyRendering.java`
（`cdn.jsdelivr.net/gh/<owner>/<repo>@<tag>/<path>` 亦可，但必须用**正确 tag**：AE2 forge 线是 `forge/v15.4.11` 这类，GTCEu 1.20.1 线是 `1.20.1-1.4.5-build_1169` 这类；tag 列表来自 `https://data.jsdelivr.com/v1/packages/gh/<owner>/<repo>`。分支名 `1.20.1`/`1.20.x` 均 404。）
AE2 官方 javadoc 站点（`https://appliedenergistics.org/javadoc/`）**只有最新版**，且 26.x 已把包名迁到 `appeng.client.api.AEKeyRendering`（[javadoc](https://appliedenergistics.org/javadoc/appeng/client/api/AEKeyRendering.html)）⇒ **不可用于 1.20.1 口径**，只能当跨版本对照。

---

### 6.1 目标与范围

1. **目标**：把已完成的 EMI 侧中文液体标签，叠加到 **AE2 界面里的液体图标**与 **GT 的 ME 部件界面（低代码 GUI）里的液体图标**上，使玩家在 ME 网络上也能不靠颜色分辨 GT 的 1900 种液体。
2. **覆盖两类界面**（用户确认，不得扩大/缩小）：
   - 类 A = **AE2 界面**：ME 存储终端 / 合成终端 / 样板与编码终端 / 合成确认表 / 网络状态 / 信息条 / 内容 Toast / 存储元件 tooltip / GTO `gto_ae` 设施管理界面；
   - 类 B = **GT 的 ME 部件界面**：ME 流体仓 / 库存总线 / 输出仓等由 LDLib 绘制、**不走 AE2 调度器**的界面。
3. **与 EMI 侧完全一致（硬约束）**：
   - 复用同一份 `fluidlabel_labels.json` + `fluidlabel_anchors.json`、同一个 `LabelEngine.labelFor(...)` 入口、同一套 `DistinctMds` 规则（含 §5.4 状态词缀/保牌号、§5.5 码点预算、§5.2.1 截断救援）；
   - 复用同一个 `LabelRenderer` 绘制段（缩放/描边/对比度/换行/z 序/状态复原）；
   - 同一个预算 `maxChars(3) × maxLines(2) = 6 码点`；
   - **不新增任何配置项**（`⑥` 表保持原样；总开关仍是 `enabled`）。
4. **软依赖**：AE2 / GTCEu / LDLib 任一缺失 ⇒ **安静失效**：不崩、不 `ClassNotFoundException`、不 `NoClassDefFoundError`、日志最多一行 INFO/WARN。
5. **不改 EMI 侧行为**：`FluidEmiStackMixin` / `TankWidgetMixin` 的现有路径（含 §⓪.1 的 `RETURN` 修复）**逐字保持**；新增的 AE2 路径与 EMI 路径通过**不同的公开入口**进入 `LabelRenderer`。
6. **覆盖范围外**：不改 AE2 / GTCEu / GTOCore / LDLib 的任何代码与资源；不加网络包；不加内容（item/block/fluid）；不在世界内绘制（`drawOnBlockFace` 不做）。

---

### 6.2 注入点清单（FQCN + 签名 + 几何来源 + 覆盖界面 + mixin 目标写法）

| # | 目标 FQCN | 方法签名（逐字） | 几何来源 | 覆盖界面 | mixin 目标类名写法 | 等级 |
| --- | --- | --- | --- | --- | --- | --- |
| **3** | `appeng.api.client.AEKeyRendering` | `public static void drawInGui(net.minecraft.client.Minecraft, net.minecraft.client.gui.GuiGraphics, int, int, appeng.api.stacks.AEKey)` | 调用者传进来的 `(x, y)` 即**图标左上角**；`FluidKeyRenderHandler.drawInGui` 在同处 `Blitter.dest(x,y,16,16)`（宿主 javap） | 类 A 全部（ME 网格经 `gto_ae IRepoSlot.renderSlot`、合成表、网络工具、信息条、Toast、存储元件 tooltip、设施管理屏） | `appeng.api.client.AEKeyRendering`（顶层类） | 签名已复核；覆盖广度采信宿主 |
| **4** | `com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget` | `public void drawInBackground(net.minecraft.client.gui.GuiGraphics, int, int, float)`（参数是 mouseX/mouseY/partialTicks，**不是坐标**） | 源码实证：`int stackX = position.x + 1; int stackY = position.y + 1;` 且同处 `DrawerHelper.drawFluidForGui(graphics, fluidStack, fluid.amount(), stackX, stackY, 16, 16)` ⇒ **图标 = `(position.x+1, position.y+1)` 的 16×16**（外层槽位 18×18） | 类 B：GT ME 流体仓/库存总线/输出仓等 LDLib ME 界面 | `com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget` | 上游线已复核；**补丁版待 D 复核** |
| **3'/4'（备选，非必需）** | `…AEListGridWidget$Fluid`（内部类 ⇒ 写法必须带 `$`）；GTO 的 `gto_ae.client.gui.widgets.AESlotWidget`、`gto_ae.client.gui.slot.FixedRepoSlot` | 待 javap | 待 javap | 仅当探针显示"某界面无标签"时作为补点 | 除 `$` 外与上同 | 待核实 |

#### 6.2.1 关键源码摘录（可直接引用的实证）

AE2（tag `forge/v15.4.11`，`appeng/api/client/AEKeyRendering.java`）：
```java
@OnlyIn(Dist.CLIENT)
public class AEKeyRendering {
    public static void drawInGui(Minecraft minecraft, GuiGraphics guiGraphics, int x, int y, AEKey what) {
        getUnchecked(what).drawInGui(minecraft, guiGraphics, x, y, what);
    }
    public static Component getDisplayName(AEKey stack) { return getUnchecked(stack).getDisplayName(stack); }
    public static List<Component> getTooltip(AEKey stack) { … new ArrayList<Component>(…) … }
    public static AEKeyRenderHandler<?> getOrThrow(AEKeyType channel) { … }
    public static synchronized <T extends AEKey> void register(AEKeyType channel, Class<T> keyClass, AEKeyRenderHandler<T> handler)
    public static void drawOnBlockFace(PoseStack, MultiBufferSource, AEKey, float scale, int combinedLightIn, Level)  // 世界内，本阶段不用
}
```
AE2（同 tag，`appeng/api/stacks/AEFluidKey.java`）：
```java
public final class AEFluidKey extends AEKey {
    public static AEFluidKey of(Fluid fluid, @Nullable CompoundTag tag)
    public static AEFluidKey of(Fluid fluid)
    @Nullable public static AEFluidKey of(FluidStack fluidVariant)   // ← 空栈返回 null！
    public static boolean is(AEKey what)                             // ← 本方案用它判键型
    public static boolean is(@Nullable GenericStack stack)           //   另一个重载，勿混用
    public boolean matches(FluidStack variant)
    public FluidStack toStack(int amount)
    public Fluid getFluid()                                          // ← 返回 net.minecraft.world.level.material.Fluid
    @Nullable public CompoundTag getTag()
    @Override public ResourceLocation getId() { return BuiltInRegistries.FLUID.getKey(fluid); }   // ← 直接就是注册名！
    @Override protected Component computeDisplayName() { return Platform.getFluidDisplayName(fluid, tag); }
    public static final int AMOUNT_BUCKET = 1000;
}
```
> ⚠ 上游 15.4.11 里**没有** `getReadOnlyStack()`（宿主的 javap 提到过）——它属更晚版本或补丁版；**本方案不依赖它**，只用 `getId()` + `getFluid()` + `toStack(int)`。

GT（tag `1.20.1-1.4.5-build_1169`，`com/gregtechceu/gtceu/integration/ae2/gui/widget/list/AEFluidDisplayWidget.java`）：
```java
public class AEFluidDisplayWidget extends Widget {          // com.lowdragmc.lowdraglib.gui.widget.Widget
    private final AEListGridWidget gridWidget;  private final int index;
    public AEFluidDisplayWidget(int x, int y, AEListGridWidget gridWidget, int index) {
        super(new Position(x, y), new Size(18, 18));
    }
    @Override public void drawInBackground(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        super.drawInBackground(graphics, mouseX, mouseY, partialTicks);
        Position position = getPosition();
        GenericStack fluid = this.gridWidget.getAt(this.index);
        GuiTextures.FLUID_SLOT.draw(graphics, mouseX, mouseY, position.x, position.y, 18, 18);
        GuiTextures.NUMBER_BACKGROUND.draw(graphics, mouseX, mouseY, position.x + 18, position.y, 140, 18);
        int stackX = position.x + 1;  int stackY = position.y + 1;
        if (fluid != null) {
            FluidStack fluidStack = fluid.what() instanceof AEFluidKey key
                    ? FluidStack.create(key.getFluid(), fluid.amount(), key.getTag()) : FluidStack.empty();
            DrawerHelper.drawFluidForGui(graphics, fluidStack, fluid.amount(), stackX, stackY, 16, 16);
            // 数量文字画在 stackX + 20（图标右侧），不是图标内部
        }
        if (isMouseOverElement(mouseX, mouseY)) drawSelectionOverlay(graphics, stackX, stackY, 16, 16);
    }
    @Override public void drawInForeground(…) { … tooltip … }
}
```

#### 6.2.2 mixin 目标写法（`remap = false`；类名区分大小写；内部类用 `$`）

- 注入点 3：`@Mixin(value = appeng.api.client.AEKeyRendering.class, remap = false)`
  `method = "drawInGui(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/GuiGraphics;IILappeng/api/stacks/AEKey;)V"`
- 注入点 4：`@Mixin(value = com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget.class, remap = false)`
  `method = "drawInBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V"`
- 备选（仅 javap 判明需要时）：`…AEListGridWidget$Fluid`（**内部类必须写 `$`**）、`gto_ae.client.gui.widgets.AESlotWidget`。
- **`at` 一律用 `@At("RETURN")`，禁止 `TAIL`**（§⓪.1 的教训：`TAIL` 只匹配最后一个 RETURN，多出口方法会静默漏掉真实路径）。每个 `@Inject` 都 `require = 0`。

```java
// src/main/java/com/fluidlabel/mixin/AeKeyRenderingMixin.java（注入点 3，示意）
@Mixin(value = appeng.api.client.AEKeyRendering.class, remap = false)
public abstract class AeKeyRenderingMixin {
    @Inject(method = "drawInGui(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/GuiGraphics;IILappeng/api/stacks/AEKey;)V",
            at = @At("RETURN"), require = 0)
    private void fluidlabel$overlayAe2(net.minecraft.client.Minecraft mc, net.minecraft.client.gui.GuiGraphics g,
                                       int x, int y, appeng.api.stacks.AEKey key,
                                       org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        InjectionProbe.fired(InjectionProbe.SITE_AE2_DRAW_IN_GUI, "AEKeyRendering#drawInGui");
        com.fluidlabel.LabelRenderer.drawOverlayAe2(g, key, x, y, 16, 16, false);
    }
}
```
```java
// src/main/java/com/fluidlabel/mixin/GtAeFluidDisplayMixin.java（注入点 4，示意）
// ★ Lead 实测修正（见 §6.2.4）：LDLib 的 `Widget` **没有无参构造器**（只有 `(Position,Size)` 与 `(int,int,int,int)`），
//   因此 `extends Widget` + javac 隐式默认构造器会**直接编译失败**（constructor Widget cannot be applied to given types）。
//   ⇒ 改为**不声明父类**的普通类 mixin：`@Shadow` 只针对目标类自身字段（`gridWidget`/`index` 就声明在 AEFluidDisplayWidget 里），无需父类；
//      取几何用一次编译期合法、运行期必然成功的向下转型：`Widget self = (Widget)(Object) this;`
@Mixin(value = com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget.class, remap = false)
public class GtAeFluidDisplayMixin {
    @Shadow @Final private com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEListGridWidget gridWidget;
    @Shadow @Final private int index;

    @Inject(method = "drawInBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("RETURN"), require = 0)
    private void fluidlabel$overlayGt(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float pt,
                                      org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        InjectionProbe.fired(InjectionProbe.SITE_GT_FLUID_DISPLAY, "GT AEFluidDisplayWidget#drawInBackground");
        appeng.api.stacks.GenericStack s = this.gridWidget.getAt(this.index);
        if (s == null || !appeng.api.stacks.AEFluidKey.is(s.what())) return;   // 只处理流体键
        com.lowdragmc.lowdraglib.utils.Position p = ((com.lowdragmc.lowdraglib.gui.widget.Widget)(Object) this).getPosition();
        com.fluidlabel.LabelRenderer.drawOverlayAe2(g, s.what(), p.x + 1, p.y + 1, 16, 16, false);
    }
}
```
> **偏移量 `+1` 必须与 6.2.1 的 `stackX/stackY` 逐字一致** —— 这是"标签与图标处在同一 pose、同一坐标系"的唯一保证（不依赖 GUI 缩放/组平移）。
> `AEFluidDisplayWidget` 的两个字段是 `private final`：`@Shadow` 只读不写，合法。若补丁版改了字段名/类型 ⇒ 注入失败（require=0 静默）⇒ 探针会暴露（§6.7）。

#### 6.2.3 D **必须**执行的 javap 复核清单（本轮无 shell，无法完成）

```bat
set JDK="D:\Program Files (x86)\jdk17\bin\javap.exe"
%JDK% -p -c -cp "fluid-label\libs\appliedenergistics2-forge-1.20.1-15.267.4.jar" appeng.api.client.AEKeyRendering
%JDK% -p     -cp "fluid-label\libs\appliedenergistics2-forge-1.20.1-15.267.4.jar" appeng.api.stacks.AEFluidKey
%JDK% -p -c -cp "fluid-label\libs\appliedenergistics2-forge-1.20.1-15.267.4.jar" "appeng.init.client.InitStackRenderHandlers$FluidKeyRenderHandler"
%JDK% -p -c -cp "fluid-label\libs\gtceu-1.20.1-forge-1.20.1-26.7.3.jar" com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget
%JDK% -p -c -cp "fluid-label\libs\gtceu-1.20.1-forge-1.20.1-26.7.3.jar" "com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEListGridWidget$Fluid"
:: GTO 的 gto_ae/** 在 jarJar 内嵌 jar 里 ⇒ 先抽出 GTOCore 的 META-INF/jarjar/appliedenergistics2-forge-1.20.1-15.267.4.jar 再查
%JDK% -p -c -cp "<抽出的 ae2 补丁 jar>" gto_ae.hooks.gui.menu.IRepoSlot
%JDK% -p -c -cp "<抽出的 ae2 补丁 jar>" gto_ae.client.gui.widgets.AESlotWidget
%JDK% -p -c -cp "<抽出的 ae2 补丁 jar>" gto_ae.client.gui.slot.FixedRepoSlot
```
判定口径：① `AEKeyRendering#drawInGui` 描述符与 6.2.2 **逐字符**一致；② `AEFluidDisplayWidget#drawInBackground` 的 RETURN 个数（≥1 即可，一律用 `RETURN`）；③ `stackX/stackY` 是否仍是 `position.x+1` / `position.y+1`；④ `FluidKeyRenderHandler.drawInGui` 是否仍是 `Blitter.dest(x,y,16,16)`（决定 z 序与 16×16 前提）；⑤ `gto_ae` 是否仍 `invokestatic AEKeyRendering.drawInGui`（决定类 A 覆盖广度）。

#### 6.2.4 Lead 的 javap 复核结果（**已执行**，2026-10-02，JDK 17，对本机 `libs/` 内补丁版 jar 实测）

| 判定 | 结果 | 实测证据（javap 输出逐字） |
| --- | --- | --- |
| ① 注入点 3 描述符 | **✅ 逐字符一致** | `public static void drawInGui(net.minecraft.client.Minecraft, net.minecraft.client.gui.GuiGraphics, int, int, appeng.api.stacks.AEKey);`（`appeng/api/client/AEKeyRendering.class`） |
| ① 辅助方法 | ✅ 均在 | `register` / `get` / `getOrThrow` / `getDisplayName` / `getTooltip` / `drawOnBlockFace`；私有 `static getUnchecked(AEKey)`；字段 `private static volatile Map<AEKeyType, AEKeyRenderHandler<?>> renderers` |
| ② 注入点 4 方法 | **✅ 存在**，单出口 | `public void drawInBackground(net.minecraft.client.gui.GuiGraphics, int, int, float);`（补丁版 GTCEu 26.7.3） |
| ③ 几何偏移 | **✅ 即 `+1`** | 字节码：`82: aload 5 / 84: getfield Position.x / 87: iconst_1 / 88: iadd / 89: istore 7`、`91: aload 5 / 93: getfield Position.y / 96: iconst_1 / 97: iadd / 98: istore 8` ⇒ `stackX = position.x+1`、`stackY = position.y+1` |
| ③ 键型判断 | ✅ 同源 | `107: GenericStack.what()` → `114: instanceof appeng/api/stacks/AEFluidKey` ⇒ 与方案的 `AEFluidKey.is(...)` 等价 |
| ③ 构造与字段 | **✅ 与 `@Shadow` 一致** | `super(new Position(x,y), new Size(18,18))`；`putfield gridWidget:L…AEListGridWidget;`、`putfield index:I`；`AEListGridWidget.getAt(int):GenericStack`、`static drawSelectionOverlay(GuiGraphics,int,int,int,int)` |
| ④ 图标几何 | **✅ 16×16** | `FluidKeyRenderHandler.drawInGui`：`FluidBlitter.create(AEFluidKey)` → `bipush 16 / bipush 16` → `Blitter.dest(IIII)` → `Blitter.blit(GuiGraphics)` |
| ⑤ `gto_ae` 覆盖路径 | **✅ 存在** | `IRepoSlot`（interface）确有 `public default void renderSlot(GuiGraphics, appeng.client.gui.me.common.Repo, boolean, boolean)`；`gto_ae.client.gui.widgets.AESlotWidget implements appeng.client.gui.ICompositeWidget`；`gto_ae.client.gui.slot.FixedRepoSlot extends ClientReadOnlySlot implements IRepoSlot, IDecoratedSlot, IDraggableSlot` |
| `AEFluidKey` 成员 | **✅ 含 `getReadOnlyStack()`** | `of(Fluid)` / `of(Fluid, CompoundTag)` / `of(FluidStack)` / `is(AEKey)` 与 `is(GenericStack)` / `getId():ResourceLocation` / `getFluid()` / `getTag()` / `toStack(int)` / `getReadOnlyStack()` / `protected computeDisplayName()` / `public static final int AMOUNT_BUCKET` |
| `libs/ldlib-forge-1.20.1-1.0.50.jar` | **✅ 已拷入** | 3,114,158 B（源：实例 `mods\`，同名同大小） |

⇒ **R-AE2-1 / R-AE2-2 / R-AE2-7 的前提全部落实**：注入点 3/4 的方法签名与几何与方案逐字相符，**无需改架构**；`require = 0` 仍保留作为版本漂移保险。R-AE2-9（探针数组扩容）仍由 C 执行。

---

### 6.3 标签来源与解析路径（与 EMI 侧同源，零新增规则）

```
appeng.api.stacks.AEKey key
 ├─ key instanceof AEFluidKey ?  （用 AEFluidKey.is(AEKey) 判，非液体键一律 return）
 ├─ id   = ((AEFluidKey) key).getId()             → ResourceLocation
 │        = BuiltInRegistries.FLUID.getKey(fluid)   ← 与 EMI 侧 FluidEmiStack#getId() 同源（同一个流体注册名）
 ├─ name = AEKeyRendering.getDisplayName(key)     → Component
 │        ⇒ AEKeyRenderHandler(Fluid)#getDisplayName ⇒ Platform.getFluidDisplayName(fluid, tag)
 │        ← 与 EMI 侧 FluidEmiStack#getName() 同源（同一个 fluid 本地化名 + GT 的 lang 模板）
 └─ LabelEngine.labelFor(id, name)                ← **与 EMI 侧同一个公开入口**
        ① 别名表 config/fluidlabel-aliases.json > ② 内置表 byRegistryName→byDisplayName > ③ 运行时 MDS 兜底
        （DYNAMIC 档先查 FrameContext 已发布快照；STABLE 档查预计算表 + 冻结池兜底）
```
- **派生名 `熔融高速钢-G` 如何命中已有规则**：这个名字是 GT 用 lang 模板（`gtceu.fluid.molten = 熔融%s`）在运行期拼出来的，**不在 1898 条预计算表里** ⇒ 查表 miss ⇒ 走 §5.4 落地的**状态词缀组合层 + 保牌号档**：`STATE_PREFIX 熔融→熔融/熔` + `gradeCandidates` ⇒ `熔高速钢-G`（与 EMI 侧结果**逐字相同**，因为规则住在 `DistinctMds` 里、与"从哪个界面来"无关）。AE2 侧**不需要任何新规则**。
- **DYNAMIC 档一致性**：AE2 路径也必须在热路径调 `FrameContext.observed(id)`（EMI 侧 `LabelRenderer.drawSlot` 已调；新增的 `drawOverlayAe2` 同样要调）⇒ AE2 屏与 EMI 侧栏同帧共存时，两边标签在**同一个池**里互异。
- **缓存（§5.6 纪律）**：`AEKeyRendering.getDisplayName` 每帧每槽调用会分配 `Component`，`LabelEngine.labelFor` 内部还会 `ResourceLocation#toString()` 分配字符串 ⇒ 新增 `Ae2Compat` 持 `ConcurrentHashMap<AEFluidKey, String>`（`AEFluidKey` 已实现 `equals/hashCode` 且 `hashCode` 在构造期算好，适合做键），上限 4096、超限整体清空（与 `LabelRenderer.LAYOUTS` 同策略）；`enabled=false` 时第一行返回，不触缓存。
- **`byRegistryName` 命中率**：AE2 侧 id 来自 `BuiltInRegistries.FLUID`，与生成器采集的 `gtceu:…` / `gtocore:…` 注册名同族 ⇒ 主索引应当高命中；`byDisplayName` 作为第二索引；miss 仍有兜底规则（功能不会失效）。

---

### 6.4 渲染实现与"不重复绘制"

**新公开入口（`LabelRenderer`，签名钉死给 C）**
```java
public static void drawOverlayAe2(net.minecraft.client.gui.GuiGraphics guiGraphics,
                                  Object aeKey, int x, int y, int w, int h, boolean amountShown);
```
实现要点（**复用现有绘制段**，EMI 路径行为不变）：
1. 第一行 `if (!FluidLabelConfig.enabled()) return;`（A5 短路，与 EMI 侧一致）；
2. `if (guiGraphics == null || !Ae2Compat.isFluidKey(aeKey)) return;`（**只对液体键画**：AE2 的物品键是 `AEItemKey`、GT 侧 `GenericStack.what()` 还可能是物品键）；
3. **去重 memo（O(1)、无分配）**：记录 `(FrameContext.epoch(), x, y, key)`，与上一次相同则直接 return。理由：唯一现实的重复绘制形态是"同一次调用栈里对同一个图标的重复 `drawInGui`"（外层 wrapper + 内层实现）；两个不同图标不可能落在同一像素，所以单条 memo 就能拦住，且**不会**误伤"同一液体出现在不同槽位"（坐标不同）。
4. `String label = Ae2Compat.labelOf(aeKey);` → `if (label.isEmpty()) return;`
5. 把 `LabelRenderer#drawSlot` 的绘制段（`layoutOf` → `pushPose` → `translate(0,0,Z_ABOVE_FLUID=150)` → `translate(drawX,drawY)` → `scale(scale)` → 8 向描边 → `popPose` + `setShaderColor(1,1,1,1)`）抽成 `private static void drawLabel(GuiGraphics, String label, int tint, int x, int y, int w, int h, boolean amountShown, boolean preferTop)`，由 EMI 的 `drawSlot(...)` 与新入口**共同调用**（纯重构，不改 EMI 输出）。
6. **对比度**：`autoContrast` 需要 tint。EMI 侧用 `EmiStack#getTint()`；AE2 侧建议 `net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions.of(fluidStack).getTintColor()`（Forge 1.20.1 常规做法，**待核实**）——取不到/抛异常一律退化 `0xFFFFFFFF`（⇒ 浅色字 + 黑描边，安全默认）。
7. **16×16 内切行**：`maxChars=3 × maxLines=2`，`Layout` 按码点切行（现有 `buildLayout` 直接复用，不新建布局代码）。
8. **不避让数量文字**：AE2 的 `FluidKeyRenderHandler` 不在图标内画数量；GT 侧数量文字在 `stackX + 20`（图标**右侧**）⇒ 两处都传 `amountShown = false`（标签居中即可）。
9. **z 序**：AE2 图标经 `Blitter`（宿主 javap）绘制、GT 图标经 `DrawerHelper.drawFluidForGui` 绘制，都在调用者 pose 空间 ⇒ 沿用 `Z_ABOVE_FLUID = 150`；实测被盖住就调大常量（不改结构）。
10. **状态复原**：`pushPose/popPose` 成对 + 结束 `RenderSystem.setShaderColor(1,1,1,1)`（沿用现有 finally 块，不引入 `enableScissor`）。

**重复绘制防护的三层**
| 层 | 手段 | 防的是什么 |
| --- | --- | --- |
| ① | `AEFluidKey.is(key)` 键型过滤 | 物品键/其它键被顺手画上液体标签 |
| ② | `(epoch, x, y, key)` memo | 同一图标被两条路径各画一次（wrapper + 实现、或兼容层叠加） |
| ③ | 不引入第二个"整屏级"钩子（不 hook LDLib `Widget#draw`、不 hook `DrawerHelper`） | 从源头避免与注入点 3 交叉覆盖 |

> 跨界面（AE2 屏 + EMI 侧栏）本来就是**不同坐标**，不需要互相去重；若后续发现 AE2 的 EMI 插件把同一批 key 也交给 `FluidEmiStack` 渲染（R-AE2-5），用 ② 的 memo 即可，不必改架构。

---

### 6.5 依赖接线与软依赖（**build.gradle 只有 D 能改**）

```groovy
dependencies {
    // EMI：只编译期可见（沿用现状）
    compileOnly files("libs/emi-1.1.24+1.20.1+forge.jar")
    // ★ 第二阶段新增：AE2（GTO 补丁版，从 jarJar 抽出）、GTCEu、LDLib（GT GUI 框架；注入点 4 的目标父类所在库）
    compileOnly files("libs/appliedenergistics2-forge-1.20.1-15.267.4.jar")
    compileOnly files("libs/gtceu-1.20.1-forge-1.20.1-26.7.3.jar")
    compileOnly files("libs/ldlib-forge-1.20.1-1.0.50.jar")     // ← 实测存在：实例 mods\ldlib-forge-1.20.1-1.0.50.jar
}
```
- **必须拷进 `libs/`**：`ldlib-forge-1.20.1-1.0.50.jar`（角色：B；源路径 `…\GregTech.Odyssey-0.5.6-beta\minecraft\mods\ldlib-forge-1.20.1-1.0.50.jar`）。理由：`AEFluidDisplayWidget extends com.lowdragmc.lowdraglib.gui.widget.Widget`，**javac 解析父类需要它**，否则编译注入点 4 会 `cannot access Widget`。`gtceu`/`gtolib`/`gtmthings`/`commons-math3`/`fastcollection` 已在 `libs/`；若 javac 仍报缺类，按报错逐个补拷（**不要**为此装 maven 依赖）。
- **不新增运行时依赖**：三颗 jar 一律 `compileOnly`，**绝不** `runtimeOnly`、**绝不**打进产物（运行时由整合包提供）。
- **不要走 maven**：AE2 确实在 Maven Central（上游 README 实证：`compileOnly "org.appliedenergistics:appliedenergistics2:VERSION:api"`），但那是 **API 子集**、且整合包内是 GTO 补丁版 ⇒ 与 EMI 同一策略：用 `libs/` 本地 jar，顺带规避本机网络不稳。
- **运行时软依赖判定**：`FluidLabelMod` 里
  ```java
  AE2_PRESENT   = ModList.get().isLoaded("ae2")   || classPresent("appeng.api.client.AEKeyRendering");
  GTCEU_PRESENT = ModList.get().isLoaded("gtceu") || classPresent("com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget");
  ```
  `classPresent` = `Class.forName(name, false, FluidLabelMod.class.getClassLoader())` 包 try/catch（jarJar 内嵌的 mod 仍会注册自己的 modId，但双保险更稳）。
- **mixin 策略**：`fluidlabel.mixins.json` 的 `"client"` 追加两个新 mixin；`"required": false` **不变**；每个 `@Inject` `require = 0` **不变** ⇒ 目标类缺失时 Mixin 只 WARN，不崩（A6 语义在 AE2/GTCEu 上同样成立）。
- **类引用隔离（沿用 §0.4 的 jar 校验法）**：`appeng.*` / `com.gregtechceu.*` / `com.lowdragmc.*` 类型**只允许**出现在 `mixin/`、`compat/Ae2Compat.java`、`Ae2SelfCheck.java` 里；`FluidLabelMod.class` 常量池**必须**不含 `appeng/`、`gregtechceu/`、`lowdragmc/`（校验：`javap -p -c -cp build\libs\fluid-label-*.jar com.fluidlabel.FluidLabelMod | findstr /C:"appeng/" /C:"gregtechceu/" /C:"lowdragmc/"`，应无输出）。
- **`mods.toml` 追加两条软依赖**（`mandatory=false`，绝不写 `clientSideOnly`）：
  ```toml
  [[dependencies.fluidlabel]]
    modId="ae2"
    mandatory=false
    versionRange="[15,)"
    ordering="AFTER"
    side="CLIENT"

  [[dependencies.fluidlabel]]
    modId="gtceu"
    mandatory=false
    versionRange="[1.0,)"      # 注意：整合包内是 GTO 补丁版 26.7.3，别写上限
    ordering="AFTER"
    side="CLIENT"
  ```
  （`versionRange` 具体写法为**待核实**项：Forge 47.4.x 若报解析错，改 `"*"` 或只放下限。）

---

### 6.6 风险与不确定项

| # | 风险 | 影响 | 等级 | 缓解 / 验证 |
| --- | --- | --- | --- | --- |
| **R-AE2-1** | 整合包内 AE2 是 **GTO 补丁版 15.267.4**，本轮只核实了上游 `forge/v15.4.11` ⇒ `drawInGui` 可能被改签名/被搬迁 | 注入点 3 失配 ⇒ 类 A 无标签（静默） | 中 | `require=0` + 探针（§6.7）+ `Ae2SelfCheck` 反射点名 + **§6.2.3 的 D javap 复核**；若描述符不同 ⇒ 按 javap 结果改描述符（架构不变） |
| **R-AE2-2** | 整合包内 GTCEu 是 **26.7.3（GTO 补丁）**，上游核实的 `1.4.5` 几何可能被改（`stackX/stackY`、方法名、父类） | 注入点 4 失配或标签错位 | 中 | 同上；错位时先核 `stackX/stackY` 的 javap 结果再改偏移 |
| **R-AE2-3** | `drawInGui` 也在 **tooltip / Toast / 信息条**场景被调用（`StorageCellClientTooltipComponent`、`ContentToast`、`InfoBar$StackWidget`） | 标签画到 tooltip 内的小图标上，可能与 tooltip 文字挤在一起 | 中 | 先按现状上（那些地方也是 16×16 图标，语义不算错）；实机若不可接受 → 给**注入点 3**加 `Screen` 白名单（`AEBaseScreen` 子类 + `gto_ae` 设施屏，类名**待核实**），注入点 4 **不加**过滤（否则杀掉类 B）。仍**不新增配置项** |
| **R-AE2-4** | (x,y) 处实际尺寸在缩放 pose 下不一定是 16×16（例如被 `pose.scale()` 后的图标） | 标签相对图标偏大/偏小 | 低 | 契约来自 `Blitter.dest(x,y,16,16)`（宿主 javap）；实机截图即可判断；不做 pose 反解（复杂度不值） |
| **R-AE2-5** | AE2/EMI 插件若把同一批 key 也交给 `FluidEmiStack` 渲染 | 同一图标双重绘制 | 低 | §6.4 的去重 memo（②）；跨界面坐标不同，无需处理 |
| **R-AE2-6** | `AEFluidKey.of(FluidStack)` 对空栈返回 **null**（上游实证） | 用 `of()` 做转换时 NPE | 低 | 本方案**不用** `of()`，只用 `AEFluidKey.is()` + `getId()`/`getFluid()`；`Ae2Compat` 全部调用包 try/catch |
| **R-AE2-7** | 注入点 4 的 mixin 需要 LDLib 在编译期 classpath | 编译失败 `cannot access Widget` | **高（必踩）** | B 拷 `ldlib-forge-1.20.1-1.0.50.jar` 进 `libs/`，D 加 `compileOnly files(...)`（§6.5） |
| **R-AE2-8** | AE2 与 GT 侧显示名/id 与 EMI 侧是否**逐字**一致 | 若不一致：`byDisplayName` 命中率下降、同一液体在不同界面标签不同 | 中 | 源码层面两者同源（`BuiltInRegistries.FLUID` 注册名 + 同一 fluid 本地化名/GT 模板）；实机用 `dumpRegistry` 导出实况比对；不一致时靠兜底规则 + 人工锚点修正（功能不失效） |
| **R-AE2-9** | 新增探针越界静默：`InjectionProbe.fired()` 对 `site > FIRED.length` **直接 return 且不打日志** | 新注入点"生效了但日志没有"，把排查带进沟里 | **高（必踩）** | C 必须把 `FIRED` 从 `new AtomicBoolean[2]` 扩到 `[4]`，并加 `SITE_AE2_DRAW_IN_GUI = 3`、`SITE_GT_FLUID_DISPLAY = 4`；自检里显式打印两个新探针状态 |
| **R-AE2-10** | 缺 AE2/GTCEu 时类加载失败（NoClassDefFoundError） | 崩游戏（违反 A6 语义） | 中 | 三处隔离（§6.5）+ jar 常量池校验；实机用"移走 gtocore"启动验证 |

---

### 6.7 验收方案

**A. 离线（C 实现，D 执行；不依赖 Minecraft/AE2 类型）**
1. 新增 `tools/parity/Ae2LabelCheck.java`（只依赖 JDK + `DistinctMds`，与 `DynamicAnchorCheck.java` 同风格的纯函数测试）：
   - 输入：从 `_gtodata/assets/{gtceu,gtocore}/lang/zh_cn.json` 的 `fluid.*`/`material.*` + §5.4 的 5 个模板（`熔融%s`/`液态%s`/`气态%s`/`%s等离子体`/`%s蒸气`）派生的 `(registryName, displayName)` 对，外加 `_diag/derived_labels.txt` 的样例；
   - 断言：① 每条标签 ≤6 码点；② 同屏集合（按族抽 6 个）内两两互异；③ **parity**：同一 `(id, name)` 连续两次进 `LabelEngine.labelFor` 得到同一标签（AE2 侧与 EMI 侧共用该入口 ⇒ 由构造保证）+ 锚点 83 条仍 83/83 生效；④ `熔融高速钢-G → 熔高速钢-G`、`熔融铝合金-2090 → 熔铝2090` 与 §5.4 样例逐字一致。
   - 运行：`javac -encoding UTF-8 -d out src\main\java\com\fluidlabel\DistinctMds.java tools\parity\Ae2LabelCheck.java` → `java '-Dfile.encoding=UTF-8' -cp out com.fluidlabel.Ae2LabelCheck` ⇒ 期望 `VERDICT: ALL PASS`。
   - 说明：注入点**存在性**离线测不了（要 MC/AE2 运行期），由下面的启动自检兜。
2. 既有门禁**必须**继续全绿（防回归）：`tools/audit_table.py`、`tools/check_anchors.py`、`tools/derived_check.py`、`tools/parity/ParityCheck`、`tools/parity/DynamicAnchorCheck`。

**B. 启动自检（新类 `com.fluidlabel.Ae2SelfCheck`，由 `FluidLabelMod` 反射调用，仅 `AE2_PRESENT`/`GTCEU_PRESENT` 时跑）**
- 反射确认存在：① `appeng.api.client.AEKeyRendering#drawInGui(Minecraft, GuiGraphics, int, int, AEKey)`；② `appeng.api.stacks.AEFluidKey#is(AEKey)`、`#getId()`、`#getFluid()`；③ `…AEFluidDisplayWidget#drawInBackground(GuiGraphics, int, int, float)`；
- 扫三个目标类的 `getDeclaredMethods()` 找 Mixin 生成的 `handler$…$fluidlabel$…` → 打"注入器已装配"；
- 打印 4 个探针状态；任一项缺失 → WARN 点名（文案模板："本注入点不会生效（AE2/GTCEu 版本变了？）"）。

**C. 实机（探针 + 截图；`latest.log` 判据）**
1. 打开界面后日志**必须**出现（不受 `debugLog` 控制）：
   - `[fluidlabel] 注入点3 已生效：AEKeyRendering#drawInGui`
   - `[fluidlabel] 注入点4 已生效：GT AEFluidDisplayWidget#drawInBackground`
   出现其一 ⇒ 对应类别注入器真的在跑；某类界面开了却没有对应行 ⇒ 注入器没装配（先看 `Ae2SelfCheck` 的 WARN）。
2. 逐界面截图对照（**类 A**）：ME 存储终端（流体页）、合成终端、样板/编码终端、合成确认表（`CraftConfirmTableRenderer`）、网络状态（`NetworkStatusScreen`）、信息条（`InfoBar$StackWidget`）、网络内容 Toast、存储元件 tooltip；**类 B**：GT ME 流体仓 / 库存总线 / 输出仓 GUI；**GTO**：`gto_ae` 设施管理界面。判据：液体图标上出现 ≤6 码点标签、同屏互异、`≤6 字材料名必须原样`（A2 口径）。
3. **回归**：随便开一个 GT 化工配方 ⇒ 仍出现 `注入点2 已生效`（T2 修复未破）；EMI 侧栏/槽位标签与第一阶段逐像素一致。
4. **软依赖**：临时把 `gtocore-forge-1.20.1-0.5.6-beta.jar` 移出 `mods\`（连带 AE2/GTCEu 全部消失）启动 ⇒ 不崩、无 `Mixin apply failed`、日志最多一行 INFO；此时 EMI 侧功能应完全正常。
5. **开关**：`enabled=false` ⇒ AE2/GT 侧也不画（第一行短路）。
6. **构建（D）**：`mc_gradle`：`clean` → `build --stacktrace` → `jar`；冷启动 ≥1800s、增量 ≥600s。产物校验：jar 内含 `AeKeyRenderingMixin.class`、`GtAeFluidDisplayMixin.class`、`Ae2SelfCheck.class`；manifest 仍有 `MixinConfigs`；**未打包** `appeng/`、`com/gregtechceu/`、`com/lowdragmc/`、`dev/emi/` 任何条目；`FluidLabelMod.class` 常量池不含这三家包名。
7. 若日志出现 `Target method not found` ⇒ 按 §4.4 判据切路线 Y（mixin AP + refmap）。

---

### 6.8 变更文件清单（相对 `fluid-label/`，精确到路径 + 归属）

| 文件（相对路径） | B | C | D |
| --- | --- | --- | --- |
| `libs/ldlib-forge-1.20.1-1.0.50.jar` | **从实例拷入**（新） | — | 校验大小 |
| `build.gradle` | 不改 | **不改**（如需改：写 `docs/BUILD-REQUESTS.md`） | 按 §6.5 加 3 条 `compileOnly files(...)` |
| `src/main/resources/fluidlabel.mixins.json` | 不改 | 追加 `AeKeyRenderingMixin`、`GtAeFluidDisplayMixin`（`"client"` 列表） | 审查 |
| `src/main/resources/META-INF/mods.toml` | 不改 | 追加 `ae2`/`gtceu` 两条 `mandatory=false` 软依赖 | 审查 |
| `src/main/java/com/fluidlabel/mixin/AeKeyRenderingMixin.java` | 不建 | **新建**（注入点 3） | 审查 |
| `src/main/java/com/fluidlabel/mixin/GtAeFluidDisplayMixin.java` | 不建 | **新建**（注入点 4） | 审查 |
| `src/main/java/com/fluidlabel/compat/Ae2Compat.java` | 不建 | **新建**（AE2 类型隔离 + per-key 标签缓存 + `isFluidKey/idOf/displayNameOf/labelOf/tintOf`） | 审查 |
| `src/main/java/com/fluidlabel/Ae2SelfCheck.java` | 不建 | **新建**（启动自检） | 审查 |
| `src/main/java/com/fluidlabel/InjectionProbe.java` | 不改 | **改**：`FIRED` 扩到 4 + 两个新常量（R-AE2-9） | 审查 |
| `src/main/java/com/fluidlabel/LabelRenderer.java` | 不改 | **改**：新增 `drawOverlayAe2(...)`，把绘制段抽成 `drawLabel(...)`（EMI 输出不变） | 审查 |
| `src/main/java/com/fluidlabel/FluidLabelMod.java` | 不改 | **改**：`AE2_PRESENT/GTCEU_PRESENT` 判定 + 反射调 `Ae2SelfCheck.run()` | 审查（常量池校验） |
| `src/main/java/com/fluidlabel/mixin/FluidEmiStackMixin.java`、`mixin/TankWidgetMixin.java` | 不改 | **不改**（EMI 侧逐字冻结） | 审查 |
| `tools/parity/Ae2LabelCheck.java` | 不建 | **新建**（离线 parity/预算/互异门禁） | 执行 |
| `docs/BUILD-REQUESTS.md` | 不建 | 需要改 build 时写这里 | 执行 |

**给 C 的硬性纪律（与 §⑨.2 同源，逐条适用）**
1. 新入口第一行必须 `enabled` 短路；`Object` 形参 + `instanceof`/`AEFluidKey.is()` 防御；
2. `appeng/gtceu/lowdragmc` 类型只出现在 `mixin/`、`compat/Ae2Compat`、`Ae2SelfCheck`；
3. 所有 `@Inject` = `at = @At("RETURN")` + `require = 0`；两个 `@Mixin` 都 `remap = false`；**注入点 4 的 mixin 不声明父类**（见 §6.2.2 的 javap 修正：LDLib `Widget` 没有无参构造器，`extends Widget` 会编译失败 `constructor Widget cannot be applied`），改用 `@Shadow @Final` 字段 + `((Widget)(Object) this).getPosition()` 转型；
4. **渲染热路径性能纪律**：禁止 `Component#getString()`、正则、`String.format`、反射、重复的 `ResourceLocation#toString()`（走 §6.3 的 per-key 缓存）；
5. 不新增配置项、不改 `FluidLabelConfig`。

---

### 6.9 实施记录（B/C/D 实际产出 + 构建环境突变 + 门禁口径修正，2026-10）

**B（骨架）实际产出**：`compat/Ae2Compat.java`（6 桩）、`mixin/AeKeyRenderingMixin.java`、`mixin/GtAeFluidDisplayMixin.java`、`Ae2SelfCheck.java`、`tools/parity/Ae2LabelCheck.java`（桩）；`fluidlabel.mixins.json` 的 `"client"` 现为 `FluidEmiStackMixin, TankWidgetMixin, AeKeyRenderingMixin, GtAeFluidDisplayMixin`（`package: com.fluidlabel.mixin`、`required: false`、`defaultRequire: 0`、`compatibilityLevel: JAVA_17`）；`META-INF/mods.toml` 追加 `ae2 [15,)` / `gtceu [1.0,)` 两条 `mandatory=false` + `ordering="AFTER"` + `side="CLIENT"`。
> **分工修正**：§6.8 原把 mixins.json / mods.toml 两行划给 C，实际由 B 落地 ⇒ C **未**重复追加（已核，无重复条目）。

**C（内容）实际产出（无 shell，一行未编译）**：① `InjectionProbe.FIRED` 扩到 **5**（`SITE_AE2_DRAW_IN_GUI = 3`、`SITE_GT_FLUID_DISPLAY = 4`）；② `Ae2Compat` 全实现（`AEFluidKey.is(AEKey)` 判定、`getId()`、`getDisplayName().getString()`、`labelOf` 只调 `LabelEngine.labelFor(ResourceLocation, Component)`、`tintOf` 用 `toStack(1)` + `IClientFluidTypeExtensions`；`ConcurrentHashMap` 上限 4096 超限清空；热路径零 `getString/toString/正则/格式化`）；③ `LabelRenderer` 抽 `drawLabel(...)`（EMI 行为不变）+ 新增 `drawOverlayAe2(...)`（enabled 短路 → `isFluidKey` → `(epoch,x,y,key)` memo → `labelOf` → `drawLabel`）；④ `Ae2SelfCheck` 纯反射实现（零 `appeng/gregtechceu/lowdragmc` import）；⑤ `FluidLabelMod` 增 `AE2_PRESENT`/`GTCEU_PRESENT` + 反射调 `Ae2SelfCheck.run()`。

**D（审查+构建）实测 —— 构建环境突变（本轮最重要的新事实）**：
- **vendored `/libs` 里的 AE2/GTCEu 是 classfile 65（Java 21）**，javac 17 直接拒读，首错原文：
  `错误的类文件: …libs\appliedenergistics2-forge-1.20.1-15.267.4.jar(/appeng/api/stacks/AEKey.class) 类文件具有错误的版本 65.0, 应为 61.0`
  ⇒ `fluid-label/build.gradle:13` 的 toolchain **17 → 21**（解析到本机 `C:\Users\18409\.gradle\jdks\eclipse_adoptium-21-amd64-windows\jdk-21.0.12.1+1`，非下载），同时新增 `fluid-label/build.gradle:19-21` `tasks.withType(JavaCompile).configureEach { options.release = 17 }` 保证产物仍是 Java 17 字节码。
  **依据（Lead 复核）**：目标实例 `instance.cfg` → `JavaPath=D:/Program Files (x86)/jdk25/bin/javaw.exe`、`JavaVersion=25.0.4.1`、`IgnoreJavaCompatibility=true`；`mmc-pack.json` → MC `1.20.1` + Forge `47.4.20` ⇒ 61/65 字节码都能加载，`release = 17` 安全且更稳。
- D 修的编译错（仅此一处产品代码）：`compat/Ae2Compat.java:271-272` `.of(stack)` → `.of(stack.getFluid())`（`IClientFluidTypeExtensions` 只有 `of(FluidState)/of(Fluid)/of(FluidType)`，**没有** `of(FluidStack)`）。
- `build.gradle` 的 3 条新 `compileOnly`：`libs/appliedenergistics2-forge-1.20.1-15.267.4.jar`、`libs/gtceu-1.20.1-forge-1.20.1-26.7.3.jar`、`libs/ldlib-forge-1.20.1-1.0.50.jar`（R-AE2-7 命中）。
- 构建：`clean` ✅ 20s → `build --stacktrace` ❌（9 错）→ 修两处 → ✅ 2m25s → 加 `options.release` 后再 `build` ✅ 17s。

**门禁口径修正（`tools/parity/Ae2LabelCheck.java`，Lead 修，D 初跑报 1793 FAILED）**：三处工具缺陷，**均不是产品代码问题**：
1. **语料喂错**：原 `readJsonStringValues` 把 lang 里**全部** 13357 条字符串值当液体基名（含 `"gtceu.machine.…" : "§5高级电路组装机 III§r"` 这类机器名与颜色码）套状态模板 ⇒ 80121 条现实中不存在的派生名。改为只取键前缀 `material.` / `fluid.` 的**值**，并丢弃含 `%s` 的模板值（模板不是显示名）⇒ 基名 **1914** 条。
2. **入口用错**：② 族内互异原用整池分配器 `DistinctMds.assignLabels` —— 它的"无解条目"收尾允许写回**超长原名**（生成器口径，`uniqueLabel` 允许超预算），拿它当渲染门禁必然假失败。改为 `DistinctMds.labelForName`（= 渲染路径真正调用的那个，`uniqueWithinBudget` 保证 ≤6 码点）。
3. **互异判据口径错**：③b 原按 854 个**条目键**数互异值，而工具刻意制造了 `table:X` 别名键；"同一液体的多个身份键共用同一标签"是 §0.5 明文要求的**正确行为**，被误判成撞车。改为按**显示名**判互异（454/454）。

**最终验收（Lead 全跑，2026-10）**：

| 检查 | 结果 |
| --- | --- |
| `tools/parity/Ae2LabelCheck` | **ALL PASS**（failures 0）：语料 656+1259 ⇒ 基名 1914 ⇒ 派生 **11474**；①逐条 `labelForName` 超长 **0**、最长 6 码点、颜色码 **0**；②5 族各 1914 条、可见形式撞车 **0**、超长 **0**；③`assignLabels`×2 mismatch **0**；④7 条用户样例逐字；⑤锚点 **83/83**、显示名互异 454/454；⑥缓存隔离 4/4 |
| `tools/audit_table.py` | **ALL PASS**：253,449 B / `byDisplayName` 1898 / `byRegistryName` 3800 / conflicts 0；R1–R6 全 PASS |
| `tools/check_anchors.py` | 锚点 **83/83** 在表里生效；长度/禁用字符/重复/缺键违规计数均 0 |
| `tools/derived_check.py` | **ALL PASS**（4 条门禁：≤6 码点 / 两两互异 / 阶梯有解 / 不撞表标签） |
| `tools/parity/ParityCheck` | **1815/1815** 逐字一致；Java 侧 1898 个标签两两互异 → `PARITY OK` |
| `tools/parity/DynamicAnchorCheck` | **ALL PASS**（10 个 case 全 PASS，锚点 83/83） |
| `tools/parity/DerivedLabelCheck` | compared 9688（跳过 7）/ mismatches **0** → **ALL PASS** |

**产物与部署**：`fluid-label/build/libs/fluid-label-0.1.0.jar` = **118,011 字节**、33 条目、sha256 `4DFF1AD9B5334BC734EAA74FAA01A024D8B9D54587577910A304FC2558339E55`（**首版**）；四个新 class 全在；`appeng/`/`com/gregtechceu/`/`com/lowdragmc/`/`dev/emi/` 条目数 **0**；`FluidLabelMod.class` 常量池对四家包名 present=false；5 个关键 class `major 61`；内嵌 `fluidlabel_labels.json`（253,449 B / sha256 `89CF10BA…4D04C9`）、`fluidlabel_anchors.json`（4,691 B / sha256 `1FE9F530…AA1E8D`）、`fluidlabel.mixins.json`（282 B / sha256 `ED95021E…21ED`）与工作区文件**逐字节相同**；已覆盖实例 `minecraft/mods/fluid-label-0.1.0.jar`（两侧 sha 相同）。

**仍未验证（需实机）**：注入点 3/4 的 Mixin 装配与探针触发（`Ae2SelfCheck` 的启动日志 / `注入点3/4 已生效`）、`mods.toml` 的 `versionRange="[15,)"/"[1.0,)"` 是否被 Forge 接受、软依赖移走 gtocore 的启动用例（§6.7.C.4）、以及 tooltip/Toast 场景下标签的观感（R-AE2-3）。

---

### 6.10 实机缺陷修复（用户 m01281 实测：AE2 网络内所有液体均无标签）

**用户反馈（m01281）**：「测试后发现AE2物品网络内所有液体均未显示标签，与本次修改前无区别」。

**取证（只读日志，不猜）**：`instances/GregTech.Odyssey-0.5.6-beta/minecraft/logs/{latest,debug}.log`（会话 02Oct2026 23:01，Forge 47.4.20 / MC 1.20.1 / Java 25）：

- `latest.log:269`：`Mixin apply failed fluidlabel.mixins.json:AeKeyRenderingMixin -> appeng.api.client.AEKeyRendering: InvalidInjectionException 'static' modifier of handler method does not match target in appeng/api/client/AEKeyRendering::fluidlabel$overlayAe2 [ -> handler$fan000$fluidlabel$overlayAe2(...)V -> Prepare]`
- `latest.log:309`：`[fluidlabel] 自检：找不到类 int（AE2/GTCEu 未安装或版本变化）—— 对应注入点不会生效。`
- `debug.log:2828-2834`：mixin 配置本身注册成功（`Registering mixin config: fluidlabel.mixins.json`）⇒ **不是**配置/清单问题。
- `debug.log`：**没有** `GtAeFluidDisplayMixin` 的 apply 失败 ⇒ 注入点 4 的 mixin 装配成功（`Ae2SelfCheck` 在启动时以 `Class.forName(…, false, …)` 加载过该目标类、`@Shadow` 字段 `gridWidget`/`index` 与 `javap` 一致）。

**缺陷 ①（致命）：注入点 3 的 handler 少了 `static`**

- 目标 `appeng.api.client.AEKeyRendering#drawInGui(Minecraft, GuiGraphics, int, int, AEKey)` 是 **static**（javap 逐字复核：`public static void drawInGui(...)`），而 Mixin 要求 **handler 的 static 修饰符与目标一致**；原写法 `private void fluidlabel$overlayAe2(...)` 是实例方法 ⇒ 装配期抛 `InvalidInjectionException`，**整个 mixin 被丢弃**，AE2 全部界面一根标签都不画。
- **这是 WARN 不是崩溃**：游戏照常启动，配合 `require = 0` 完全静默 —— 唯一的线索就是那行 WARN。
- 修法：`AeKeyRenderingMixin.java` 的 handler 改 `private static void`。对照：注入点 4 的目标 `AEFluidDisplayWidget#drawInBackground` 是**实例**方法，其 handler 就必须**非** static（该文件未改）。
- 教训（写进两份源码的 javadoc）：**`@Inject` 的 handler 必须与目标同 static 性**；动手写 mixin 前先用 `javap` 确认目标的修饰符，别只看签名。

**缺陷 ②：`Ae2SelfCheck.params()` 把基本类型当类名**

- 原实现把 `"int"` / `"float"` 直接交给 `Class.forName` ⇒ `ClassNotFoundException: int` ⇒ 日志那行"找不到类 int"是**假警报**，且 `params(...)` 返回 `null` 让注入点 3/4 的签名校验**整体静默跳过** —— 正是它把缺陷 ① 掩盖了。
- 修法：新增 `resolve(String)`，先查基本类型表（`boolean/byte/char/short/int/long/float/double/void`）再走 `load()`。

**复验（Lead，本机离线全绿）**：

| 检查 | 结果 |
| --- | --- |
| `mc_gradle build` | **BUILD SUCCESSFUL in 17s**（compileJava/jar/reobfJar 全跑） |
| jar 内 handler 修饰符（javap） | `AeKeyRenderingMixin`：`private static void fluidlabel$overlayAe2(Minecraft, GuiGraphics, int, int, AEKey, CallbackInfo)` ✅；`GtAeFluidDisplayMixin`：`private void fluidlabel$overlayGt(GuiGraphics, int, int, float, CallbackInfo)` ✅（实例，符合目标） |
| 依赖签名复核（javap） | `AEFluidDisplayWidget`：`private final AEListGridWidget gridWidget` + `private final int index` + `public void drawInBackground(GuiGraphics,int,int,float)`；`AEFluidKey.is(AEKey)` static、`getId()`、`getFluid()` 均在 |
| 标签池口径复核 | 表内 `gtceu:` 键 1306 条（`gtceu:severely_hydro_cracked_naphtha`、`gtceu:sulfuric_acid` 在表内）；`minecraft:` 键 0 条 ⇒ 原版水在 AE2/EMI 两侧行为一致（走 `ruleFallback` 出单字标签，非缺陷） |
| `tools/parity/Ae2LabelCheck` | **ALL PASS**（failures 0，与 §6.9 同口径） |
| jar 泄漏检查 | `appeng/`/`com/gregtechceu/`/`com/lowdragmc/`/`dev/emi/` 条目数 **0**；33 条目 |

**新的产物与部署（本轮）**：`fluid-label/build/libs/fluid-label-0.1.0.jar` = **118,528 字节**、33 条目、sha256 `9A2BB79652A940827A2BCB3F133D6CEEED34E4B36A710E23597BBECAC7C3AF5F`；已 `Copy-Item -Force` 覆盖实例 `minecraft/mods/fluid-label-0.1.0.jar`，两侧 sha 相同（10/02/2026 23:14:55）。

**重启后应看到的启动日志（自检现在真的会跑）**：

```
[fluidlabel] 注入点3 AEKeyRendering#drawInGui 目标在：AEKeyRendering.drawInGui(Minecraft, GuiGraphics, int, int, AEKey) → 注入器已装配
[fluidlabel] AEFluidKey#is(AEKey) 目标在：AEFluidKey.is(AEKey) → 注入器已装配
[fluidlabel] 注入点4 AEFluidDisplayWidget#drawInBackground 目标在：AEFluidDisplayWidget.drawInBackground(GuiGraphics, int, int, float) → 注入器已装配
[fluidlabel] 探针：site1=… site2=… site3(AE2)=待触发 site4(GT ME)=待触发
```

若哪一行显示"目标方法不存在"或"注入器状态未知"，把那行原文发回来 —— 那就是下一个要修的点。

---

## ⑪ §7 —— GT/GTO 机器界面液体槽（第三阶段）

### 7.1 目标与范围

**用户原话（m01374）**：「AE2部分验收通过，目前希望能再将GT机器物品栏中的液体也加上同样的标签」。

- 范围：GT/GTO 机器 GUI 里的**全部液体槽**——蒸汽锅炉、流体仓（`FluidHatchPartMachine`）、量子缸（`QuantumTankMachine`）、缓冲机（`BufferMachine`）、焦炉（`CokeOvenMachine`）、创造储罐（`CreativeTankMachine`）、双重仓（`DualHatchPartMachine`）、末影流体链接覆盖板（`EnderFluidLinkCover`）、`FancyTankConfigurator` 等；以及幽灵/过滤流体槽（`PhantomFluidWidget`、`ScrollablePhantomFluidWidget`）与 GTO 的 ME 流体配置槽（`AEFluidConfigSlotWidget`）。
- 风格：与 EMI/AE2 侧**逐字一致**（同一 `LabelEngine` / 同一张表 / 同一预算 / STABLE-DYNAMIC 两档），**不新增配置项**。

### 7.2 选型：注入绘制原语，而不是逐个控件（决定 + 理由）

**不**给每个控件写 mixin，改为注入 LDLib 画液体的那一个原语：

```java
// com.lowdragmc.lowdraglib.gui.util.DrawerHelper（两个都是 public static）
public static void drawFluidForGui(GuiGraphics g, com.lowdragmc.lowdraglib.side.fluid.FluidStack stack,
                                   long amount, int x, int y, int w, int h);      // 注入点 5
public static void drawFluidForGui(GuiGraphics g, com.lowdragmc.lowdraglib.side.fluid.FluidStack stack,
                                   float x, float y, float w, float h);           // 注入点 6
```

理由（三条都可验证）：

1. **一次覆盖全部**：GT 机器 UI 用的 `com.gregtechceu.gtceu.api.gui.widget.TankWidget extends com.lowdragmc.lowdraglib.gui.widget.Widget`（Forge `FluidStack`/`IFluidHandler` 口径）以及 LDLib 自己的 `TankWidget`（LDLib `IFluidTransfer` 口径）最终都调 `drawFluidForGui`；ME 部件同族控件也是。逐控件注入要写 5–8 个 mixin，且 GT 的 `PhantomFluidWidget` 会**覆写** `drawInBackground`（`super` 前先自己画），漏一个就漏一片。
2. **爆炸半径可枚举**：常量池扫描 `drawFluidForGui` 的全部调用者只有 7 个类 —— gtceu 4 个（`TankWidget`、`PhantomFluidWidget`、`AEFluidDisplayWidget`、`ProspectorMode$2`）、ldlib 2 个（`TankWidget`、`PhantomFluidWidget`）、gtocore 1 个（`com/gtocore/common/machine/multiblock/part/ae/widget/slot/AEFluidConfigSlotWidget`）。不存在"未知海量调用者"。
3. **不碰 LDLib 继承树**：注入静态方法不需要 `extends` 任何 LDLib 类，绕开"`Widget` 没有无参构造器"这个坑（§6.2.2 踩过）。

**代价（写进限制）**：注入点在"液体绘制"这一层，不在"控件"这一层 —— 凡是别的模组用这两种重载画非槽位图元（进度条底纹、探针模式预览），也会命中，故有 `MIN_SLOT` 门槛（见 7.4）。

### 7.3 注入点 5/6 的证据与纪律

| 项 | 证据（javap 实测，jar 为 `libs/ldlib-forge-1.20.1-1.0.50.jar`） |
| --- | --- |
| 签名 | `public static void drawFluidForGui(GuiGraphics, side.fluid.FluidStack, long, int, int, int, int)` / `(…, float, float, float, float)`；两个都是 **static** ⇒ handler 必须 `private static`（§6.10 缺陷 ① 同类陷阱） |
| mixin descriptor | `drawFluidForGui(Lnet/minecraft/client/gui/GuiGraphics;Lcom/lowdragmc/lowdraglib/side/fluid/FluidStack;JIIII)V` 与 `…;FFFF)V`（`remap = false`，Mojang 类名写字符串是本工程已验证写法，见 §4.1/§4.2） |
| 几何（7 参 int 版） | 传的是**完整内缩矩形**：`Position.x+1, Position.y+1, Size.width-2, Size.height-2`（GT `TankWidget#drawInBackground` offset 229–255，hover overlay 用同一矩形实证） |
| 几何（5 参 float 版） | 四个 float 就是**像素矩形 `(x,y,w,h)`**：字节码 `cx=(int)(w/16)`、`cy=(int)(h/16)`、`bottom = y + h`，内层双层循环画 16×16 图块、左下角 `(x + i*16, bottom - (j+1)*16)` ⇒ 自底向上铺贴图，**不是 UV** |
| 但 float 版在 LDLib `TankWidget` 里拿到的是"填充切片" | offset 249–302：`x+u*innerW, y+v*innerH, innerW*drawnWidth, innerH*drawnHeight`（`FillDirection.getDrawnU/V/Width/Height(fillPercent)`）⇒ 液位低时矩形会缩小并上移 |
| 对照：ME 部件控件走 7 参版 | `AEFluidDisplayWidget` offset 158–179：`getFluid()/getTag()` → Forge 栈 → `FluidHelperImpl.toFluidStack(...)` → 7 参重载，传 `bipush 16 bipush 16` 与局部 7/8（= `stackX/stackY = position.x+1/y+1`） |

**实际 handler**（`mixin/DrawerHelperFluidMixin.java`）：

- `@Mixin(value = com.lowdragmc.lowdraglib.gui.util.DrawerHelper.class, remap = false)`，两个 handler 都是 `@At("RETURN")` + `require = 0` + `remap = false`；
- `private static final int MIN_SLOT = 10`：`w < MIN_SLOT || h < MIN_SLOT` 直接 return（挡进度条底纹、探针预览与低液位切片）；
- 第一行无条件调 `InjectionProbe.fired(站点, 说明)`；渲染调用包在 try/catch 里静默失败（纯客户端装饰，绝不能因一个异常把 GUI 打崩）；
- float 版先 `Math.round` 取整，`amountShown = false`（切片矩形不代表整槽）；int 版 `amountShown = amount > 0L`。

### 7.4 为什么注入点 4 降级为探针（避免双描边）

`AEFluidDisplayWidget` 的图标**就是**走 7 参 `drawFluidForGui` 画的，矩形（`p.x+1, p.y+1, 16, 16`）与流体来源逐字节一致 ⇒ 注入点 5 已经覆盖它。两条路径同时画会让同一标签的描边叠粗。

因此 `mixin/GtAeFluidDisplayMixin.java` 撤掉了 `LabelRenderer.drawOverlayAe2(...)` 调用、删掉 `@Shadow gridWidget/index`，**只留探针**（`注入点4 …（渲染已移交注入点5）`），handler 仍是实例方法（目标 `drawInBackground` 是实例方法）。

### 7.5 标签来源与渲染路径（零新增规则）

- 新增 `compat/LdlibFluidCompat.java`（与 `Ae2Compat` 同构）：`isFluid / idOf / displayNameOf / labelOf / tintOf / clearCaches / cacheSize`。
  - **缓存键 = `net.minecraft.world.level.material.Fluid`，不是 `FluidStack`**：GT 的 `TankWidget` 每帧都 `FluidHelperImpl.toFluidStack(...)` new 一个新栈对象，用栈当键 = 每帧重建缓存（identity 永不命中）。
  - 建缓存时才做字符串活：`ForgeRegistries.FLUIDS.getKey(fluid).toString()` + `sample.getDisplayName().getString()` + `LabelEngine.labelFor(ResourceLocation, Component.literal(name))`；tint 惰性 `IClientFluidTypeExtensions.of(fluid).getTintColor(new net.minecraftforge.fluids.FluidStack(fluid, 1))`（照抄 §6 已验证写法）。
  - `CACHE_LIMIT = 4096` 超限整体清空；`BUILD_LOCK` 双检锁；异常只打一行 WARN。
- `LabelRenderer` 新增 `public static void drawOverlayLdlib(GuiGraphics, Object ldlStack, int x, int y, int w, int h, boolean amountShown)`：`enabled` 短路 → `isFluid` → `idOf` → **同帧 `(epoch, x, y, idStr)` 去重**（`memoLdlibId` 是 `String`，因为每帧新栈对象，identity memo 不可能命中）→ `labelOf` → `FrameContext.observed(parseId(idStr))` → 共用 `drawLabel(...)`（`useLightTextFor(tintOf)`，矩形 `max(w,16) × max(h,16)`）。
- 卸载缓存：`FluidLabelMod.onConfigEvent` 里追加 `LdlibFluidCompat.clearCaches()`。

### 7.6 变更文件清单（相对 `fluid-label/`）

| 文件 | 动作 |
| --- | --- |
| `src/main/java/com/fluidlabel/mixin/DrawerHelperFluidMixin.java` | 新增（注入点 5/6） |
| `src/main/java/com/fluidlabel/compat/LdlibFluidCompat.java` | 新增 |
| `src/main/java/com/fluidlabel/LabelRenderer.java` | 新增 `drawOverlayLdlib` + `memoLdlib*` 字段 + import |
| `src/main/java/com/fluidlabel/InjectionProbe.java` | 站点 5/6；`FIRED` 长度 7；日志 `info → warn` |
| `src/main/java/com/fluidlabel/mixin/GtAeFluidDisplayMixin.java` | 撤渲染调用，只留探针 |
| `src/main/java/com/fluidlabel/Ae2SelfCheck.java` | 新增 ⑤ 段（LDLib 两个重载 + handler 反射校验）；原 ④ 段扩成 ⑥ 段六个站点；`verify` 行 `info → warn` |
| `src/main/java/com/fluidlabel/{EmiSelfCheck,FluidLabelMod}.java` | 关键日志 `info → warn`（诊断锚点不能赌 INFO 落盘：2026-10 两轮实机会话里本模组 INFO 行全缺，原因未查明） |
| `src/main/resources/fluidlabel.mixins.json` | `"client"` 加 `DrawerHelperFluidMixin`（现 5 个） |
| `src/main/resources/META-INF/mods.toml` | 新增 `ldlib` 软依赖（`mandatory=false` / `versionRange="[1.0,)"` / `ordering="AFTER"` / `side="CLIENT"`） |
| `build.gradle` | **未改**（`libs/ldlib-forge-1.20.1-1.0.50.jar` 的 `compileOnly` 在 §6.9 已接线） |

### 7.7 实施记录（Lead，2026-10）

- `mc_gradle build` → **BUILD SUCCESSFUL in 17s**（compileJava / processResources / jar / reobfJar 全跑；jarJar / reobfJarJar SKIPPED；test NO-SOURCE）。
- 产物：`build/libs/fluid-label-0.1.0.jar` = **124,275 字节 / 36 条目 / sha256 `0041FAECD7B5B05252F792F8ABD33791AF7036489D6509DC45F2CAFE389E8F4C`**；已 `Copy-Item -Force` 覆盖实例 `minecraft/mods/fluid-label-0.1.0.jar`（两侧同 sha，2026/10/2 23:34:10）。
- 产物审查：`DrawerHelperFluidMixin` 两个 handler javap 为 `private static`（签名逐字见 README 表）；`GtAeFluidDisplayMixin` 的 `fluidlabel$overlayGt` 为私有实例方法；全部 33 个 `com/fluidlabel/**` class `major 61`；`appeng/`、`com/gregtechceu/`、`com/lowdragmc/`、`dev/emi/` 条目数均为 **0**；内嵌 `fluidlabel_labels.json`（253,449 B / `89CF10BA…4D04C9`）与 `fluidlabel_anchors.json`（4,691 B / `1FE9F530…AA1E8D`）与工作区**逐字节相同**；`mods.toml` 已含 `ldlib` 块（processResources 展开版本号，故与源文件字节不同，属正常）。
- 离线门禁**七项全绿**：`tools/audit_table.py` ALL PASS（R1–R6）、`tools/check_anchors.py` 83/83、`tools/derived_check.py` 四门禁 ALL PASS（9695 条派生名）、`tools/parity/ParityCheck` 1815/1815 且 Java 侧 1898/1898 互异（`PARITY OK`）、`tools/parity/DynamicAnchorCheck` ALL PASS、`tools/parity/DerivedLabelCheck` compared 9688 / mismatches 0 ALL PASS、`tools/parity/Ae2LabelCheck` failures 0 ALL PASS。
  跑法（本工程已验证，`tools/parity/*.java` 都在 `package com.fluidlabel`）：
  ```powershell
  javac -encoding UTF-8 -nowarn -cp build\classes\java\main -d build\gates tools\parity\*.java
  java "-Dfile.encoding=UTF-8" -cp "build\classes\java\main;build\gates" com.fluidlabel.ParityCheck src\main\resources\fluidlabel_labels.json tools\anchors.json
  ```
  （`ParityCheck` **必须给两个参数**，否则 `args[0]` 越界。）

### 7.8 未验证项（重启后才有证据）

1. 注入点 5/6 在实机是否真的装配（`require = 0` 下装配失败只有一行 WARN）；
2. `MIN_SLOT = 10` 是否把该画的槽也挡掉了（若蒸汽锅炉这类 18×18 槽位的内缩矩形是 16×16，阈值为 10 不会挡；低液位切片会按设计不画）；
3. `mods.toml` 里 `ldlib versionRange="[1.0,)"` 是否被 Forge 接受（`mandatory=false`，即使不满足也只影响软依赖提示）。

**预期日志（重启后进任意 GT 机器界面）**：

```
[fluidlabel] 注入点5 DrawerHelper#drawFluidForGui(int 重载) 目标在：DrawerHelper.drawFluidForGui(GuiGraphics, FluidStack, long, int, int, int, int) → 注入器已装配
[fluidlabel] 注入点6 DrawerHelper#drawFluidForGui(float 重载) 目标在：DrawerHelper.drawFluidForGui(GuiGraphics, FluidStack, float, float, float, float) → 注入器已装配
[fluidlabel] 探针：site1=… site2=… site3(AE2)=… site4(GT ME)=… site5(LDLib int)=已生效 site6(LDLib float)=已生效
[fluidlabel] 注入点5 已生效：DrawerHelper#drawFluidForGui(GuiGraphics, FluidStack, long, int, int, int, int)
```

**若图标仍无标签**：先搜日志 `Mixin apply failed fluidlabel`（拿原文），再确认 `Ae2SelfCheck` 第 ⑤ 段两行是否出现 —— 若连"目标在"都没有，说明 `DrawerHelper` 类没被加载到（LDLib 缺失或版本不符）。

---

## ⑫ §8 —— 实机缺陷：GT 机器里标签 20 Hz 亮灭（第四阶段，2026-10）

### 8.1 现象（User said m01640）

> 发现问题：在GT机器内很多液体的标签会发生闪烁，一秒内闪烁10-20次左右。

即第三阶段（注入点 5/6）上线后，GT/GTO 机器界面里的液体标签**每秒亮灭 10–20 次**；EMI 侧与 AE2 侧（已验收）不复现。

### 8.2 根因：用 20 Hz 的 `FrameContext.epoch()` 当"帧号"去做逐帧去重

两处渲染入口各有一条"同一次调用栈去重"的单条 memo（AE2 侧第 75 行起、LDLib 侧第 356 行起），
原判据是 `epoch == memoEpoch && x == memoX && y == memoY && key 相等`。

问题在于 `epoch` 的推进频率**不是渲染帧率**：

- `fluid-label/src/main/java/com/fluidlabel/FluidLabelMod.java:114-116`
  ```java
  private static void onClientTick(TickEvent.ClientTickEvent event) {
      if (event.phase == TickEvent.Phase.END) {
          FrameContext.endFrame();   // → FrameContext.roll() → EPOCH.incrementAndGet()
      }
  ```
  `ClientTickEvent.Phase.END` 是**客户端 tick = 20 Hz**；`FrameContext.roll()` 每次 `EPOCH.incrementAndGet()`。
- 于是 epoch 每秒只变 20 次，而 GUI 渲染 60–144 fps ⇒ **连续 3～7 帧的 epoch 完全相同**。
- 当同一个槽在相邻两次绘制之间坐标与液体都没变（单储罐机器、静态液面、`PhantomFluidWidget`
  先 `super` 画真实流体再画幽灵流体的"同栈双画"），memo 在**同一 epoch 内**把后续帧整体判为
  "重复绘制"而 `return` ⇒ 标签只在 epoch 变化的那一帧出现 ⇒ **以 20 Hz 亮灭**，正是用户看到的
  "一秒 10–20 次"。
- 为什么 EMI / AE2 没暴露：EMI 侧 `drawSlot`（`LabelRenderer.java:132-175`）**根本没有 memo**；
  AE2 界面里液体图标多，单条 memo 只记"最后一次绘制"，连续两帧的首个图标坐标不同 ⇒ 不会连续命中。
  GT 机器一个界面往往只有 1 个液体槽 ⇒ 必现。

### 8.3 修法：memo 判据改为"亚帧时间窗"，不依赖 epoch

`fluid-label/src/main/java/com/fluidlabel/LabelRenderer.java`：

```java
/** 去重时间窗（纳秒） */
private static final long DEDUPE_WINDOW_NANOS = 2_000_000L;   // 2ms
```

- 两处 memo 的 epoch 字段换成时间戳字段：`memoEpoch` → `memoNanos`、`memoLdlibEpoch` → `memoLdlibNanos`
  （初值 `0L`，**不用** `Long.MIN_VALUE`，避免 `now - 初值` 溢出成负数而误判为命中）；
- 命中条件：`now - memoNanos < DEDUPE_WINDOW_NANOS && x/y 相同 && id 相同`；命中后 return，否则记录 `now` 再绘制。
- 语义依据：真正要挡的"同一次调用栈里的重复绘制"相隔**微秒级**（≪ 2 ms 必挡）；
  相邻两帧至少隔 ~7 ms（144 fps 时 6.9 ms）⇒ 永不误挡。
- `FrameContext` / `LabelEngine` 的 DYNAMIC 语义**不动** —— epoch 仍是"这套标签属于哪一版快照"的版本号，
  本来就不是帧号，被误用的只是渲染去重这一处。

### 8.4 回归门禁

新增 `fluid-label/tools/render_dedupe_guard.py`（6 项断言，`VERDICT: ALL PASS`）：

1. `LabelRenderer.java` 存在 `DEDUPE_WINDOW_NANOS`；2. 使用 `System.nanoTime()`；
3. `LabelRenderer.java` **代码里**（剥掉注释后）不出现 `FrameContext.epoch()` / `endFrame()`；
4. 两个 overlay 入口（`drawOverlayAe2` / `drawOverlayLdlib`）都引用该时间窗常量；
5. `LabelEngine.java` 仍用 `FrameContext.epoch()` 做 DYNAMIC 快照版本号（不能被顺手删掉）。

跑法：`python tools\render_dedupe_guard.py`。

### 8.5 产物与部署

- `mc_gradle build` → **BUILD SUCCESSFUL in 22s**。
- `fluid-label/build/libs/fluid-label-0.1.0.jar` = **124,314 字节 / 36 条目 /
  sha256 `0012B97746B903C210624BB1B6AB296D9852BCB303228213F4B1429609FD5824`**；
  已 `Copy-Item -Force` 覆盖实例 `minecraft/mods/fluid-label-0.1.0.jar`（两侧同 sha）。
- javap 复核：`LabelRenderer` 两个入口都出现 `invokestatic java/lang/System.nanoTime:()J` +
  `ldc2_w // long 2000000l`；`DEDUPE_WINDOW_NANOS` 常量在常量池中；`FrameContext.epoch` 引用已从本类消失。
- 其它门禁不受影响（本次只改渲染去重，未动标签表 / 锚点）：表仍 253,449 B / 1898 名 / conflicts 0。

### 8.6 待实机验证

1. 进单储罐机器（蒸汽锅炉 / 量子缸 / 流体仓等）看标签是否**稳定不闪**；
2. 多槽机器（大型多方块控制器的流体仓阵列）标签是否正常；
3. 若仍闪：说明 2 ms 窗口与实际重复间隔不符 —— 下一步是在 `drawOverlayLdlib` 里加一次性计数日志
   （每秒打印 `draw` / `dup-skip` 次数）来定性，而不是继续猜。

## ⑬ §9 —— 性能评估（第五阶段：用户 m01754 提问，2026-10）

用户问："目前实现路径的整体性能如何？在边界场景下是否会严重影响渲染性能？"
本章给出**可复现的数字**，并把"能测的"与"只能在实机测的"分开写清楚，不用感觉作答。

### 9.1 方法学（先说清楚口径，避免拿错数字）

| 层 | 工具 | 口径 |
|---|---|---|
| 纯逻辑（查表 / 去重 / DYNAMIC 重建 / 兜底） | `tools/parity/PerfProbe.java`（离线，JDK 21，中位数） | 已测，见 9.2 |
| 绘制（`GuiGraphics.drawString` × 9/行） | `LabelPerf`（实机自计时，`debugLog=true` 才开） | 只能实机测，见 9.5 |
| `ResourceLocation.tryParse` | 无（需要 MC 类） | 未量化，见 9.7 |

`PerfProbe` 的查表口径是对齐 `LabelTables` 真实结构的：字段是
`volatile Map` 包着 `Collections.unmodifiableMap(HashMap)`（`LabelTables.java:43-45`），
探针用同样结构复现，唯一差异是少了那次 volatile 读（~1 ns 量级）。
`LabelTables.reload()` **不能离线调**（方法体引用 `FluidLabelConfig` → 需要 Forge 配置类），
它是一次性成本，本章不测。

跑法：
```
javac -encoding UTF-8 -nowarn -cp build\classes\java\main -d build\gates tools\parity\PerfProbe.java
java "-Dfile.encoding=UTF-8" -cp "build\gates;build\classes\java\main" com.fluidlabel.PerfProbe
```
原始输出存档：`fluid-label/tools/perf-baseline.txt`。

### 9.2 离线实测（1898 显示名 / 3800 注册名 / 83 锚点，表 188.9 KB）

| 项 | 调用频率 | 中位耗时 |
|---|---|---|
| `labelByRegistryName`（HashMap.get） | 每槽每帧 1 次 | **23 ns** |
| `labelByDisplayName` | 注册名 miss 时才走 | **23 ns** |
| `aliasLabel`（默认为空表） | 每槽每帧 1 次 | **8 ns** |
| 快照 `ConcurrentHashMap.get` + 布局键 `String` 拼接 | 每槽每帧各 1 次 | **255 ns** |
| 每槽每帧纯逻辑全链路（查表 ×2 + 快照 ×1 + 键拼接 ×1） | — | **≤0.4 µs**（JIT 会把只取 `length()` 的拼接优化掉，实测 0.08–0.4 µs 波动，取上限用） |
| `DistinctMds.assignLabels`（DYNAMIC 重建） | 每 epoch 一次（≤20 Hz，**仅 DYNAMIC 档**） | 16 名 **0.19 ms** / 50 名 **0.15 ms** / 100 名 **0.22 ms** / 200 名 **0.44 ms** / 500 名 **0.90 ms** / 1898 名 **2.49 ms** ⇒ ≈ **1.3 µs / 可见液体**，线性 |
| `DistinctMds.applyAnchors`（83 锚点 / 200 可见） | 每次重建后一次（**仅 DYNAMIC 档**） | **0.17 ms** |
| `DistinctMds.labelForName`（运行时兜底） | 每个**新**液体一次（非每帧） | 冷 **0.20 µs** / 热 **0.11 µs** |

**结论（纯逻辑层）**：默认 **STABLE 档**下运行时**没有** `assignLabels` —— 每槽每帧的 Java 侧成本
是"3 次 HashMap.get + 1 次 CHM.get + 1 次短字符串拼接 + 1 次 `synchronized` 入集合"，
即 **≈0.3–0.5 µs/槽/帧**。128 个槽同屏 ⇒ **≈40–65 µs/帧**，相对 60fps 的 16.6 ms 预算是 **0.2–0.4%**。
这条路径**不可能**成为渲染瓶颈。

### 9.3 每槽每帧的真实大头：绘制调用数

`LabelRenderer.drawLabel`（`LabelRenderer.java:207`）对每一行文字画 **8 向描边 + 1 次正色 = 9 次
`drawStringNoShadow`**（`outline=true` 默认，第 263-276 行），`maxLines=2` ⇒
**每槽每帧最多 18 次文字绘制**（标签 ≤3 码点时为 9 次），外加 1 次 `font.width` × 行数、
1 次 `pose.pushPose/popPose`、1 次 `RenderSystem.setShaderColor`。
这些 `drawString` 都进同一个 `MultiBufferSource` 批次（`DisplayMode.NORMAL`、无背景），
不会每次 flush，但每次都要走"字形查表 + 4 顶点矩阵变换 + VertexConsumer 写入"。

**只有当同屏标签槽很多时，这一项才可能被感觉到** —— 它随槽数线性增长，且与液体数量、标签长度无关。
量级判断见 9.5 的实机数字（`LabelPerf`），本章不猜。

### 9.4 边界场景逐条结论

| 场景 | 每帧槽数（同屏） | 判定 |
|---|---|---|
| GT 单储罐机器（蒸汽锅炉 / 量子缸 / 流体仓） | 1–4 | 可忽略（纯逻辑 <2 µs，绘制 <40 次调用） |
| GT 多方块控制器（流体仓阵列） | 8–32 | 可忽略 |
| EMI 配方页（含流体槽的 GT 配方） | 10–40 | 可忽略；与 EMI 自身的槽位渲染同量级 |
| AE2 / GT ME 终端（一屏可见液体行） | 30–60 | 可忽略到轻微；绘制调用 270–1080 次/帧 |
| 极端：AE2 大网络全屏流体（滚动到底） | 100–200 | 绘制 900–3600 次/帧 —— **唯一值得实测的场景**，用 `LabelPerf` 看 ms/s |
| DYNAMIC 档 + 200 可见液体 | — | 每 epoch 0.44 + 0.17 = **0.61 ms**，20 Hz ⇒ **12 ms/s**（占单核 1.2%），尖峰 0.6 ms/50 ms，不丢帧 |
| DYNAMIC 档 + 1898 可见（理论极值） | — | 2.49 + 0.17 ≈ **2.7 ms/epoch**，20 Hz ⇒ 54 ms/s（5.4%）；仍不致命，但这是本设计里**最高的一项** |
| 首次出现的派生名液体（表外） | 一次性 | `labelForName` 0.2 µs + 记入 `EXTRA_LABELS`（上限 8192）⇒ 可忽略 |
| 布局缓存溢出（`LAYOUT_CACHE_LIMIT = 4096`） | 一次性 | 触发 `clear()` 后重建若干 `Layout`（每标签几十 ns 级 `font.width` × 行数）⇒ 无感 |
| `FrameContext.observed` 的 `synchronized` | 每槽每帧 | 无竞争偏向锁约 20 ns；渲染线程与 tick 线程的临界区只有 `roll()` 的集合拷贝（O(可见数)，20 Hz） |

### 9.5 实机自计时（`LabelPerf`，本次新增）

`src/main/java/com/fluidlabel/LabelPerf.java` + `LabelRenderer.java:208`（入口）+ `:282`（finally）：

```java
final long perfStart = LabelPerf.enabled() ? System.nanoTime() : 0L;   // 关闭时只多一次配置读
...
} finally {
    pose.popPose();
    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    if (perfStart != 0L) { LabelPerf.record(System.nanoTime() - perfStart); }
}
```

- 开关复用 `debugLog`（默认 false）⇒ **默认零成本**：不取时间戳、不计数（回归门禁 4 项覆盖）。
- 打开后每秒一行 WARN：
  `[fluidlabel] 标签渲染耗时：{N}/s 个标签槽、平均 {x} µs/槽（含描边绘制）、合计 {y} ms/s、单槽峰值 {z} µs`。
- 读法：`合计 ms/s` ÷ 1000 = 标签绘制占用的 CPU 时间比例；`平均 µs/槽 × 每帧槽数` = 每帧附加耗时。
  参考线：**合计 < 2 ms/s 可忽略；> 20 ms/s（2%）才需要动手**。

### 9.6 可优化项（按性价比排序，暂无一项被证明必要）

1. **`parseId` 的每帧分配**（`LabelRenderer.java:447` → `ResourceLocation.tryParse`）：
   每槽每帧一个小对象。把解析结果缓存在 `LdlibFluidCompat.Info` / `Ae2Compat` 的缓存项里
   （键本身就是稳定 id 字符串）即可消除，收益 = 每槽每帧一次分配 + ~100 ns，**改动 10 行以内**。
   只有在 `LabelPerf` 显示绘制成本已经很小时才值得做。
2. **布局键拼接**：`layoutOf` 每槽每帧拼 `label+'\u0001'+scale+'\u0001'+maxChars+'\u0001'+maxLines`
   再查 CHM（`LabelRenderer.java:462-474`）。可换成 `(label.hashCode(), scale, maxChars, maxLines)`
   编码进一个 `long` 键，省掉一次分配 ⇒ 每槽每帧省 ~0.1 µs，**优先级最低**。
3. **描边从 8 向降到 4 向**（配置 `outline`，或减少对角偏移）：直接砍掉一半绘制调用，
   代价是低对比度液体上描边略薄。这是**唯一有量级意义的旋钮**，交给实机数字决定是否暴露。
4. **DYNAMIC 档的重建降频**（仅当 `LabelPerf` 显示重建尖峰可感）：把 20 Hz 重建降到"可见集合变化时才重建"
   （比较 `FrameContext.currentFrame()` 与上次集合）。默认档 STABLE 不涉及。

### 9.7 未量化项（诚实边界）

1. **GPU 侧的填充成本**：`LabelPerf` 测的是 CPU 侧提交耗时，显存/带宽影响不在内。
   标签是小面积半透明文字，且与 EMI/AE2 自身的槽位绘制同批次，预计不显著，但**未测**。
2. **`ResourceLocation.tryParse`**：需要 MC 类，离线不可跑；量级按"短字符串解析 + 一次小对象分配"估 ≈0.1–0.2 µs/槽/帧。
3. **`MiniJson` 解析 188.9 KB 表的启动成本**：一次性，未量化（量级 <100 ms，只发生在启动/配置重载）。
4. **实机帧率影响**：需用户在本机用 `debugLog=true` 取 `LabelPerf` 数字，或做 `enabled=false` 的 A/B 对照。

### 9.8 本次新增/变更

| 文件 | 变更 |
|---|---|
| `src/main/java/com/fluidlabel/LabelPerf.java` | 新增（自计时，`debugLog` 开关，秒级窗口聚合） |
| `src/main/java/com/fluidlabel/LabelRenderer.java` | `drawLabel` 入口 +1 行取时间戳、finally +3 行上报（默认关闭时只多一次配置读） |
| `tools/parity/PerfProbe.java` | 新增（离线性能探针，纯逻辑层） |
| `tools/perf-baseline.txt` | 新增（探针原始输出存档，UTF-8） |
| `tools/render_dedupe_guard.py` | +4 项检查（自计时的零成本纪律），总 11 项 ALL PASS |

产物：`fluid-label/build/libs/fluid-label-0.1.0.jar` = **125,901 字节 / 37 条目 /
sha256 `16A911E7330BEB0614220065FAB6D68DFC8F25AB2D1ED0837C68E9434293EC32`**，已部署到实例（两侧同 sha）。
门禁复核：`audit_table.py` ALL PASS、`check_anchors.py` 83/83、`derived_check.py` ALL PASS、
`render_dedupe_guard.py` 11 项 ALL PASS、`ParityCheck` 1815/1815 + 1898/1898 PARITY OK、
`DynamicAnchorCheck` ALL PASS、`DerivedLabelCheck` ALL PASS、`Ae2LabelCheck` failures 0。

---

## ⑭ §10 —— 配色统一为「深色字 + 浅色描边」（第六阶段：用户 m01954，2026-10）

### 10.1 现象（User said m01954）

> 现在NEI界面的字体是统一白色包边的黑色字体，但AE2和GT内字体会根据液体颜色变为黑字白包边或白字黑包边。建议统一为黑字白包边

也就是说：EMI（用户口中的 NEI）页一直是黑字白边，而 AE2 终端与 GT 机器里同一个液体在不同底色上
会时而黑字白边、时而白字黑边 —— 同一套标签在三个界面观感不一致。

### 10.2 根因：对比度自适应只在"拿得到 tint"的两条路径上生效

- 渲染入口 `LabelRenderer.drawLabel(..., boolean useLightText, ...)`：`true` = 浅色字 + 深色描边，
  `false` = 深色字 + 浅色描边（常量见 `LabelRenderer.java:58-61`）。
- **EMI 路径**（`LabelRenderer.java:184-188`）此前是 `useLightText = true`，仅在 `autoContrast()` 为真时
  才按 `luminance(EmiCompat.tintOf(emiStack)) < 0.55F` 切换；而 `EmiCompat.tintOf` 走反射，
  EMI 1.1.x 拿不到 tint 就返回 `0xFFFFFFFF`（白，注释见 `EmiCompat.java:80-81`）⇒ 亮度 1.0
  ⇒ 恒选深色字。**这就是"EMI 页永远是黑字白边"的原因，不是特意统一过。**
- **AE2 / GT 路径**（`Ae2Compat.tintOf`、`LdlibFluidCompat.tintOf`）能拿到真实液体 tint
  ⇒ `useLightTextFor(tint)`（旧实现 `return !FluidLabelConfig.autoContrast() || luminance(tint) < 0.55F;`）
  真的按亮度切换 ⇒ 亮色液体白字黑边、暗色液体黑字白边，与 EMI 页不一致。

结论：不一致不是"AE2/GT 的 bug"，而是**两条路径的自适应能力不同**；把自适应关掉、统一取深色字，
三处就自动一致。

### 10.3 修法：默认关掉自适应，并把键改名（**不是**只改默认值）

| 改动 | 内容 | 理由 |
|---|---|---|
| 配置项改名 | `autoContrast` → **`adaptiveTextColor`**，`FluidLabelConfig.java:49,81-84` | 用户现有 `config/fluidlabel-client.toml` 已被 Forge 写入 `autoContrast = true`；沿用旧键名会让新默认值对该配置**不生效**。改名后老配置读不到新键 ⇒ 拿到默认 `false`；旧键成为孤儿行，留在 toml 里无害、可手删 |
| 新默认值 | `false` = 所有界面统一「深色字 + 浅色描边」（与 EMI 页一致） | 用户诉求原文；`true` 仍保留旧的自适应行为作为回退开关 |
| EMI 路径 | `LabelRenderer.java:184-187`：`boolean useLightText = FluidLabelConfig.adaptiveTextColor() && luminance(EmiCompat.tintOf(emiStack)) < 0.55F;` | `&&` 短路 ⇒ 关闭时既不取 tint 也不算亮度，热路径还更快 |
| AE2 / LDLib 路径 | `LabelRenderer.java:452-458`：新增按键短路的 `useLightTextForAe2(Object aeKey)` / `useLightTextForLdlib(Object ldlStack)`，各自 `return FluidLabelConfig.adaptiveTextColor() && luminance(….tintOf(key)) < 0.55F;` | 与 EMI 路径同一条判据，三处行为由同一个键决定；**先判开关再取 tint**，关闭自适应时不查 tint 缓存（原写法 `useLightTextFor(Ae2Compat.tintOf(aeKey))` 会急切求值） |
| 注释/契约同步 | `EmiCompat.java:80-81`、`Ae2Compat.java:165-167`、`LdlibFluidCompat.java:112-114`、`LabelRenderer.java:199-205`；`fluid-label/PLAN.md` 配置表 + M2 里程碑 + §9.2 接口契约 | 避免文档里继续写 `autoContrast` |

> `useLightText` 的语义**没有变**（`false` 仍是深色字 + 浅色描边），变的是"谁来选"：
> 以前是 tint 亮度，现在默认由配置决定。

### 10.4 取舍与回退

- 深色字在**极暗液体**（黑曜石粉浆、煤焦油一类）上，可读性完全依赖 `COLOR_OUTLINE_LIGHT = 0xFFF0F0F0`
  的浅色描边；这是用户明确要的观感，默认按此交付。
- 若某整合包/材质下觉得暗液体上不清楚：把 `config/fluidlabel-client.toml` 的
  `adaptiveTextColor` 改成 `true` 即恢复旧的自适应行为（AE2/GT 会重新随 tint 换字色，EMI 页仍是黑字）。

### 10.5 回归门禁（`tools/render_dedupe_guard.py`，11 项 → 16 项）

新增 5 项：`FluidLabelConfig` 中 `adaptiveTextColor` 默认值为 `false`；`LabelRenderer` 的代码体内
（剥掉注释后）不再出现 `autoContrast`；三条路径（EMI / AE2 / LDLib）都经
`FluidLabelConfig.adaptiveTextColor()` 取开关（不允许各写一份判据）；不允许
`useLightTextFor(tintOf(...))` 这种**急切求值**（关闭自适应时不该去查 tint）；AE2 / LDLib 两条路径
改用按键短路的 helper（`useLightTextForAe2(aeKey)` / `useLightTextForLdlib(ldlStack)`，见 10.3 备注）。

### 10.6 待实机验证

1. EMI 配方页：观感应**与改动前完全一致**（本来就黑字白边）。
2. AE2 终端 + GT ME 部件：浅色液体上应不再出现白字黑边。
3. GT 机器流体槽（含单储罐机器）：应全部为黑字白边，且**不再闪烁**（§8 的修复仍须在本轮一并确认）。
4. 若老配置文件里仍写着 `autoContrast = true`：新键缺失 ⇒ 默认 `false` 生效（这正是改名的目的）。

### 10.7 本次新增/变更与产物

| 文件 | 变更 |
|---|---|
| `src/main/java/com/fluidlabel/FluidLabelConfig.java` | 字段 `AUTO_CONTRAST` → `ADAPTIVE_TEXT_COLOR`；键 `autoContrast` → `adaptiveTextColor`，默认 `false`；getter 改名 |
| `src/main/java/com/fluidlabel/LabelRenderer.java` | EMI 路径改 `&&` 短路判定；`useLightTextFor(int)` → `useLightTextForAe2(Object)` / `useLightTextForLdlib(Object)`；javadoc 同步 |
| `src/main/java/com/fluidlabel/compat/{Emi,Ae2,Ldlib}*Compat.java` | 仅注释（tint 现在是"仅自适应时使用"） |
| `tools/render_dedupe_guard.py` | +5 项配色断言，总 **16 项 ALL PASS** |
| `fluid-label/PLAN.md`、`fluid-label-PLAN.md` §10、`README.md` | 配置表 / 里程碑 / 接口契约 / 排障条目 / 产物行同步 |

产物：`fluid-label/build/libs/fluid-label-0.1.0.jar` = **125,984 字节 / 37 条目 /
sha256 `1FF2E56DBD705DCE275F4EA4230760A8F01167AB5CE06EEE425EF66762852045`**，已部署到实例（两侧同 sha、同字节数、同时间戳）。

字节码实证（javap）：`LabelRenderer` 中 EMI 路径 `119: invokestatic FluidLabelConfig.adaptiveTextColor()` → `126: invokestatic EmiCompat.tintOf(...)`（**先判开关才取 tint**）；两个 helper 各自 `0: adaptiveTextColor()` → `7: tintOf(...)`；`FluidLabelConfig` 常量池含 `ldc "adaptiveTextColor"` 与新的注释串，**无 `autoContrast`**。

门禁复核：`render_dedupe_guard.py` 16 项 ALL PASS；`audit_table.py` ALL PASS；`check_anchors.py` 83/83（唯一 WARN = 语料键 `多氯联苯冷却液 ` 带尾随空格）；`derived_check.py` ALL PASS；`ParityCheck src\main\resources\fluidlabel_labels.json tools\anchors.json` = 1815/1815 + 1898/1898 PARITY OK；`DynamicAnchorCheck` ALL PASS；`DerivedLabelCheck` 9688/0 mismatches；`Ae2LabelCheck` failures 0。

> 本条改动**不触碰**标签表、算法、锚点、注入点与几何，因此上述表类门禁本应不变 —— 实测确认全部与 §9 时点一致。
