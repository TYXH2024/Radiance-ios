# Radiance iOS 移植可行性分析报告

> 分析对象：`Minecraft-Radiance/Radiance` 0.1.6-alpha（本 fork：TYXH2024/Radiance-ios，MC 1.21.4 Fabric 分支）
> 参考启动器：`Gsjsjzhznsz/Prisma-Minecraft-iOS-Launcher2`（Amethyst 系）
> 分析日期：2026-10-26
> 分析范围：Radiance Java 模组源码（553 文件）、MCVR 原生渲染器源码（C++，src/core 153 文件 + src/shader 299 文件）、Shaderpack-Advanced 着色器包（94 GLSL + RT 阶段源）、Prisma 启动器源码（JVM/图形栈/native 加载机制）、MoltenVK 上游能力核实

---

## 1. 结论摘要（TL;DR）

**结论：可以移植，且属于"架构上顺路、单点被卡、绕行方案明确"的移植——但不是零改动直接跑。**

- **架构层面高度可行**：Radiance 不是 OpenGL 光影包，而是 Vulkan C++ 原生渲染器（MCVR），硬件光追完全走 Vulkan KHR RT 扩展；而 Prisma 启动器已经内置 MoltenVK 1.4.3 并打通了 "CAMetalLayer → VkSurface → Minecraft/LWJGL 经 libMoltenVK 自管 Vulkan" 的完整通路，甚至已内置 VulkanMod 兼容性取证逻辑。两边的技术栈天然对齐。
- **唯一硬缺口**：上游 MoltenVK（含启动器内置的 1.4.3）**尚未实现 Vulkan 光追三扩展**（`VK_KHR_acceleration_structure` / `VK_KHR_ray_tracing_pipeline` / `VK_KHR_ray_query`），这是设备会被 MCVR 直接拒选的唯一原因。其余 MCVR 硬性需求（`VK_KHR_push_descriptor`、`VK_KHR_deferred_host_operations`、Vulkan 1.4、descriptorIndexing、synchronization2、bufferDeviceAddress 等）MoltenVK 全部支持。
- **推荐路线（路线 B）**：在 MCVR 内部增加一个 **Metal RT 后端**（互操作走 MoltenVK 官方支持的 `VK_EXT_metal_objects`），着色器链改为 GLSL → SPIR-V（shaderc，原样保留）→ MSL（SPIRV-Cross 的 `metal::ray_tracing` 后端）→ Metal RT 管线。MCVR 的 RT API 调用面极小且封装良好（详见 §4.3），该路线不触碰启动器的 MoltenVK 二进制。
- **设备门槛**：真正的硬件光追要求 Apple9 GPU 家族及以上（iPhone 15 Pro / A17 Pro 起，iPhone 16/17 全系，M3/M4 iPad Pro）。M1/M2、A14–A16 机型 Metal RT API 可用但无专用求交硬件，性能会大打折扣。
- **性能预期**：A17 Pro 级别设备在低分辨率渲染 + FSR 超分 + NRD 性能档降噪下，30 FPS 量级的可玩目标是现实的（Radiance 面向桌面 GPU，移动端必须配激进画质预设）。

---

## 2. Radiance 架构解剖（源码证据）

### 2.1 三仓库体系

| 仓库 | 角色 | 关键事实 |
| --- | --- | --- |
| **Radiance**（本 fork） | Java 模组层（Fabric/Forge/NeoForge） | 183 个 Java 文件，52 个 `vulkan_render_integration` mixin；按 `os.name` 加载原生库并从 jar 释放 `shaders/`、`modules/` |
| **MCVR** | Vulkan C++ 原生渲染器 | C++23；CMake；JNI；运行时 shaderc 编译 GLSL→SPIR-V（目标环境 Vulkan 1.4，带 .spv 磁盘缓存） |
| **Shaderpack-Advanced** | 路径光追着色器包 | `path/direct|indirect(diffuse/specular)|transmission`、`lighting/restir`（ReSTIR GI）、`lighting/shadow`、`volume` 等完整 PT 体系，raygen/closest-hit/any-hit/miss 全套 RT 阶段 |

### 2.2 Java 层关键机制（移植相关）

- **原生库加载**（`RadianceClient.java:61-89`）：`os.name` 含 `windows` → 加载 `core.dll`；含 `linux` → 加载 `libcore.so`；**其他一律抛异常**。iOS 端必须新增分支（见 §6 工程清单 E-1）。
- **GLFW 复用而非自建**（`RendererProxy.java:21` + MCVR `middleware/com_radiance_client_proxy_vulkan_RendererProxy.cpp:33-120`）：native 侧用 `dlopen(name, RTLD_NOW | RTLD_NOLOAD)` 找到 **LWJGL 已加载的 GLFW**，再 `dlsym` 出 `glfwCreateWindowSurface` / `glfwGetRequiredInstanceExtensions` 等符号。这意味着 MCVR 不自己链接 GLFW，**与启动器的 GLFW shim 天然兼容**（启动器已为 LWJGL Vulkan 路径提供 CAMetalLayer surface）。
- **UI 后端双变体**（`build.gradle:22-29`，`gradle.properties` 默认 `radianceUiBackend=opengl`）：`opengl` 变体用 `GL_EXT_memory_object` + `GL_EXT_semaphore` 做 GL UI ↔ Vulkan 世界互操作（`device.cpp:125-148` 为硬性要求）；**`vulkan` 变体 UI 也走 Vulkan，完全不需要 GL 互操作**——iOS 应采用此变体，绕开 GL 互操作这个大坑。
- Vulkan UI 模式下 MC 的 UI 绘制经 `proxy/vulkan`（BufferProxy/PipelineStateProxy/ShaderProxy/TextureProxy）与 mixin 层重定向进 MCVR，无需真实 GL 上下文参与合成。

### 2.3 MCVR 对 Vulkan 的硬性需求清单（设备选择门槛）

`src/core/vulkan/physical_device.cpp:112-186`（设备候选拒选条件）与 `device.cpp`（设备创建）：

**硬性设备扩展**：`VK_KHR_swapchain`、`VK_KHR_ray_tracing_pipeline`、`VK_KHR_acceleration_structure`、`VK_KHR_deferred_host_operations`、`VK_KHR_push_descriptor`；（OPENGL UI 变体额外要求 external_memory/semaphore —— VULKAN UI 变体不需要）

**硬性特性**：`rayTracingPipeline`、`rayTracingPipelineTraceRaysIndirect`、`rayTraversalPrimitiveCulling`、`accelerationStructure`、`synchronization2`、`bufferDeviceAddress`、`shaderDrawParameters`

**可选/降级**（不支持的扩展会被过滤跳过，`device.cpp:211-221`）：`VK_EXT_extended_dynamic_state2/3`、`VK_EXT_vertex_input_dynamic_state`、`VK_KHR_maintenance5`、AMD coherent memory、DLSS/FSR/XeSS 各自按能力探测降级。

**着色器管线**：GLSL 源码在设备上由 shaderc 现场编译为 SPIR-V（`shader.cpp:435-443`，含 raygen/closesthit/anyhit/miss/intersection/callable 全阶段映射），带依赖哈希的 .spv 缓存。**着色器源就是 GLSL 文本（jar 内 `shaders/` 目录 + shaderpack），无离线二进制依赖**——这为"GLSL→SPIR-V→MSL"双跳转换提供了理想入口。

### 2.4 RT 调用面（决定移植工作量的核心事实）

对 `src/core` 全量统计：

| 符号 | 调用点数 |
| --- | --- |
| `vkCmdTraceRaysKHR` | 1 |
| `vkCmdTraceRaysIndirectKHR` | 1 |
| `vkCreateRayTracingPipelinesKHR` | 1 |
| `vkGetRayTracingShaderGroupHandlesKHR` | 1 |

- AS 封装集中在 `as.cpp`（862 行）：BLAS **全部为三角形几何**（`VK_GEOMETRY_TYPE_TRIANGLES_KHR`），TLAS **全部为实例几何**（`VK_GEOMETRY_TYPE_INSTANCES_KHR`），**没有 AABB/procedural 几何**——这是映射到 Metal `MTLPrimitiveAccelerationStructureDescriptor`（三角形）+ 实例 TLAS 的**最友好形态**。
- SBT 封装集中在 `sbt.cpp`（269 行）；RT 管线创建在 `pipeline.cpp`（390 行）。
- 结论：**RT 面总计约 2000 行、高度封装、调用点个位数**——具备整体替换为 Metal 后端的工程条件。

### 2.5 重依赖项在 iOS 上的处置

| 依赖 | iOS 处置 |
| --- | --- |
| GLFW | 编译剥离：复用启动器已加载的 GLFW shim（dlopen NOLOAD 机制），或新增 Metal surface 后端（`window_surface.cpp` 已有 Glfli/Xlib/Win32 三后端可仿照） |
| volk | Apple 分支已会依次尝试 `libvulkan.dylib` → `libMoltenVK.dylib`（`volk.c:93-102`）；启动器预加载 libMoltenVK，可达 |
| NRD（降噪，刚需） | 保留：纯 Vulkan SPIR-V compute，MoltenVK 可消费；Apple GPU 上正确性/性能需实测 |
| DLSS / XeSS | 关闭（`MCVR_ENABLE_XESS=OFF`、DLSS 探测自然降级）——NVIDIA/Intel 专有 |
| FidelityFX SDK（FSR3/FG） | 首期关闭（`MCVR_ENABLE_FFX_UPSCALER=OFF`），超分用 MCVR 自带 native 档或后续评估 arm64 适配 |
| VMA / GLM / stb / json / minizip / tinyexpr / sharc | 纯 C/C++ 库，直接交叉编译 |
| shaderc（glslang） | 可在 iOS 运行（启动器 MobileGlues 已内置 glslang 运行时编译，先例成立） |

---

## 3. 启动器（Prisma/Amethyst）图形栈解剖

### 3.1 与移植直接相关的能力（源码证据）

| 能力 | 证据 |
| --- | --- |
| **内置 MoltenVK 1.4.3** | `Natives/ctxbridges/vk_bridge.m:30-37`（"libMoltenVK.dylib 已更新为 1.4.3，上游 main @ 2c28e0c8，2026-10-09 CI 构建"） |
| **CAMetalLayer 直接作为 Vulkan surface，MC/LWJGL 自管 Vulkan** | `Natives/egl_bridge.m:865`（"返回 CAMetalLayer 作为 Vulkan surface，MC/LWJGL 通过 libMoltenVK.dylib 自管 Vulkan"） |
| **VulkanMod 运行取证**（Vulkan 渲染器模组先例） | `JavaLauncher.m:2995-3029`（Task244：VulkanMod + Iris 共存检测、`graphicsApi=prefer_vulkan` 路径） |
| **JVM 报告 `os.name = "Mac OS X"`** | `JavaLauncher.m:1122`（为让 LWJGL/JNA 在 iOS 工作而伪装） |
| **LWJGL 3.4.1 + vulkan/shaderc/spvc 绑定** | `JavaApp/libs/lwjgl-341/`（lwjgl-vulkan.jar、lwjgl-shaderc.jar、lwjgl-spvc.jar） |
| **mod 经 JNI 加载 iOS 原生库的先例** | TouchController 内置 iOS 传输层（README「模组双 ABI 传输层」）；JNA darwin-aarch64 libjnidispatch 从 jar 提取后正常加载（`JavaLauncher.m:3375`） |
| **JIT / Library Validation bypass** | 崩溃根治系列、JIT 工具七选一（StikDebug/SideStore/TrollStore 等），外部 runtime 与 dylib 加载已常态化 |
| **SDL3 Vulkan loader 句柄共享** | MC 26.3 路径 `Natives/sdl3_hook.m`（"Vulkan 加载器句柄共享"） |

### 3.2 结论

启动器侧**不需要为 Radiance 做结构性改动**：JVM（Java 21 运行时）、MoltenVK 加载、窗口/surface、native 库加载先例、Fabric 模组运行环境全部现成。MC 1.21.4 + Fabric 正是启动器的常规支持区间（`radianceUiBackend=vulkan` 构建的 jar 放入 mods 即为集成形态）。

---

## 4. 关键差距：MoltenVK 的光追缺口（已核实）

### 4.1 事实

- MoltenVK 1.4.x 宣告 **Vulkan 1.4**（与 MCVR 的 shaderc 目标环境、`RenderSystem.apiDescription = "Vulkan 1.4"` 完全一致）。
- 上游仓库代码搜索 `MTLAccelerationStructure` / `AccelerationStructure`：**0 命中**——RT 三扩展未实现。
- 跟踪 issue KhronosGroup/MoltenVK#427（2018 开至 2026-06 仍开放，53 评论）：核心卡点为 **Metal 的 BLAS 无法直接以 `VkDeviceAddress` 形式被 TLAS 实例引用（宿主侧问题）**；且社区确认 MoltenVK 已转维护模式，新特性规划在继任项目（KosmicKrisp，目前面向 Apple Silicon macOS，iOS 仍推荐 MoltenVK）。
- **MCVR 硬性需求中 MoltenVK 已支持的**：`VK_KHR_push_descriptor`（仓库 vulkan.mm 扩展表确认）、`VK_KHR_deferred_host_operations`、`VK_EXT_metal_objects`（Vulkan↔Metal 资源互操作的官方桥梁）、Vulkan 1.4 核心（descriptorIndexing / sync2 / BDA / SPIR-V 1.5+）。

### 4.2 三条移植路线

#### 路线 B（推荐）：MCVR 内嵌 Metal RT 后端（混合渲染栈）

```
MC Java + Radiance mixin
        │ JNI
        ▼
MCVR ──┬── 光栅/计算/UI/后处理/资源管理 ──► Vulkan 1.4 ──► MoltenVK 1.4.3 ──► Metal
       │        （现有代码不动，VK_EXT_metal_objects 导出 MTLBuffer/MTLTexture）
       └── AS 构建 / RT 管线 / SBT / traceRays ──► 新增 Metal RT 后端 ──► MTLAccelerationStructure
                （GLSL→SPIR-V 原样 + SPIRV-Cross MSL metal::ray_tracing 后端）
```

- RT 侧新代码集中在 `as.cpp`（→ MTLPrimitiveAccelerationStructureDescriptor 三角形 BLAS + 实例 TLAS，含 refit/compact）、`sbt.cpp`（→ intersection function table + 各阶段函数）、`pipeline.cpp` RT 分支（→ Metal RT compute pipeline）、`command.cpp` 的 2 个 trace 调用点（→ compute dispatch；`traceRaysIndirect` → `dispatchThreadgroupsWithIndirectBuffer`）。
- 着色器链：GLSL →(shaderc)→ SPIR-V →(SPIRV-Cross MSL RT)→ MSL →(内置包预编译 metallib；自定义包运行时 `newLibraryWithSource` + 缓存)→ Metal。
- 优点：不替换启动器任何二进制（沿用其"MoltenVK 零改动"哲学）；RT 面小而封装好；可增量开发（先 vanilla-pt 后 advanced）。
- 难点：SPIRV-Cross MSL RT 的 payload/hit-attribute/`traceRayEXT` 映射细节需适配；`rayTraversalPrimitiveCulling` 用 intersector mask/flags 模拟。

#### 路线 A（替代）：fork MoltenVK 补齐 RT 三扩展

- 在 MoltenVK fork 内把 `vkCreateAccelerationStructureKHR` 等映射到 Metal RT；BDA→MTLAccelerationStructure 用**对象注册表**翻译（MCVR 的实例缓冲为宿主侧写入，注册表法可行）。
- 优点：MCVR 代码几乎零改动，所有 Vulkan RT 程序通用。
- 缺点：需要替换/捆绑启动器的 MoltenVK 二进制（与启动器"零改动"哲学冲突，升级维护负担外移）；工程面比路线 B 大；KosmicKrisp 上位后此 fork 生命周期存疑。

#### 路线 D（保底，不推荐）：计算着色器软 BVH 遍历

- 放弃硬件 RT，把遍历改为普通 compute（MoltenVK 直接可跑，无需任何后端）。
- 代价：整个 Shaderpack 的 RT 着色器体系要重写；Apple GPU 上软件遍历跑路径光追的性能不可接受。仅作为无 RT 硬件机型的理论保底。

### 4.3 工作量与风险（路线 B）

**量级**：一个熟练图形工程师 2–4 个月做出可玩 alpha（用户已声明不考虑工程量，此处仅供排期参考）。

**分阶段**：
1. **P0 可跑通**：iOS arm64 交叉编译（iOS toolchain、剥离 GLFW/DLSS/XeSS/FFX、JNI 头从 iOS JDK 生成）→ `RadianceClient.java` 加 Darwin 分支 → Metal surface 后端（或走启动器 GLFW shim 的 `glfwCreateWindowSurface`）→ **先以"无 RT"模式验证 Vulkan 1.4 基础渲染**（需临时放宽 MCVR 的 RT 硬拒选，跑通 UI/管线/纹理链路）
2. **P1 RT 最小闭环**：Metal RT 后端三件套（AS/管线/trace）+ vanilla-pt 着色器 MSL 转换链 → 单 BLAS 三角形场景出首帧
3. **P2 完整体验**：分块 BLAS 增量构建/refit/compact、TLAS 实例更新、`traceRaysIndirect`、Advanced 包（ReSTIR/NRD）适配、性能预设与 FSR 评估
4. **P3 集成**：随启动器 bundle 分发或 jar 内嵌加载、签名链、崩溃分析器适配（`CrashAnalyzer.m` 已识别 libMoltenVK 帧）

**主要风险**：
- SPIRV-Cross MSL RT 后端对 MCVR 着色器写法（inline ray payload、贡献缓冲、hit attribute 布局）的兼容性——预计 80% 直译、20% 需改写着色器
- NRD 在 Apple GPU 的数值正确性与耗时（占帧预算比重需实测，必要时换轻量降噪）
- 8GB 机型内存预算（JVM + MC + NRD + AS + 纹理驻留）；建议 AS 分批构建 + 紧凑模式常态化
- `shaderBufferInt64Atomics` 等 feature 在 Apple GPU 缺失——MCVR 已按 supported 值透传（不会硬崩），但需验证无着色器依赖
- iOS 后台切换/内存警告路径的 Vulkan 设备丢失处理

---

## 5. 设备与性能预期

| 设备档 | GPU 家族 | Metal 硬件 RT | 预期 |
| --- | --- | --- | --- |
| iPhone 15 Pro / 16 / 17 系列 | Apple9 / Apple10+（A17 Pro–A19） | ✅ 专用求交硬件 | **目标平台**：50% 渲染分辨率 + FSR + NRD 性能档 ≈ 30 FPS 量级 |
| iPad Pro M4 / M5 | Apple10+ | ✅ | 更好的散热与内存，体验最佳 |
| M1/M2 iPad、iPhone 14 Pro 及更早 | Apple7/8 | API 可用、无专用硬件 | 不推荐：遍历回退软件路径，帧率预计不可玩 |
| A12 及更早 | < Apple7 | ❌ | 不支持 |

---

## 6. 工程清单（路线 B 落地项）

**E-1 Java 层（本 fork，必改）**
- `RadianceClient.java`：`os.name` 含 "Mac OS X"（启动器伪装值）时加载 `libcore.dylib`（jar 内嵌资源，提取后 `System.load`；命名沿用 core 规约）
- `RendererProxy.glfwLibCandidates()`：追加启动器 shim 的库名候选

**E-2 MCVR 构建系统**
- 新增 iOS 交叉工具链（CMAKE_IOS、-target arm64-apple-ios、`find_package(JNI)` 指向 iOS OpenJDK include）
- 新增 `MCVR_UI_BACKEND=VULKAN`（现有开关直用）+ 新开关 `MCVR_RT_BACKEND=METAL`
- `MCVR_ENABLE_XESS=OFF`、`MCVR_ENABLE_FFX_UPSCALER=OFF`（首期）、DLSS 编译剥离
- 内置 shaderpack 的 MSL/metallib 预编译目标

**E-3 MCVR 运行时**
- `window_surface.cpp` 新增 Metal 后端（`vkCreateMetalSurfaceEXT`，VK_EXT_metal_surface；或复用启动器 GLFW shim 路径）
- `physical_device.cpp`/`device.cpp`：iOS 下放宽 RT 三扩展硬拒选（RT 能力改由 Metal 后端自检提供）
- 新增 `src/core/metal_rt/`：AS 封装（BLAS 三角形/TLAS 实例，build/refit/compact/serialize）、RT 管线（intersection function table + 各阶段 kernel）、SBT 映射、traceRays/traceRaysIndirect 派发、`VK_EXT_metal_objects` 资源桥
- 着色器链：内嵌 SPIRV-Cross（MSL RT）；GLSL→SPIR-V 原有 shaderc 路径不动

**E-4 发布/集成**
- 产物：`Radiance-<ver>-ios-fabric.jar`（内含 libcore.dylib + shaders + modules）
- 加载路径依赖 JIT/CS_DEBUGGED 环境（启动器生态已常态化）；或与启动器协商随 bundle 分发预签名 dylib
- `CrashAnalyzer.m` 符号表追加 libcore（MCVR）帧

---

## 7. 附：MCVR 硬性需求 vs MoltenVK 1.4.3 现状对照

| 需求 | 类型 | MoltenVK 现状 | iOS 落点 |
| --- | --- | --- | --- |
| VK_KHR_swapchain | 扩展 | ✅ | 现成 |
| VK_KHR_ray_tracing_pipeline | 扩展 | ❌ | **路线 B：Metal RT 后端** |
| VK_KHR_acceleration_structure | 扩展 | ❌ | 同上 |
| VK_KHR_deferred_host_operations | 扩展 | ✅ | 现成 |
| VK_KHR_push_descriptor | 扩展 | ✅ | 现成 |
| VK_KHR_spirv_1_4 | 扩展 | ✅（Vulkan 1.4 核心） | 现成 |
| VK_EXT_descriptor_indexing | 扩展 | ✅ | 现成（limits 需实测） |
| VK_EXT_extended_dynamic_state2/3 | 扩展 | ✅（核心/扩展） | 现成 |
| VK_EXT_vertex_input_dynamic_state | 扩展 | ✅（按能力过滤） | 现成 |
| rayTracingPipeline / TraceRaysIndirect / PrimitiveCulling / accelerationStructure | 特性 | ❌ | **路线 B：Metal RT 后端**（primitiveCulling 以 intersector mask 模拟） |
| synchronization2 | 特性 | ✅ | 现成 |
| bufferDeviceAddress | 特性 | ✅ | 现成 |
| shaderDrawParameters | 特性 | ✅ | 现成 |
| GL_EXT_memory_object / semaphore 互操作 | OPENGL UI 变体 | 不适用 | **改用 VULKAN UI 变体绕开** |
| Vulkan 1.4 实例/设备 API | 核心 | ✅ | 现成 |
| 运行时 GLSL→SPIR-V（shaderc） | 工具链 | — | 可行（MobileGlues 内置 glslang 先例） |
| SPIR-V→MSL（含 RT 阶段） | 工具链 | SPIRV-Cross MSL RT 后端 | 路线 B 核心工作 |

---

## 8. 给后续开发的第一步建议

1. fork `Minecraft-Radiance/MCVR` 与 `Minecraft-Radiance/Shaderpack-Advanced`（移植主战场在 MCVR）
2. 先做 **P0**：iOS 交叉编译 + Darwin 加载分支 + Metal surface，在启动器上以无 RT 模式跑通 Vulkan 基础渲染（验证 Vulkan 1.4 链路、JNI、纹理/UI 全链路）
3. 同步启动 **P1** 的技术验证 spike：取 `Shaderpack-Advanced/path/direct.rgen` + 一个 closest-hit，手工走 GLSL→SPIR-V→MSL→Metal RT 管线，在 A17 Pro 真机出第一束光——这一步直接决定路线 B 的着色器链设计
