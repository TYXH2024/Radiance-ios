# 2.1 场景摄取设计 —— scene-ingestion（Metal 直连 · 第一章块）

> 状态：设计定稿（2026-10-27）
> 前置：2.0 METAL_RT_OK（docs/chapter2-metal-rt-probe-field/）——投送通道与进程内 Metal RT 已真机验证
> 上游参照：`src/main/java/com/radiance/client/proxy/world/ChunkProxy.java`（Vulkan 下游，MC 1.21.4/yarn 1.21.4+build.8，与真机环境完全一致）

---

## 1. 上游摄取协议解剖（复用依据）

上游 Vulkan 版的场景摄取链路（ChunkProxy 1766 行 + 3 个 mixin）：

```
BuiltChunkStorage.createChunks ──→ ChunkProxy.init(网格尺寸)
BuiltChunk.scheduleRebuild/clear ──→ enqueueRebuild(builtChunk)      [mixin 事件驱动]
后台 rebuild 线程:
  ChunkRendererRegionBuilder.build(world, sectionPos) → region
  SectionBuilder.build(sectionPos, region, vertexSorter, threadLocal storage)
      → RenderData{ Map<RenderLayer, BuiltBuffer> }                  [MC 官方编译器 + 自有 allocator]
  每 RenderLayer: BuiltBuffer → vertexAddress()/vertexCount()/format/indexType
      → 裸指针数组 → rebuildSingleNative(origin, index, ..., generation)
native (libcore): VkBuffer + Vulkan BLAS/TLAS
```

**关键洞察**：上游必须"自己 rebuild"而非读 MC 产物——MC 官方 mesh 的 BufferAllocator
所有权归 GL 上传路径（上传后即回收复用）；传自有 `BlockBufferAllocatorStorage` 给
`SectionBuilder.build` 才能安全持有 CPU 侧顶点。该约束对 Metal 版同样成立，设计继承。

上游 native 核为外置二进制（Windows `core.dll` / Linux `libcore.so`，不在本仓库）——
iOS 移植实质 = **在 iOS 上重写 native 下游**，Java 摄取协议按需降维复刻。

## 2. 与上游的三个刻意差异

| # | 上游（Vulkan） | 2.1（Metal，验证优先） | 理由 |
|---|---|---|---|
| 1 | mixin 事件驱动（scheduleRebuild/clear/setSectionPos 挂钩） | **轮询驱动**（后台线程定期扫描 BuiltChunkStorage 网格，比对 chunkData 引用/sectionPos 版本） | 沿用 2.0 哲学：零 mixin/refmap/Loom 产线依赖，ecj 零 MC classpath 可编译；mixin 挂点知识转化为轮询字段。2.2 需要事件精度时再升级 |
| 2 | takeChunk 转移语义（BufferAllocator 手术） | **SectionBuilder.build + ThreadLocal 自有 storage**（同上游 build 入口），读完即 close，不转移 | 首版避免所有权手术的边角风险；顶点数据在 rebuild 线程内同步拷入 MTLBuffer，生命周期封闭 |
| 3 | Vulkan BLAS/TLAS + Vista 远景 + BlockEntity 摄取 + 双线程池重要度调度 | **仅区块 BLAS/TLAS + 单后台线程 + 限量重建** | 2.1 验证目标：真实世界几何进入 Metal 加速结构且可求交；实体/远景/调度优化属 2.2+ |

## 3. 架构

```
┌──────────────────── MC 1.21.4 进程（zink 档渲染主链，不动）───────────────────┐
│                                                                              │
│  scene-ingestion mod（Fabric，CLIENT_STARTED 后台线程）                      │
│   ┌───────────────────── Java（全反射，intermediary 名）─────────────────┐   │
│   │ SceneIngestionMod   装载/判定/报告（scene_ingestion_report.txt）      │   │
│   │ SceneProxy          轮询 BuiltChunkStorage 网格 → 脏集 → 重建调度     │   │
│   │ ReflectionCache     intermediary 符号表（yarn tiny 校验过的硬编码）   │   │
│   └────────────────────────────┬────────────────────────────────────────┘   │
│                              JNI（裸指针 + 元数据数组）                       │
│   ┌────────────────────────────▼────────────────────────────────────────┐   │
│   │ libradiance_scene.dylib（Metal 直连，自建 MTLDevice/queue）          │   │
│   │  SceneGrid: 槽位数组（origin/generation/state/BLAS/顶点缓冲）        │   │
│   │  rebuild: quad 顶点 → recentre(-origin) → MTLBuffer → 三角 BLAS     │   │
│   │  frameTick: TLAS 脏时重建（instance transform = translate(origin)） │   │
│   │  castRay: ray_query kernel 场景求交（验证 + 2.2 种子）              │   │
│   └─────────────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────────────┘
```

## 4. 数据流与坐标系

- **顶点格式**：MC 1.21.4 区块 mesh 顶点 = world-absolute float32（position 恒在 offset 0，
  float3）。stride 由 Java 侧 `VertexFormat.getVertexSize()` 反射读取后随顶点指针一并传
  native —— native 侧零格式表。
- **索引**：区块 mesh 为 QUADS 非索引；native 侧按 quad 生成索引
  （`[i, i+1, i+2, i, i+2, i+3]`，uint32）。
- **recentring（关键正确性决策）**：顶点在进 BLAS 前平移 `-origin` 存 chunk-local 坐标，
  TLAS instance transform = `translate(origin)`。双重动机：
  1. float32 world-absolute 在 |坐标| > 2^24（16.7M，MC 世界边界 ±30M）时整数精度即失——
     BLAS 求交在 local 空间保精度；
  2. 2.2 路径追踪的相机 ray 同样 camera-relative，坐标系纪律从 2.1 起建立。
- **generation 协议**（继承上游）：每槽位带 generation 计数，rebuild/invalidate/relocate
  携带 generation，native 侧拒绝过期写入——防止轮询模式下重建乱序覆盖新网格。

## 5. JNI 边界（2.1 版，降维自上游 rebuildSingleNative）

```c
initNative(numChunks, sizeX, sizeY, sizeZ, bottomY)     // 网格初始化
frameTickNative()                                       // TLAS 脏时重建（节流）
rebuildSingleNative(ox, oy, oz, index, generation,
                    layerCount, verticesAddr,           // long[]  每 layer 顶点缓冲地址
                    vertexCountsAddr, stridesAddr)      // int[]   顶点数 / stride
invalidateSingle(index, ox, oy, oz, generation)
relocateSingle(index, ox, oy, oz, generation)
isChunkReady(index) -> bool
sceneStatsNative() -> (readyBLAS, totalTris, totalVerts, tlasBuilds, ...)  // 报告用
castRayNative(ox,oy,oz, dx,dy,dz, maxDist) -> (hitDist, instanceIdx)      // 验证用
shutdownNative()
```

砍掉的下游参数：geometryType/texture/groupName（RT 摄取仅需 position），
transferredBuffers 生命周期数组（同步拷贝后即 close），terrain（Vista 属 2.2+）。

## 6. 验证协议（2.1 的判定表）

进入世界后自动执行，增量落盘 `scene_ingestion_report.txt`：

| 段 | 内容 |
|---|---|
| S0 | 环境快照（同 2.0） |
| S1 | 资产解包（libradiance_scene.dylib） |
| S2 | System.load 装载 |
| S3 | 反射符号解析审计（每个 intermediary 类/字段/方法逐项 PASS/FAIL——轮询路线的专属防错段） |
| S4 | 网格发现（网格尺寸/槽位数/已发现区块） |
| S5 | 重建与 BLAS/TLAS 构建（计数/三角形总量/耗时；节流下的 TLAS 重建次数） |
| S6 | 场景求交验证：玩家眼部位置向 6 个轴向发射 ray（±X/±Y/±Z，maxDist 512），报告命中距离与命中槽位 |
| VERDICT | SCENE_INGEST_OK（ready BLAS ≥ 1 且三角形 > 0 且至少一向命中）/ SCENE_EMPTY（超时无几何，未进世界）/ REFLECT_FAIL（S3 段失败，逐符号定位）/ NATIVE_FAIL / NATIVE_MISMATCH |

## 7. 风险

1. **intermediary 硬编码风险**：反射符号无编译期校验——缓解：yarn tiny v2 离线校验
   （每个符号的 named↔intermediary + descriptor 全部对照）+ S3 段运行时逐符号审计。
2. **SectionBuilder.build 签名漂移**：以仓库内上游源码（同 yarn 版本）为准 +
   tiny descriptor 校验。
3. **轮询延迟**：500ms 扫描间隔 + 每轮限量重建（首版 ≤8 区块/轮）——验证场景足够，
   2.2 升级事件驱动。
4. **双引擎显存**（路线裁决 §4.3-4）：S5 报告 MTLBuffer/BLAS 字节计量，2.3 做预算。
