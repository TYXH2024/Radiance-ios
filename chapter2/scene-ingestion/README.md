# scene-ingestion — 第二章 2.1 阶段探针（Metal 直连 · 场景摄取）

> 设计文档：`docs/chapter2-scene-ingestion-design.md`
> 前置：2.0 `metal-rt-probe` 真机判定 METAL_RT_OK（投送通道 + 进程内 Metal RT 已验证）
>
> **✅ 阶段终局（2026-10-27）：v0.5.0 真机判定 SCENE_INGEST_OK——哨兵 3/3、SECTION-LOCAL R=(0,0,0) 精确、命中面整数格线自证。2.1 闭环，后续演进在 `chapter2/pathtrace`（2.2）。现场记录：`docs/chapter2-scene-ingestion-field/`**

## 验证问题

| Q | 内容 |
|---|---|
| Q1 | 真实 MC 世界几何能否进入 Metal 加速结构（每区块 BLAS + 实例 TLAS 动态重建）？ |
| Q2 | 摄取计数与 GL 侧 mesh 是否一致（区块/三角形量级合理性）？ |
| Q3 | 场景求交是否命中（TLAS instance + ray_query 全链路正确性）？ |

## 架构（上游 ChunkProxy 的 Metal 版降维）

```
Fabric CLIENT_STARTED → 后台摄取线程（500ms 轮询，零 mixin/refmap）
  MinecraftClient.world → worldRenderer.chunks → BuiltChunkStorage.chunks[]
  脏判定：BuiltChunk.data 引用变化 / sectionPos 变化
  重建（每轮 ≤16，v0.3 起全量排空约 19s）：
    ChunkRendererRegionBuilder.build → region
    SectionBuilder.build(sectionPos, region, BY_DISTANCE, ThreadLocal 自有 storage)
      → Map<RenderLayer, BuiltBuffer>
    逐 layer：ByteBuffer 顶点 + vertexCount + stride → JNI
  JNI → libradiance_scene.dylib：
    拼接 recentred float3（提取坐标 − origin）+ raw AABB 记录（v0.4 顶点空间审计）
    QUADS → uint32 索引展开 → 三角 BLAS → 槽位就绪 → TLAS 置脏
  TLAS 节流重建（1s）：instance transform = translate(t)，t 由布局模式决定
    （v0.4：ABS t=origin / MEASURED t=2·origin−R，矩阵平移在 flat[9..11]，
     MTLPackedFloat4x3 = 4x4 前三行列主序 —— Apple 文档定谳）
  验证（v0.5 诊断矩阵）：S6.0 回显 → S6.1 哨兵（隔离 TLAS + 顶点读回回显）→
    S6.2 raw-AABB 包围盒判定（SECTION-LOCAL → R=0 精确；中位数仅回退）→
    S6.3 双假设锦标赛（6 ray × 2 模式）→ S6.4 自动选优 + 14 ray 终验
    （6 轴向 + 8 对角，hitPoint 整数格线自证 + 归因到 section origin）
```

## 组成

- `src/net/prisma/sceneingest/`：Java 七类
  - `SceneIngestionMod` 主控（S0→S6 报告流水线 + 判定）
  - `SceneProxy` 轮询摄取调度（generation 协议防乱序）
  - `Mappings` intermediary 符号表 + S3 段逐项审计（yarn 1.21.4+build.8 离线校验）
  - `Native` JNI 声明 / `ProbeReport` 增量落盘 / 双 Fabric 入口
- `native/metal_scene.m`：场景管理（槽位网格 / BLAS/TLAS 动态构建 / 退役资源延迟释放 / castRay / v0.4 诊断矩阵：raw-AABB 审计 + 哨兵 TLAS + 布局模式切换 + 命中归因）
- `native/scene_trace.metal`：单 ray 场景求交 kernel（2.0 已验证的 `intersection_query` API）
- `build.sh`：macOS 全链构建（交叉编译 + metallib + ecj + JAR 组装）
- CI：`.github/workflows/chapter2-scene-ingestion.yml`（构建 → JNI 导出验证 → 幂等发 Release）

## 判定表（v0.4）

| 判定 | 含义 |
|---|---|
| SCENE_INGEST_OK | 终验电池（14 ray）至少一命中；判定行给出胜出布局与 R；hitPoint 行以整数格线自证零系统偏移 —— 2.2 路径追踪引擎绿灯 |
| SCENE_EMPTY | 摄取窗口内无就绪 BLAS（未进世界/区块 mesh 始终为空） |
| REFLECT_FAIL | S3 反射符号解析失败（报告逐符号定位——MC 版本不符的指纹） |
| NATIVE_FAIL | 原生段 FATAL/异常 |
| SENTINEL_FAIL | 隔离 TLAS 哨兵求交失败——v0.5 已修复仪器缺陷（顶点缓冲 36→48 B），若复现即为实例机制/矩阵编码的真实问题 |
| EXTRACTION_GARBAGE | 顶点提取异常（NaN/巨跨度）且锦标赛全 miss——顶点格式/stride 嫌疑 |
| NATIVE_MISMATCH | 哨兵通过 + AABB 健康 + 双假设锦标赛均 0 命中——顶点空间非两假设之一，或更深摆放问题 |
| CAMERA_FAIL | 相机不可读且无缓存（几何已摄取但未发射 ray；需回报） |
| LOAD_FAIL / EXTRACT_FAIL | 投送通道受阻（同 2.0） |

## 使用（真机）

1. Release 下载 `scene-ingestion-0.5.0.jar` 放 mods/（**替换**旧版，勿共存；建议移除 metal-rt-probe 避免重复报告）
2. 渲染器保持 zink 档
3. 启动游戏，**进入一个存档世界**，停留约 30–40 秒（全量排空约 19s + 验证窗口；log 出现 [scene] shutdown 后即可退出）
4. 回传游戏目录 `scene_ingestion_report.txt` 整份

## 归因开关

`-Dsceneingest.disabled=true`（或 `SCENEINGEST_DISABLED=1`）：零原生调用，闪退 A/B 对照。
