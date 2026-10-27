# chapter2-pathtrace 设计（2.2 v0.1 —— 路径追踪引擎首帧）

> 前置：2.1 scene-ingestion v0.5 真机判定 SCENE_INGEST_OK（2026-10-27）。
> 摆放契约盖章：**SectionBuilder 顶点为 section 局部坐标；TLAS instance =
> translate(origin)；MTLPackedFloat4x3 平移在 flat[9..11]**。
> 现场记录：`docs/chapter2-scene-ingestion-field/`

## 1. 阶段问题

| Q | 内容 | 判据 |
|---|---|---|
| Q1 | 摄取链在简化摆放契约下复现健康度？ | S4/S5 计数与 2.1 四连一致（~600 区块 / ~169k 三角 / 零积压） |
| Q2 | 简化摆放真机等价？ | 哨兵 3/3 + -Y 脚下地板命中 + 整数格线 ≥2（v0.5 同场景为 8/9） |
| Q3 | 全链路径追踪出图？ | PNG 落盘 + 图像统计非退化 + 用户眼见为实 |
| Q4 | 实测吞吐？ | kernel 内 atomic 计数 → [render] 行 Mray/s（非估算） |

## 2. 架构（fork + 三处外科手术 + 一处新增）

fork `chapter2/scene-ingestion`（v0.5，四连真机验证骨架），改动：

1. **COLOR 提取**：`VertexFormat.has(COLOR)`→`getOffset(COLOR)`
   （class_293#method_60836/60835 + class_296#field_52108，mappings 离线
   校验）动态解析逐层偏移；无 COLOR 的 layer 回退白色。native 侧逐顶点
   uchar4 入库。**不做纹理图集**——MC 把草绿/水蓝等染色烘进顶点色，
   顶点色即 albedo。
2. **摆放简化**：顶点原样入库（不 recentre），instance = translate(origin)。
   与已验证的 recentring+translate(2·origin) 代数等价，S6 抽查验证。
3. **shading pools**（随每次 TLAS 重建，序=实例序）：
   `triColors`（逐三角 3×uchar4，经 quad 索引表映射——kernel 重心插值与
   BLAS 三角形按构造一致）、`triNormals`（CPU 算的几何面法线——零 MSL
   API 风险）、`instTriBase`（实例首三角索引）。
   池失败→置 nil→渲染拒绝（陈旧池与新 TLAS 实例序不匹配是错误着色源）。

新增 `renderPathtraceNative`：相机基（yaw 0=+Z、pitch −90=上、70° 垂直
FOV；正负号约定与 MC `Camera.getYaw/getPitch` 一致）→ `pathtrace_kernel`
全格 dispatch（16×16 threadgroup + 越界 guard）→ RGBA8 回读 + atomic
ray 计数 → `[render]` 行实测统计。

移除 2.1 v0.4 诊断矩阵（sceneInfo/setPlacementMode/双假设锦标赛）——
问题已由 v0.5 定谳；哨兵与整数格线抽查作为**常驻回归仪器**保留。

## 3. pathtrace_kernel（MSL metal3 ray_query）

- 每 pixel × spp：抖动相机 ray → `intersection_query<instancing,
  triangle_data>`（2.0/2.1 已真机验证 API + 本版新增
  `get_committed_primitive_id` / `get_committed_triangle_barycentric_coords`）
- 命中：双面法线翻转 → 重心插值 albedo → 太阳直射（sunDir 固定，
  ndl>0 时**阴影 ray** any-hit）+ 半球天空环境 → cosine 加权弹射
  （throughput ×= albedo）
- 逃逸：天空渐变 + 太阳圆盘
- 累积 spp → Reinhard → gamma 2.2 → RGBA8
- **ray 计数器**：`device atomic_uint`，每次 query +1——吞吐是实测值

## 4. 验证纪律（沿用 2.1 v0.5 的零粉饰原则）

- 判定每档语义独立：PATHTRACE_OK / PLACEMENT_FAIL / RENDER_FAIL /
  IMAGE_DEGENERATE / SENTINEL_FAIL / SCENE_EMPTY / CAMERA_FAIL
- 图像统计自证（mean/range/量化颜色数/主色 top-4）与用户目检双通道
- 中心 ray 交叉验证行：单 ray 仪器与渲染 kernel 共用同一 TLAS，
  画面中心内容可由 CPU 侧预测（证据行，不参与判定）
- v0.1 明确不做：渐进实时化、GL 合成（2.3）、alpha 覆写、纹理 UV、
  MC 光照贴图——首帧先证明全链，再谈保真

## 5. 风险与对策

| 风险 | 对策 |
|---|---|
| MSL 新 API 名拼写（primitive_id/barycentric） | CI（macos-26）metal 编译即门槛；2.1 已验证 API 占核 |
| iOS JVM 无 java.desktop/ImageIO | 手写零依赖 PNG（java.util.zip only） |
| COLOR 元素缺席某 layer | 白色回退 + [rebuild] color=WHITE 行如实上报 |
| 池与 TLAS 实例序失配 | 同函数内同序构建；失败置 nil 拒渲 |
| 相机姿态反射链断 | CAMERA_FAIL 独立判定（不出假图） |
| 吞吐不足 | 480×270×24 spp ≈ 7.8M query（2.0 基线 5.34 Mray/s → 预计 <2s） |
