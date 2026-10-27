# rt-probe —— Vulkan 硬件光追能力探针（Radiance 移植 · 第一章）

一个 Fabric mod 形态的探针 JAR，运行在 **Prisma/AngelAuraAmethyst 启动器的真实 JAR 环境**里
（bundled LWJGL 3.3.3/3.4.1 + libMoltenVK.dylib），一次运行即可裁决：

> **"在 iOS 上，不改 IPA 的前提下，从 Java 侧能否真·硬件光追（加速结构 + ray query）？"**

探针全程无头计算（不创建 surface/swapchain），不触碰 MC 渲染管线，与活动渲染器零冲突。

---

## 0. v1.2 变更（针对 2026-10-27 第二轮真机报告的修复）

**第二轮真机结果**（M3 / iOS 18.3.2 与 A20 Pro / iOS 27.0.1，均跑 Vulkan 渲染档）：
探针 v1.1 完整跑通并双双判 **C**（捆绑 MoltenVK 1.4.3 不暴露
`VK_KHR_acceleration_structure` / `VK_KHR_ray_query`，134/135 项设备扩展全清单在案）
——**第一章裁决落定**。但游戏随后依然闪退，崩溃点两台完全一致：

```
SIGSEGV pc=0x0, libmobileglues.dylib glGetError+0x14
  <- GL.createCapabilities() <- Minecraft Window 构造（Render thread）
```

时序取证（latestlog）：探针在 mod 初始化阶段跑完（判 C）→ `glfwCreateWindow` →
"Initializing MobileGlues ..."（GL→Vulkan 翻译层开始初始化）→ settings 打印完后
**Vulkan 后端未完成** → MC 调 `glGetError` 撞 NULL 函数表 → SIGSEGV。

**归因**：v1.0 时（探针零原生调用）A20 Pro 能通过 GL 初始化进到存档；v1.1 时两台
都崩在 GL 初始化——唯一变量是探针在后台线程创建了自建 `VkInstance` 并**泄漏未销毁**
（判 C 路径直接 return），与 Render thread 上 MobileGlues 的初始化**并发竞争** MoltenVK，
疑似导致翻译层后端初始化失败。同时注意：v1.0 时 A20 Pro 进档 1 秒后也闪退过——
**渲染栈在 Vulkan 档本身另有不稳定因素**，与探针无关的那部分需另行排查
（建议闪退时切稳定渲染器档位对照）。

**v1.2 修复**：
1. **延迟执行**：探针不再在 mod 初始化阶段跑。新增 client entrypoint，经反射注册
   fabric-api 的 `CLIENT_STARTED` 事件——MinecraftClient 完全启动（Window/GL 上下文/
   MobileGlues 全部就绪）后的安全窗口才执行；fabric-api 缺失时由 90 秒 watchdog 兜底
   （此时 GL 初始化同样早已完成）；
2. **实例全路径销毁**：判 C/E 提前退出与 RtPipeline 结束时统一 `vkDestroyInstance`
   （v1.1 泄漏实例残留整个进程周期）；A 路径 cleanup 同时补 `vkDestroyDevice`；
3. **归因对照开关**：`-Drtprobe.disabled=true`（或环境变量 `RTPROBE_DISABLED=1`）
   时探针只写环境快照、**零原生 Vulkan 调用**——配合"不装探针"基线，可干净归因闪退；
4. 报告新增"触发方式"字段（CLIENT_STARTED / fallback-timeout / standalone-main）；
   fabric.mod.json 版本号修正（1.0.0 → 1.2.0，此前一直误报 1.0.0）。

---

## 0.5 v1.1 变更（针对真机 M3 / A20Pro 首轮报告的修复）

**真机现象**：两台设备首轮均在 S1 处判 F，异常为
`IllegalStateException: Vulkan has already been created.`

**根因**（已核对 LWJGL 3.3.3 官方源码）：`org.lwjgl.vulkan.VK` 类带**静态初始化块**，
进程内任何代码首次触碰 org.lwjgl.vulkan.* 都会自动 `VK.create()`。启动器/渲染栈先触碰了
Vulkan 类，库早已加载完毕；探针再显式 `VK.create()` 就抛 "already been created"。
这其实是**好消息**：MoltenVK 确实已在进程内且全局命令可用。

**v1.1 修复**：
1. S1 捕获 "already been created"，直接**复用**宿主已加载的库句柄（其余异常仍按 F 处理）；
2. LWJGL 3.3.3 的实例级/设备级命令全部经由句柄对象自带函数表解析，探针自建实例与宿主
   **零共享零干扰**（已核对 VK10/VK11 生成源码确认）；
3. 实例 apiVersion 按实现支持上限自动升到 1.2（使 Vulkan12Features 特性链完全合法）；
4. 增加防重复运行守卫；报告版本号升为 1.1。

**关于两台设备的游戏闪退**（M3 挂在启动阶段、A20Pro 撑到进存档 1 秒）：与探针无关——
v1.0 在 S1 抛出的是纯 Java 异常，被捕获后探针就退场了，**从未执行过任何原生 Vulkan 调用**；
且探针在 mod 加载阶段就已写完报告（两份报告尾部完整可证），闪退发生在其后的渲染栈阶段。
诊断闪退请回传：
- `.../versions/<档位>/game/latestlog.txt`（启动器日志，含完整崩溃堆栈）；
- `.../versions/<档位>/game/crash-reports/` 下最新文件（若存在）。

另外：v1.1 起探针**不再依赖渲染器选 MoltenVK**（自建/复用 Vulkan 均可）。若 MoltenVK 渲染器
档位闪退过早，可切回稳定渲染器跑探针，判定同样有效。

---

## 1. 交付物清单

| 文件 | 说明 |
|---|---|
| `rt-probe-1.1.jar` | **成品探针**，直接放进 mods/ 或独立运行（含预编译 SPIR-V，v1.1 修复 S1 复用） |
| `src/net/prisma/rtprobe/*.java` | 全部源码（5 个类，注释齐全） |
| `shaders/probe.comp` | ray query 计算着色器 GLSL 源 |
| `assets/rtprobe/probe12.spv` | 预编译 SPIR-V（Vulkan 1.2 设备用） |
| `assets/rtprobe/probe14.spv` | 预编译 SPIR-V（Vulkan 1.1 + VK_KHR_spirv_1_4 设备用） |
| `fabric.mod.json` | Fabric mod 描述（无 MC 版本依赖，任何 Fabric 档位都能加载） |
| `build.sh` | 一键重建脚本（自动下载依赖） |

## 2. 三步运行（推荐路径）

1. **建一个 Fabric 档位**：启动器内安装任意 MC 版本 + Fabric loader（探针不依赖 MC 版本与 Fabric API）。
2. **把 `rt-probe-1.1.jar` 放进该版本的 `mods/` 目录**。渲染器选 Vulkan/MoltenVK 或稳定的
   GL 渲染器均可（v1.1 起探针自建/复用 Vulkan，不依赖档位渲染器；MoltenVK 档位下
   `org.lwjgl.vulkan.libname=MoltenVK` 会由启动器自动设置）。
3. **启动游戏**，等待日志出现 `[RTProbe] 最终判定:`（约 3-10 秒，后台线程，不影响进游戏）。

结果位置（双份，都能拿到）：
- 游戏目录下 `rt_probe_report.txt`（每一步增量落盘，中途崩溃也有已完成部分）；
- 启动器日志里所有 `[RTProbe]` 前缀行。

## 3. 判定表（拿到报告后对号入座）

| 判定 | 含义 | 下一步 |
|---|---|---|
| **A** | 全通过：BLAS/TLAS GPU 构建 + ray query 求交 + 回读全部正确 | **无需改包**。直接进第二章：Radiance 管线适配 |
| **B** | LWJGL 缺 Vulkan RT 绑定类（S0 节会列出缺哪些） | 改包替换/升级 LWJGL jar（3.3.3+ 均有完整绑定，通常不会触发） |
| **C** | 设备扩展清单里没有 `VK_KHR_acceleration_structure` / `VK_KHR_ray_query` | **当前 MoltenVK 未暴露 Metal RT**（符合上游现状，见第 5 节）。走 ICD 替换路线 |
| **C2** | 扩展在列但 features2 特性位关闭 | MoltenVK 半实现/能力伪装，附报告回报后仍走 ICD 替换 |
| **C3** | 扩展+特性都过但 `vkCreateDevice` 被拒 | 特性链组合被实现拒绝，附报告回报 |
| **D** | 管线全建出来但回读数值不对（该中的不中/该脱靶的没脱靶） | 求交语义缺陷——极有价值的数据点，附报告回报 |
| **E** | 中途 Vulkan 调用失败（实例/设备/构建等，FAIL 行会标明具体 vkResult） | 按报错位置定位，附报告回报 |
| **F** | LWJGL 都加载不了 Vulkan 库（v1.1 后仅剩库本体缺失/架构不符会触发；"already created" 已按复用处理） | 附报告回报 |

无论哪种判定，报告都包含完整取证：实例层/扩展、全部设备扩展、features2 特性位、
driverName/driverInfo（MoltenVK 版本）、内存类型、队列族、Java/OS/启动器属性快照——
**一次运行 = 一次完整的环境测绘**，不用二次往返。

## 4. 探针技术路径（都做了什么）

```
S0 环境快照        Java/OS/启动器属性 + LWJGL Vulkan 绑定类齐全性（15 个类逐一 Class.forName）
S1 加载 Vulkan     VK.create()；若宿主/静态初始化已加载则复用（v1.1）
S2-S3 实例探测     vkEnumerateInstanceVersion + 层/扩展全量清单（含 VK_LAYER_MoltenVK 检测）
S4 创建实例        自动启用 VK_KHR_portability_enumeration + ENUMERATE_PORTABILITY_BIT（iOS 必需）
S5-S7 设备审计     props/driver props（读出 MoltenVK 版本）/ 设备扩展全量 / features2 pNext 链
                   （accelerationStructure + rayQuery + bufferDeviceAddress 三个特性位）
S8 创建逻辑设备    按需启用扩展 + 特性链（API>=1.2 走 Vulkan12Features，否则走 KHR 扩展）
T1 缓冲分配        host-visible 统一内存路径，BDA 地址就绪
T2 BLAS            三角形几何 -> vkGetAccelerationStructureBuildSizesKHR ->
                   vkCmdBuildAccelerationStructuresKHR（GPU 构建，前后屏障齐全）
T3 TLAS            单实例引用 BLAS device address -> 同上 GPU 构建
T4 计算管线        SPIR-V 内含 SPV_KHR_ray_query；描述符绑定 accelerationStructureKHR + storage buffer
T5 验证            两根射线：必中（期望 t=1.0）+ 必脱靶（期望 t=-1.0）-> 回读比对
T6 清理            全资源 best-effort 销毁
```

抗风险设计：
- KHRRayQuery 在启动器捆绑的 lwjgl.jar 里只是常量壳类——**不影响本探针**。ray query 的
  `rayQueryInitializeEXT/proceedEXT` 是着色器侧指令，宿主侧只需要 AS 构建命令
  （KHRAccelerationStructure 类已确认完整，含 vkCmdBuildAccelerationStructuresKHR）。
- SPIR-V 双变体自动按设备 API 版本选择（1.2+ 用 probe12，1.1 用 probe14）。
- 每步增量落盘，任何位置崩溃都有完整现场。

## 5. 背景情报（侦察 + 检索结论，读报告前先知道）

- 启动器捆绑 **MoltenVK 1.4.2**（2025-11，Vulkan 1.4 层）截至本探针发布日，上游
  **尚未实现** `VK_KHR_acceleration_structure` / `VK_KHR_ray_query`（KhronosGroup/MoltenVK
  issue #427、#1956 仍开放）。Khronos 已于 2026-10-13 宣布 MoltenVK 进入社区维护模式，
  推荐 Mesa 系 **KosmicKrisp**（Metal 4 上的全 conformant Vulkan 1.4，其 honeykrisp 驱动
  **已支持 ray tracing**；iOS 支持在其路线图上）。
- 因此**最可能的判定是 C**——这不是探针失败，恰恰是第一章要的答案：
  - JAR 环境本身已被证明可用（Vulkan 加载、设备枚举、扩展测绘全部工作）；
  - RT 能力缺失的瓶颈精确定位在 ICD 层；
  - 后续"改包替换 libMoltenVK.dylib 为支持 Metal RT 的 ICD"（KosmicKrisp/honeykrisp
    路线或自研 shim）完成后，**用同一个探针 JAR 复测即可验证新 ICD**——探针同时就是
    ICD 替换工作的验收工具。
- 若启动器运营方已给捆绑 MoltenVK 打过 RT 补丁，或某天 bundled 版本升级，判定会直接翻转成 A。

## 6. 从源码重建

```bash
./build.sh          # 需要 JDK8+（无 javac 时自动用 ecj）与 glslangValidator（>=12），依赖自动下载
```

独立运行（不经 mods/，适合有自定义 JVM 参数入口的场景）：
```bash
java -cp rt-probe-1.1.jar net.prisma.rtprobe.RTProbeMod
```
