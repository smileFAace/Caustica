# 2. 知识调研 — 方块发光 / 局部光 / 同类 RT 渲染器

| 项 | 值 |
|---|---|
| 版本 | A0 |
| 日期 | 2026-07-18 |
| 范围 | 白天发白、方块灯惨白/照不远/缺色温；对照 Radiance 与工业路径追踪常见做法 |
| 输入 | `1-ARCHITECTURE.md`、Caustica 源码、[Radiance](https://github.com/Minecraft-Radiance/Radiance) / [MCVR](https://github.com/Minecraft-Radiance/MCVR) |

---

## 1. 领域认知

### 核心问题

在 Minecraft 体素世界里做 **硬件路径追踪** 时，如何让：

1. 太阳/天空 HDR 经曝光后不发白；
2. 火把、萤石、末地烛等 **局部光源** 有正确色温；
3. 局部光 **照亮范围** 接近原版 light level 的可读性；
4. 在有限 SPP / bounce 下仍可玩。

### 基本概念

| 概念 | 定义 | 关系 |
|---|---|---|
| Path tracing | 对光照方程 Monte Carlo 积分 | 需要重要性采样才收敛 |
| NEE (Next Event Estimation) | 直接对光源采样并做 shadow ray | 太阳易做；无数小方块灯难做 |
| Emissive surface | 表面本身发光（命中即加 radiance） | 无 NEE 时只能靠“撞到灯” |
| Light level (MC) | 0–15 整数块光传播 | 原版光栅用 lightmap；RT 无默认等价物 |
| Color temperature / tint | 光源光谱色偏（橙/黄/冷白） | 与 albedo 不同，应单独标定 |
| Emission cell | 贴图 UV 子区域的平均发光色/强度 | Radiance 用 R-tree 查询 |
| LightInfo / 面光源 | 显式四边形灯 + radiance + area | 供局部 NEE |
| Exposure / tonemap | HDR → 显示映射 | 与光源标定强耦合 |
| SHARC | 空间哈希辐射缓存 | 补间接光、降噪声 |
| ReSTIR | 时空重采样重要性 | 多灯场景主流 |

### 经典方案

| 方案 | 来源 | 核心思路 | 适用 |
|---|---|---|---|
| 仅 hit emission | 简单 PT | 撞到发射体才加光 | 实现快，小灯噪声大 |
| 显式灯列表 + NEE | 工业 PT / Radiance MCVR | 提取灯几何，直接采样 | 照程、稳定性 |
| Lightmap 混合 | MC 传统 | 用原版块光当 ambient 项 | 像原版范围 |
| Emission 贴图 / LabPBR | 资源包生态 | `_s` 或内置 emissive | 灯体自发光形状 |
| 色温表 | 内容数据 | block→RGB | 语义稳定 |
| SHARC / ReSTIR | NVIDIA 等 | 缓存/重采样 | 多灯、间接 |

---

## 2. 现有方案

### 2.1 同类产品

| 产品 | 类型 | 版本/栈 | 局部光做法（据公开代码/文档） | 可借鉴 |
|---|---|---|---|---|
| **Caustica** | Fabric RT 模组 | MC 26.2，Java + Slang，挂 Blaze3D Vulkan | hit emission × 常数；无 NEE；LabPBR/heuristic；无色温表（调研前） | 自身基线 |
| **Radiance + MCVR** | Fabric + **C++ 全 Vulkan** | MC **1.21.4** | `LightInfo` 面光缓冲；`EmissionCell` 平均色；lightmap 通道；SHARC/NRD/DLSS | **灯列表+NEE、发光 cell 色** |
| Iris / 光影包 | 光栅着色器 | 多版本 | 模拟光/原版 lightmap | 观感参考，不是 RT |
| 工业引擎 (UE/Unity HDRP) | 引擎 | — | 显式灯 + RT / ReSTIR | 模式参考 |

### 2.2 Radiance / MCVR 要点（源码事实）

仓库：

- Java：https://github.com/Minecraft-Radiance/Radiance  
- C++：https://github.com/Minecraft-Radiance/MCVR  

关键结构：

```text
MCVR/src/core/render/emission.hpp|cpp
  EmissionCell { uvMin, uvMax, avgEmission, avgColor }
  EmissionCellRTree — UV 空间查询
  LightInfo { p0..p3, normal, radiance, area, textureID, stableID }

chunks.hpp
  buildLightInfos(Emission&)
  lightBuffer / lightCount  per chunk

shared.hpp
  lightMapTextureID, albedoEmission on vertices
  DirectionalLight + ExposureData histogram

extern/sharc, nrd, DLSS, FSR, XeSS
```

README 声明：有 **internal emission textures**，仍推荐 PBR；TODO 含更多版本移植、帧生成、HDR。

**与 Caustica 架构差**：Radiance 是整渲染器替换；Caustica 是 MC Vulkan 上的世界 RT 插件。不能整包移植，只能抽算法。

### 2.3 Caustica 基线（调研时）

| 项 | 事实 |
|---|---|
| 太阳 | NEE + 大气透射（`skyPush` / rmiss） |
| 方块灯 | **无 NEE**（`world.rgen` 注释） |
| 发光强度 | 硬编码 `EMISSIVE_STRENGTH = 5.0` |
| 颜色 | albedo × emission mask；无方块色温 |
| 语义 | `getLightEmission()` 决定是否发；LabPBR / heuristic / override |
| 调试 | debugView 0–9（含 Emission Mask/Source） |
| UI | 塞在视频设置里 |

### 2.4 可用库 / 组件

| 组件 | 状态 | 对 Caustica |
|---|---|---|
| LabPBR 资源包 | 成熟 | 直接用，改善灯体形状 |
| NVIDIA DLSS-RR | 已集成 | 保持 |
| NRD | Radiance 用 | 非 NVIDIA 去噪，后期 |
| SHARC | Radiance submodule | 间接光，后期 |
| 自研 LightInfo 缓冲 | 模式成熟 | **应自研**，贴现有 section 发布 |

---

## 3. 业界模式总结

局部光在体素 RT 中常见分层：

```text
L1  灯体自发光（emissive BRDF / 贴图）     → 灯“看起来亮”
L2  显式灯采样（NEE / ReSTIR）             → 周围“被照亮”
L3  lightmap / 探针 / SHARC                 → 稳定间接与范围感
L4  曝光 + tonemap                          → 不与太阳抢白
```

Radiance 覆盖 L1–L3 较多；Caustica 基线主要是 L1 + 太阳 NEE + L4。

---

## 4. 差距分析

| 用户观感 | 根因 | Radiance | Caustica 基线 | 差距 |
|---|---|---|---|---|
| 白天发白 | 曝光/太阳标定 | 有 histogram exposure | 有 auto exposure；太阳 peak 固定 | 调参 + 可选默认 |
| 灯体惨白 | 强度过大 × 无色温 | avgColor + 面光 radiance | albedo×5 | **色温表 + 可调 strength** |
| 照不远 | 无局部 NEE | LightInfo + buffer | 仅 hit emission | **大：需 LightInfo NEE** |
| 缺橙/黄/蓝 | 无语义色 | emission cell 色 | 无 | **色温表 / cell** |
| 可调试闭环 | UI + debug | 自有选项 | 视频设置混杂 | **独立设置页 + debug 视图** |

### 可复用评估（四档）

| 能力 | 评估 | 说明 |
|---|---|---|
| 运行时 emission 三参数 | **自研（小）** | push constant / WorldPush 即可 |
| 方块色温表 | **自研（小）** | 数据表；参考 Radiance 的 avgColor 思想 |
| Emission debug views | **自研（小）** | 扩展现有 debugView |
| 独立设置 UI | **自研（小）** | OptionsSubScreen 模式 |
| 贴图 EmissionCell R-tree | **参考不直接用** | 可二期；表 + LabPBR 先顶 |
| LightInfo + 局部 NEE | **参考后自研（中大）** | 对齐 Radiance 结构 |
| lightmap 混合 | **参考后自研（中）** | 读 MC light 数据 |
| SHARC / ReSTIR | **后期** | 依赖与工程量大 |

---

## 5. 实施映射（本阶段落地 vs 后续）

### 本阶段已实现 / 正在实现（能直接改 jar 的）

| 项 | 位置 | 热调？ |
|---|---|---|
| `emission.strength / light-level-power / tint-strength` | `CausticaConfig.Rt.Emission` → `WorldPush.emissionParams` | 是（下帧） |
| 硬编码 5.0 移除 | `world.rgen.slang` | — |
| 方块色温表 | `RtEmissionColorTable` → prim `aux0` | 否（改表需 F3+A） |
| 调试 10/11 | emission color / lit | 是 |
| 独立 Caustica 设置页 | `CausticaSettingsScreen` + Options 入口 | — |
| 视频设置只留入口按钮 | `VideoSettingsScreenMixin` | — |

### 后续（需更大改动，对齐 Radiance）

1. **`RtLocalLights`**：section 发布时从 emissive prim 抽四边形 → GPU light buffer → 1–N 次局部 NEE  
2. Emission cell 平均色（贴图驱动，补全表外方块）  
3. 可选 lightmap ambient  
4. SHARC / 更多 bounce 策略  

---

## 6. 调试契约（给“截图调参”用）

| 视图 | debugView | 看什么 |
|---|---|---|
| Emission Mask | 8 | 谁在发、mask 是否合理 |
| Emission Source | 9 | LabPBR / heuristic / uniform / override |
| Emission Color | 10 | 色温是否橙/黄/冷（调 tint 与表） |
| Emission Lit | 11 | strength×level 后贡献是否过曝 |

运行时 toml / UI：

```toml
[emission]
strength = 3.5
light-level-power = 1.0
tint-strength = 1.0
```

色温改代码表后 **F3+A** 重建地形。

---

## 7. 依赖与许可注意

- Radiance / MCVR：可作算法参考；**勿整文件复制**未核对许可证的实现。  
- NVIDIA DLSS/NGX：Caustica 已有第三方声明路径。  
- LabPBR 资源包：用户侧安装，不绑定仓库。

---

## 8. 结论

1. 发白 / 灯惨白 **优先** 曝光 + emission 标定 + 色温，不必先重写管线。  
2. **照不远** 必须上局部 NEE（Radiance 的 `LightInfo` 是正确模板）。  
3. Caustica 与 Radiance 版本/架构不同，借鉴应落在 **数据结构与采样策略**，不是合仓。  
4. 本阶段交付：可调接口 + 色温表 + 独立设置页 + 调试视图；NEE 记入下一实施阶段。

---

*下一文档：`3-PLAN.md` 可把 Local Lights NEE 拆成可验收里程碑。*
