# Chapter 1 — Foundation Test Suite (底子测试)

第一章交付物：验证 Radiance 移植到 iOS 的**底层地基**是否成立。
全部结论只依赖两类证据：源码事实（本仓库 + 上游仓库，标注 文件:行号）与可复现的实测（GitHub Actions 真实运行）。

**第一章已完成，实测结论见 `docs/chapter-1-foundation-test.md` §5。** 要点：

- MoltenVK（两条锁定渲染器的共同底座）：MCVR 12 项硬门槛 8 项 PRESENT；sync2 经 Vulkan 1.3 核心特性成立；vertex input dynamic state 需降级；RT 三项缺失（Metal RT 后端的职责）；**VK_EXT_metal_objects 互操作桥存在**
- Metal RT 工具链：MSL `metal3.2` 光追内核编译为 macOS + iOS metallib；探针宿主代码交叉编译为 iOS arm64 —— 全通
- Metal RT 运行时：CI 的 macOS VM GPU 为 paravirtual（Apple5，无 RT），运行时验证留待真机执行（探针二进制即真机测试工具）

## 两个探针

| 探针 | 问题 | 判定 |
| --- | --- | --- |
| **Probe A `probe_metal_rt.m`** | iOS 的光线追踪 API（Metal Ray Tracing）在 Apple GPU 上是否端到端可用：三角形 BLAS 构建、实例 TLAS 构建、compute kernel 内联求交、mask 剔除（对应 Vulkan 的 primitive culling 语义）、命中距离数值正确性、运行时 MSL 编译（shaderpack 路径需要）、iOS arm64 交叉编译 | 输出 `VERDICT: METAL_RT_OK` 即通过（真机）；CI 上验证工具链与 API 用法 |
| **Probe B `probe_moltenvk.c`** | 锁定的两个渲染器（MobileGlues=GL→Vulkan 转译、MoltenVK=LWJGL 直连 Vulkan）脚下的 MoltenVK，对照 MCVR 的设备硬门槛（`physical_device.cpp:112` + `device.cpp:28-39`）逐项打分 | 输出完整 PRESENT/ABSENT 矩阵；RT 扩展缺失是**预期数据**而非失败 |

## 为什么是这两个探针

- MCVR 的渲染栈是 Vulkan（实例要求 1.3+，`instance.cpp:49`）；
- iOS 上唯一的 Vulkan 实现是 MoltenVK（启动器内置 1.4.3，`Natives/resources/Frameworks/libMoltenVK.dylib`）；
- MoltenVK 上游源码中 Vulkan 光追三扩展（`VK_KHR_acceleration_structure` / `VK_KHR_ray_tracing_pipeline` / `VK_KHR_ray_query`）**零实现**——因此硬件光追的唯一路径是 **Metal RT 直连**，Vulkan/MoltenVK 只承载光栅与计算；
- 于是底子成立与否等价于两件事：Metal RT 能用（Probe A），MoltenVK 的其余部分齐活（Probe B）。

## 运行方式

推送 `chapter1/**` 到 `main`，或手动触发 workflow：

```
.github/workflows/chapter1-foundation.yml
```

macOS Apple Silicon runner 上自动执行：构建并运行两个探针 + 对启动器内置的 iOS 版 libMoltenVK.dylib 做二进制级验证（file/strings）+ 全套探针的 iphoneos arm64 交叉编译。

结果与结论汇总见 `docs/chapter-1-foundation-test.md`。
