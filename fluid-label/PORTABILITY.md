# fluid-label 可迁移性分析

> 本文档**只做分析，不改动任何代码**（对应 2026-10 用户要求：“不用再改现有mod了，分析后续可迁移性如何？1.跨整合包、2.跨游戏版本、3.多语言适配”）。
> 所有结论都标注了证据位置（`文件:行号`）。凡属推断而未经实测的，一律标注 **需验证**。

---

## 0. 结论摘要

| 问题 | 可迁移性 | 主要成本 | 结论 |
|---|---|---|---|
| **1. 跨整合包** | **高**（同族 GT 包近乎零成本） | 重新生成标签表 + 核对探针 | 引擎与门禁 100% 复用；包绑定的只有“表 + 锚点 + mixin 目标版本面”。真正缺口是非 GT 系 mod 的自绘流体槽（Mekanism / Create / IE / Thermal 等），每加一个库要新注入点 |
| **2. 跨游戏版本** | **中**，且强烈分层：1.20.x 低风险 / 1.21.x 中风险 / 26.x 高风险 | 重新做“注入点取证” + 平台迁移 + 渲染 API 重核 | 平台无关内核（算法/表/缓存/去重/自计时/全部 Python 门禁）可整块搬走；适配层（`LabelRenderer` + 5 个 mixin + `*Compat`）每个版本都要重写。当前工程已把“Mojang 名 descriptor + `remap = false`”全面落地，这一条在 26.x 去混淆时代反而是**利好** |
| **3. 多语言适配** | **低（现状）→ 中（改造后）** | 设计改动，不是体力活 | **这是三者里唯一需要改算法设计的**：`MAX_LEN = 6` 码点、字级缩写阶梯、5 条中文状态词缀全部按中文标定。注册名索引、两两互异判据、DYNAMIC 快照、渲染几何是语言无关的；display-name 索引与别名表键是语言相关的。**非中文客户端当前会画出中文标签或垃圾截断** |

推荐推进顺序：**跨整合包（1–2 天）→ 多语言（3–5 天）→ 跨版本（每版本 0.5–7 天，看档位）**。

---

## 1. 现状事实清单（论证基础）

### 1.1 依赖面：5 个 mixin 目标

| 注入点 | 目标类 | 方法 descriptor | 所属 mod | 失败后果 |
|---|---|---|---|---|
| 1 | `dev.emi.emi.api.stack.FluidEmiStack` | `render(Lnet/minecraft/client/gui/GuiGraphics;IIFI)V` | EMI ≥1.1 | EMI 页无标签 |
| 2 | `com.gregtechceu.gtceu.api.gui.widget.TankWidget` | `drawStack(Lnet/minecraft/client/gui/GuiGraphics;IIF)V` | GTCEu ≥1.0 | GT 自绘槽无标签 |
| 3 | `appeng.api.client.AEKeyRendering` | `drawInGui(Lnet/minecraft/client/Minecraft;Lnet/minecraft/client/gui/GuiGraphics;IILappeng/api/stacks/AEKey;)V` | AE2 ≥15 | AE2 全界面无标签 |
| 4 | `com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget` | `drawInBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V` | GTCEu ≥1.0 | **仅探针**（渲染已移交注入点 5） |
| 5/6 | `com.lowdragmc.lowdraglib.gui.util.DrawerHelper` | `drawFluidForGui(Lnet/minecraft/client/gui/GuiGraphics;Lcom/lowdragmc/lowdraglib/side/fluid/FluidStack;JIIII)V` / `(…;FFFF)V` | LDLib ≥1.0 | GT/GTO/LDLib 机器槽无标签 |

证据：`fluid-label\src\main\java\com\fluidlabel\mixin\*.java` 的 `@Mixin` / `method =` 行；声明版本见 `fluid-label\src\main\resources\META-INF\mods.toml:28-59`。

### 1.2 数据面：三层标签来源

`fluid-label\src\main\java\com\fluidlabel\LabelEngine.java:62-112` 的优先级阶梯：

1. **DYNAMIC 快照**（`mode=DYNAMIC` 时）——本帧已发布的互异标签，O(1)
2. **外部别名表** `config/fluidlabel-aliases.json` ——**键 = 显示名**（语言相关），支持热重载
3. **内置表**：先 `labelByRegistryName(idStr)`（**语言无关**，3800 条），再 `labelByDisplayName(name)`（**语言相关**，1898 条）
4. **运行时规则兜底** `ruleFallback` → `extraSafeLabel` → `DistinctMds.labelForName`（中文阶梯，结果恒 ≤6 码点，`LabelEngine.java:224-251`）

### 1.3 算法面：中文标定的常量

| 常量 | 位置 | 说明 |
|---|---|---|
| `MAX_LEN = 6` | `DistinctMds.java:59` | 6 码点 = 渲染几何 3 字 × 2 行 |
| `PREFIX_ABBREV` | `DistinctMds.java:74-95` | 长前缀→简称，全是中文（`哈斯特洛依合金`→`哈氏`、`不锈钢-`→`不锈`…） |
| `TOKEN_ABBREV` | `DistinctMds.java:134` | 中文词级缩写 |
| `STATE_PREFIX` | `DistinctMds.java:452-455` | `{熔融→熔, 液态→液, 气态→气}` |
| `STATE_SUFFIX` | `DistinctMds.java:459-461` | `{等离子体→浆, 蒸气→汽}` |
| 5 个运行期模板 | `LabelEngine` + `DistinctMds.java:498` 注释 | GT 的 `gtceu.fluid.molten = 熔融%s` 等，**运行期拼接**，不在表内 |
| ASCII 处理 | `DistinctMds.java:526-553, 1042-1048` | **只用于型号/牌号**（`2090`、`-G`、`N75`），不构成拉丁语言策略 |

### 1.4 构建面

- `fluid-label\build.gradle:13` 编译 toolchain **JDK 21**（vendored AE2/GTCEu jar 是 classfile 65）；`:19-21` `options.release = 17` 把产物钉到 classfile 61
- `build.gradle:50-55` 四个依赖全部 `compileOnly`（EMI/AE2/GTCEu/LDLib，本地 vendored jar）⇒ **产物零外部引用**
- `fluid-label\src\main\resources\fluidlabel.mixins.json`：5 个 client mixin、`required:false`、`defaultRequire:0`、`compatibilityLevel:JAVA_17`
- 全工程 mixin 一律 `remap = false` + Mojang 名 descriptor

---

## 2. 跨整合包

### 2.1 什么能直接复用

**平台无关、可直接搬走的部分（约 80% 的代码行）**：

- 全部算法：`DistinctMds`（两两互异判据、阶梯、锚点消解）、`LabelEngine`、`FrameContext`、`LabelTables`、`LabelPerf`
- 全部生成器与门禁：`tools/gen_labels.py`、`audit_table.py`、`check_anchors.py`、`derived_check.py`、`render_dedupe_guard.py`、`tools/parity/*.java`（含 `PerfProbe`）
- DWARF 级的诊断资产：`InjectionProbe`（6 个注入点，全部 WARN 级）+ `EmiSelfCheck` / `Ae2SelfCheck`——**这是可迁移性里最被低估的东西**：换包后"标签没出现"能在日志里一眼定性到具体注入点，而不是靠猜

### 2.2 什么与包绑定

| 绑定项 | 换包后 | 处理 |
|---|---|---|
| 标签表（1898 显示名 / 3800 注册名，源自 gtceu + gtocore 的 `material.*`） | 名字集合变化 | `python tools/gen_labels.py --lang <新包 assets 根> --out src/main/resources/fluidlabel_labels.json`（脚本已支持任意 `<mod>/lang/<locale>.json` 目录，`gen_labels.py:757-830`） |
| 锚点表 83 条（`fluidlabel_anchors.json`） | GTO 特定人工挑选 | 重新挑或留空（空表不影响功能，只是少了"人工消歧"） |
| mixin 目标的**版本面** | 见 §3 | 探针日志定性 |

补充：表里 GTO 独有的 1247 条名字，在只有原版 GTCEu 的包里是**死重**（`byRegistryName` 不命中就自然失效，无害）；反之 GTO 独有的液体在纯 GTCEu 包里则走规则兜底。

### 2.3 覆盖矩阵（换包后哪些界面会有标签）

| 注入点 | 前提 | 跨包覆盖 |
|---|---|---|
| 1 EMI | 包里有 EMI ≥1.1 | **任何包**（EMI 配方页/物品列表里的流体） |
| 3 AE2 | 包里有 AE2 ≥15 | 任何有 AE2 的包（含非 GT 包） |
| 5/6 LDLib | 包里有 LDLib ≥1.0 | **任何用 LDLib 原语画流体槽的 mod**，GT 只是其中之一 |
| 2 GTCEu | 包里有 GTCEu 且 `drawStack` 签名未变 | GT 系专属 |
| 4 GT+AE2 | 同上 | 仅探针 |

**明确缺口**：Mekanism、Create、Immersive Engineering、Thermal 等**各自实现**流体槽渲染的 mod 不在注入面内（这些库没有统一的"画流体"原语，LDLib 是特例）。每加一个库 = 一次"注入点取证"（选点 + javap 验证几何 + 探针），成本见 PLAN §7.3 的方法论——该表就是为这件事写的模板。

### 2.4 建议的交付模型（若将来要公开/多包分发）

1. **表外置**：`LabelTables` 已有"外部 JSON + 热重载"的先例（`config/fluidlabel-aliases.json` + `refreshAliasesIfChanged()`），把内置表也改成"jar 内默认 + `config/fluidlabel/labels_*.json` 覆盖"即可，**不需要改引擎**
2. **生成器 CLI 化**：`gen_labels.py` 已是 `--lang/--out/--report/--anchors` 形态，包作者可自助出表
3. **锚点按语言分文件**：见 §4
4. **保留探针 WARN**：换包后第一时间知道注没注进去（注意 `defaultRequire:0` 是**静默降级**设计，必须有探针兜底，否则"没标签"无法归因）

---

## 3. 跨游戏版本

### 3.1 三档风险

#### 档位 A：1.20.x 内部（1.20.1 → 1.20.4）—— 低风险，约 0.5–1 天

- 只改 `build.gradle` 的 MC/Forge 版本、`mods.toml` 的 `versionRange`、`pack.mcmeta`
- EMI 1.1.x / AE2 15.x / LDLib 1.0.x 在 1.20.x 线的类名与签名**大体一致**（需逐版本 javap 核对，不能假设）
- Java 字节码无需变（1.20.4 仍是 classfile 61）
- 必须重跑：六探针 + 全部 Python 门禁 + `ParityCheck`

#### 档位 B：1.21.x（NeoForge 主流线）—— 中风险，约 3–7 天

需要处理的新增项（**均需验证**）：

1. **平台迁移**：`mods.toml` → `neoforge.mods.toml`；`@Mod` 注解参数与事件总线（`IEventBus` 构造注入 / `@EventBusSubscriber`）与 Forge 不同；`TickEvent.ClientTickEvent`（本项目 20 Hz 推进 epoch 的挂点，`FluidLabelMod.java:114-116`）在 NeoForge 叫 `ClientTickEvent.Post`
2. **渲染 API**：1.20.5+ 起 `GuiGraphics` 系列方法签名有变动，需要重核 `drawLabel` 用到的三件事——`fill(int,int,int,int,int)`、`drawString(Font,String,int,int,int,boolean)`、pose 栈（`LabelRenderer.java:242-284`）
3. **注入点重新取证**：GTCEu 1.21 分支改用 LDLib2，`com.lowdragmc.lowdraglib.gui.util.DrawerHelper#drawFluidForGui` 是否仍存在**需验证**；AE2 17.x+ 的 `AEKeyRendering#drawInGui` 是否仍是 `public static` **需验证**（本工程已因 static/instance 不匹配踩过一次坑：`InvalidInjectionException 'static' modifier of handler method does not match target in appeng/api/client/AEKeyRendering`）
4. Java 字节码：1.20.5+ 需要 `release = 21`（改一行）

#### 档位 C：26.x（去混淆 mojmap / NeoForge）—— 高风险，约 1–2 周

- **利好**：26.1+ Minecraft 不再混淆 ⇒ 本工程已全面 `remap = false` + Mojang 名 descriptor 的写法天然契合；Mixin 的 remap 环节反而简化
- **成本项**：1.21.5–26.x 期间 GUI 渲染经历 render state 重构（`GuiGraphics` / `guiRenderState` 体系），文本与填充的入口名字/签名会变，`LabelRenderer` 的绘制段几乎要重写；Java 25 toolchain
- **建议**：等到有明确的“要在 26.x 上跑”的需求再做，届时按 §3.3 的分层结构做适配层

### 3.2 复用度分层（跨版本的核心结论）

| 层 | 内容 | 复用度 |
|---|---|---|
| **内核（平台无关）** | `DistinctMds`、`LabelEngine`（去掉 MC 类型参数）、`LabelTables`、`FrameContext`、`LabelPerf`、`tools/gen_labels.py`、7 个门禁、`tools/perf-baseline.txt` | **100%**（已可离线编译+离线测试：`javac ... tools/parity/*.java` 无需 MC） |
| **适配层（每版本重写）** | `LabelRenderer`（`GuiGraphics` 调用段）、5 个 mixin、`*Compat`（EMI/AE2/LDLib）、`LabelTables` 的资源路径/语言钩子、`mods.toml`、`mixins.json`、`build.gradle` | **0%（重写）**，量级约 5 个类 × 100–200 行 |

> 这个分层不是设想：`LabelTables` 已经**没有任何 Minecraft import**，`DistinctMds`/`FrameContext`/`PerfProbe` 已能在纯 javac 下跑——只是因为工程目前是单模块，才没在目录上体现。

**结构建议（若真要多版本维护）**：

```
fluid-label/
  core/                  # 平台无关：算法 + 表 + 门禁（candidate: 纯 java-library 子项目）
  platform/forge-1.20.1/ # LabelRenderer + mixin + Compat + mods.toml
  platform/neoforge-1.21/# 同上，重写
  tools/                 # 生成器 + 门禁（跨版本共用）
```

单模块 + `git branch per version` 也能活，但每次上游改动都要手工三向合并；多项目/多 sourceSet 能避免这一点。

### 3.3 每个新版本的最小验证清单

1. `mc_gradle build` 成功，产物 classfile 版本与目标 JRE 匹配（`javap -v` 抽查 `LabelPerf`）
2. javap 核对 5 个 mixin 目标的**签名是否仍存在**（本工程 `tools/parity/*.java` 里的 `Ae2SelfCheck` 就是干这个的，可扩成通用清单）
3. 启动后日志出现 6 条 `[fluidlabel] 注入点N 已生效`（WARN 级）
4. 三个界面各看一遍：EMI 配方页 / AE2 终端 / GT 机器（单一储罐 + 多槽）
5. 九个门禁全绿（尤其 `render_dedupe_guard.py` 与 `ParityCheck`）
6. 若 `debugLog=true`：`LabelPerf` 每秒一行，`合计 ms/s` 应 <2

---

## 4. 多语言适配（最需要设计的一项）

### 4.1 哪些是语言无关 / 语言相关

| 语言无关 ✓ | 语言相关 ✗ |
|---|---|
| 注册名索引（3800 条，`labelByRegistryName`） | display-name 索引（1898 条，客户端换语言即失配） |
| “两两互异”判据本身（集合唯一性） | 候选阶梯（字级截断/中文缩写表） |
| DYNAMIC 快照、`MAX_EXTRA_LABELS`、`NAME_CACHE` | `MAX_LEN = 6` 码点预算（按 3 字×2 行标定） |
| 帧去重时间窗（2 ms）、`LabelPerf` | 5 条中文状态词缀 + 5 个中文模板 |
| 渲染几何（像素矩形） | 别名表键（显示名） |

### 4.2 非中文客户端**当前的实际行为**（诚实说明）

假设玩家把语言切成 `en_us`：

1. `labelByDisplayName("Hydrochloric Acid")` 未命中
2. `labelByRegistryName("gtceu:hydrochloric_acid")` **仍命中** ⇒ 该液体照样显示**中文**标签（标签字符串是构建时烘死的中文）
3. 表外液体走 `DistinctMds.labelForName`（中文阶梯）⇒ 英文名被头尾截断到 6 码点，再被渲染层切成 3 码点 × 2 行 ⇒ 形如 `Hyd` / `roc` 的**垃圾**
4. 别名表若按中文显示名写的，同样失配

⇒ 结论：**现状不是“不支持多语言”，而是“会安静地产生垃圾标签”**。这是需要优先处理的正确性问题，不是增强项。

### 4.3 适配方案（按改动量从小到大）

| # | 改动 | 位置 | 量级 | 效果 |
|---|---|---|---|---|
| 1 | **语言守卫**：客户端语言与表语言不符时，`LabelSource.TABLE_ONLY` 或 `enabled=false` | `FluidLabelMod` / `LabelEngine.labelFor` | ~20 行 | 消除“垃圾截断”，最低成本止血 |
| 2 | **表按语言分文件**：`fluidlabel_labels_<locale>.json`，按 `Minecraft.getInstance().getLanguageManager().getSelected()` 选表 | `LabelTables` 资源路径 | ~30 行 | 一份 mod 支持多语言标签 |
| 3 | **语言切换热重载**：监听客户端资源重载，重选表 + `LabelEngine.invalidateCache()` | 已有 `refreshAliasesIfChanged()` 先例 | ~20 行 | 换语言不必重启 |
| 4 | **别名表键改注册名优先** | `LabelTables.aliasLabel` 调用点 | ~10 行 | 翻译变动不再打断用户别名 |
| 5 | **状态词缀自动发现**：不硬编码 5 条中文，改为扫目标语言 lang 文件里含 `%s` 的模板（GT 的 `gtceu.fluid.molten = 熔融%s` / `Molten %s`） | `gen_labels.py` | ~50 行 Python | 词缀表语言自适应 |
| 6 | **拉丁策略**：`LabelStrategy` 接口 + `TokenStrategy`（首词 / 首字母缩写 `Hydrochloric Acid → HCl` / 头词+尾词），替代“按码点截断” | `DistinctMds` 拆接口 | 3–5 天（含测试） | 非 CJK 语言真正可用 |
| 7 | **预算改双约束**：像素宽（`font.width`）+ 码点上限，按语言 profile（中文 3×2 / 拉丁 6×1 等） | `LabelRenderer.layoutOf` + 配置 | 1–2 天 | 拉丁名不再被 3 字切碎 |
| 8 | **锚点表按语言分文件**：`anchors/<locale>.json` | 资源 | 轻 | 人工消歧可本地化 |

### 4.4 一个重要观察

第 5 项揭示：**GT 的语言文件本身就把词缀模板化了**（`熔融%s` / `Molten %s`）。这意味着“状态词缀表”不需要人工维护——它是可以从目标语言资源里**推导**的。同样的推导思路也可以用在模板发现上（本项目第二阶段就是为了处理运行期模板派生名才加的状态词缀拆分，`DistinctMds.java:498-507`）。

---

## 5. 总账

| 方向 | 可行性 | 估算 | 依赖前提 | 建议动作 |
|---|---|---|---|---|
| 跨整合包 | 高 | 1–2 天 | 目标包的 lang 资源 | 重生成表；探针核对；缺口库按 §2.3 补注入点（每个库约 0.5–1 天） |
| 多语言 | 中（需设计） | 止血 0.5 天 / 完整 3–5 天 | 无需新版 MC | 先做 §4.3-1（语言守卫），再按需推进 2/5/6 |
| 跨版本 1.20.x | 高 | 0.5–1 天/版本 | — | 改 build/元数据 + 全量门禁 |
| 跨版本 1.21.x | 中 | 3–7 天 | LDLib2 与 AE2 17.x 的 API 需重新取证 | 按 §3.3 清单做一次完整“注入点取证” |
| 跨版本 26.x | 低（当前） | 1–2 周 | 渲染重构面 + Java 25 | 有明确需求再做 |

**一句话**：这套东西的**内核**（算法 + 表 + 门禁 + 诊断）是可迁移资产；**适配层**是易耗品，每个新平台/版本都要重做一遍——把这两层在目录上分开，是后续任何迁移动作里性价比最高的一步。

---

## 6. 本轮边界

本文档为纯分析：**未修改任何 Java/Kotlin 源码、资源、构建脚本或配置**。文档本身新增 `fluid-label\PORTABILITY.md`。
文中标注 **需验证** 的条目（LDLib2 / AE2 17.x / 26.x 渲染 API）均未实测，实施前必须按 Iron rule 2 走“先查参考、再 javap 取证”的流程。
