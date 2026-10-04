# 全局稳定标签：唯一性针对全部 1900 个材料名

| 预算 | 可唯一区分的名字 | 覆盖率 |
| --- | --- | --- |
| ≤1 字符 | 138/1900 | 7.3% |
| ≤2 字符 | 330/1900 | 17.4% |
| ≤3 字符 | 722/1900 | 38.0% |
| ≤4 字符 | 1084/1900 | 57.1% |
| ≤5 字符 | 1326/1900 | 69.8% |
| ≤6 字符 | 1478/1900 | 77.8% |
| ≤7 字符 | 1603/1900 | 84.4% |
| ≤8 字符 | 1701/1900 | 89.5% |
| ≤9 字符 | 1763/1900 | 92.8% |
| ≤10 字符 | 1814/1900 | 95.5% |
| ≤11 字符 | 1838/1900 | 96.7% |
| ≤12 字符 | 1857/1900 | 97.7% |
| ≥13 字符 | 1900/1900 | 100.0% |

机制分布: full-name=1464、giveup=436
行数分布: 1行=1900

在所有 1900 个名字里，长度≤3 字（一行放得下、无需处理）的有 722 个 (38%)；需要缩短的有 1178 个。

**在被缩短的 1178 个名字中**，标签长度分布：
- ≤4 字符：362 (累计 30.7%)
- ≤5 字符：242 (累计 51.3%)
- ≤6 字符：152 (累计 64.2%)
- ≤7 字符：125 (累计 74.8%)
- ≤8 字符：98 (累计 83.1%)
- ≤9 字符：62 (累计 88.4%)
- ≤10 字符：51 (累计 92.7%)
- ≤11 字符：24 (累计 94.7%)
- ≤12 字符：19 (累计 96.3%)
- ≥13 字符：43 (累计 100.0%)

## 边界情况：中文名完全相同、只能靠颜色/位置区分

同名组数: 2，涉及 4 个 material 键（占 0.2%）
- `乙腈` × 2: gtceu:glycolonitrile、gtocore:acetonitrile
- `金属残渣` × 2: gtocore:metal_residue、gtocore:metallic_residues

## 用户举例相关家族（真实数据）

含「石脑油」的 6 个：
  - `gtceu.sulfuric_naphtha` = 含硫石脑油 → 标签 `含硫石脑油`
  - `gtceu.naphtha` = 石脑油 → 标签 `石脑油`
  - `gtceu.lightly_hydro_cracked_naphtha` = 轻度加氢裂化石脑油 → 标签 `轻度加氢裂化石脑油`
  - `gtceu.lightly_steam_cracked_naphtha` = 轻度蒸汽裂化石脑油 → 标签 `轻度蒸汽裂化石脑油`
  - `gtceu.severely_hydro_cracked_naphtha` = 重度加氢裂化石脑油 → 标签 `重度加氢裂化石脑油`
  - `gtceu.severely_steam_cracked_naphtha` = 重度蒸汽裂化石脑油 → 标签 `重度蒸汽裂化石脑油`
含「十六烷」的 4 个：
  - `gtocore.hexadecyl_bromide` = 1-溴代十六烷 → 标签 `1-溴代十六烷`
  - `gtocore.cetane` = 十六烷 → 标签 `十六烷`
  - `gtocore.ctab` = 十六烷基三甲基溴化铵 → 标签 `十六烷基三甲基溴化铵`
  - `gtceu.cetane_boosted_diesel` = 高十六烷值柴油 → 标签 `高十六烷值柴油`
含「辛烷」的 5 个：
  - `gtocore.perfluorooctanesulfonic_acid` = 全氟辛烷磺酸 → 标签 `全氟辛烷磺酸`
  - `gtceu.octane` = 辛烷 → 标签 `辛烷`
  - `gtocore.octane_loaded_phase_change_microbeads` = 重载辛烷的相变微珠 → 标签 `重载辛烷的相变微珠`
  - `gtocore.silver_coated_octane_ceramic_beads` = 镀银的辛烷陶瓷微珠 → 标签 `镀银的辛烷陶瓷微珠`
  - `gtceu.high_octane_gasoline` = 高辛烷值汽油 → 标签 `高辛烷值汽油`
含「汽油」的 3 个：
  - `gtceu.gasoline` = 汽油 → 标签 `汽油`
  - `gtceu.raw_gasoline` = 粗汽油 → 标签 `粗汽油`
  - `gtceu.high_octane_gasoline` = 高辛烷值汽油 → 标签 `高辛烷值汽油`
