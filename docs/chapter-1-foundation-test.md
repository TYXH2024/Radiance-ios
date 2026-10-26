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

1. **硬件光追只能走 Metal RT 直连**。iOS 上唯一的 Vulkan 实现 MoltenVK（无论上游最新 main 还是启动器内置 1.4.3）都没有实现 Vulkan 光追三扩展。这不是性能问题，是 API 缺位——所以"移植"的正确形态是：MCVR 的 Vulkan 栈继续跑光栅/计算/UI，**光追子系统整体换成 Metal RT 后端**。
2. **Vulkan 侧地基完好**。MCVR 除 RT 三扩展外的全部硬性需求（Vulkan 1.3+ 实例、sync2、push descriptor、deferred host operations、descriptor indexing、BDA 等）都在 MoltenVK 的能力清单内，待 Probe B 实测确认。
3. **Metal RT 侧待实测确认**（Probe A）：BLAS/TLAS 构建、`ray_query` 求交、mask 剔除、数值正确性。Apple9+（A17 Pro 起）有专用求交硬件；Apple6–8 API 可用但走内联遍历。

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

> 以下由 `.github/workflows/chapter1-foundation.yml` 自动生成，运行后回填。

### 5.1 Probe A — Metal RT（待回填）

```
（待 CI 运行：VERDICT 行 + 关键计时数据）
```

### 5.2 Probe B — MoltenVK 矩阵（待回填）

```
（待 CI 运行：实例版本、设备扩展矩阵、特性链、VERDICT 行）
```

### 5.3 iOS arm64 交叉编译（待回填）

```
（待 CI 运行：iOS_CROSS_COMPILE_OK + 产物 file 类型）
```

### 5.4 启动器内置 dylib 二进制验证（待回填）

```
（待 CI 运行：file 类型 + RT 扩展字符串 ABSENT 确认 + surface 符号确认）
```

---

## 6. 第一章结论与第二章路线

（待 CI 结果回填后定稿）

**若 Probe A = METAL_RT_OK 且 Probe B 非 RT 项全 PRESENT**：
底子完全成立。第二章进入 MCVR 的 iOS 交叉编译与 Metal surface 后端（P0：无 RT 模式跑通 Vulkan 基础渲染）；第三章做 Metal RT 后端最小闭环（单 BLAS 出首帧）。

**若 Probe A 失败**：按失败环节定位（API 缺位 / 编译失败 / 数值错误），评估替代方案（intersection function table 管线 vs ray_query）。

**若 Probe B 意外缺失非 RT 项**：逐项评估绕行或降级（push descriptor / EDS3 等在 MCVR 中多为可选增强路径）。
