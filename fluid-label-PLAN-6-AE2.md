## ⑩ §6 —— AE2 侧液体标签（第二阶段：AE2 全部界面 + GT 的 ME 部件界面）

> **编号说明**：本文件顶层已有 ⑥（配置项表），故本阶段排为第 10 个顶层章节，内部小节按委派要求写 §6.1–§6.8。
> **合并说明**：本轮执行环境**没有 shell**（无法运行 `javap`）、子代理不可用，而 `write` 会整体覆盖既有 733 行 ⇒ §6 以本文片段形式交付，由宿主/Lead 用 `edit`/`>>` 追加到 `fluid-label-PLAN.md` **⑨ 之后**，内容无需改动。
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
// Mixin 规则：带父类的 mixin 必须声明成"与目标父类相同"的父类 ⇒ 这里 extends Widget，
// 于是 getPosition()（LDLib Widget 的公开方法）可直接调用，无需 accessor、无需反射。
@Mixin(value = com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEFluidDisplayWidget.class, remap = false)
public abstract class GtAeFluidDisplayMixin extends com.lowdragmc.lowdraglib.gui.widget.Widget {
    @Shadow private com.gregtechceu.gtceu.integration.ae2.gui.widget.list.AEListGridWidget gridWidget;
    @Shadow private int index;

    @Inject(method = "drawInBackground(Lnet/minecraft/client/gui/GuiGraphics;IIF)V", at = @At("RETURN"), require = 0)
    private void fluidlabel$overlayGt(net.minecraft.client.gui.GuiGraphics g, int mouseX, int mouseY, float pt,
                                      org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        InjectionProbe.fired(InjectionProbe.SITE_GT_FLUID_DISPLAY, "GT AEFluidDisplayWidget#drawInBackground");
        appeng.api.stacks.GenericStack s = this.gridWidget.getAt(this.index);
        if (s == null || !appeng.api.stacks.AEFluidKey.is(s.what())) return;   // 只处理流体键
        com.lowdragmc.lowdraglib.utils.Position p = getPosition();
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
3. 所有 `@Inject` = `at = @At("RETURN")` + `require = 0`；两个 `@Mixin` 都 `remap = false`；带父类的 mixin（注入点 4）父类必须与目标父类一致（`extends Widget`）；
4. **渲染热路径性能纪律**：禁止 `Component#getString()`、正则、`String.format`、反射、重复的 `ResourceLocation#toString()`（走 §6.3 的 per-key 缓存）；
5. 不新增配置项、不改 `FluidLabelConfig`。
