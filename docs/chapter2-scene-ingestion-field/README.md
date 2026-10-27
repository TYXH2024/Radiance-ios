# chapter2-scene-ingestion-field — v0.1.0 真机实测回收（2026-10-27）

设备: Apple M3 真机（iPhone，AngelAuraAmethyst / Pojav 系）
档位: OSMesa 软渲染宿主（AMETHYST_RENDERER=libOSMesa.8.dylib / GALLIUM_DRIVER=null）
MC: fabric-loader 0.20.1 + 1.21.4 / LWJGL 3.3.3 / Java 21.0.8-internal aarch64
原始报告: `M3-v0.1.0-report.txt`

## 判定: REFLECT_FAIL（46/47 符号通过）——但根因不在真机，在探针自身

### 现象

S1 解包 PASS（libradiance_scene.dylib 93936 B + scene_trace.metallib 5560 B），
S2 装载 PASS（受信装载链复验成立），S3 审计 47 项中唯一 FAIL：

```
[FAIL ] method net.minecraft.class_6850#method_39969 未找到（签名漂移？）
```

### 根因（离线复盘，证据链完整）

`method_39969` = `ChunkRendererRegionBuilder.build(World, ChunkSectionPos)`。
yarn 1.21.4+build.8 v2 tiny（mappings.tiny 第 13238-13244 行）明确：

```
c  net.minecraft.class_6850  net.minecraft.client.render.chunk.ChunkRendererRegionBuilder
m  (Lnet/minecraft/class_1937;Lnet/minecraft/class_4076;)Lnet/minecraft/class_853;  method_39969  build
```

符号宿主与签名**从未漂移**。真机 46/47 全 PASS（含 1.21.4 特有的 SectionBuilder
全套 method_60904 四参签名）亦证明运行时就是 1.21.4。

FAIL 的真实机制在探针 `Mappings.java`（v0.1）：

1. `resolveAll()` 的类审计列表只有 21 个类，**漏了 World（class_1937）**——
   而 `method_39969` 审计需要它做参数类型；
2. `cls()` 对未注册类名**静默 fallback 返回 `Object.class`**（无任何报错）；
3. 于是实际执行的是
   `class_6850.getDeclaredMethod("method_39969", Object.class, class_4076)`
   ——参数类型不匹配，`NoSuchMethodException` 必然发生；
4. 报告提示语"签名漂移？"因此误导归因方向。

一句话：**探针拿错误的参数类型去查一个本身正确的符号，然后把这个错算到了
真机头上。**

### 修复（v0.2.0，commit 本目录同步入库）

- `Mappings.java`：World（class_1937）纳入审计列表（21→22 类，S3 报告将新增
  `class net.minecraft.class_1937` PASS 行）；`cls()` 加固——未注册类显式
  `Class.forName` 并缓存，解析失败显式抛 `IllegalStateException`，
  **彻底消灭静默降级**；
- `SceneIngestionMod.java`：报告头 bump 0.2.0；REFLECT_FAIL 提示语更正
  （v0.2 修复后若复现该判定，即为真实的版本/映射不符指纹）；
- 版本标识全链 bump（fabric.mod.json / build.sh / CI workflow），
  Release tag `scene-ingestion-v0.2`；
- 本地 ecj 冒烟：9 class 产出零错误。

### 经验教训（后续阶段沿用）

反射审计器的参数类型类必须与符号同表管理；**任何 fallback 都必须显式**——
静默降级 `Object.class` 制造了"真机符号漂移"的假象，浪费一轮真机实测。
这一课已写进 WORKLOG 条目（七）。

### 下一步

v0.2.0 真机复测（zink 档 + 进入存档世界 + 等 30s），期望走到 S4/S5/S6：
网格发现 → 真实几何进 BLAS/TLAS → 相机 6 向 ray 求交 →
`SCENE_INGEST_OK` → 2.2 路径追踪引擎。

---

# v0.2.0 真机实测回收（2026-10-27 16:55）——摄取主链成立，S6 被三重缺陷拦停

原始报告: `M3-v0.2.0-report.txt`（用户操作：进存档原地转半分钟后退出游戏）

## 报告判定: NATIVE_MISMATCH —— 但判定语义失真：S6 实际未发射任何 ray

## 已被真机验证的部分（2.1 核心问题 Q1/Q2 的答案）

- **S3 全量 57 项 PASS**（含 v0.2 修复的 method_39969 与新增 class_1937）
  ——反射符号表与运行时完全对齐，v0.2 修复生效；
- **网格发现**：600 槽位（5×24×5 = 80×384×80 方块柱），与 OSMesa 软渲染
  宿主上的 MC 渲染主链并行不悖——轮询摄取协议成立；
- **BLAS 构建 30 次**：常规 0.7–4 ms/区块（万级三角形），首次 10 ms，
  一次 1245 ms 离群（疑首次大 scratch/serializer 预热，单次性，不阻塞）；
- **TLAS 动态重建 5 次**（0.43–3.08 ms，instance 8→30 渐增）——增量
  重建 + 退役延迟释放协议（retired 列表在新 TLAS 完成后才 release）真机工作正常；
- **几何量级**：36556 三角形 / 73112 顶点 / 顶点+索引缓冲 1.5 MB——
  真实 MC 场景 mesh 成功进入 Metal 加速结构（Q1 成立）。

## 缺陷 1（报告可见）：S6 相机读取 NPE——field_1352 未审计 + res() 静默 null

`cameraPos()` 运行时读 Vec3d.x/y/z（field_1352/1351/1350），但这三个字段
不在 S3 审计列表 → `res()` 对未注册符号**静默返回 null** → `f.get()` 抛
裸 NPE → 被包装成 `IllegalStateException: field read field_1352`。

与 v0.1 的 `cls()` 静默降级是**同一缺陷家族的第三次出现**（v0.2 只修了
cls()）。且判定逻辑把"cam==null 跳过 S6"与"ray 全 miss"混为一谈，输出
了误导性的 NATIVE_MISMATCH——实际一个 ray 都没发。

## 缺陷 2（报告可见）：摄取率 30/600——脏标记被 cap 截流后错误消费

首轮扫描把全部 600 个 data 引用快照进 `lastDataRef`；每轮 8 个的重建
cap 截流了 592 个，但它们已被标记为"干净"——此后只有 MC 重新发布 mesh
的区块（用户转动视角触发的 translucent 重排序等）才会再变脏。30 次重建
里 #9–#30 全是这种"被动重发布"，主动积压从未排空。

## 缺陷 3（离线文档核查抓出的定时炸弹）：TLAS 实例矩阵布局错误

被相机 NPE 掩盖——若 S6 真跑了，ray 会打到错位几何上全 miss，产出
"排查 TLAS 变换"的误导方向。离线证据链：

- Apple 官方文档：`simd_float3x4` = **"3 列 × 4 行"**，`columns` 为三个
  `simd_float4`（每列 4 float）；
- Metal 4 `instanceTransformationMatrixLayout` 文档：实例变换矩阵默认
  **`MTLMatrixLayoutColumnMajor`**；
- Apple 开发者论坛存档（thread/653267）：从 simd_float4x4 转实例矩阵
  需要**转置**。

数学推导：matrix_float3x4 内存 = col0(4) | col1(4) | col2(4)，行向量
约定 `p' = [x y z 1]·M(4×3)`，单位阵+平移的正确布局是

```
[1, 0, 0, tx,   0, 1, 0, ty,   0, 0, 1, tz]
```

平移在 flat[3]/[7]/[11]（各列的 .w）。v0.2 的 metal_scene.m 误按
"4 行×3 列行主序"写在 flat[9..11]，Metal 解码出剪切型垃圾矩阵
（p'y = x 等）——几何整体被压扁剪切，ray 必然全 miss。2.0 探针的
TLAS 用**单位变换**，所以这个布局 bug 从未被真机暴露。

## v0.3.0 修复清单

1. **metal_scene.m**：实例矩阵按 simd 列主序布局重写
   （m[3]/m[7]/m[11] = origin 平移分量）；
2. **Mappings.java**：Vec3d x/y/z 入 S3 审计（57→60 项）；`res()` 对
   未知符号显式抛错——静默 null 家族三连修；
3. **SceneProxy.java**：从未摄取（generation==0）槽位永远视为脏（积压
   排空 30/600→全量）；每轮限额 8→16（600 槽位约 19s 排空）；收口
   条件 = 积压排空 / 静置 6s / 35s 强制；**摄取期相机位置缓存**（每轮
   刷新，用户退出世界后 S6 仍可用缓存位置发射 ray——ray 是纯 native
   操作不依赖 MC 存活）；
4. **SceneIngestionMod.java**：S6 直读失败回退缓存；新增 CAMERA_FAIL
   判定（与"全 miss"语义分离）；NATIVE_MISMATCH 提示语更新（布局已修）。

## 下一步

v0.3.0 真机复测：进存档停留 30–40 秒（全量排空 + 验证窗口）→
期望 `SCENE_INGEST_OK`（-Y ray 应在数米内命中地面，水平向 ray 命中
周围已摄取区块）→ 2.2 路径追踪引擎。

---

# v0.3.0 真机实测回收（2026-10-27 17:31）——摄取全链 4.6× 规模复验成立；矩阵"修复"方向反了

原始报告: `M3-v0.3.0-report.txt`（用户操作：进存档原地转半分钟后退出游戏）

## 报告判定: NATIVE_MISMATCH（六向全 miss）——但摄取本体全面转绿

## 本次被真机验证的部分（v0.3 三修复全部生效）

- **S3 全量 60 项 PASS**（含 Vec3d field_1352/1351/1350 与 res() 显式化）；
- **v0.2 的"相机 NPE / 30/600 截流 / 判定语义混淆"三缺陷全部消失**：本次相机
  位置 (26.08, 92.62, 7.56) 正常读取并发射了 6 条 ray；
- **摄取排空修复验证**：600 槽位全量排空（180 次重建 / 157 ready BLAS /
  积压 0 / 19.5s）——v0.2 的 30/600 成为历史；
- **规模复验**：三角形 36556 → **168974**（4.6×），顶点 337948，TLAS 7 次
  重建（0.56–2.41 ms）全部成功，退役延迟释放持续工作；
- **v0.2 的 1245 ms BLAS 离群未复现**（本次最大 16.16 ms）——确认为一次性
  首触发预热现象，维持延迟释放策略即可。

## 根因（离线定谳，证据链三重闭环）：v0.3 的矩阵"修复"恰好改反了

> **勘误**：上一节（v0.2 回收）"缺陷 3"里的推导把 transformationMatrix
> 认定为 `simd_float3x4`（3 列 × 4 行）——**类型认定错误**，v0.3 据此把
> 平移从 flat[9..11] 搬到了 flat[3]/[7]/[11]，把唯一正确的一处改坏了。
> 该推导当时未被任何真机数据检验（v0.2 的 S6 死于相机 NPE，一条 ray 都
> 没发过）。以下为 v0.3 报告回收后的重新定谳。

真实类型与布局（Apple 官方文档，英文/德文页一致）：

```
transformationMatrix: MTLPackedFloat4x3
  "A structure that contains the top three rows of a 4x4 matrix
   ... in column-major order."
  columns: (MTLPackedFloat3, MTLPackedFloat3, MTLPackedFloat3, MTLPackedFloat3)
  （底行隐含 [0,0,0,1]）
```

即内存 = **4 个 packed float3 列**（每列 3 float）：col0=flat[0..2]、
col1=flat[3..5]、col2=flat[6..8]、**col3=flat[9..11]=平移**。
单位阵+平移的正确编码：

```
[1, 0, 0,   0, 1, 0,   0, 0, 1,   tx, ty, tz]
```

**这正是 2.0 探针单位变换 / v0.2 平移的同款布局。**

证据三角：

1. **文档**：上引 Apple 原文（developer.apple.com/documentation/metal/
   mtlpackedfloat4x3-swift.typealias）——"4x4 前三行 + 列主序 + 4 列"，
   平移必然在第 4 列；
2. **2.0 真机数据**：2.0 的 kIdentityColumns = `[1,0,0, 0,1,0, 0,0,1,
   0,0,0]` 恰好按"4 列 packed float3"书写（第 4 列全零 = 零平移），
   真机 TLAS 求交全命中——第 4 列语义成立；
3. **v0.3 真机数据**：v0.3 编码 `[1,0,0,tx, 0,1,0,ty, 0,0,1,tz]` 在真布局
   下解码为 col1=(tx,0,1)、col2=(0,ty,0)、col3=(0,1,tz)——剪切型垃圾矩阵，
   p' = (x + tx·y, ty·z + 1, y + tz)，几何被撕碎——与"摄取统计完全健康 +
   六向 ray 全 miss"的报告形态精确吻合。

唯一在两种布局下都无法区分的 2.0 单位阵（全零平移）掩盖了这一切；v0.3
第一次把非零平移送上真机，也第一次把布局错误暴露出来——只是暴露的方式
是"新引入的回归"而非"遗留 bug"。

## 次级悬案（本次无法排除）：顶点空间——世界绝对 vs section 局部

即便矩阵修对，还有一个独立隐患：设计 §4 假设 SectionBuilder.build 产出
**世界绝对坐标**顶点（故 native recentring -origin + TLAS translate(origin)）。
但 MC 1.18+ 的已知渲染行为是区块网格顶点在烘焙时即 **section 局部坐标**
（渲染侧才 translate(sectionOrigin − camera)）。若如此，recentring 会把
几何塌缩到世界原点 [0,16]³：

- 相机 (26.08, 92.62, 7.56) 在塌缩体之外 → 六向 ray 全 miss——**与 v0.3
  报告同样精确吻合**；
- v0.2/v0.3 两轮真机均未产出任何顶点坐标证据（报告只有计数），无法
  从现有数据区分"矩阵回归"与"顶点空间错位"谁在起作用（也可能两者都在）。

## v0.4.0 修复与诊断矩阵（一次真机运行全部定谳）

1. **矩阵编码恢复文档布局**（平移 flat[9..11]，encode_instance_matrix
   单点收敛，附完整归因注释）；
2. **raw-AABB 审计**（S6.0/S6.2）：rebuild 时逐槽记录原始顶点 AABB；
   审计 e = rawCenter − 8 的分布——随 origin 变化 → 世界绝对；恒为常数 R
   → 区块局部(+R)。数据定谳，不依赖任何文档；
3. **哨兵求交**（S6.1）：零 MC 数据的隔离 TLAS（单位实例 + 平移实例 +
   阴性对照），单独验证实例机制与矩阵编码；
4. **双假设锦标赛 + 自动选优**（S6.3/S6.4）：ABS（t=origin）vs
   MEASURED（t=2·origin−R）各 6 向 ray 实测，胜者自动应用并跑 14 ray
   终验（含命中区块归因）——无论顶点空间是哪种，一次真机运行即闭环。

## 下一步

v0.4.0 真机复测（替换 v0.3 jar；进存档停留 30–40s）→
期望 `SCENE_INGEST_OK` + 报告给出胜出布局模式（即 2.2 引擎的顶点空间
最终答案）→ 2.2 路径追踪引擎。
