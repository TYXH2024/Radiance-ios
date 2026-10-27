# 第一章结案裁决：判定 C —— 捆绑 MoltenVK 不暴露 Vulkan RT 扩展

> 依据：2026-10-27 rt-probe v1.1 两台 iOS 真机报告（M3 / iOS 18.3.2、
> A20 Pro / iOS 27.0.1，AngelAuraAmethyst + Fabric Loader 0.20.1 + MC 1.21.4 +
> LWJGL 3.3.3，均跑 Vulkan 渲染档）。证据归档：本目录。

## 裁决

**判定 C（两台一致，无歧义）**：

| 项 | M3 | A20 Pro |
|---|---|---|
| 物理 | Apple M3 GPU（iPad15,3） | Apple A20 Pro GPU（iPhone19,7） |
| 实例版本 | Vulkan 1.4.357 | Vulkan 1.4.357 |
| 设备 apiVersion | 1.2.357 | 1.2.357 |
| driverName / driverInfo | MoltenVK / **1.4.3** | MoltenVK / **1.4.3** |
| 设备扩展数 | 134 | 135 |
| `VK_KHR_acceleration_structure` | **无** | **无** |
| `VK_KHR_ray_query` | **无** | **无** |
| `accelerationStructure` / `rayQuery` 特性 | false / false | false / false |
| AS 依赖项（deferred_host_ops / get_mem_req2 / BDA / spirv_1_4） | 全部在场 | 全部在场 |

JAR 环境（LWJGL 绑定类、库加载、实例创建、设备枚举）全链路工作正常——
探针自身的执行有效性由 S0-S5 全过证明。**缺的只是驱动层的两个 RT 扩展。**

## 结论与路线

- **"不改包 IPA"路线正式终结**：游戏进程内可直接使用的 Vulkan 实现是启动器捆绑的
  MoltenVK 1.4.3，其上游（含 1.4.x 全系，issue #427 / #1956）未实现
  `VK_KHR_acceleration_structure` / `VK_KHR_ray_query`，Apple GPU 的 Metal RT
  （`MTLAccelerationStructure` / `intersection_query`）从未被映射到 Vulkan RT 扩展。
- **第二章必须替换 Vulkan ICD**：改包 IPA，将 libMoltenVK.dylib 替换为暴露 RT 扩展的
  实现——候选为 KosmicKrisp / honeykrisp（Khronos 2026-10 弃用 MoltenVK 后的社区继任，
  支持 RT）或自研 shim。替换后用同一个 rt-probe 复测，判 A 即硬件 RT 就绪。
- 探针八类判定与验收流程见 `chapter1/rt-probe-java/README.md`。

## 附：本轮闪退归因（与判定无关，但影响可玩性）

两台在探针判 C 落盘后随即闪退，崩溃点完全一致：

```
SIGSEGV pc=0x0  libmobileglues.dylib glGetError+0x14
  <- GL.createCapabilities() <- Window 构造（Render thread，elapsed 3.7-4.8s）
```

时序（latestlog）：探针判 C 退场 → `glfwCreateWindow` → `Initializing MobileGlues ...`
（GL→Vulkan 翻译层开始初始化）→ settings 打印完毕后 Vulkan 后端未完成 →
MC 调 `glGetError` 撞 NULL 函数表。

**首要嫌疑是探针 v1.1 自身**：它在 mod 初始化阶段的后台线程创建了自建 `VkInstance`
并在判 C 路径**泄漏未销毁**，与 Render thread 上 MobileGlues 的初始化并发竞争
MoltenVK（v1.0 时探针零原生调用，A20 Pro 能通过 GL 初始化进到存档——唯一变量）。
rt-probe **v1.2** 已修复：延迟至 `CLIENT_STARTED`（GL/MobileGlues 就绪后的安全窗口）
执行 + 全路径 `vkDestroyInstance` + `-Drtprobe.disabled=true` 归因对照开关。

**注意**：v1.0 时 A20 Pro 进档 1 秒后也闪退过一次，说明 Vulkan 渲染档
（MobileGlues 翻译链）自身另有不稳定因素；若 v1.2 复测仍闪退，请同时做
"移除探针"基线与"切稳定渲染器档"对照，把闪退的渲染栈份额与探针份额分开。
