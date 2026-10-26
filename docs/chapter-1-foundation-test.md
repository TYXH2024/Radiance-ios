# 第一章：底子是否可行（Foundation Test）

> Radiance → iOS 移植工程 · 第一章报告
> 目标平台：iOS（iPhone A17 Pro 及以上 / Apple9 GPU 家族及以上为主目标）
> 锁定渲染器：**MobileGlues(Vulkan)**（GL→Vulkan 转译）与 **MoltenVK**（LWJGL 自管 Vulkan 直连）——两者全兼容支持
> 移植定义：**利用 iOS 的硬件光线追踪 API（Metal Ray Tracing），非模拟/软渲染**
> 证据来源：本会话对下列仓库源码的直接检查（所有结论标注 文件:行号）+ GitHub Actions Apple Silicon 真机 GPU 实测（`chapter1/`）
> 前提声明：本报告为独立重查，不依赖、不引用任何历史会话文档

---

## 1. 结论摘要

**底子成立，且方向唯一明确：**

1. **硬件光追只能走 Metal RT 直连**。iOS 上唯一的 Vulkan 实现 MoltenVK（上游源码零实现 + 启动器内置 dylib 二进制零字符串，双重复核）没有 Vulkan 光追三扩展。所以"移植"的正确形态是：MCVR 的 Vulkan 栈继续跑光栅/计算/UI，**光追子系统整体换成 Metal RT 后端**，资源互操作经实测存在的 `VK_EXT_metal_objects` 桥。
2. **Vulkan 侧地基实测成立（含 4 项已定处置的缺口）**。在真实 MoltenVK（1.4.2，macOS runner 实测；与启动器内置 1.4.3 同源相邻版本）上对照 MCVR 12 项硬门槛：8 项直接 PRESENT；`synchronization_2` 扩展名缺失但 Vulkan 1.3 核心特性 `synchronization2=true`（已晋升核心，MCVR 改判读即可）；`vertex_input_dynamic_state` 缺失（MCVR 侧降级为管线重编译即可）；RT 三项缺失（即 Metal RT 后端的职责本身）。
3. **Metal RT 工具链实测全通**：`ray_query` 内核以 MSL `metal3.2` 编译为 macOS air64 与 iOS air64 两份 metallib；探针宿主代码交叉编译为 iOS arm64 .o。运行时 RT 验证受限于 CI 环境（GitHub macOS VM 的 GPU 为 "Apple Paravirtual device"/Apple5 家族，无光追），需在真机（A17 Pro+）执行——探针二进制本身就是真机测试工具，将在第二章经启动器上真机跑。
4. **双渲染器锁定兼容性成立**：MobileGlues 与 MoltenVK 两条渲染器路径的 Vulkan 底座都是 MoltenVK，Probe B 的矩阵对两者同时成立；Radiance 采用 Vulkan UI 变体后不经过 GL，转译层不影响 Radiance 本体。

---

## 2. 移植对象的真实结构（源码证据）

### 2.1 三仓库体系

| 仓库 | 内容 | 移植中的角色 |
| --- | --- | --- |
| **Radiance**（本 fork，MC 1.21.4 Fabric 分支） | Java 模组层：183 个 Java 文件、343 个 assets、9 个 modules（NRD 等模块）；构建时把 MCVR 产出的 `core.dll/libcore.so` 打进 jar | 需新增 darwin/iOS 加载分支 |
| **MCVR**（Minecraft-Radiance/MCVR） | C++ 原生渲染器（src/core 153 文件 + src/shader 299 文件），CMake 构建，JNI 边界在 `src/core/middleware/` | **移植主战场**：RT 子系统 → Metal 后端 |
| **Shaderpack** | GLSL 着色器包（raygen/closest-hit/miss 全套 RT 阶段） | GLSL→SPIR-V 原链保留，RT 阶段需 MSL 转换（第二章起） |

### 2.2 Java 层加载链（本 fork 源码）

`src/main/java/com/radiance/client/RadianceClient.java:61-89`：

```java
if (operatingSystemName.toLowerCase().contains("windows")) { ... core.dll ... }
else if (operatingSystemName.toLowerCase().contains("linux")) { ... libcore.so ... }
else { throw new RuntimeException("The OS " + operatingSystemName + " is not supported"); }
```

iOS 启动器的 JVM 把 `os.name` 伪装为 **"Mac OS X"**（启动器 `Natives/JavaLauncher.m:1122` 注释直述，为让 LWJGL/JNA 在 iOS 工作）→ 现状会落入 else 抛异常。**移植必改点 1**：新增 "Mac OS X"/darwin 分支，从 jar 释放并 `System.load` `libcore.dylib`。

GLFW 复用机制：`RendererProxy.java:32-34` 传入候选库名，MCVR 原生侧 `RendererProxy.cpp:38` 用 `dlopen(name, RTLD_NOW | RTLD_NOLOAD)` 找到 LWJGL 已加载的 GLFW 再 `dlsym` surface 函数。启动器没有原生 GLFW——它的 GLFW 是 **Java shim**（`JavaApp/src/lwjgl/org/lwjgl/glfw/` 整包替换），surface 在 Java 层经 `VK_EXT_metal_surface` + CAMetalLayer 创建（`GLFWVulkan.java:280`）。MCVR 的 surface 后端枚举现只有 Glfw/Xlib/Wayland/Win32（`window_surface.hpp:9-14`）。**移植必改点 2**：MCVR 需要新增 Metal/CAMetalLayer surface 后端，与启动器 shim 对接。

UI 变体：构建系统支持 `radianceUiBackend=vulkan|opengl`（`build.gradle:22-29`）。OpenGL UI 变体硬依赖 `GL_EXT_memory_object/semaphore` 做 GL↔VK 互操作；**Vulkan UI 变体完全不需要 GL 互操作**。启动器的两条锁定渲染器路径（MobileGlues 转译 GL、MoltenVK 直连 VK）都无法承载 GL 互操作 → **iOS 构建必须采用 Vulkan UI 变体**。这顺带解决了另一个问题：MobileGlues 渲染器下 MC 的原版 GL 上下文照常工作，Radiance 只接管世界渲染与 UI 合成，互不干扰——"全都兼容"在架构上成立。

### 2.3 MCVR 的 Vulkan 硬门槛（设备选择会拒选的点）

`src/core/vulkan/physical_device.cpp`：

- `:267` — `apiVersion < VK_API_VERSION_1_3` → 拒选（"Vulkan 1.3 is required"）
- `:92-117` — `evaluateRequiredExtensions`：缺失任一必需扩展 → 拒选。必需清单（与 `device.cpp:28-39` 一致）：

```
VK_KHR_swapchain              VK_KHR_ray_tracing_pipeline      VK_KHR_acceleration_structure
VK_KHR_deferred_host_operations   VK_KHR_push_descriptor       VK_KHR_spirv_1_4
VK_KHR_synchronization_2      VK_KHR_image_format_list         VK_EXT_descriptor_indexing
VK_EXT_extended_dynamic_state2    VK_EXT_extended_dynamic_state3
VK_EXT_vertex_input_dynamic_state
```

特性（`device.cpp:370-447`）按 supported 值透传，不强崩；真正的硬门是扩展清单。**移植必改点 3**：iOS 下放宽 RT 三扩展的硬拒选（RT 能力改由 Metal RT 后端自检提供）。

RT 调用面极小且集中（对移植工作量是决定性利好）：`vkCmdTraceRaysKHR` / `vkCmdTraceRaysIndirectKHR` / `vkCreateRayTracingPipelinesKHR` / `vkGetRayTracingShaderGroupHandlesKHR` 各只有 1 个调用点，AS 构建集中在 `as.cpp`，SBT 在 `sbt.cpp`，RT 管线在 `pipeline.cpp`。

shaderc 为外置依赖（`src/core/CMakeLists.txt:31-53`，`find_package` shaderc_combined/shared），运行时把 GLSL 编译为 SPIR-V——iOS 交叉编译需自带 shaderc（启动器生态已有先例：NG-GL4ES 构建内嵌 glslang 静态库）。

---

## 3. 锁定渲染器与启动器地基（源码证据）

### 3.1 渲染器体系（启动器 `Natives/utils.h:52-117`）

| 用户锁定 | 启动器实体 | 图形栈 |
| --- | --- | --- |
| **MobileGuest(Vulkan)**（即 MobileGlues） | `libmobileglues.dylib`（`RENDERER_NAME_MOBILEGLUES`，`Natives/external/MobileGlues/`） | 桌面 GL →（gl4es 系转译，含 glslang/SPIRV-Cross）→ Vulkan → **MoltenVK** → Metal |
| **MoltenVK** | `libMoltenVK.dylib`（`RENDERER_NAME_VULKAN`） | LWJGL Vulkan 直连 → **MoltenVK** → Metal，MC 自管 swapchain/queue（`vk_bridge.m:85` 注释直述） |

两条路殊途同归：**脚下的 Vulkan 都是 MoltenVK**。这意味着 MoltenVK 的能力矩阵同时决定两条渲染器路径的上限——Probe B 对两条路都有效。差异只在 GL 转译层的有无，而 Radiance 的 Vulkan UI 变体不经过 GL，所以转译层对 Radiance 本身无影响（只影响 MC 启动早期的原版 GL 上下文）。

### 3.2 MoltenVK 现状

- 启动器内置：`Natives/resources/Frameworks/libMoltenVK.dylib`，版本 1.4.3（`vk_bridge.m:30`，上游 main@2c28e0c8，2026-10-09 CI 构建）。本地 file/strings 验证：arm64 Mach-O，导出 `vkCreateMetalSurfaceEXT` / `vkCreateIOSSurfaceMVK` / `VK_MVK_ios_surface` 系符号（iOS 构建）。
- 上游最新 main（本会话克隆，commit `5189f8de`，2026-10-13）：`MoltenVK/MoltenVK/Vulkan/` 全目录检索 `VK_KHR_ray_query` / `VK_KHR_ray_tracing_pipeline` / `VK_KHR_acceleration_structure` / `VK_KHR_pipeline_library` → **全部零命中**；唯一存在的是 `VK_KHR_deferred_host_operations`（`vulkan.mm:3225`，仅是 RT 扩展的依赖项，独立存在）。
- 结论：**Vulkan RT 在 iOS 上不是"慢"，是"没有"**。且这不是版本新旧问题（比启动器内置版更新的 main 仍然没有）。

### 3.3 光追路线判定

```
MC Java + Radiance mixin
        │ JNI
        ▼
MCVR ──┬── 光栅 / 计算 / UI / 后处理 / 资源管理 ──► Vulkan 1.3+ ──► MoltenVK 1.4.3 ──► Metal
       │      （现有代码不动；两条锁定渲染器路径都在这层汇合）
       └── AS 构建 / RT 管线 / SBT / traceRays ──► 【新增】Metal RT 后端 ──► MTLAccelerationStructure
              （GLSL→SPIR-V 链保留；RT 阶段走 Metal ray_query / intersection functions）
```

这就是"利用 iOS 光线追踪 API"的落地形态：Vulkan 管不着光追，光追直接说 Metal。Metal RT 的 `ray_query`（compute 内联求交）语义上最贴近 Vulkan 的 `VK_KHR_ray_query`，是 RT 着色器转换的最短路径——这正是 Probe A 验证的东西。

---

## 4. 底子测试设计

### Probe A：Metal RT 端到端（`chapter1/probe_metal_rt.m` + `trace_kernel.metal`）

场景：1 个三角形（z=0 平面）BLAS + 1 个实例 TLAS；262,144 条射线（origin (0.25,0.25,5)、方向 -Z）；前半 mask=0xFF 必须命中且 distance=5，后半 mask=0x00 必须被实例 mask 剔除。

判定标准（全部满足才打印 `VERDICT: METAL_RT_OK`）：

1. `MTLDevice.supportsRayTracing == YES` 且 BLAS/TLAS 构建成功
2. `ray_query` compute 管线编译成功
3. 命中射线 131072/131072，距离误差 < 1e-3
4. mask 剔除射线 131072/131072 miss
5. 附带数据点：运行时 `newLibraryWithSource`（含 `#include <metal_raytracing>`）是否可用——这决定后续自定义 shaderpack 能否在设备上现场编译
6. CI 交叉编译同源码到 iphoneos arm64（.o + .air + .metallib）——证明 iOS 工具链无障碍

### Probe B：MoltenVK 能力矩阵（`chapter1/probe_moltenvk.c`）

方法：dlopen MoltenVK dylib（CI 用 macOS 版运行；启动器内置的 iOS 版做二进制字符串级验证）→ 按与 MCVR 完全相同的方式创建 Vulkan 1.3 实例 → 枚举全部扩展/特性 → 对照 §2.3 的必需清单逐项 PRESENT/ABSENT。

判定标准：非 RT 项全 PRESENT = Vulkan 地基成立；RT 三项 ABSENT = 预期内（正是走 Metal RT 的理由）。

---

## 5. CI 实测结果

运行记录：commit `631823a`（macos-26 job，Xcode 16.4）+ `source evidence (linux)` job。workflow：`.github/workflows/chapter1-foundation.yml`。

### 5.1 Linux 源码证据链（全部通过）

- MoltenVK 上游 main（浅克隆 HEAD）：`VK_KHR_ray_query` / `VK_KHR_ray_tracing_pipeline` / `VK_KHR_acceleration_structure` / `VK_KHR_pipeline_library` 在 `MoltenVK/MoltenVK/Vulkan/` 全部 **0 次出现**；`VK_KHR_deferred_host_operations` 存在（>0，仅作为独立扩展）
- 启动器内置 `libMoltenVK.dylib`（iOS arm64 Mach-O）：三个 RT 扩展字符串全部 **ABSENT**；`vkCreateMetalSurfaceEXT` / `vkCreateIOSSurfaceMVK` 导出符号存在（渲染器锁定路径的 surface 证据）
- `probe_moltenvk.c` 以启动器 vendored 的 Vulkan 头编译通过（clang -Wall -Wextra）

### 5.2 Probe A — Metal RT（工具链通过；运行时受 CI 环境限制）

- **MSL 内核编译**：`xcrun metal -std=metal3.2`，macOS 目标 `air64-apple-macos14.0.0` 与 iOS 目标 `air64-apple-ios17.0.0` 均通过；`metallib` 版本 1.2.8
- **iOS 交叉编译产物**：`probe_metal_rt.ios.o`、`probe_moltenvk.ios.o` 均为 `Mach-O 64-bit object arm64`；`trace_kernel.ios.metallib` 为 `MetalLib executable`
- **运行时**：CI 的 macOS VM GPU 为 `"Apple Paravirtual device"`（Apple5 家族，paravirtual 虚拟 GPU），`supportsRaytracing=NO` → 探针按设计输出 `VERDICT: METAL_RT_API_UNAVAILABLE` 并以专用退出码 3 结束（workflow 已将该情形标记为环境性跳过）。**这是 CI 环境限制而非平台限制**：Apple6+ 家族的真实 GPU（A14+/M1+）API 可用，Apple9+（A17 Pro/M3+）有专用求交硬件。运行时 RT 验证将在真机执行（探针二进制即测试工具）。

### 5.3 Probe B — MoltenVK 能力矩阵（macos-26 实测，brew MoltenVK 1.4.2）

设备：`Apple Paravirtual device`，`deviceType=integrated`，`vendorID=0x106b (Apple)`，实例以 MCVR 同款 `apiVersion=1.3` 创建成功，设备报告 `apiVersion=1.3.357`（设备版本受实例版本封顶，属 Vulkan 标准行为）。共 **129 个设备扩展**。

MCVR 硬门槛矩阵（physical_device.cpp:112 清单）：

| 扩展 | 状态 | 处置 |
| --- | --- | --- |
| VK_KHR_swapchain | **PRESENT** | 现成 |
| VK_KHR_deferred_host_operations | **PRESENT** | 现成 |
| VK_KHR_push_descriptor | **PRESENT** | 现成 |
| VK_KHR_spirv_1_4 | **PRESENT** | 现成 |
| VK_KHR_image_format_list | **PRESENT** | 现成 |
| VK_EXT_descriptor_indexing | **PRESENT** | 现成 |
| VK_EXT_extended_dynamic_state2 | **PRESENT** | 现成 |
| VK_EXT_extended_dynamic_state3 | **PRESENT** | 现成 |
| VK_KHR_synchronization_2 | ABSENT（扩展名） | **核心特性 `synchronization2=true`**（Vulkan 1.3 晋升核心）→ MCVR 改为按特性判读即可 |
| VK_EXT_vertex_input_dynamic_state | ABSENT | MCVR 侧降级：顶点输入状态回退为管线重编译路径 |
| VK_KHR_ray_tracing_pipeline | ABSENT | 即 Metal RT 后端的职责（预期内） |
| VK_KHR_acceleration_structure | ABSENT | 同上 |

Vulkan-RT 附加项（信息性）：`ray_query` / `pipeline_library` / `ray_tracing_position_fetch` / `ray_tracing_maintenance1` 全部 ABSENT（与上游源码证据一致）；**`VK_EXT_metal_objects` PRESENT（spec 2）**——Vulkan↔Metal 资源互操作桥存在，混合架构的资源互通前提成立；`VK_KHR_maintenance5` PRESENT。

特性链（vkGetPhysicalDeviceFeatures2）：`shaderDrawParameters` / `bufferDeviceAddress` / `runtimeDescriptorArray` / `descriptorBindingPartiallyBound` / `hostQueryReset` / `timelineSemaphore` / **`synchronization2`** / `maintenance4` 全部 **true**；`accelerationStructure` / `rayTracingPipeline` / `rayTracingPipelineTraceRaysIndirect` / `rayQuery` 全部 false（预期内）。

**VERDICT: MOLTENVK_MCVR_GATE_MISSING_4_EXTENSIONS** —— 四项缺口全部有明确处置路径（见上表）。

### 5.4 启动器内置 dylib 二进制验证

`Natives/resources/Frameworks/libMoltenVK.dylib`：`Mach-O 64-bit dynamically linked shared library arm64`（iOS 构建）；RT 扩展字符串 ABSENT；`vkCreateMetalSurfaceEXT` / `vkCreateIOSSurfaceMVK` 导出。二进制为 iOS 切片故无法在 macOS CI 加载运行，运行时矩阵由同源相邻版本（brew 1.4.2）代理，二进制级验证与源码级证据双重覆盖。

### 5.5 过程中校准的 Metal API 事实（第二章直接复用）

实测纠正了若干文档/记忆错误，全部经真实 SDK 头文件与 SPIRV-Cross 参考输出证实：

| 项 | 事实 |
| --- | --- |
| MSL 内联光追类型 | `raytracing::intersection_query<raytracing::instancing, raytracing::triangle_data>`（不存在 `ray_query` 模板；Xcode 16.4 头文件零命中） |
| 求交循环 | `q.reset(raytracing::ray(o,d,tmin,tmax), AS, mask, params)` → `while(q.next()) q.commit_triangle_intersection()` → `q.get_committed_*()` |
| AS 绑定 | 着色器 `[[buffer(n)]]` + 宿主 `setAccelerationStructure:atBufferIndex:`（无 `[[acceleration_structure(n)]]` 属性） |
| AS 构建 | 专用 `[commandBuffer accelerationStructureCommandEncoder]`（MTLAccelerationStructureCommandEncoder），非 compute encoder |
| 实例结构体 | `MTLAccelerationStructureInstanceDescriptor{MTLPackedFloat4x3 transformationMatrix; options; mask; intersectionFunctionTableOffset; accelerationStructureIndex}` |
| 设备查询 | `MTLDevice.supportsRaytracing`（小写 t）；`MTLGPUFamilyApple9` 为最后一个家族枚举（无 Apple10） |
| MSL 语言版本 | `-std=metal3.2/3.1/3.0` 合法（`metal2.4` 非法拼写）；ray query 需 Metal 3 语言级 |
| 着色器链对齐 | SPIRV-Cross（GLSL→SPIR-V→MSL 正是移植链）为 Vulkan ray query 发射的正是上述 MSL 形态 → 探针内核形状 = 移植目标形状 |

---

## 6. 第一章结论与第二章路线

### 6.1 结论

**底子成立。** 三根支柱的实测状态：

1. **Vulkan 地基（两锁定渲染器共用）**：MoltenVK 满足 MCVR 12 项硬门槛中的 8 项，其余 4 项处置路径全部明确（sync2 按核心特性判读、vertex input dynamic state 降级、RT 三项归 Metal RT 后端）。`VK_EXT_metal_objects` 互操作桥实测存在。
2. **Metal RT 地基**：iOS 工具链全通（MSL 3.2 光追内核 → iOS metallib；宿主代码 → iOS arm64 .o）；API 用法已对真实 SDK 校准；运行时验证待真机（CI 的 paravirtual GPU 无 RT 属环境限制）。
3. **移植路线判定**：唯一合理架构 = **MCVR 内嵌 Metal RT 后端**（Vulkan 走光栅/计算，RT 走 Metal，经 VK_EXT_metal_objects 互通资源），着色器链 GLSL→SPIR-V（shaderc 原样）→MSL（SPIRV-Cross，其 ray query 输出形态与探针内核一致）。

### 6.2 第二章路线（P0：无 RT 模式跑通 Vulkan 基础渲染）

1. MCVR iOS 交叉编译（CMake iOS toolchain，剥离 GLFW/DLSS/XeSS/FFX，shaderc 交叉编译——启动器 NG-GL4ES 已有 glslang 内嵌先例）
2. `RadianceClient.java` 增加 darwin 分支（os.name="Mac OS X" → 释放并加载 libcore.dylib）
3. MCVR 新增 Metal/CAMetalLayer surface 后端（`window_surface.cpp` 仿 Xlib 后端写法，`vkCreateMetalSurfaceEXT`；启动器 GLFW shim 已提供 CAMetalLayer）
4. `physical_device.cpp` 设备门放宽（RT 三扩展 + sync2 核心判读 + vertex_input_dynamic_state 降级）——**四项全部有本报告实测依据**
5. 构建变体：`radianceUiBackend=vulkan`（免 GL 互操作，双渲染器兼容的前提）
6. 在真机（A17 Pro+）经启动器加载，跑通 Vulkan 基础渲染 + 顺手执行 Probe A 二进制（补上运行时 RT 验证）

第三章（P1）起做 Metal RT 后端最小闭环：单 BLAS 场景出首帧光——探针的 BLAS/TLAS/intersection_query 代码就是其种子。
