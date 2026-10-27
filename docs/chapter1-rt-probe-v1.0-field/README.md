# 第一章 · rt-probe v1.0 真机现场取证（2026-10-27）

> 背景：rt-probe v1.0（纯 mod JAR 探针，Fabric 入口，无头计算链路
> `vkEnumeratePhysicalDevices → 扩展/特性审计 → vkCmdBuildAccelerationStructuresKHR → ray query 回读`）
> 在两台 iOS 真机上随 AngelAuraAmethyst（Pojav 系启动器）+ Fabric Loader 0.20.1 / MC 1.21.4 / LWJGL 3.3.3 运行。

## 证据

| 文件 | 设备 | 环境 | 结果 |
|---|---|---|---|
| `M3.txt` | Apple M3（iPad） | Mac OS X 18.3.2（iOS 18.3.2）/ mobileglues 渲染层 | S0 全过 → S1 `IllegalStateException: Vulkan has already been created` → 判 F |
| `A20Pro.txt` | Apple A20 Pro | Mac OS X 27.0.1（iOS 27.0.1）/ MobileGL 渲染层 | 同上，判 F |

两份报告均完整落盘（探针先于游戏闪退完成全部写盘动作），证明崩溃时探针已正常退场，
后续 JVM 闪退与探针无涉（见各自报告尾部）。

## 判读结论（v1.0 的 F 判定是探针 bug，不是能力结论）

- S0 环境快照全过：`org.lwjgl.vulkan.libname=MoltenVK` 已由启动器设置，
  `java.library.path` 指向 app Frameworks 目录 —— B（LWJGL 缺 Vulkan 绑定）排除。
- S1 报错根因：LWJGL 3.3.3 的 `VK` 类带静态初始化块，进程内任何代码首次触碰
  `org.lwjgl.vulkan.*` 即自动 `VK.create()`。游戏运行时（GL→Vulkan 翻译层，
  vulkan 库名均为 MoltenVK）已抢先完成加载，探针二次显式 `VK.create()` 撞上
  LWJGL 幂等保护抛 `IllegalStateException`，v1.0 未捕获该消息语义，误判 F。
- **这恰恰是好消息**：报错本身证明 MoltenVK 已在进程内加载成功、全局命令可用。
  修复后探针复用现有库句柄即可继续（LWJGL 3.3.3 实例级/设备级命令均经句柄对象
  自带函数表解析，探针自建 VkInstance 与宿主零共享零干扰）。

## 修复（rt-probe v1.1，随本目录同批次发布）

1. S1 捕获 `already been created` 转判"复用宿主已加载库"，其余异常仍判 F；
2. S4 实例 `apiVersion` 按实现上限自动升 1.2（`VkPhysicalDeviceVulkan12Features` 链合法化）；
3. `RTProbeMod` 增加 `AtomicBoolean` 防重复执行守卫；
4. 版本号与文档 1.0 → 1.1。

预期判定：C（捆绑 MoltenVK 1.4.2 上游未实现 `VK_KHR_acceleration_structure` /
`VK_KHR_ray_query`，见上游 issue #427 / #1956）→ 届时转"改包替换 ICD"路线
（KosmicKrisp / honeykrisp）。若出 A 则无需改包直接进第二章。

## 复测要求（用户操作）

用 v1.1 jar 替换 mods/ 内旧探针，正常启动进存档，回传：
`<instance>/game/rt_probe_report.txt`（v1.1 版）+ `latestlog.txt` + `crash-reports/`（若闪退）。
