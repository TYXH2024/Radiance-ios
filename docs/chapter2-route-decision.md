# 第二章路线裁决 —— Metal 直连 RT 核（metal-direct）为主路线

> 状态：已裁决（2026-10-27），且已被 metal-rt-probe 2.0 真机实测验证（2026-10-27，M3/zink 档，**METAL_RT_OK**，见 docs/chapter2-metal-rt-probe-field/）
> 输入：第一章判 C 双机铁证（docs/chapter1-rt-probe-v1.1-field/VERDICT.md）+
> 用户真机确认（zink 渲染档、M3、可稳定进游戏）+ 本日驱动生态调研 + 启动器源码侦察
> 结论：**不等待任何 Vulkan RT 驱动；mod JAR 内置 Metal RT 原生核，走"Metal 直连"路线。**

---

## 1. 第一章结案确认（用户实测）

- 判定 C 维持：捆绑 MoltenVK 1.4.3 无 `VK_KHR_acceleration_structure` / `VK_KHR_ray_query`（134/135 设备扩展清单在案）。
- 闪退悬案闭环：与探针无关，根因为 Vulkan 渲染档混合态（renderer=libMoltenVK.dylib + 无 VulkanMod 的 MobileGlues GL fallback 确定性失败）。
- **用户改选 zink 渲染档后 M3 真机可稳定进入游戏** —— 第二章所需的稳定宿主环境成立：
  - zink 档 = `libOSMesa.8.dylib`（Mesa 25.0.7 osmesa 前端 + zink gallium，`GALLIUM_DRIVER=zink`）
  - GL → zink → Vulkan → MoltenVK → Metal，游戏渲染与后续 RT 侧车互不干扰。

## 2. 驱动生态调研（2026-10-27）

### 2.1 MoltenVK（KhronosGroup/MoltenVK）

- RT 状态：**未实现且不会再有**。issue #427（2018 年开至今）：
  - 资助方（Holochip）明确"无客户、无资金、无人可派"（2024-10）；
  - 2026-04 社区共识"MoltenVK 处于维护模式，新特性全部走 KosmicKrisp"；
  - 技术死结：Metal BLAS 无法经 VkDeviceAddress 被 TLAS 原语引用（natevm 2024-10 说明）。
- 第一章源码级证据链（CI source-evidence job）持续验证上游零 RT 扩展。

### 2.2 KosmicKrisp（LunarG，Mesa 26.0 内）

- 现状：Vulkan 1.4 conformant（2026-10）、已入 Vulkan SDK、性能三倍于初版。
- **RT 状态：路线图项，未实现**。XDC 2025 演讲"未来目标"列表含"potentially ray tracing"；
  2026-10 Vulkan 1.4 conformance 公告的新增扩展清单（Tessellation/Robustness2/Multi-draw）**无任何 RT 扩展**。
- 平台约束：最新版要求 **Metal 4**（macOS 26+；对应 iOS 26+），旧系统只能留在 MoltenVK。
  用户 M3 设备在 iOS 18.3.2（Metal 3）——即便 KosmicKrisp iOS 产物可用也覆盖不到该机。
- iOS 状态：官方口径"实验性，Mesa 树内未启用为可分发形态"（State of Vulkan on Apple，2026-10）。
- 结论：**不可作为即插即用 RT ICD；列为跟踪项**（其 RT feature request 落地后重跑 rt-probe 验收判 A）。

### 2.3 HoneyKrisp（Asahi Linux）

- 真身：Asahi 逆向 AGX 硬件的 Linux 用户态 Mesa Vulkan 驱动（NVK 衍生），**只在 Linux 内核 + Asahi DRM 上运行，不经 Metal**。
- 误用排除：与 iOS 无关（此前会话摘要中"honeykrisp 支持 RT"系指 Linux 侧能力）。对本项目不可用。

### 2.4 综合裁决

**2026-10-27 时点不存在任何可直接投放 iOS 的 Vulkan RT 驱动**：
等待 = 时间表不可控（KosmicKrisp RT 无 ETA + Metal 4 门槛 + iOS 未开分发）。
而设备侧硬件 RT 已被第一章探针实锤可用：

- `probe_metal_rt.m` + `trace_kernel.metal`（MSL `raytracing::intersection_query`，Metal 版 ray_query 等价物）
  已在 CI 完成 iOS arm64 交叉编译验证；
- Apple9 GPU family（A17 Pro/A20 Pro/M3 起）带专用交集硬件，`device.supportsRaytracing` 全线可用；
- macOS CI runner GPU 为 paravirtual（无 RT），**runtime 验证必须也顺理成章落在真机** —— 与第一章 rt-probe 同一验证模型。

## 3. 启动器侦察（Prisma-Minecraft-iOS-Launcher2）

### 3.1 渲染器档位与 MoltenVK 汇聚点

| 档位（AMETHYST_RENDERER） | 物理库 | 与 Vulkan/MoltenVK 关系 |
|---|---|---|
| `libMoltenVK.dylib`（Vulkan 档） | Frameworks/libMoltenVK.dylib | LWJGL `org.lwjgl.vulkan.libname=MoltenVK` 直接 dlopen（PojavLauncher.java:258-261） |
| `libMobileGL.dylib`（MobileGL 档） | 同名 | **链接期硬依赖 `@rpath/libMoltenVK.dylib`**（strings 实锤） |
| `libOSMesa.8.dylib`（zink 档，用户现用） | 同名 | 未定义符号 `vkGetInstanceProcAddr/vkGetDeviceProcAddr` 由 dyld 对已加载 libMoltenVK 解析；启动器 fishhook（ZinkStrideFix）仅做 stride 修补后透传 |
| `libOSMesaVirgl.dylib`（virgl 档） | 同名 | 进程内 vtest server + virgl guest |

三条 Vulkan 消费路径全部汇聚到 `.app/Frameworks/libMoltenVK.dylib` 单点 —— 这本是"改包 ICD 替换"的落点，但§2 已证明没有可替换的 RT 驱动，该路线降级为跟踪项。

### 3.2 免改包投送通道（已被启动器自身证明）

`main_hook.m` 的 `hooked_dlopen` 对 home/tmp 目录下的 dylib 执行 `PLPatchMachOPlatformForFile`
Mach-O 平台修补（iOS 26+ 非 TXM 设备另有硬件断点重定向路径）。**LWJGL 自带 natives
（liblwjgl.dylib 等）就是从 JAR 解包到 tmp 后 dlopen 的**（Task193 专门扫描 lwjgl 路径并
重绑 libjvm 的 `_dlopen` 槽位使 JVM 的 System.load 进入 hook）。

推论：**mod JAR 内置 arm64 dylib → Java 解包到 game 目录（user.dir，HOME 之下）→
System.load() → 走与 LWJGL natives 完全相同的受信装载链**。这是第二章原生核的免改包投送通道。

### 3.3 关键环境事实（真机取证）

- `java.library.path` = `AngelAuraAmethyst.app/Frameworks`
- game 目录可写：`Documents/instances/default/versions/fabric-loader-0.20.1-1.21.4/game`
  （rt_probe_report.txt 已在此落盘成功）
- LWJGL 3.3.3，JVM 21；MC 1.21.4 走 GL，宿主不触碰 LWJGL Vulkan 类（zink 档下零竞争）。

## 4. 裁决：Metal 直连 RT 核

### 4.1 架构

```
┌──────────────────────── MC 1.21.4 (Fabric) ────────────────────────┐
│  渲染主链（不动）: GL → zink(libOSMesa) → MoltenVK → Metal          │
│                                                                    │
│  Radiance RT 侧车（新增）:                                          │
│    Java mod（Fabric CLIENT_STARTED 后台线程）                       │
│      → 解包 libradiance_*.dylib + trace_kernel.metallib 到 game 目录 │
│      → System.load（LWJGL natives 同款受信通道）                    │
│      → JNI: 场景数据(顶点/索引/变换) → MTLAccelerationStructure      │
│        BLAS/TLAS 构建 → MSL ray_query 路径追踪 compute → 回读       │
│      → 结果纹理/缓冲经 MC 常规 GL 机制呈现                          │
└────────────────────────────────────────────────────────────────────┘
```

- 与渲染主链**同进程、双引擎、零干扰**：RT 核自建 MTLDevice/queue（Metal 允许多设备），
  不触碰 MoltenVK/zink 的任何状态 —— 吸取第一章 v1.1 VkInstance 泄漏教训，全路径显式释放。
- 不需要 Vulkan RT 驱动、不需要改包、不需要动启动器。
- 将来若 KosmicKrisp 落地 RT + iOS 分发，同架构可平移（JNI 层换 LWJGL Vulkan 调用），rt-probe 届时复验收 A。

### 4.2 阶段计划

| 阶段 | 交付物 | 验证问题 |
|---|---|---|
| 2.0 ✅ 2026-10-27 真机通过（METAL_RT_OK，M3/zink） | `metal-rt-probe` mod（JAR 内置 dylib+metallib） | 投送通道 + 进程内 Metal RT 全链路（BLAS/TLAS/ray_query/掩码剔除/距离正确性 + 首个真机硬件 RT 吞吐数据 5.34 Mrays/s）—— §4.3 风险 1/2 双双销项 |
| 2.1（当前） | 场景摄取 | MC 区块网格 → JNI → 动态 BLAS/TLAS 重建（增量/refit） |
| 2.2 | 路径追踪引擎 | Radiance 移植：MSL 路径追踪 kernel + 光照回传 + GL 纹理呈现 |
| 2.3 | 性能与融合 | 吞吐调优（Apple9 专用交集硬件特性）、双引擎同步、帧节奏 |

### 4.3 风险与开放问题

1. **dylib 装载签名**：clang（Xcode 15+）链接时默认 ad-hoc 签名；CI 再加 ldid -S 保险。
   若真机仍拒载，报告会捕获 UnsatisfiedLinkError 明文（2.0 的核心观测点之一）。
2. **MSL 运行时编译**（shaderpack 路线）：iOS 上 newLibraryWithSource 对 metal3 语言级的
   支持随 OS 走 —— 2.0 探针带该数据点（metallib 预编译为主路线，运行时编译仅为观测项）。
3. **Metal RT 最低 OS**：raytracing API iOS 14+；Apple9 专用交集硬件 A17 Pro/M3+。
   两台目标机（M3/iOS 18.3.2、A20 Pro/iOS 27.0.1）均满足。
4. **双引擎显存**：TLAS + 场景缓冲与 zink/MoltenVK 共享设备显存预算 —— 2.1 起计量。

## 5. 第一章遗留物的角色转换

- `rt-probe`（Java/Vulkan 探针）：转为 KosmicKrisp 跟踪线的验收工具（其 RT 落地时跑判 A）。
- `chapter1/probe_metal_rt.m` + `trace_kernel.metal`：**直接升格为第二章原生核的种子代码**
  （2.0 的 JNI 化改造即基于此）。
- CI `chapter1-foundation.yml` 的 iOS 交叉编译产线：作为 chapter2 工作流的模板。
