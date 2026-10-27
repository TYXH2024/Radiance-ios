# metal-rt-probe 2.0 真机现场判读 —— METAL_RT_OK

> 归档时间：2026-10-27
> 测试设备：Apple M3（iPad，iOS 18.3.2，gpuFamily Apple9）
> 宿主：AngelAuraAmethyst（Pojav 系）· zink 渲染档 · Fabric Loader 0.20.1 · MC 1.21.4 · LWJGL 3.3.3 · JVM 21
> 被测物：`metal-rt-probe-0.1.0.jar`（Release `metal-rt-probe-v0.1`，CI 产物）
> 原始报告：`M3-zink-report.txt`（用户回传全文，本目录）

---

## 1. 判定

**METAL_RT_OK —— 第二章 2.0 阶段全绿，2.1 场景摄取绿灯。**

进程内 Metal RT 全链路（BLAS → TLAS → ray_query compute → 掩码剔除 → 距离校验）
在 MC 游戏进程内、zink 渲染主链同进程并存的前提下全部通过，且与渲染主链零干扰。
这等价于第一章判定表中的"判 A"在 Metal 直连路线上达成。

## 2. 逐段判读

### S0 环境快照
- 渲染档确认为 zink（`AMETHYST_RENDERER=libOSMesa.8.dylib`，
  `org.lwjgl.opengl.libname` 同值）——即第一章结案后用户选定的稳定宿主档。
- `GALLIUM_DRIVER=null`：与启动器 zink 档预期注入值 `zink` 不一致。不影响判定
  （渲染主链正常工作、游戏可进；推测启动器在 native 侧按需设置或该变量仅在某些
  版本注入）。如实记录，供 2.3 双引擎同步时参考。
- `org.lwjgl.vulkan.libname=MoltenVK` 为启动器固定 JVM 参数（第一章已实锤），
  zink 档下宿主不触碰 LWJGL Vulkan 类——RT 侧车与渲染主链的"零竞争"前提成立。

### S1 资产解包（投送通道前半）
- CI 产出的 `libradiance_metal_probe.dylib`（89408 B）与 `trace_kernel.metallib`
  （5720 B）从 mod JAR 解包到 game 目录（`user.dir`，HOME 之下）成功。
- 尺寸与 CI artifact 一致，无截断。

### S2 System.load 装载（投送通道后半）
- **路线裁决文档 §4.3 风险 1（dylib 装载签名）正式销项**：ad-hoc 签名的 arm64
  dylib 经 JVM dlopen（即启动器 `hooked_dlopen` 受信链，与 LWJGL natives 同款
  通道）装载成功，无 UnsatisfiedLinkError。
- 免改包投送通道全链路（JAR 内置 → 解包 → System.load → 受信 dlopen）成立，
  这是后续所有原生核（场景摄取 / 路径追踪引擎）的同一通道。

### S3 Metal RT 全链路
- 设备侧：`Apple M3 GPU` / gpuFamily **Apple9** / `supportsRaytracing=YES` /
  **`dedicatedIntersectionHW=YES`** —— Apple9 起的专用交集硬件在场，与第一章
  CI 交叉编译验证、路线裁决 §2.4 的预期一致。
- 构建侧：三角 BLAS 14.64 ms（as=344 B / scratch=320 B）、实例 TLAS 9.17 ms
  （as=480 B）。绝对值为微型测试场景（单 BLAS 单三角形）量级，2.1 真实区块
  网格的构建耗时另测；重要的是 **构建 API 全链路无失败**。
- 管线侧：ray_query compute pipeline 创建 373.15 ms（首次创建含管线编译，
  正常量级；threadsPerThreadgroup=1024）。
- **风险 2（MSL 运行时编译 / shaderpack 路线）正式销项**：
  `newLibraryWithSource(<metal_raytracing>)` 在真机 OK —— 2.2 阶段若走 shader
  模块热载路线，运行时编译通道可用。
- 吞吐：262144 rays / 49.10 ms = **5.34 Mrays/s**（首个 iOS 真机硬件 RT 吞吐
  数据点入库）。注意该数字出自微型测试 kernel（单 BLAS 单三角形、每 ray 单次
  ray_query），是**下界基准**；真实场景复杂度（多 BLAS/多三角形/多次弹射）下的
  吞吐在 2.2/2.3 校准。
- 正确性：hit-rays 131072/131072、masked-miss rays 131072/131072 —— 命中/掩码
  剔除/距离校验全部正确，无一错 ray。

## 3. 与第一章的对照（判定体系连续性）

| 维度 | 第一章（Vulkan 路线） | 第二章 2.0（Metal 直连） |
|---|---|---|
| 探针 | rt-probe v1.2（判 C：MoltenVK 1.4.3 无 RT 扩展） | metal-rt-probe 2.0（**METAL_RT_OK**） |
| RT 后端 | MoltenVK（无 VK_KHR_acceleration_structure / ray_query） | MTLAccelerationStructure + MSL `raytracing::intersection_query` |
| 投送 | —（无需原生投送） | JAR 内置 dylib → 解包 → System.load 受信链（实测通过） |
| 结论 | 不改包路线终结 → Metal 直连裁决 | 裁决被真机验证 → 2.1 绿灯 |

## 4. 下一步（2.1 场景摄取）

- 目标：MC 区块网格（含 PBR 扩展顶点格式）→ JNI → 动态 BLAS/TLAS 重建
  （增量 / refit），实体与玩家代理后续接入。
- 上游参照：`src/main/java/com/radiance/client/proxy/world/ChunkProxy.java`
  （Vulkan 下游）—— 2.1 保持其摄取拓扑，下游替换为 Metal 加速结构。
- 验证问题：进入真实世界后 RT 侧车构建出的 BLAS 数量/三角形计数与 GL 侧
  mesh 一致；相机射线求交命中预期几何；区块更新触发正确的增量重建。
