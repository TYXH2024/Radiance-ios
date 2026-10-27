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
