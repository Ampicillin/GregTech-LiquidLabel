# PLAN —— EMI 液体图标中文短标签（纯客户端，MC 1.20.1 + Forge）

> 交付物：一个可丢进 `C:\Users\18409\AppData\Roaming\PrismLauncher\instances\GregTech.Odyssey-0.5.6-beta\minecraft\mods\` 的 reobf 后 jar。
> 本文件是 B（骨架）/ C（内容）/ D（审查+构建）三级的唯一接口契约。
> **v2 修订说明**：用户答复（"以EMI为主（本整合包用户可使用界面以EMI为主）"，m00440）后，把目标从 JEI 改为 **EMI**。
> v1（JEI 版）的全部 JEI 章节作废，代之以本文件 ④ 的两处 EMI 注入点 —— **证据全部来自本机安装版 jar 的 `javap -c` 反汇编，非源码推断、非记忆**。

---

## ① 目标与验收标准

### 1.1 目标
在 EMI 配方/物品列表界面里，把**每一个液体图标（16×16）**叠加上一个**可区分的中文短标签**（默认 ≤3 汉字，一行放下），让人不必依赖颜色就能分辨 GT 化工配方里成百上千种液体；悬停时 EMI 原本的全名 tooltip 保持不变。

两个档位，配置项切换（用户要求两档都做、可 A/B）：
- **档 A · STABLE（跨配方稳定，默认）**：标签来自离线预计算表（1900 条 GT 材料名池），同一液体在任何界面显示同一标签，便于记忆。
- **档 B · DYNAMIC（同屏动态）**：只用**当前一帧画面里出现过的液体集合**重算标签，标签更短更贴切，但会随界面变化。

### 1.2 验收标准（逐条可验证）
| # | 标准 | 验证方式 |
| --- | --- | --- |
| A1 | 模组能在目标实例里正常加载，不崩，出现在模组列表 | 启动实例看模组列表 + `logs/latest.log` 无 `Mixin apply failed` |
| A2 | 打开任意 GT 化工配方（如"加氢裂化"），液体槽上出现 ≤3 汉字标签，同一屏内**互不相同** | 实机截图 |
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

    @Inject(method = "drawStack(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("TAIL"), require = 0)
    private void fluidlabel$overlayTank(GuiGraphics guiGraphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        LabelRenderer.drawOverlay(guiGraphics, (Object) this, getBounds());
    }
}
```
> 实现细节留给 C：`LabelRenderer` 需要一个 `Bounds` 重载，或由 mixin 传 `bounds.x(), bounds.y(), bounds.width(), bounds.height()`。
> `SlotWidgetMixin`（若需要继承 `getStack()`）由 C 决定；**保持 `getBounds()` 用 `@Shadow` 取，不要反射**。

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
**用户举例的实测结果**：重度加氢裂化石脑油→`重加氢`、轻度加氢裂化石脑油→`轻加氢`、重度蒸汽裂化石脑油→`重蒸汽`、轻度蒸汽裂化石脑油→`轻蒸汽`、石脑油→`石脑油`、含硫石脑油→`含硫石`。

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

### 5.4 缓存与性能（**必须遵守**）
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
| `adaptiveTextColor` | boolean | `false` | — | `false` = 所有界面统一「深色字 + 浅色描边」（与 EMI 页一致）；`true` = 按液体 tint 亮度在深色字/浅色字之间切换（旧行为）。**2026-10 由 `autoContrast` 改名而来，见 §10** |
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
| **M2 静态标签可见** | `LabelTables`（双索引）+ `LabelEngine`（三层优先级）+ `LabelRenderer`（缩放/描边/对比度）+ `gen_labels.py` 产出 labels.json；配置 `enabled/scale/maxChars/adaptiveTextColor/outline` | 打开 GT 化工配方，液体槽出现 ≤3 汉字标签、同屏互不相同；tooltip 仍为全名 | C + D |
| **M3 储罐槽 + 动态档** | `TankWidgetMixin`；`FrameContext` + `mode` 配置；`DistinctMds` 运行时可用 | 储罐槽也有标签且不与槽位标签撞车；`DYNAMIC` 与 `STABLE` 实机可辨差异 | C + D |
| **M4 配置与别名表** | 剩余配置键；别名表热路径；`dumpRegistry` 导出 | 改别名表重进游戏生效；导出文件可用于校正索引 | C |
| **M5 进实例验证** | reobf jar 复制到 `...\GregTech.Odyssey-0.5.6-beta\minecraft\mods\`；启动；A1–A8 逐条验收；截图 | A1–A8 全过 | D + 宿主/用户 |
| **M6（可选 P2）** | 图案叠加层（WCAG G111）、JEI 适配、别名表 GUI | — | 后续 |

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
   public static boolean adaptiveTextColor();
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
