# fluid-label —— 液体图标中文短标签（Minecraft 1.20.1 / Forge，纯客户端）

在配方与库存界面里，把**液体的中文短标签直接画在流体图标上**，让同屏的一堆液体不再只能靠颜色区分。
注入点覆盖三类界面：EMI 配方页、AE2（含 GT 的 ME 部件）、GT/GTO 机器流体槽。
全部是客户端渲染拦截（Mixin），不改服务端、不改配方数据。

- 目标版本：**Minecraft 1.20.1 + Forge 47+**，Java 17 字节码（构建用 JDK 21 toolchain）
- 软依赖（缺失即安静失效）：EMI、AE2 15+、GTCEu、LDLib
- 当前状态：三个阶段（EMI / AE2 / GT 机器）均已实现并部署到实机实例；GT 机器标签 20 Hz 闪烁已修（§8）；性能已离线实测并加实机自计时（§9）；三个界面配色统一为「深色字 + 浅色描边」（§10，`adaptiveTextColor`）
- 待确认：GT 机器标签、闪烁修复与配色统一的**实机验收**（重启后看 `debugLog` 日志与观感）

## 构建与安装

```bat
cd fluid-label
.\gradlew.bat build
```

产物：`fluid-label/build/libs/fluid-label-0.1.0.jar`（本仓库另存了一份到 `dist/`）。
安装：把 jar 丢进整合包实例的 `minecraft/mods/` 即可，无服务端要求。

编译依赖（EMI / AE2 / GTCEu / LDLib 的 dev jar）**不走 maven**，直接放在 `fluid-label/libs/`，
`build.gradle` 以 `compileOnly files(...)` 引用；因此这几个 jar 也在仓库里。

## 仓库结构

| 路径 | 内容 |
|---|---|
| `fluid-label/` | 模组工程本体：`src/`（Java 源码 + mixin 配置）、`tools/`（标签表生成器与全部门禁脚本）、`libs/`（编译用第三方 jar）、Gradle 包装器 |
| `fluid-label/README.md` | 模组详细说明：注入点、配置项、探针排查、门禁清单 |
| `fluid-label/PORTABILITY.md` | 可迁移性分析：跨整合包 / 跨游戏版本 / 多语言适配 |
| `fluid-label-PLAN.md` | 活计划与实施记录（§0–§10，含每次实机缺陷的根因与修法） |
| `fluid-label-PLAN-6-AE2.md` | AE2 阶段（第二阶段）的注入点取证与方案 |
| `液体图标标注-可行性与实现路径.md`、`液体标签方案-设计与实测.md` | 立项期调研（含被放弃的 JEI 路线与算法实测） |
| `_gtodata/` | 标签语料与算法分析脚本：`assets/<mod>/lang/*.json` 是真输入，`analyze*.py` / `report*.md` 是算法演化记录 |
| `dist/` | 最终产物 jar 与 `SHA256SUMS.txt` |

## 配置

`config/fluidlabel-client.toml`（首次启动生成）。常用项：

- `enabled` / `scale` / `maxChars` / `maxLines` —— 开关与排版
- `adaptiveTextColor = false` —— 默认所有界面统一「深色字 + 浅色描边」；设 `true` 恢复按液体亮度自适应（旧行为）
- `mode` —— `STABLE`（默认，用预烘标签表）或 `DYNAMIC`（运行时按同屏液体重算）
- `debugLog = true` —— 打印注入点状态、自检与每秒一行的渲染耗时统计

> 老配置里若残留 `autoContrast = true`，该键已改名、不再读取，可手删。

## 说明

- 这是**本地仓库**，未配置任何远程，`git push` 无从谈起（按需求暂不接 GitHub）。
- `fluid-label/tools/` 下的门禁脚本可离线复现标签表与算法结论，用法见 `fluid-label/README.md`；
  其中 `derived_check.py` 会重新生成 `_diag/derived_replay.txt`（该目录是临时诊断产物，未入库）。
