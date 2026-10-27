# Radiance-ios 工作日志（会话记忆固化仓）

> 协作红线（用户指令）：一切修改直推本仓库 main（禁 PR）；构建必发 Releases；
> 记忆先落仓库再干活。事故全录：docs/incident-2026-10-26/。

---

## 2026-10-26 · 闪退双根因定案 + 修复落地 + 首个 Release

Task: 分析 200120.ips（alpha-ios15 仍闪退）→ 双根因定案 → 推记忆/发 Release/落地修复

Work Log:
- 双 .ips 全量解析（scripts 侧 ips_analyze.py）：
  - ips#1 (152753)：SIGBUS @ `nmethod::mark_as_maybe_on_stack` ← `BarrierSetNMethod::nmethod_entry_barrier` = **W^X bug 首帧实锤**（v4 handler raise() 链回 JVM 无解）
  - ips#2 (200120)：致命 SIGBUS PC = `radiance_takeover_start+1028`（libcore 自身 TEXT，在 66KB claim 窗口之外）→ 被判 foreign 转发 JVM → `VMError::report_and_die → abort`；**同转储满屏 G1 线程 = `-XX:+UseSerialGC` 从未生效**（参数入口漏配：jvm_preset_custom_args 仅 custom 档注入等）
- 记忆固化（直推 main `6882f8e`）：docs/incident-2026-10-26/（INCIDENT_REPORT + SESSION_STATE + 7 件证据含双 ips/hs_err/日志/ios3 jar + 一代仓 worklog 快照）
- 首个 Release：`v0.1.6-alpha-ios3`（历史回填，19.7MB jar 在架）
- 启动器侧修复（一代仓 Task331，commit `b5573a58`，CI run 40345114868 绿，已发 v7.0.0-beta3 预发布）：Radiance 会话检测（mods 目录文件名含 radiance）→ 末端强制追加 -XX:+UseSerialGC（用户显式 +/- 尊重）+ 有效 argv 全量回显
- ios16 原生重建启动（直推 main `0b4a5c9`）：`ios/libcore_v7.c`（structure-v7 三铁律 R1 claim 全 TEXT / R2 白名单转发 / R3 零故障官方 API 探针）+ CI ios16-core job（iphoneos arm64 交叉编译，artifact=libcore.dylib）
- CI 备注：macos-26 runner 排队取消成风（并行会话同款），ios16-core 首跑被取消已 rerun（attempt 2）；source-evidence 恒绿

Stage Summary:
- 闪退根因从"libcore 劫持 JVM 信号"演进为完整双因链：W^X（参数未生效）+ claim 洞（误转发）——两层修复分别落在启动器（当日可测）与 libcore v7（重建中）
- 交付物：事故报告+证据库（本仓）、ios3 Release、启动器 beta3 预发布（Task331）、libcore v7 源码+CI 产线
- 待办：①libcore v7 CI 编译过绿后随 alpha-ios16 发 Release ②Java 侧基于上游源码重建 iOS 增量（装载 banner 字符串 alpha-iosN 升级）③设备实测 beta3（锚点：Task331 日志行 + 不再秒退）④若 Radiance 需上二代 ZL2 应用，同款 SerialGC 修复需在二代仓重做

---

## 2026-10-27 · 第一章并行线：rt-probe Java 探针 v1.0 真机误判定案 → v1.1 发布（首个 RT 能力探针 Release）

Task: 回收 M3 / A20 Pro 两台真机 v1.0 探针报告 → 修复 S1 "Vulkan has already been created" 误判 F → rt-probe v1.1 发 Release

Work Log:
- 本线与 2026-10-26 原生线并行，目标互补：Java 探针在**不改包 IPA**的前提下于游戏进程内审计捆绑 MoltenVK 是否暴露 `VK_KHR_acceleration_structure` / `VK_KHR_ray_query`，为第二章（真实 RT 渲染管线）裁定路线
- 两份真机报告判读（归档 `docs/chapter1-rt-probe-v1.0-field/`）：S0 全过（B 排除），S1 均抛 `IllegalStateException: Vulkan has already been created` —— 根因是 LWJGL 3.3.3 `VK` 类静态初始化块自动 create，宿主渲染栈先触碰即完成加载，探针二次显式 create 撞幂等保护；报错本身反而证明 MoltenVK 已在进程内
- v1.1 修复四处：S1 捕获 already-created 转复用 / S4 实例 apiVersion 按上限自动升 1.2 / RTProbeMod 加 AtomicBoolean 防重守卫 / 版本文档 1.0→1.1；ecj+glslang 重建，本机无 Vulkan 冒烟通过（报告落盘、异常干净捕获、exit=0）
- 游戏闪退与探针无涉：报告尾部完整证明探针先于闪退完成退场，且 v1.0 从未执行原生 Vulkan 调用
- **会话事故全录（本条目）**：会话沙箱 `/home/z/my-project` 顶层被旧会话初始化成了 git 仓库且嵌套克隆无独立 .git，本次在 `repos/Radiance-ios` 内执行 `reset --hard + clean -fd` 时实际操作了外层仓库，导致 download/（v1.1 成品与源码）、scripts/、upload/（三份真机报告）被清；凭快照提交 `bef1f7c` 对象完整恢复全部资产（`git archive bef1f7c <paths> | tar -x`），外层 .git 已隔离为 `.git-quarantined-bef1f7c-snapshot`，并重新干净克隆本仓（独立 .git 验证过）。教训：多仓工作区必须先 `git rev-parse --show-toplevel` 确认仓库根再执行破坏性命令
- Release：`rt-probe-v1.1`（assets：rt-probe-1.1.jar 可直放 mods/ + rt-probe-1.1-full.zip 含源码/着色器/build.sh）；源码同时入库 `chapter1/rt-probe-java/`（剔除 deps 大 jar 与构建中间物）

Stage Summary:
- 第一章 Java 探针线交付闭环：真机证据 → 根因定案 → v1.1 修复 → Release 发布
- 预期判定 C（捆绑 MoltenVK 1.4.2 上游未实现 RT 扩展，issue #427/#1956）→ 届时与原生线殊途同归转 ICD 替换（KosmicKrisp/honeykrisp）；若 A 则免改包直进第二章
- 待办：①用户以 v1.1 jar 在 M3 / A20 Pro 复测，回传 rt_probe_report.txt + latestlog.txt + crash-reports（闪退定位）②按判定表走裁决 ③并行回收 Prisma 启动器侦察结论补 hook 点接入说明


---

## 2026-10-27（二）· 第一章结案：双真机判 C + 闪退归因（探针泄漏实例为首要嫌疑）→ rt-probe v1.2

Task: 回收 M3/A20Pro v1.1 双件套（探针报告 + 闪退证据）→ 第一章裁决 → 修复探针崩溃源 → v1.2 发 Release

Work Log:
- v1.1 双真机全链路跑通，双双判 C：MoltenVK 1.4.3（实例 Vulkan 1.4.357 / 设备 api 1.2.357）134/135 设备扩展中无 VK_KHR_acceleration_structure / VK_KHR_ray_query，特性全 false；AS 依赖项（deferred_host_ops/BDA/spirv_1_4）反而在场——缺的只有驱动层两扩展
- 第一章裁决落定 VERDICT.md：不改包路线终结，第二章转 ICD 替换（KosmicKrisp/honeykrisp 或自研 shim），替换后以同一探针复验收 A
- 闪退归因：双机同点 SIGSEGV pc=0x0 @ libmobileglues glGetError+0x14（GL.createCapabilities ← Window 构造）；latestlog 时序显示 MobileGlues 初始化未完成。v1.0 对照（探针零原生调用时 A20 能过 GL 初始化）锁定唯一变量：v1.1 探针在 mod init 后台线程建 VkInstance 且判 C 路径泄漏未销毁，与 Render thread 的 MobileGlues 初始化并发竞争 MoltenVK
- 另行记录：v1.0 时 A20 进档 1 秒亦闪退——Vulkan 渲染档翻译链自身另有不稳定因素，需以"移除探针基线 + 切稳定渲染器档"对照剥离
- rt-probe v1.2 四项修复：①延迟执行（client entrypoint 反射注册 fabric-api CLIENT_STARTED——回调签名引用 net.minecraft.client.Minecraft，为保零 depends 改用反射+Proxy；无 fabric-api 时 90s watchdog 兜底）②实例全路径销毁（判 C/E/枚举失败路径 + RtPipeline cleanup 补 vkDestroyDevice/vkDestroyInstance）③-Drtprobe.disabled=true 归因开关（零原生调用）④fabric.mod.json 版本 1.0.0→1.2.0 修正 + 报告新增触发方式字段
- 构建：build.sh 加 fabric-lifecycle-events-v1 + fabric-api-base 编译依赖（最终 FabricClientEntry 改反射后其实只在开发期有用，保留）；glslang 二进制随沙箱事故丢失，build.sh 加 SPIR-V 新鲜度跳过逻辑复用现有 spv；双降级路径冒烟通过（异常干净捕获/落盘/exit=0）
- 证据归档 docs/chapter1-rt-probe-v1.1-field/：双探针报告 + 双 hs_err + 双 latestlog + 双 fatal_trace + ame273 崩溃报告 + VERDICT.md；v1.2 源码同步 chapter1/rt-probe-java/

Stage Summary:
- 第一章闭环：判 C 铁证入库，第二章路线定为 ICD 替换；rt-probe 转型为替换后的验收工具（目标判 A）
- v1.2 发布 rt-probe-v1.2 Release（jar + full zip）
- 待办：①用户 v1.2 复测：预期判 C 不变 + 游戏不再因探针闪退；若仍闪退做移除探针基线/切渲染器对照 ②第二章启动：KosmicKrisp/honeykrisp ICD 调研与改包方案 ③Prisma 启动器 hook 点接入说明回收


---

## 2026-10-27（三）· v1.2 复测判读：闪退与探针无关定案，根因改判 Vulkan 渲染档混合态

Task: 回收用户 v1.2 真机三件套（hs_err_pid6534.log / latestlog.txt / fatal_trace.txt，"这一次没探针了"）→ 判读 → 归因修正落盘 → 更新 Release

Work Log:
- 用户反馈 v1.2 仍闪退；三份新证据显示两次连续启动（14:35:47 / 14:36:22）同点确定性崩溃：SIGSEGV pc=0x0，`glGetError+0x14`（libmobileglues.dylib GOT 间接跳转 stub，槽位 NULL）← `GL.createCapabilities()` ← Window/MinecraftClient 构造
- 关键发现——探针零介入证据链五条：①mod 列表 rtprobe 1.2.0（确认用户装的就是 v1.2 发布版：rt-probe-1.1.jar 内部版本实为 1.0.0，1.2.0 仅 v1.2 有）②线程表只有 RTProbe-Watchdog（blocked 睡眠中）无 RTProbe 工作线程 ③latestlog 零 [RTProbe] 输出 ④崩溃 elapsed 4.74s 早于 90s watchdog 与 CLIENT_STARTED ⑤无 rt_probe_report.txt 生成。v1.2 延迟执行设计完全生效，本次实测在事实上等价"移除探针"基线对照——基线同样闪退
- 归因修正：v1.1-field/VERDICT.md 的"探针并发竞争 MoltenVK"首要嫌疑假说证伪（探针没跑也崩）；真根因为 Vulkan 渲染档（renderer=libMoltenVK.dylib，configuredVulkan=1）+ 无 VulkanMod（actualVulkanPath=0）的混合态下 MobileGlues GL fallback 初始化确定性失败
- 勘误入档：v1.0 报告判 F 时"切换 MoltenVK 渲染档"提示系错误指引——org.lwjgl.vulkan.libname=MoltenVK 是启动器固定 JVM 参数（v1.0 未切档现场 S0 已存在），常规档下探针同样可加载 MoltenVK；用户被该提示带入 Vulkan 档固有崩溃。判 C 数据属"带病取证"，判定本身有效（换档不换库）
- 第一章判定不受影响：判 C 维持，第二章 ICD 替换路线不变
- 落盘：docs/chapter1-rt-probe-v1.2-field/（README 判读 + 三份证据归档）；VERDICT.md 追加"归因修正"更新段
- Release rt-probe-v1.2 更新：body 追加复测结论与用户操作指引，上传现场判读文档资产

Stage Summary:
- 闪退悬案闭环：三版崩溃同源（渲染档混合态），探针全线清白；v1.2 的价值反而被本次实测确证（延迟执行=天然归因对照）
- 用户下一步两步指引：①渲染器切回常规 GL 档 ②保留 v1.2 探针复跑，回传 rt_probe_report.txt（预期判 C 复现 + 游戏存活到标题屏）
- 待办：①等用户常规档复测报告 ②第二章启动：KosmicKrisp/honeykrisp ICD 调研与改包方案（主线）③Prisma 启动器 hook 点接入说明回收（并行）

---

## 2026-10-27（四）· 第二章启动：路线裁决 Metal 直连 + 2.0 探针交付

Task: 用户确认（判 C + zink 档 M3 稳定进游戏）→ 驱动生态调研 + 启动器源码侦察 → 第二章路线裁决 → metal-rt-probe 2.0 交付

Work Log:
- 用户反馈落档：第一章判 C 维持；zink 渲染档（libOSMesa.8.dylib + GALLIUM_DRIVER=zink）下 M3 真机可稳定进游戏 —— 第二章所需稳定宿主环境成立
- 驱动生态调研（详见 docs/chapter2-route-decision.md §2）：
  - MoltenVK：RT 无资金无实现（issue #427），维护模式，新特性全走 KosmicKrisp
  - KosmicKrisp：Vulkan 1.4 conformant / Mesa 26.0 / SDK 内可分发；但 RT 仅为路线图项（XDC 2025 "potentially ray tracing"，2026-10 v1.4 公告新增扩展无 RT）；最新版要求 Metal 4（iOS 26+，用户 M3 在 iOS 18.3.2 覆盖不到）；iOS 官方口径实验性未开分发 → 降级为跟踪项
  - HoneyKrisp：真身为 Asahi Linux 的 AGX 逆向驱动（Linux-only，不经 Metal）→ 与 iOS 无关，排除
  - 结论：当前时点不存在可直接投放 iOS 的 Vulkan RT 驱动，"改包换 ICD"路线无米下锅
- 启动器源码侦察（Prisma-Minecraft-iOS-Launcher2，重新克隆后定向挖掘）：
  - 渲染器档位全表：zink=libOSMesa.8.dylib / MobileGL=libMobileGL.dylib(链接期硬依赖 @rpath/libMoltenVK.dylib) / Vulkan=libMoltenVK.dylib(LWJGL libname 直载) / virgl 等，utils.h 常量实锤
  - zink 档的 Vulkan 解析：libOSMesa 未定义符号 vkGetInstanceProcAddr/vkGetDeviceProcAddr 由 dyld 对已加载 libMoltenVK 解析；启动器 fishhook(ZinkStrideFix) 仅做 stride 修补透传
  - 免改包投送通道实锤：main_hook.m hooked_dlopen 对 home/tmp 目录 dylib 做 PLPatchMachOPlatformForFile 平台修补；LWJGL natives 即 JAR 解包 → tmp → dlopen 的同款链路（Task193 重绑 libjvm _dlopen 槽使 System.load 进 hook）→ mod JAR 内置 dylib 投送通道成立
- 第二章路线裁决（docs/chapter2-route-decision.md）：**Metal 直连 RT 核**为主路线 —— mod JAR 内置 arm64 原生核（MTLAccelerationStructure BLAS/TLAS + MSL ray_query compute），Java 侧 Fabric 延迟入口 + 解包 + System.load + JNI；与 zink/MoltenVK 渲染主链同进程双引擎零干扰；不依赖任何 Vulkan RT 驱动、不改包、不动启动器。KosmicKrisp RT 落地后同架构可平移
- 2.0 交付 metal-rt-probe（chapter2/metal-rt-probe/）：Java 五类（FabricEntry watchdog / FabricClientEntry CLIENT_STARTED 反射+Proxy / MetalProbeMod 主流程+解包+判定 / Native JNI 声明 / ProbeReport 增量落盘）+ 原生 metal_probe.m（chapter1 CLI 探针 JNI 化，报告增量写、判定码回传、含运行时 MSL 编译数据点与 Mrays/s 吞吐行）+ trace_kernel.metal（chapter1 同源）+ build.sh（macOS 全链：交叉编译 iphoneos arm64 dylib + metallib -std 级联 + ad-hoc 签名 + ecj/javac + JAR 组装，资产内嵌）
- 设计红线继承：延迟执行 / 零共享自建 MTLDevice / 增量落盘 / -Dmetalprobe.disabled 归因开关
- 本地冒烟：ecj 编译 Java 侧零错误（exit=0，5 个 class 产出）；原生部分仅 CI 可编译
- CI 产线 .github/workflows/chapter2-metal-probe.yml：macos-26 构建全链 → nm 验证 JNI 导出 → artifact → 幂等发预发布 Release metal-rt-probe-v0.1（gh CLI + GITHUB_TOKEN，contents:write）

Stage Summary:
- 第二章路线定案：Metal 直连（不等驱动生态）；ICD 替换降级为 KosmicKrisp 跟踪项
- metal-rt-probe 2.0 源码 + CI 产线入库，Release 由 CI 幂等发布
- 用户下一步：从 Release 下载 metal-rt-probe-0.1.0.jar 放 mods/（zink 档）→ 回传 metal_rt_probe_report.txt（预期 METAL_RT_OK = 2.1 场景摄取绿灯）

---

## 2026-10-27（五）· metal-rt-probe 2.0 真机判定 METAL_RT_OK → 2.1 场景摄取启动

Task: 回收用户 M3/zink 档真机报告 → metal-rt-probe 2.0 判读落档 → Release 更新 → 启动 2.1 场景摄取

Work Log:
- 用户回传全量报告（S0-S3 原文归档 docs/chapter2-metal-rt-probe-field/M3-zink-report.txt），判读 README 同目录落档
- 判定 **METAL_RT_OK**：投送通道（S1 解包 89408B dylib + 5720B metallib / S2 System.load 走 hooked_dlopen 受信链）与进程内 Metal RT 全链路（S3）同时成立——等价第一章判定表"判 A"在 Metal 直连路线上达成
- 硬件侧实锤：Apple M3 GPU / gpuFamily Apple9 / dedicatedIntersectionHW=YES（专用交集硬件在场）
- 路线裁决 §4.3 风险项双双销项：风险 1（dylib 装载签名，ad-hoc 签名 + 受信 dlopen 链）PASS；风险 2（MSL 运行时编译 newLibraryWithSource，shaderpack 路线）PASS
- 首个 iOS 真机硬件 RT 吞吐数据点：262144 rays / 49.10 ms = 5.34 Mrays/s（微型测试 kernel 下界基准，真实场景复杂度待 2.2/2.3 校准）
- 正确性：hit 131072/131072 + masked-miss 131072/131072，距离/掩码剔除零错 ray
- 现场备注：GALLIUM_DRIVER=null 与启动器 zink 档预期注入值不一致（不影响判定，渲染主链正常；记录供 2.3 双引擎同步参考）
- route-decision.md 状态头 + §4.2 阶段表更新：2.0 标记真机通过，2.1 转当前阶段
- 2.1 场景摄取启动：上游架构侦察（本仓 src/main/java/com/radiance/ —— Minecraft-Radiance/Radiance mod fork，Vulkan 路径追踪 mod）—— client/proxy/world/ChunkProxy（区块摄取）/EntityProxy/PlayerProxy 为摄取拓扑参照，client/vertex/PBRVertexFormats 为 PBR 扩展顶点格式，client/proxy/vulkan/ 为待替换的 Vulkan 下游

Stage Summary:
- 第二章 2.0 阶段闭环：Metal 直连路线被真机全链路验证，免改包投送通道成立，后续所有原生核复用该通道
- Release metal-rt-probe-v0.1 更新：body 追加实测结论，上传现场判读资产
- 2.1 场景摄取开工：摄取拓扑沿用上游 proxy/world 设计，下游从 Vulkan buffer 换 Metal 加速结构（动态 BLAS/TLAS + 增量重建）
- 待办：①MC 1.21.4 mesh 摄取点侦察（client jar + mojmap 对准 SectionRenderDispatcher/MeshData 真实类名与 mixin 挂点）②2.1 架构设计落库 ③chapter2/scene-ingestion 首批代码 + CI + Release
