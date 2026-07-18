# 3. Radiance / MCVR 管线领略 — 去噪栈与稳定光照

| 项 | 值 |
|---|---|
| 版本 | A0 |
| 日期 | 2026-07-18 |
| 目的 | 从源码读作者思路，指导 Caustica 下一阶段；**不移植、不合仓** |
| 本地参考 | `docs/refs/Radiance`、`docs/refs/MCVR`（已 gitignore，浅克隆） |
| 上游 | [Radiance](https://github.com/Minecraft-Radiance/Radiance)（GPLv3）、[MCVR](https://github.com/Minecraft-Radiance/MCVR)（GPLv3 + 部分 Apache/MIT） |
| 许可边界 | Caustica 若保持非 GPL，只能借鉴**算法结构与接口形状**，不可整文件复制 MCVR 实现 |

---

## 1. 作者在解决什么

Radiance 不是「在 MC 上挂一个 rgen」。它把世界渲染拆成 **可插拔模块流水线**，并提供两套 RT shader pack：

| Shader pack | 含义 | 依赖 |
|---|---|---|
| `vanilla_pt` | 较传统路径追踪 | 可无灯列表 |
| `restir_di` | ReSTIR Direct Illumination | **必须** `collect_chunk_emission` |

核心判断（从代码结构倒推）：

1. **1 spp 路径追踪永远吵** → 必须有「先降噪声再上屏」的栈，且栈可换（DLSS / NRD / SVGF / 纯时域）。
2. **方块灯不能只靠撞表面** → 从 chunk 几何抽出显式面光，再做 NEE / ReSTIR。
3. **直接光与间接光要分治** → 直接光用灯采样+重用；间接用 bounce / SHARC；去噪侧也分 diffuse / specular / direct 通道。
4. **稳定 ID 贯穿灯生命周期** → 时域重用需要「同一盏灯」可识别，而不是每帧重新编号。

这和 Caustica 当前「单 rgen 路径 + 太阳 NEE + hit emission + 单一 DLSS-RR」是同一问题的不同深度。

---

## 2. 去噪栈：可换后端，统一 G-buffer 契约

### 2.1 模块目录（MCVR）

```text
MCVR/src/core/render/modules/world/
  ray_tracing/          # 噪声 radiance + guides
  nrd/                  # NVIDIA ReBLUR 封装
  dlss/                 # DLSS / RR 封装（Apache 改自 nvpro）
  svgf/
  temporal_accumulation/
  fsr_upscaler/  xess_upscaler/
  tone_mapping/
  post_render/
```

Java 侧 `DenoiserMode` 暴露给玩家：`dlss | svgf | nrd | temporal`。  
模块 YAML（如 `nrd.yaml`）声明输入图名与可调参数（antilag、max accumulated frames、blur radius…）。

### 2.2 NRD 路径暴露的作者偏好

`NrdModule` 输入不是「一张 noisy color」，而是 **分通道**：

| 输入（名字示意） | 作用 |
|---|---|
| diffuse / specular **indirect** radiance | 间接分开降噪 |
| **direct** radiance | 直接光单独通道 |
| diffuse albedo + metallic、specular albedo | 解调（demodulation） |
| normal + roughness、motion、linear depth | 几何一致性 |
| base emission | 自发光不进模糊 |
| diffuse/specular hit depth、fog、refraction | 难路径单独处理 |

NRD 默认 **ReBLUR**（`makeDefaultReblurSettings`），参数里大量 **antilag / history fix / firefly**——专门打拖影和闪点。

**思路**：去噪不是事后磨皮，而是渲染器从一开始就按「去噪器契约」产出多缓冲。  
Caustica 的 DLSS-RR 已有 color + depth + MV + albedo + normal + specular MV，接近 RR 契约；**还没有** diffuse/specular/direct 分离，也没有可调 antilag 栈。

### 2.3 为什么他们看起来更「稳」

| 手段 | Radiance | Caustica 现状 |
|---|---|---|
| 多 denoiser 可选 | 有 | 基本只有 DLSS-RR |
| 分通道 radiance | NRD 路径有 | 合成进一张 HDR |
| 可调 history / antilag | YAML + UI | RR 黑盒 + 有限 reset |
| 升频可选 FSR/XeSS | 有 | 无（NVIDIA 绑定） |
| 噪声源头 | ReSTIR 后直接光方差低 | 局部灯靠 hit → 方差高 → RR 更糊 |

结论：**完整去噪栈 = 契约化 G-buffer + 可换后端 + 上游少噪声。**  
只换去噪器、不减噪声源，糊和拖影会换一种形态出现。

---

## 3. 稳定光照：Emission → LightInfo → 邻域 → ReSTIR DI

### 3.1 数据流（作者主线）

```text
贴图 / tile 上传
    → EmissionCell { uvMin/Max, avgEmission, avgColor, stableKey }
    → 每 texture 一棵 EmissionCellRTree（UV 空间查询）

Chunk 网格（四顶点一 quad）
    → buildLightInfos(Emission&):
         对每个 textured quad
         UV bbox ∩ emission cells
         在 UV 上 clip 三角形 → 世界空间小三角面光
         radiance = cell.avgColor * vertexTint * cell.avgEmission
         stableID = hash(chunk, geometry, quad, cell, piece)

    → packLight → GPU LightData / ChunkPackedLight
         p0..p3, normal, radiance, area, sourceID
         每 chunk 一个 light SSBO（device address）

帧内 advanced pack:
    precompute_light_neighborhoods
    generate_initial_samples  → temporal_reuse → spatial_reuse
    visibility / direct_light 评估
    （可选）SHARC resolve 补间接
```

### 3.2 `LightInfo` 形状（算法模板，非复制源码）

```text
CPU:
  p0..p3, normal, radiance, area, textureID, stableID

GPU ChunkPackedLight:
  p0Area (xyz=p0, w=area), p1, p2, p3, normal, radiance, sourceIDData
```

要点：

- **面光**（有面积）→ 可做面积采样与正确 PDF，不是点光近似随便打。
- **stableID** → ReSTIR 时域能认「还是那盏灯」。
- **按 chunk 存 buffer + 全局 chunk 表** → 世界尺度可扩展，不全局一个巨型灯数组。

### 3.3 采样思路（`direct_light_reservoir.glsl`）

`sampleAreaLightSource` 一类逻辑：

1. 取中心 chunk 的 **light neighborhood**（预计算邻域条目 + 概率）；
2. 按邻域概率抽一个 chunk；
3. 在该 chunk 的 light buffer 里均匀（或加权）抽一盏灯；
4. 在灯三角形上采样位置 → 方向、距离、几何项、BRDF、shadow；
5. 写入 reservoir，再 **temporal / spatial reuse**。

这是标准 **ReSTIR DI**：用时空重用摊平「世界里成千上万小灯」的采样成本。

`vanilla_pt` 则更接近「普通 PT + 可选灯」；高级观感主要来自 **restir-di + collect_chunk_emission**。

### 3.4 SHARC

shader pack 带 `sharc_resolve.comp`，RT 模块属性 `use_sharc`。  
定位：**间接光** 的空间哈希缓存，不是直接光主力。  
作者分层：直接光靠灯列表+ReSTIR；间接靠 bounce/SHARC。

---

## 4. 与 Caustica 的差距（按「糊 / 拖影 / 灯」映射）

| 观感 | Radiance 侧手段 | Caustica | 可借鉴优先级 |
|---|---|---|---|
| 糊（静止） | 更高质量升频 + 上游噪声低 + 分通道 | 低 res + RR 猛抹 + 1spp | 先 quality/SPP；中期减噪声源 |
| 拖影（运动） | NRD antilag / history fix；ReSTIR 有 plane/normal 阈值 | RR history 几乎不控 | **相机突变 reset**；以后再谈分通道 |
| 灯惨白 | EmissionCell 色 + tint | 已有色温表 + tint（本阶段） | 继续调表 |
| 灯照不远 / 噪 | LightInfo + ReSTIR DI | 仅 hit emission + 太阳 NEE | **局部灯列表 + 简化 NEE** |
| 暗处间接脏 | SHARC / 多 bounce | 有限 bounce | 后期 |

---

## 5. 作者思路压缩成几条启发式

1. **先契约后算法**：渲染输出按去噪器需要的图来设计，而不是渲完再想办法糊弄。
2. **噪声在源头灭**：灯多 → 显式灯 + 重采样；不要指望去噪器从 1spp 火把噪声里变清晰。
3. **直接 / 间接拆开**：采样策略不同，去噪通道不同。
4. **稳定身份**：灯、历史样本都要可追踪 ID。
5. **模块可换**：DLSS 不可用时 NRD/SVGF 兜底；Caustica 可先不实现多后端，但接口别焊死。
6. **内容数据与采样分离**：EmissionCell 是内容/贴图分析；LightInfo 是几何实例；shader 只吃 GPU 打包结果。

---

## 6. Caustica 落地路线（只借鉴结构）

### 阶段 A — 去噪可控（小，对症糊/拖影）

| 项 | 说明 |
|---|---|
| RR `resetHistory` | 相机位置/朝向突变、进出世界、分辨率变化时置位 |
| 默认 DLSS quality↑ | 减升频糊 |
| Debug：MV / depth 可视化 | 已有 debug view，可补 MV 幅度图验证拖影根因 |
| （可选）曝光/SPP 与 RR 联动提示 | UI 文案 |

不引入 NRD（许可与工程量大）；先把 **现有 RR 用满**。

### 阶段 B — 局部光稳定（中，对症照程/噪声）

对齐 Radiance 的 **数据形状**，实现自研精简版：

```text
mesh section 时:
  识别 emissive quads（已有 emission 语义 + 色表）
  产出 RtLightInfo { p0..p2 或 quad, normal, radiance, area, stableId }
  按 section/chunk 上传 SSBO

rgen / 独立 pass:
  对 primary hit 做 1～K 次局部灯 NEE（邻域 section 列表即可，先不做完整 ReSTIR）
  MIS 与 hit emission 防双计

后续:
  邻域表 + reservoir（类 ReSTIR DI 子集）
  再谈 SHARC
```

**刻意不做的**：

- 不复制 `EmissionCellRTree` 实现（可先用「整 quad 一盏灯 + 色表」）；
- 不整包 `restir-di.zip`；
- 不引入 NRD/SHARC submodule，除非单独评估许可与构建。

### 阶段 C — 分通道与可换去噪（大）

仅当 B 仍不够且有明确硬件矩阵需求时：

- 路径输出拆 direct / indirect（或 diffuse / specular）；
- 评估 NRD（Apache wrapper 可参考 nvpro，**不要**直接 copy MCVR 的 GPLv3 胶水而不改许可）。

---

## 7. 和 `2-INFORMATION.md` 的关系

| 文档 | 角色 |
|---|---|
| `2-INFORMATION.md` | 问题定义、方案表、Caustica 已做 emission 调参 |
| **本文** | 用 Radiance/MCVR **源码结构**讲清「更完整去噪 + 更稳光照」作者怎么拆 |

实现时以本文阶段 A/B 为计划输入；具体任务拆到 `4-PLAN` 或 issue 即可。

---

## 8. 关键源码锚点（本地 refs）

| 主题 | 路径 |
|---|---|
| LightInfo / EmissionCell | `MCVR/src/core/render/emission.hpp` |
| buildLightInfos | `MCVR/src/core/render/chunks.cpp` (`buildLightInfos`) |
| GPU 灯布局 | `MCVR/src/shader/.../advanced/common/light.glsl` |
| 邻域采样 / reservoir | `.../advanced/common/direct_light_reservoir.glsl` |
| NRD 输入契约 | `MCVR/src/core/render/modules/world/nrd/nrd_module.hpp` |
| Denoiser 枚举 | `Radiance/.../option/DenoiserMode.java` |
| restir pack 开关 | `Radiance/.../pipeline/Pipeline.java`（`restir-di` + emission 收集） |
| 许可 | `MCVR/LICENSE.md`、`Radiance/LICENCE` |

---

## 9. 一句话

Radiance 的「清晰」来自 **上游用灯列表+ReSTIR 把直接光方差打下来**，再用 **契约化多缓冲 + 可换去噪（含 antilag）** 收尾；不是单靠某一个 DLSS 开关。  
Caustica 最短路径：先 **管住 RR history 与质量**，再 **自研精简 LightInfo NEE**，ReSTIR/SHARC/NRD 往后排。
