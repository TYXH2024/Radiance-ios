# pathtrace — 第二章 2.2 阶段交付物（Metal 直连 · 路径追踪引擎首帧）

> 设计文档：`docs/chapter2-pathtrace-design.md`
> 前置：2.1 `scene-ingestion` v0.5 真机判定 SCENE_INGEST_OK——摄取契约已盖章。
> **v0.1 真机回收（2026-10-28 01:08）：判 PLACEMENT_FAIL**（六向全 miss、哨兵 3/3、相机区块已摄取且 verts=2344 与 2.1 一致）——离线逐函数比对确认 fork 与 2.1 数学等价，v0.2 按零粉饰纪律装判别仪器（不盲改）。
> **v0.2 真机回收（2026-10-28 02:23）：仍判 PLACEMENT_FAIL，但仪器已定谳**——LIVE TLAS/BLAS/矩阵/摆放全部正确（ISO=LIVE 一致，3/6 命中、脚下地板 1.62、hitPoint 与 v0.5 完全一致），S6 全 miss 是探针通道自身缺陷；隔离渲染出图 + 62.40 Mray/s。**v0.3：探测线程重锚 + A/B 复测自证 + albedo 回读仪器。**现场记录：`docs/chapter2-pathtrace-field/`

## 取成品（两个通道，内容相同）

| 通道 | 位置 | 说明 |
|---|---|---|
| **A · 工作流运行页成品文件** | Actions → `chapter2-pathtrace` → 任意绿勾运行 → 页面底部 **Artifacts** 区 → `pathtrace-jar` | 每次绿勾必有；zip 内含 `pathtrace-0.3.0.jar` + dylib + metallib。**运行中途被取消的运行没有 artifact**（也不会发布 Release——可能遗留草稿） |
| **B · GitHub Release（正式交付）** | https://github.com/TYXH2024/Radiance-ios/releases/tag/pathtrace-v0.3 → `pathtrace-0.3.0.jar` | 幂等 `--clobber` 重传；若 Releases 页看不到，先检查页面底部"Drafts"区（被取消的运行可能遗留未发布草稿） |

> v0.1 首发 workflow 遗漏了 artifact 上传步骤（2.0/2.1 均有）——导致"绿勾了但运行页看不到 jar"。v0.1.1 已补齐：**以后看绿勾运行页最下方的 Artifacts 即可直接下载**。

## 使用

1. 删除 `mods/` 内旧探针 JAR（scene-ingestion / metal-rt-probe / rt-probe / 旧版 pathtrace），放入 `pathtrace-0.3.0.jar`——同存档、同启动器档位（Vulkan/MoltenVK 档，OSMesa 宿主实测兼容）。
2. 进入世界后停留 **30–40s**（600 槽位全量摄取约 19s + 静置收口；报告出现 `[scene] shutdown — all GPU resources released` 后即可退出）。
3. 回报 game 目录下两个文件：**`pathtrace_report.txt`**（全量报告）+ **`pathtrace_frame.png`**（480×270 / 24 spp / 2 bounces 首帧，眼见为实）。

## 验证问题

| Q | 内容 |
|---|---|
| Q1 | 摄取链在 2.2 摆放契约（免 recentring + t=origin，等价 2.1 已验证的 recentring + translate(2·origin)）下是否复现健康度？ |
| Q2 | 简化摆放真机等价性（整数格线抽查 + 哨兵）？ |
| Q3 | 全链路径追踪能否渲染一帧可目检的图（相机 ray → TLAS → 重心插值 albedo → 太阳直射 + 阴影 ray → 漫反射弹射 → 累积 → Reinhard+gamma → PNG）？ |
| Q4 | 实测吞吐（GPU atomic 计数器回读，非估算）？ |

## 判定表

| 判定 | 语义 |
|---|---|
| `PATHTRACE_OK` | 摄取健康 + 摆放等价 + 渲染成功 + 图像非退化（含统计自证） |
| `ISO_RENDER_OK` | **v0.2 新增**：live 摆放未过但隔离渲染通道（相机区块单实例 TLAS）出图——渲染链全链健康，缺陷隔离到 multi-instance TLAS 组装；看 [audit] 行判读 ISO/LIVE 对照 |
| `CAMERA_FAIL` | 相机姿态读取失败（S6 前置） |
| `SCENE_EMPTY` | 摄取后无就绪 BLAS |
| `SENTINEL_FAIL` | 哨兵求交失败（实例机制/矩阵编码异常，与场景数据无关） |
| `PLACEMENT_FAIL` | 整数格线抽查失败（v0.2 起 [audit] 行已判别根因方向） |
| `RENDER_FAIL` | 离屏渲染派发/回读失败 |
| `IMAGE_DEGENERATE` | 渲染成功但图像统计退化（全黑/全白/无方差） |

归因开关：`-Dpathtrace.disabled=true` 或 `PATHTRACE_DISABLED=1` → 只写环境快照、零原生调用（闪退 A/B 对照用）。

## v0.3 实证修复 + 证据补齐（基于 v0.2 仪器定谳）

| 变更 | 依据 |
|---|---|
| **refreshTlasNative（探测线程重锚）** | 三轮真机铁律：2.1 v0.5 每次电池前总有探测线程 TLAS 重建（setPlacementMode+frameTick）→ 9/14 命中；2.2 v0.1/v0.2 直查摄取期 TLAS → 0/6 ×2（确定性）；v0.2 审计在同一 g_tlas 上、前面有 build_iso_tlas → 3/6 命中。判别变量 = 探测线程上是否刚有 AS build（驱动层，离线不可复现；trace_ray 等已逐字节 diff 排除代码缺陷） |
| **A/B 复测自证** | 首轮全 miss → 重锚 → 复测：两轮行都在报告里——复测命中 = 机制当场坐实；仍 miss = 审计照常判别（零粉饰） |
| **live 渲染前同样重锚** | renderPathtraceNative 直查 g_tlas（与 S6 同通道）——不重锚会渲染出纯天空帧 |
| **COLOR/albedo 回读仪器** | v0.2 帧 ≥57% 相机命中但无绿无结构——顶点色+池条目直接回读，2.3 的 atlas 采样决策看这行证据 |
| **camHit% 相机命中率**（第二 atomic） | 一眼区分"全天空帧"与"贴脸山坡"（v0.2 靠 queries 反推的量，v0.3 直接测） |
| 审计电池标签修正 + LIVE 距离回显 + 分带亮度 + ISO 诊断档门槛 | dir[3]=-Y 曾误标 +Y(上)；ISO 帧（单区块+任意视角）可合法近平场，结构门槛仅对全场景帧生效 |

## v0.2 判别仪器（S6.5，PLACEMENT_FAIL 根因分离）

| 仪器 | 判什么 |
|---|---|
| raw-AABB 审计 | 提取顶点是否仍为 section-local（v0.1 fork 丢了 2.1 的这台仪器） |
| 相机区块顶点回读 | BLAS 里到底是什么字节（眼见为实） |
| live 实例矩阵回读 | flat[9..11] 是否 = translate(origin) |
| 隔离 TLAS 对照电池 | 同一 BLAS 同一 trace_ray，ISO vs LIVE 并排打同一组 ray：ISO 命中+LIVE miss ⇒ live TLAS 组装缺陷；ISO 也 miss ⇒ BLAS 内容缺陷 |
| 隔离渲染通道 | live 未过时用相机区块单实例 TLAS 渲染同一帧——成图即渲染链健康（即第一帧光） |

另含摄取覆盖修复：v0.1 真机实测 36/157 覆盖塔缩（低 y 层首轮空试后永不再试）；v0.2 空试槽位窗口内每 2s 重试（上限 6 次）。**v0.2 真机实证：174 重建/157 BLAS/168,982 三角，y=-64..112 全 12 层——覆盖修复生效。**

## 架构

```
Fabric CLIENT_STARTED → 后台摄取线程（500ms 轮询，零 mixin/refmap，2.1 骨架 fork）
  SectionBuilder.build → 顶点层提取（坐标 + COLOR 双通道）
  COLOR 偏移 = VertexFormat.getOffset(COLOR) 运行时解析（无硬编码）
  JNI → libradiance_pathtrace.dylib：
    顶点 section-local 原样入库（免 recentring）
    QUADS → uint32 索引展开 → 三角 BLAS
    TLAS instance = translate(origin)（2.1 已验证布局的等价简化）
    TLAS 随建 shading pools：逐三角 3 顶点色 + CPU 法线 + 实例基址表
  pathtrace.metallib（metal3 ray_query，双 kernel）：
    scene_trace_kernel — 2.1 原样（哨兵/抽查复用）
    pathtrace_kernel — 相机 ray → TLAS → 重心插值 albedo → 太阳直射 + 阴影 ray
      → cosine 加权漫反射弹射 → spp 累积 → Reinhard + gamma → RGBA8
      atomic 计数器回读吞吐（Mray/s 实测）
  PngWriter — 手写零依赖 PNG 编码器（不依赖 java.desktop）
```

## 现场

真机运行记录存档于 `docs/chapter2-pathtrace-field/`（回收后写入）。
