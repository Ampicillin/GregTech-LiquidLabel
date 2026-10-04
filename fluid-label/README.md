# fluid-label

MC **1.20.1 / Forge / 纯客户端** 模组：给 EMI 配方界面里的每个液体图标（16×16）叠加一个可区分的
中文短标签（**≤6 汉字**），让人不依赖颜色也能分辨 GT 化工配方里的上千种液体。
悬停时 EMI 原本的完整中文名 tooltip 保持不变。

同一条标签也叠在 **AE2 的物品网络界面**上（第二阶段，2026-10）：ME 终端 / 合成确认 / 合成状态 /
样板供应器 / 网络工具 / 存储元件 tooltip 等一切走 `AEKeyRendering.drawInGui` 的地方（注入点 3），
以及 **GT 的 ME 部件界面**里的流体槽（`AEFluidDisplayWidget.drawInBackground`，注入点 4）。
两侧共用同一个 `LabelEngine` ⇒ 同一液体的标签**逐字一致**，且没有新增配置项。

第三阶段（2026-10）：**GT/GTO 机器界面**里的液体槽（蒸汽锅炉、流体仓、量子缸、缓冲机、焦炉、
创造储罐、样板供应器……，以及幽灵/过滤流体槽与 ME 流体配置槽）也叠同一份标签。
做法**不是**逐个控件写 mixin，而是注入 LDLib 画液体的那个原语
`DrawerHelper#drawFluidForGui`（注入点 5/6）—— 常量池扫描确认 GT/GTO/LDLib 机器 UI 的液体图标
全部经过它，调用者只有 7 个类，爆炸半径可控。三阶段共用同一个 `LabelEngine`。

标签规则（v3 / T1，用户 m00934 定的）：
- 材料名本身 **≤6 字 ⇒ 原样显示，一字不改**（实测 1476/1898 = 77.8%）；
- **>6 字** ⇒ `前缀简称 + 核心压缩` 压到 ≤6 字，长前缀风格如 `重度加氢裂化的→重氢裂`、`轻度蒸汽裂化的→轻蒸裂`；
- 自动结果不好看的（牌号族、超长化学名）由 `tools/anchors.json` 的人工关键字覆盖；
- 池内所有标签**两两互异**；材料名撞名（同显示名多条材料）共享同一标签。

- modId：`fluidlabel`（钉死；**不是** kebab-case，见 `PLAN.md` §③ / §④.5）
- package：`com.fluidlabel`
- 主类：`com.fluidlabel.FluidLabelMod`
- mixin config：`fluidlabel.mixins.json`（jar 根，由 jar manifest 的 `MixinConfigs` 注册）
- 目标实例：`GregTech.Odyssey-0.5.6-beta`

完整设计、注入点字节码证据、验收标准 A1–A8 见仓库根目录的 `PLAN.md`。

## 构建

```sh
# Windows
gradlew.bat build

# Linux / macOS
./gradlew build
```

产物：`build/libs/fluid-label-<version>.jar`（经 `reobfJar` 重映射），拖入实例的 `mods/` 启动。

> ⚠ 本仓库的构建必须经宿主提供的构建工具执行（PLAN §⑧ 环境风险）；
> 不要绕过封装直接调 `gradlew`。

## 编译依赖（本机不走 maven）

EMI / AE2 / GTCEu / LDLib **只在编译期可见**，由本地 jar 提供——`maven.terraformersmc.com` 等在本机不可达：

```
libs/emi-1.1.24+1.20.1+forge.jar                        # 1,121,750 字节，从目标实例 mods/ 拷入
libs/appliedenergistics2-forge-1.20.1-15.267.4.jar      # 8,464,692 字节，从 gtocore 的 jarJar 里解出
libs/gtceu-1.20.1-forge-1.20.1-26.7.3.jar               # 11,682,928 字节，同上
libs/ldlib-forge-1.20.1-1.0.50.jar                      # 3,114,158 字节，从目标实例 mods/ 拷入
```

> AE2 在本整合包里**不是**独立 mod：它是 GTO 用 jarJar 内嵌的 `gtocore-forge-1.20.1-0.5.6-beta.jar`
> → `META-INF/jarjar/appliedenergistics2-forge-1.20.1-15.267.4.jar`（= 上游被 GTO 打过补丁的 AE2）。
> 所以上面三个 jar 是"运行时真实存在的字节"的副本，编译期对齐用的就是它们，不是 maven 上的版本。

`build.gradle` 里的接线（`compileOnly files(...)` + jar manifest 的 `MixinConfigs`）由
D 阶段按 `PLAN.md` §6.5 增补；EMI/AE2/GTCEu/LDLib **不得**被打进产物（运行期由整合包提供）。

⚠ **编译 JDK 必须是 21，但产物必须仍是 Java 17 字节码**：上面 vendored 的 AE2/GTCEu 是
classfile **65（Java 21）**，javac 17 会直接拒绝读它（`类文件具有错误的版本 65.0, 应为 61.0`）。
因此 `build.gradle` 用 `JavaLanguageVersion.of(21)` + `options.release = 17`。
目标实例跑的是 **JDK 25**（`instance.cfg` 的 `JavaPath=…/jdk25/bin/javaw.exe`，Forge 47.4.20 /
MC 1.20.1），61/65 都能加载。

## 当前状态（M1–M5 完成，已装入目标实例）

全部源码就位：`FluidLabelConfig` / `LabelEngine` / `LabelTables` / `DistinctMds` / `LabelRenderer` /
`FrameContext` / `InjectionProbe` / `EmiSelfCheck` / `Ae2SelfCheck` / `compat/EmiCompat` /
`compat/Ae2Compat` / `compat/LdlibFluidCompat` / `mixin/FluidEmiStackMixin` / `mixin/TankWidgetMixin` /
`mixin/AeKeyRenderingMixin` / `mixin/GtAeFluidDisplayMixin` / `mixin/DrawerHelperFluidMixin` /
`tools/gen_labels.py` / `src/main/resources/fluidlabel_labels.json`。

`build` 通过（Gradle 8.8 / ForgeGradle 6.0.54 / **JDK 21 toolchain + `options.release = 17`**），
产物已拷进 `…/instances/GregTech.Odyssey-0.5.6-beta/minecraft/mods/fluid-label-0.1.0.jar`。
**EMI / AE2 两侧已由用户实机验收通过；注入点 5/6（GT 机器液体槽）尚未经实机验证**（需要重启游戏，
看下面的探针日志）。

## 已验证（本轮，命令可复现）

| 检查 | 结果 |
| --- | --- |
| `build` 任务 | **BUILD SUCCESSFUL**（`clean` 20s / 修错后全量 2m25s / 增量 17s） |
| 产物 | `build/libs/fluid-label-0.1.0.jar` = **125,984 字节**，sha256 `1FF2E56DBD705DCE275F4EA4230760A8F01167AB5CE06EEE425EF66762852045`，与实例内副本一致（37 个条目） |
| 字节码级别 | 全部 **33 个** `com/fluidlabel/**` class 均为 `magic 0xCAFEBABE major 61`（Java 17；`options.release = 17` 生效） |
| jar 内注入点 | `fluidlabel.mixins.json` 登记 **5** 个 mixin（`FluidEmiStackMixin` + `TankWidgetMixin` + `AeKeyRenderingMixin` + `GtAeFluidDisplayMixin` + `DrawerHelperFluidMixin`）；`TankWidgetMixin.class`/`DrawerHelperFluidMixin.class` 常量池含 `RETURN`、不含 `TAIL` |
| 注入点 5/6 的 handler 是 static | `DrawerHelperFluidMixin` 两个 handler 在 jar 内 javap 为 `private static void fluidlabel$overlayLdlibTank(GuiGraphics, FluidStack, long, int, int, int, int, CallbackInfo)` 与 `…$overlayLdlibTankFloat(…, float, float, float, float, CallbackInfo)` —— 目标是 `public static`，handler 必须同 static 性（见下表「handler 修饰符」行） |
| 主类不依赖 EMI/AE2/GTCEu | `FluidLabelMod.class` 常量池**无** `dev/emi`、`appeng`、`com/gregtechceu`、`com/lowdragmc`（自检走 `Class.forName("com.fluidlabel.EmiSelfCheck"/"…Ae2SelfCheck")` 反射加载） |
| EMI/AE2/GTCEu/LDLib 未被打进产物 | `dev/emi/*.class` = 0、`appeng/*` = 0、`com/gregtechceu/*` = 0、`com/lowdragmc/*` = 0、`net/minecraft/*` = 0 |
| 标签表 | `byDisplayName` 1898 条 / `byRegistryName` 3800 条 / 冲突 0；**≤6 字 100%**；人工关键字 83 条；内嵌资源 253,449 字节（sha256 `89CF10BA116F561114EB929909B3DDAE8E46B93B9DF2CAD7558FBF612E4D04C9`） |
| 随包锚点 | `src/main/resources/fluidlabel_anchors.json` = 4,691 字节 / 83 条（sha256 `61590307…50B9`），jar 内与工作区**逐字节相同**；DYNAMIC 档靠它跟 STABLE 档对齐 |
| `tools/audit_table.py` | **ALL PASS**（R1 ≤6 码点 / R2 1898 条两两互异 / R3 ≤6 字名字原样 / R4 conflicts 空 / R5 注册名标签有效 / R6 用户手验样例） |
| `tools/check_anchors.py` | 锚点 83 条：长度/禁用字符/互异全过，**83/83 在表里生效**，键全部精确命中语料 |
| `tools/derived_check.py` | 5 个 GT 流体模板 × 词库 = **9695 条派生名**（走阶梯 4954 / 原样 4741）：首候选 ≤6 码点 / 两两互异 / 阶梯有解 / 不撞表标签 —— **四条门禁全 PASS**；含用户实测的熔融+牌号样例 |
| `tools/diff_labels.py` | 换代对比工具：按"牌号字符保留数"分变好/变差/持平，防止保牌号档把已发布的好标签改坏 |
| Java↔Python 算法同构 | `tools/parity/ParityCheck <table> tools/anchors.json`：**1815/1815 完全一致**，Java 侧 1898 个标签两两互异 |
| 派生名 Java 复算 | `tools/parity/DerivedLabelCheck`（读 `_diag/derived_replay.txt` 复算 9688 条 + 8 条用户样例）：**VERDICT: ALL PASS** |
| DYNAMIC 档锚点叠加 | `tools/parity/DynamicAnchorCheck`：**VERDICT: ALL PASS**（锚点覆盖注册名键+显示名键、未命中者不变、撞车就地重算、真实语料 1898 名互异且锚点 **83/83** 生效） |
| **AE2 侧标签门禁** | `tools/parity/Ae2LabelCheck`：**VERDICT: ALL PASS**（语料 = `gtceu`/`gtocore` lang 里 `material.*`/`fluid.*` 的 656/1259 条显示名 ⇒ 基名 1914 ⇒ 派生 11474 条；①逐条 `labelForName` 超长 **0**、最长 6 码点、无颜色码 ②5 个状态族各 1914 条，可见形式撞车 **0**、超长 **0** ③`assignLabels` 跑两遍 mismatch **0** ④7 条用户样例逐字 ⑤锚点 **83/83**、454 个显示名互异 ⑥缓存隔离 4/4） |
| **handler 修饰符（实机教训）** | `@Inject` 的 handler 必须与目标**同 static 性**：`AeKeyRenderingMixin` 的目标是 `public static void drawInGui(...)` ⇒ handler 必须 `static`（jar 内 javap：`private static void fluidlabel$overlayAe2(...)`）；`DrawerHelperFluidMixin` 的两个目标也都是 `public static void drawFluidForGui(...)` ⇒ 两个 handler 都是 `private static`；`GtAeFluidDisplayMixin` 的目标是实例方法 ⇒ handler 必须**非** static。写错只会得到一行 **WARN**（`InvalidInjectionException: 'static' modifier of handler method does not match target`），`require = 0` 下游戏照常跑、界面一根标签都不画 |
| **注入点 5/6 的几何（javap 实证）** | `DrawerHelper#drawFluidForGui(GuiGraphics, FluidStack, float x, float y, float w, float h)` 的四个 float 就是**像素矩形 `(x,y,w,h)`**（内部 `cx=(int)(w/16)`、`bottom=y+h`，自底向上铺 16×16 贴图，**不是 UV**）；7 参 int 重载传的是完整内缩矩形（`Position.x+1, Position.y+1, Size.width-2, Size.height-2`）。**但 5 参 float 重载在 LDLib 的 `TankWidget` 里拿到的是"填充后的切片"**（`x+u*innerW, y+v*innerH, innerW*drawnW, innerH*drawnH`）⇒ 低液位时标签会跟着切片缩小/移动，所以 `MIN_SLOT = 10` 以下的矩形直接不画（挡进度条底纹） |
| **注入点 4 已降级为探针** | `AEFluidDisplayWidget` 的图标走的是 7 参 `drawFluidForGui`（javap：传 `bipush 16 bipush 16` + `stackX/stackY = position.x+1/y+1`），已被注入点 5 覆盖 ⇒ `GtAeFluidDisplayMixin` **撤掉了渲染调用、只留探针**，否则同一个图标会被画两遍（描边叠粗） |
| **去重 memo 用时间窗，不能用 `FrameContext.epoch()`（实机教训）** | AE2/LDLib 两条路径各有一条"同一次调用栈去重"的单条 memo。**判据必须是 `System.nanoTime()` 差值 < `DEDUPE_WINDOW_NANOS`（2 ms）**：epoch 由客户端 tick（`TickEvent.ClientTickEvent` `Phase.END`，**20 Hz**）推进，而渲染 60+ fps ⇒ 连续 3～7 帧 epoch 相同，按 epoch 去重会把后面几帧整体挡掉，标签**以 20 Hz 亮灭**（用户实测"一秒闪烁 10–20 次"，2026-10，详见 `fluid-label-PLAN.md` §8）。EMI 侧 `drawSlot` 没有 memo 所以从不闪；AE2 界面液体图标多、单条 memo 不会连续命中；GT 机器常常只有一个液体槽 ⇒ 必现。回归门禁：`tools/render_dedupe_guard.py` |
| **性能（离线实测，`tools/perf-baseline.txt`）** | 纯逻辑层：查表 **23 ns/次**、`aliasLabel` 8 ns、快照 CHM get + 布局键拼接 **255 ns** ⇒ 每槽每帧 **≤0.4 µs**（128 槽同屏 ≈ 40–65 µs/帧 = 60fps 预算的 0.2–0.4%）。真正的量级在**绘制调用数**：每行 8 向描边 + 1 正色 = 9 次 `drawString`，最多 2 行 ⇒ **每槽每帧最多 18 次**。DYNAMIC 档才会走 `assignLabels`（≈1.3 µs/可见液体，每 epoch 一次，≤20 Hz）：200 可见 ≈ 0.61 ms/epoch ≈ 12 ms/s；默认 **STABLE 档为零** |
| **实机自计时开关** | `debugLog = true`（默认 false）时 `LabelPerf` 每秒 WARN 一行：`标签渲染耗时：{N}/s 个标签槽、平均 {x} µs/槽、合计 {y} ms/s、单槽峰值 {z} µs`。读法：`合计 ms/s` < 2 可忽略，> 20（2%）才需处理。关闭时热路径只多一次配置读（回归门禁 4 项覆盖） |
| **配色统一：默认「深色字 + 浅色描边」（用户 m01954）** | 配置项 `adaptiveTextColor`（**由 `autoContrast` 改名而来**）默认 **`false`** ⇒ EMI / AE2 / GT 三处一律深色字 + `0xFFF0F0F0` 描边，与 EMI 页原本的观感一致。改名前 EMI 页之所以"一直是黑字白边"，只是因为 `EmiCompat.tintOf` 拿不到 tint 时返回白色（亮度 1.0 ⇒ 恒选深色字），并非特意统一过；AE2/GT 能拿到真实 tint 所以才时黑时白。设 `true` 恢复旧的自适应行为。**键名必须换掉**：老 `config/fluidlabel-client.toml` 里已被 Forge 写入 `autoContrast = true`，沿用旧名会让新默认值失效（详见 `fluid-label-PLAN.md` §10） |

> 标签长度单位已统一为**码点**（`DistinctMds` 的 `cpLen`/`cpHead`/`cpTail`，与渲染层 `buildLayout` 的切行口径一致）。
> 旧版 Java 用 UTF-16 `char` 计长度，会把 `𬭳`/`𫓧`/`𬭶` 这类补充平面汉字算成 2 字，导致无谓缩短
> （`熔融三氯𫓧烷` 只剩 6 码点却被压成 `熔三氯𫓧烷`）；修正后 `ParityCheck` 的历史唯一偏差
> （`𬭳掺杂的纳米管`）消失，**1814/1815 → 1815/1815**。
>
> 本机网络注意：直连只有 ~13 KB/s（wrapper 10s 超时），`gradle.properties` 末尾的
> `systemProp.*.proxy*`（`127.0.0.1:7897`）是**必须**的；换机器可整段删掉。

## 出问题怎么查（注入点探针）

`@Inject(..., require = 0)` 目标漂移时是**静默**的，所以每个注入点首次被调用时都会打一行日志（不受 `debugLog` 影响）：

```
[fluidlabel] 注入点1 已生效：FluidEmiStack#render      ← EMI 侧栏 / 普通槽位
[fluidlabel] 注入点2 已生效：TankWidget#drawStack      ← GT 机器配方界面的储罐槽
[fluidlabel] 注入点3 已生效：AEKeyRendering#drawInGui  ← AE2 全部界面（ME 终端/合成/样板…）
[fluidlabel] 注入点4 已生效：AEFluidDisplayWidget#drawInBackground ← GT 的 ME 部件流体槽（现仅探针）
[fluidlabel] 注入点5 已生效：DrawerHelper#drawFluidForGui(int 重载)   ← GT/GTO 机器界面的液体槽（主路径）
[fluidlabel] 注入点6 已生效：DrawerHelper#drawFluidForGui(float 重载) ← LDLib TankWidget（填充切片）
```

> 这些行现在是 **WARN 级**（`loaded` 行也是）：2026-10 有两轮实机会话里本模组**全部 INFO 行都缺失**
> （连无条件的 `loaded` 都没有），原因未查明，而 WARN 完整保留 ⇒ 诊断锚点不能赌 INFO 落盘。
> 每个注入点只打一次，不会刷屏。

- 进了配方界面**只有注入点1**、图标上没有文字 ⇒ 注入点 2 没装配：看启动日志里 `EmiSelfCheck` 的两行（会点名"目标方法不存在"或"注入器状态未知"）。
- 打开 ME 终端**图标上没有文字** ⇒ 注入点 3/4 没装配：看启动日志里 `Ae2SelfCheck` 那几行（AE2 版本不符/`drawInGui` 签名漂移/注入器未装配都会点名）。**并且**去日志里搜 `Mixin apply failed fluidlabel` —— 2026-10 就是这条 WARN 点出了"注入点 3 的 handler 少写 `static`"（详见 `fluid-label-PLAN.md` §6.10）。
- 打开 GT 机器界面（蒸汽锅炉 / 量子缸 / 流体仓 / 焦炉…）**图标上没有文字** ⇒ 注入点 5/6 没装配：看启动日志里 `Ae2SelfCheck` 第 ⑤ 段的两行（`注入点5 DrawerHelper#drawFluidForGui(int 重载) 目标在：… → 注入器已装配`），再去搜 `Mixin apply failed fluidlabel`。若连 `注入点5` 的探针行都没有，说明该界面根本没经过 `DrawerHelper#drawFluidForGui` —— 用 `javap -c` 看那个控件的 `drawInBackground` 实际调了什么，再挑新注入点（别猜）。
- 机器界面上标签**位置怪**（贴着液面、只在下半截）⇒ 那是注入点 6 的锅：float 重载在 LDLib `TankWidget` 里拿到的是**填充后的切片**（见上表「注入点 5/6 的几何」），调大 `MIN_SLOT` 可让它彻底不画。
- 机器界面上标签**闪烁（每秒十几次亮灭）** ⇒ 去重 memo 被误用成"帧号"了：确认 `LabelRenderer.java` 里两处 memo 用的是 `DEDUPE_WINDOW_NANOS` + `System.nanoTime()`，**不是** `FrameContext.epoch()`（`python tools\render_dedupe_guard.py` 一条命令即可判定）。详见 `fluid-label-PLAN.md` §8。
- 启动时还会打印：`[fluidlabel] 注入点1 FluidEmiStack#render 目标在：… → 注入器已装配`（`handler$…$fluidlabel$…` 反射确认）、AE2 侧的同类确认（`AEKeyRendering.drawInGui`），以及各探针的初始状态（`待触发`，属正常）。
- `debugLog = true` 时会额外打印首帧自检（enabled/mode/表条数/scale/maxChars/maxLines）。
- 想知道标签**到底吃掉多少帧时间** ⇒ `debugLog = true`，看每秒一行的 `LabelPerf` 行（`标签渲染耗时：… 合计 {y} ms/s`）；想做 A/B 就临时把 `enabled = false`，在同一界面对比。离线纯逻辑数字见 `tools/perf-baseline.txt` 与 `fluid-label-PLAN.md` §9。
- AE2 / GT 里的字色和 EMI 页**不一致**（时黑时白）⇒ 看 `config/fluidlabel-client.toml` 里的 `adaptiveTextColor`：默认 `false` 才是三处统一的深色字 + 浅色描边；若为 `true`（旧的自适应行为）就改回 `false`。注意老配置里残留的 `autoContrast = true` **不再被读取**（键已改名），留在文件里无害、可手删。详见 `fluid-label-PLAN.md` §10。
- 觉得深色字在**极暗液体**上看不清 ⇒ 同上，把 `adaptiveTextColor` 设 `true` 恢复自适应（AE2/GT 会重新按液体亮度换字色）。

## Wrapper / 工具链

- Gradle wrapper **8.8**（`gradle/wrapper/gradle-wrapper.properties`）
- ForgeGradle **6.0.54**
- 编译用 Forge **1.20.1-47.3.0**；映射 `official 1.20.1`（`gradle.properties`）
- **JDK 21 toolchain**（由 `settings.gradle` 的 foojay 自动装配；必须 21 才能读 classfile 65 的
  vendored AE2/GTCEu）+ `options.release = 17`（产物仍为 Java 17 字节码）

## 布局

| 路径 | 说明 |
| --- | --- |
| `src/main/java/com/fluidlabel/` | Java 源码根（包 `com.fluidlabel`） |
| `src/main/java/com/fluidlabel/mixin/` | Mixin 类（唯一允许 import `dev.emi.*` 的地方之一） |
| `src/main/resources/` | mixin config |
| `src/main/resources/META-INF/mods.toml` | Forge 模组元数据 |
| `src/main/resources/pack.mcmeta` | pack_format 15 |
| `libs/` | 本地 `compileOnly` jar（不进产物） |
| `build.gradle` / `gradle.properties` / `settings.gradle` | ForgeGradle 6 构建脚本（`build.gradle` 只有 D 能改） |
| `PLAN.md` | 三级代理（B 骨架 / C 内容 / D 审查构建）的唯一接口契约 |

## 工具链（标签表的生产与验收）

| 脚本 | 作用 |
| --- | --- |
| `tools/gen_labels.py` | 从 GT 语料生成 `src/main/resources/fluidlabel_labels.json`**和** `fluidlabel_anchors.json`（随包锚点，供 DYNAMIC 档）。**必须显式 `--lang`**（默认路径不存在）：<br>`python tools/gen_labels.py --lang "<workspace>\_gtodata\assets"`<br>硬校验：锚点超长/含禁用字符 ⇒ 拒收并 `[warn]`；任意两两标签冲突 ⇒ `[error]` + **exit 4** |
| `tools/anchors.json` | **人工关键字覆盖层**（83 条，最高优先级，生成器会预留防撞） |
| `tools/fix_anchors.py` | 锚点长度/冲突的一次性修正（幂等，保留作 provenance） |
| `tools/audit_table.py` | 表终审 R1–R6，输出 `PASS/FAIL` 与 `VERDICT`（导出非零退出表示失败） |
| `tools/check_anchors.py` | 锚点自检：长度 / 禁用字符 / 互异 / 键是否命中语料 / 是否真的写进表 |
| `tools/prefix_abbrev_report.py` → `tools/prefix-abbrev.md` | 长前缀统计与简称对照（回答"还有哪些长前缀需要简称"） |
| `tools/gen-report.md` / `tools/gen-leftovers.txt` | 生成器自带报告；后者 §0 冲突 / §1 无解 / §2 可疑档位 / §3 全部 >6 字 |
| `tools/parity/ParityCheck.java` | Java↔Python 算法同构校验（只需 JDK）：<br>`javac -encoding UTF-8 -d out src/main/java/com/fluidlabel/DistinctMds.java tools/parity/ParityCheck.java`<br>`java "-Dfile.encoding=UTF-8" -cp out com.fluidlabel.ParityCheck src/main/resources/fluidlabel_labels.json tools/anchors.json` |
| `tools/parity/DynamicAnchorCheck.java` | DYNAMIC 档锚点叠加层校验（4 个 case，读真实语料，失败 exit 1）：<br>`javac -encoding UTF-8 -d out src/main/java/com/fluidlabel/DistinctMds.java tools/parity/DynamicAnchorCheck.java`<br>`java "-Dfile.encoding=UTF-8" -cp out com.fluidlabel.DynamicAnchorCheck` |
| `tools/derived_check.py` | **派生名门禁**：把 5 个 GT 流体模板（`熔融%s` / `液态%s` / `气态%s` / `%s等离子体` / `%s蒸气`）套到词库上，用与 Java 同一套 `candidates()` 复算，检查 ①首候选 ≤6 码点 ②派生名两两互异 ③阶梯有解 ④不撞表标签；同时导出 `_diag/derived_replay.txt`（Java 侧复算的输入） |
| `tools/diff_labels.py` | 两版标签表对比：按"牌号字符保留数"（贪心顺序匹配）分「变好/变差/持平」，逐条打印 + 写 `_diag/label_diff*.txt` |
| `tools/parity/DerivedLabelCheck.java` | 派生名 Java 复算（读 `_diag/derived_replay.txt`，跳过查表命中）+ 8 条用户样例断言：<br>`javac -encoding UTF-8 -d out src/main/java/com/fluidlabel/DistinctMds.java tools/parity/DerivedLabelCheck.java`<br>`java "-Dfile.encoding=UTF-8" -cp out com.fluidlabel.DerivedLabelCheck` |
| `tools/render_dedupe_guard.py` | **渲染去重 + 自计时零成本 + 配色统一回归门禁**（16 项断言）：`LabelRenderer.java` 必须用 `DEDUPE_WINDOW_NANOS` + `System.nanoTime()`、代码里不得出现 `FrameContext.epoch()`/`endFrame()`、两个 overlay 入口都引用时间窗、`LabelEngine.java` 仍用 epoch 做 DYNAMIC 快照版本号、`drawLabel` 只在 `LabelPerf.enabled()` 时取时间戳、`LabelPerf.record` 全在 `if (perfStart != 0L)` 保护下、`LabelPerf.enabled()` 由 `debugLog` 控制、`adaptiveTextColor` 默认 `false`、`LabelRenderer` 代码里不出现 `autoContrast`、三条路径都经 `FluidLabelConfig.adaptiveTextColor()` 取开关、不允许 `useLightTextFor(tintOf(...))` 这种急切求值：<br>`python tools\render_dedupe_guard.py` |
| `tools/parity/PerfProbe.java` | **离线性能探针**（只需 JDK + `build\classes\java\main`）：查表 / 快照 get / 布局键拼接 / `assignLabels`（16→1898 可见）/ `applyAnchors` / `labelForName` 冷热两组，输出"中位 ms/轮 + 每次 µs"：<br>`javac -encoding UTF-8 -nowarn -cp build\classes\java\main -d build\gates tools\parity\PerfProbe.java`<br>`java "-Dfile.encoding=UTF-8" -cp "build\gates;build\classes\java\main" com.fluidlabel.PerfProbe`<br>**注意**：`LabelTables.reload()` 不能离线调（引用 `FluidLabelConfig`），探针用同结构的 `unmodifiableMap(HashMap)` 复现查表 |
| `tools/perf-baseline.txt` | `PerfProbe` 的原始输出存档（UTF-8，2026-10），报告里的数字都出自这里 |
| `tools/family_probe.py <子串>… [--anchors]` | 按子串打印「名字 / 标签长度 / 渲染前 6 码点 / 注册名」——排查"牌号丢失/截断"类问题 |
| `tools/parity/Ae2LabelCheck.java` | **AE2 侧门禁**（只需 JDK + `DistinctMds`）：语料 = lang 的 `material.*`/`fluid.*` 值 × 5 个状态模板，断言预算/族内互异/确定性/用户样例/锚点/缓存隔离：<br>`javac -encoding UTF-8 -d out src/main/java/com/fluidlabel/DistinctMds.java tools/parity/Ae2LabelCheck.java`<br>`java "-Dfile.encoding=UTF-8" -cp out com.fluidlabel.Ae2LabelCheck`<br>**注意**：② 走 `labelForName`（渲染路径）而**不是**整池 `assignLabels` —— 后者的"无解条目"收尾允许写回超长原名（生成器口径），拿来当渲染门禁会报假失败（2026-10 实测：把 13357 条机器名当液体基名时误报 1793 条） |

> PowerShell 里 `-Dfile.encoding=UTF-8` **必须加引号**，否则被当成多个参数。
> 复杂校验别用 `python -c "…"` 内联（引号/反斜杠会被 PowerShell 吃掉），写成脚本文件。
