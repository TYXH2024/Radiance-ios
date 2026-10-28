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

---

## 2026-10-27（六）· 2.1 场景摄取首批交付：scene-ingestion 0.1.0（代码 + CI + Release 产线）

Task: 2.0 判定 METAL_RT_OK 后按路线推进 2.1 —— 上游摄取协议解剖 → 架构设计落库 → scene-ingestion mod 全量实现 → CI 产线 → 发 Release

Work Log:
- 上游 Radiance 摄取协议解剖（本仓 src/main/java/com/radiance/，MC 1.21.4/yarn 1.21.4+build.8 与真机一致）：ChunkProxy（1766 行）链路 = BuiltChunkStorage.createChunks/scheduleRebuild mixin 事件 → 后台线程 ChunkRendererRegionBuilder.build → SectionBuilder.build(自有 ThreadLocal BlockBufferAllocatorStorage) → Map<RenderLayer,BuiltBuffer> → 裸指针数组 rebuildSingleNative(origin,index,generation,...)；上游必须自建 mesh（MC 官方 BufferAllocator 归 GL 上传路径所有）；上游 native 核为外置二进制（core.dll/libcore.so）→ iOS 移植 = 重写 native 下游，Java 协议降维复刻
- 设计落库 docs/chapter2-scene-ingestion-design.md：与上游三个刻意差异——①轮询驱动（500ms 扫 BuiltChunkStorage，比对 BuiltChunk.data 引用/sectionPos 变化）替代 mixin 事件（延续 2.0 零 mixin/refmap/Loom 哲学，ecj 零 MC classpath）②SectionBuilder.build + 同步拷贝后即 close（免 takeChunk 所有权手术）③仅区块 BLAS/TLAS + 单线程限量（实体/远景/调度属 2.2+）；关键正确性决策：顶点 recentring（world-absolute float32 → chunk-local，TLAS instance transform=translate(origin)，float32 大坐标精度纪律）；generation 协议继承（防轮询乱序）
- intermediary 符号表离线校验：下载 yarn 1.21.4+build.8 v2 tiny（tools/yarn-mappings），python 解析（修复 v2 javadoc 注释行 \tc 误判类声明的解析 bug）→ 全量符号（21 类 + 19 字段 + 20 方法 + 2 构造）：ChunkBuilder=class_846/BuiltChunk=class_846$class_851/SectionBuilder=class_9810/RenderData.buffers=field_52168/SectionBuilder.build=method_60904/WorldRenderer.chunks=field_4112/ClientWorld.worldRenderer=field_17780/Vec3i.getX=method_10263 等，硬编码进 Mappings.java 并做 S3 运行时逐项审计（解析失败→REFLECT_FAIL 逐符号定位）
- Java 七类实现（chapter2/scene-ingestion/src/）：SceneIngestionMod（S0→S6 报告流水线+判定表 SCENE_INGEST_OK/SCENE_EMPTY/REFLECT_FAIL/NATIVE_FAIL/NATIVE_MISMATCH/LOAD_FAIL/EXTRACT_FAIL）/ SceneProxy（轮询+重建调度+每轮≤8+TLAS 1s 节流）/ Mappings（符号表+审计）/ Native（JNI 声明：initNative/rebuildSingleNative/invalidate/relocate/frameTick/isChunkReady/sceneStats/castRay/shutdown）/ ProbeReport（增量落盘）/ FabricEntry+FabricClientEntry（2.0 同款 watchdog + CLIENT_STARTED 反射+Proxy）
- native 实现 metal_scene.m（~560 行）：SceneSlot 槽位类（blas/vertexBuffer/indexBuffer/origin/generation/ready）+ 槽位网格；rebuildSingleNative（顶点拼接 recentred float3 + QUADS→uint32 索引展开 + 三角 BLAS + GetDirectBufferAddress 直读）+ 退役资源延迟释放协议（旧 BLAS/buffers 进 retired 列表，新 TLAS build 完成后才 release——防在途引用悬垂）+ TLAS 动态重建（instance transform=translate(origin) 行主序 4x3）+ castRayNative（scene_trace_kernel 单 ray dispatch）+ generation 拒写过期
- scene_trace.metal：2.0 已验证的 intersection_query API + get_committed_instance_id（instance 归因）
- build.sh + CI（.github/workflows/chapter2-scene-ingestion.yml）：macos-26 全链构建 → JNI 三导出 nm 验证 → 幂等发预发布 Release scene-ingestion-v0.1
- 本地 ecj 冒烟：Java 侧零错误（9 class 产出；修复 Mappings.call/get 签名 String 化 + ProbeReport fail(String,Throwable) 重载两处编译错）
- 勘误补漏：上条目 959c5b9 提交时 docs/chapter2-metal-rt-probe-field/README.md 被 .gitignore *.md 拦截未入库（第一章 fc9b9d8 同款坑）——本次 git add -f 补齐
- Release 更新（metal-rt-probe-v0.1）：body 追加真机实测结论段 + FIELD-REPORT-v0.1-20261027.md 资产上传（幂等脚本 scripts/update_metal_probe_release.py）

Stage Summary:
- 2.1 首批交付完整入库：设计文档 + Java 七类 + native 场景管理 + MSL kernel + build.sh + CI 产线（Release 由 CI 幂等发布 scene-ingestion-v0.1）
- 与 2.0 的关系：同一投送通道（JAR 内置 dylib→解包→System.load）、同一 Metal 直连架构（自建 MTLDevice）；新增真实世界几何摄取与动态 BLAS/TLAS
- 待办：①CI 过绿 + Release 资产确认 ②用户真机实测（zink 档 + 进世界 + 等 30s）回传 scene_ingestion_report.txt ③判 SCENE_INGEST_OK → 2.2 路径追踪引擎（MSL path tracer + 光照回传 + GL 纹理呈现）④若 REFLECT_FAIL 按报告逐符号修符号表 ⑤若 NATIVE_MISMATCH 排查 TLAS 变换/相机位置

---

## 2026-10-27（七）· scene-ingestion 0.1.0 真机 REFLECT_FAIL 回收 → 根因定位（探针参数类解析缺陷，非真机符号漂移）→ v0.2.0 修复发布

Task: 回收用户真机 scene_ingestion_report.txt（M3 / OSMesa 宿主档，判定 REFLECT_FAIL，S3 唯一 FAIL = class_6850#method_39969）→ 离线根因定位 → 修复发版

Work Log:
- 真机报告回收存证 docs/chapter2-scene-ingestion-field/（M3-v0.1.0-report.txt 原文 + README 根因判读）：S1 解包 PASS（dylib 93936 B + metallib 5560 B）、S2 装载 PASS（受信链复验）、S3 审计 46/47，唯一 FAIL = method_39969（ChunkRendererRegionBuilder.build）
- 根因定位（离线证据链闭环）：重新下载 yarn 1.21.4+build.8 v2 tiny 交叉验证——method_39969 宿主/签名从未漂移（tiny 13238-13244 行：class_6850 上的 (Lclass_1937;Lclass_4076;)Lclass_853;）；46 项 PASS 含 1.21.4 特有 SectionBuilder 全套四参签名，运行时确系官方 1.21.4
- 真实机制：Mappings.java v0.1 的 cls() 对未注册类名**静默 fallback 返回 Object.class**——World（class_1937）不在 21 类审计列表，而 method_39969 审计需要它做参数类型 → getDeclaredMethod("method_39969", Object.class, class_4076) 参数类型必然不匹配 → NoSuchMethodException → "签名漂移？"提示语把探针自身的错归因给真机
- 修复 v0.2.0：①World（class_1937）纳入类审计列表（21→22，S3 报告新增 class_1937 PASS 行）②cls() 加固——未注册类显式 Class.forName 并缓存，解析失败显式抛 IllegalStateException，彻底消灭静默降级 ③REFLECT_FAIL 提示语更正（v0.2 后复现即为真实版本不符指纹）④版本全链 bump：fabric.mod.json / build.sh / SceneIngestionMod 报告头
- CI workflow 同步 bump：JAR scene-ingestion-0.2.0.jar / TAG scene-ingestion-v0.2 / release notes 增 v0.2 变更段 / run summary 更新
- 本地 ecj 冒烟：9 class 产出零错误（scripts/smoke_sceneingest_v02.sh）

Stage Summary:
- 0.1.0 REFLECT_FAIL 归因闭环：**探针缺陷而非真机符号漂移**——静默 fallback 制造的假阳性；教训固化为两条纪律：反射审计器的参数类型类必须与符号同表管理；任何 fallback 必须显式报错
- v0.2.0 直推 main（含 field 存证 docs/chapter2-scene-ingestion-field/），CI 自动构建发布 Release scene-ingestion-v0.2
- 待办：①CI 过绿确认 ②用户真机复测 v0.2（**替换** mods 内 v0.1 jar，勿共存）③期望走到 S4/S5/S6 判 SCENE_INGEST_OK → 2.2 路径追踪引擎 ④若 NATIVE_MISMATCH 排查 TLAS instance 变换/相机坐标系 ⑤若 SCENE_EMPTY 确认真机进世界后等满 30s

---

## 2026-10-27（八）· scene-ingestion 0.2.0 真机回收：摄取主链成立（600 槽位/BLAS/TLAS 动态重建全验证）→ 离线定位三缺陷（TLAS 矩阵布局炸弹 + res() 静默 null 复发 + 积压截流）→ v0.3.0 修复发布

Task: 回收 v0.2.0 真机报告（NATIVE_MISMATCH，S6 相机读取 NPE 拦停）→ 离线根因定位 → 修复发版

Work Log:
- 真机报告回收存证 docs/chapter2-scene-ingestion-field/M3-v0.2.0-report.txt + README v0.2 章节：S3 全量 57 项 PASS（v0.2 修复生效）；**摄取主链真机验证成立**——600 槽位网格发现（5×24×5）、BLAS 构建 30 次（常规 0.7–4ms，一次 1245ms 离群疑 scratch 预热）、TLAS 动态重建 5 次（0.43–3.08ms）、退役延迟释放协议工作正常、36556 三角形进入 Metal 加速结构；与 OSMesa 软渲染宿主并行无干扰
- 缺陷 1（S6 NPE）：cameraPos() 运行时读 Vec3d x/y/z（field_1352/1351/1350）但三字段不在 S3 审计列表，res() 对未注册符号**静默返回 null** → f.get() 裸 NPE——与 v0.1 cls() 同家族第三次出现（v0.2 只修了 cls()）；且判定逻辑把"cam==null 跳过 S6"与"ray 全 miss"混同，输出误导性 NATIVE_MISMATCH（实际未发射任何 ray）
- 缺陷 2（30/600 摄取率）：首轮扫描快照全部 data 引用后，每轮 8 个 cap 截流的 592 个区块被标记"干净"永不摄取——后续重建全靠 MC 被动重发布（用户转动触发的 translucent 重排序）；主动积压从未排空
- 缺陷 3（离线文档核查抓出的定时炸弹）：**TLAS 实例矩阵布局错误**——证据链：Apple 文档 simd_float3x4 = "3 列 × 4 行"（columns 为三个 simd_float4）+ Metal4 instanceTransformationMatrixLayout 默认 ColumnMajor + Apple 论坛存档（thread/653267）"需用转置"；数学推导：matrix_float3x4 内存 = col0(4)|col1(4)|col2(4)，行向量约定 p'=[x y z 1]·M(4×3)，单位阵+平移 = [1,0,0,tx, 0,1,0,ty, 0,0,1,tz]（平移在 flat[3]/[7]/[11]）；v0.2 误按 4 行×3 列行主序写在 flat[9..11] → Metal 解码出剪切型垃圾矩阵（p'y=x）→ ray 必然全 miss；2.0 探针 TLAS 用单位变换故从未暴露——被相机 NPE 掩盖的隐藏炸弹，若 S6 真跑会产出"排查 TLAS 变换"的误导方向
- 修复 v0.3.0：①metal_scene.m 实例矩阵按 simd 列主序重写（m[3]/m[7]/m[11]=origin 平移）②Mappings.java：Vec3d x/y/z 入审计（57→60 项）+ res() 未知符号显式抛错（静默 null 家族三连修）③SceneProxy.java：neverIngested(generation==0) 槽位永远视为脏（积压排空）；限额 8→16（600 槽位约 19s）；收口=积压排空/静置 6s/35s 强制 + 15s 最短窗口；摄取期相机位置缓存（每轮刷新，退出世界后 S6 仍可发射 ray——纯 native 操作）④SceneIngestionMod.java：S6 直读失败回退缓存 + 新增 CAMERA_FAIL 判定（与全 miss 语义分离）+ NATIVE_MISMATCH 提示语更新
- 本地 ecj 冒烟：9 class 零错误（scripts/smoke_sceneingest_v03.sh）
- 版本全链 bump：fabric.mod.json / build.sh / 报告头 / CI workflow（TAG scene-ingestion-v0.3，release notes 详述三修复与新的等待时长指引）

Stage Summary:
- **2.1 核心已真机验证**：真实 MC 世界几何 → Metal BLAS/TLAS 动态重建链路成立（Q1/Q2 有答案）；剩余 S6 世界空间求交（Q3）待 v0.3 修复后闭环
- v0.3.0 三修复：TLAS 布局（关键正确性）/ S6 相机（审计+缓存）/ 积压排空（全量摄取）
- 待办：①CI 过绿 + Release scene-ingestion-v0.3 确认 ②用户真机复测（进存档停留 30–40s，报告出现 shutdown 后可退出）③判 SCENE_INGEST_OK → 2.2 路径追踪引擎 ④若 NATIVE_MISMATCH 复现（布局已修）→ 排查相机悬空/虚空世界或回报 ⑤若 CAMERA_FAIL → 回报（理论不应出现）

## 2026-10-27（九）· v0.3 真机回收 NATIVE_MISMATCH 六向全 miss → 根因定谳：v0.3 自身把矩阵平移搬错位置（二次回归）→ v0.4.0 诊断矩阵发布

Task: 回收 v0.3.0 真机报告（六向 ray 全 miss + 哨兵位码 4）→ 定位 v0.3"修复"实为回归 → v0.4.0

Work Log:
- 真机报告存证 docs/chapter2-scene-ingestion-field/M3-v0.3.0-report.txt：S3 全过、摄取链健康（157 BLAS/36k 三角），但 S6 六向全 miss 且哨兵位码 4（单位+平移实例均 miss）→ 指向实例矩阵编码而非场景数据
- 根因定谳（Apple 文档 + 数学推导）：`MTLPackedFloat4x3` = 4×4 前三行、**列主序**、平移在 **flat[9..11]**；v0.3 把 simd_float3x4 行主序误读当定谳，把平移写到 flat[3]/[7]/[11]——把 v0.2 修掉的炸弹换了引信再埋回去；v0.2 的原始错误才是行主序 flat[3]/[7]/[11]，v0.3 应恢复的是 v0.1 时代的 flat[9..11]
- v0.4.0：encode_instance_matrix 恢复 `[1,0,0, 0,1,0, 0,0,1, tx,ty,tz]`；新增顶点空间诊断矩阵（S6.0 相机回显 → S6.1 哨兵隔离 TLAS（单位/平移/阴性对照三实例）→ S6.2 raw-AABB 审计（ABS 票 vs 局部族票）→ S6.3 双假设锦标赛（ABS t=origin vs MEASURED t=2·origin−R）→ S6.4 自动选优 + 14 ray 终验带命中归因）；新判定 SENTINEL_FAIL / EXTRACTION_GARBAGE；ecj 冒烟 smoke_sceneingest_v04.sh 9 类零错误；Release scene-ingestion-v0.4
- 真机回收（M3-v0.4.0-report.txt）：raw AABB 全 ≈[0..16]、ABS 票=0/局部族票=80/garbage=0 → **顶点为 section 局部坐标定谳**；MEASURED 胜出（5/6），终验 13/14 判 SCENE_INGEST_OK；但哨兵 FAIL（位码 4）与 R 修正 y 偏置两处未闭环——用户指令零粉饰，继续追

Stage Summary:
- 实例矩阵布局终定谳 flat[9..11]（v0.4 真机 13/14 反证）；顶点空间终定谳 section-local
- 未决：哨兵 FAIL（高置信=仪器缺陷）与 MEASURED R 的 −2.1 y 偏置 → v0.5

## 2026-10-27（十）· v0.4 两处缺陷离线定谳（哨兵 fixture stride 失配 + R 中位数估计器偏置）→ v0.5.0 仪器修复 → 真机 SCENE_INGEST_OK，2.1 闭环

Task: 零粉饰追哨兵 FAIL 与 R y 偏置 → v0.5.0 → 真机复验收口 2.1

Work Log:
- 哨兵 FAIL 根因：v0.4 哨兵顶点按 `float[3][3]` 打包（实际 stride 12 B）而几何描述符声明 `vertexStride=sizeof(simd_float3)=16 B`——顶点 1 读成 (0,0,0)、顶点 2 越界 → BLAS 编码出退化三角形 → 两阳 ray 必 miss；真实场景路径不受影响（顶点缓冲恒为精确 simd_float3 数组——终验 13/14 即实例机制的实证），纯仪器缺陷
- R y 偏置根因：v0.4 用远槽 e=rawCenter−8 的**中位数**估 R，实测 (0,−2.07,0)——但部分高度 section 的 rawCenter 拖低中位数；整数面证据（扣除偏移后 +Y 93.07→91.00、−Y 19.07→17.00，X/Z 本就精确）+ 眼高交叉验证 92.62=91.00+1.62 → 真实 R=(0,0,0)
- v0.5.0 三修：①哨兵 fixture 改 `simd_float3[3]`（48 B stride 匹配）+ GPU 读回回显行自证字节 ②R 估计改**包围盒判定**（全局 raw AABB ⊆ [−1..17]³ → SECTION-LOCAL，R=(0,0,0) 精确；中位数仅回退）③每条命中 ray 回显 hitPoint 世界坐标（整数格线 = 零系统偏移自证）；判定语义零放宽（OK 仅来自真实 ray 命中，哨兵独立上报）
- 真机回收（M3-v0.5.0-report.txt）：哨兵 3/3 PASS（vert readback 48 B/stride 16 自证）、包围盒判定 SECTION-LOCAL R=(0,0,0) 精确、hitPoint 落整数格线（27.00/91.00/6.00）、终验 9/14 → **SCENE_INGEST_OK，2.1 正式闭环，2.2 绿灯**
- 9/14 vs v0.4 13/14 非回退：v0.4 的 −2.07 y 偏置曾把本应 miss 的方向（−X/+Y/+Z 等）错报为命中；v0.5 零偏置后 miss 方向与相机所在 section (16,80,0) 的开放侧几何自洽

Stage Summary:
- 2.1 全部验证问题闭环；2.2 契约盖章：顶点 section-local 原样入库 + TLAS instance = translate(origin)（免 recentring 等价简化）
- 交付：Release scene-ingestion-v0.5（scene-ingestion-0.5.0.jar）

## 2026-10-27（十一）· 2.2 pathtrace v0.1 首发：摄取链 fork + 顶点色 albedo + 全链路径追踪 + 离屏 PNG + CI 三修

Task: 2.2 第一版——路径追踪引擎首帧

Work Log:
- 摄取链 fork 自 2.1 四连真机验证骨架，三处外科手术：①顶点 COLOR 提取（VertexFormat.getOffset(COLOR) 动态解析，MC 方块染色烘进顶点色 = path tracer albedo）②摆放简化（section-local 原样 + t=origin）③TLAS 随建 shading pools（逐三角 3 色 + CPU 法线 + 实例基址表）
- pathtrace_kernel（MSL metal3 ray_query）：相机 ray → TLAS → 重心插值 albedo → 太阳直射 + 阴影 ray → cosine 加权漫反射弹射 → spp 累积 → Reinhard + gamma → RGBA8；atomic 计数器回读吞吐（实测非估算）
- S6 摆放抽查（哨兵 + 相机 + 整数格线）+ S7 离屏渲染 480×270/24spp/2bounces + PngWriter 手写零依赖编码器 + 图像统计自证；七档判定 PATHTRACE_OK/PLACEMENT_FAIL/RENDER_FAIL/IMAGE_DEGENERATE/SENTINEL_FAIL/SCENE_EMPTY/CAMERA_FAIL；归因开关 -Dpathtrace.disabled
- CI 三修（logs 取证）：heredoc 缩进必须对齐 YAML block-scalar / simd_sub 无 float3 重载（vsub 助手）/ intersection_query API 名 clang 纠正为 get_committed_triangle_barycentric_coord
- 交付：Release pathtrace-v0.1（pathtrace-0.1.0.jar）
- 待办：真机首跑回收（pathtrace_report.txt + pathtrace_frame.png）

Stage Summary:
- 2.2 代码全量进仓 + Release 产线走通；真机验证待回收

## 2026-10-28（十二）· 交付通道事故归因（pathtrace workflow 漏 artifact 步骤 + 取消运行遗留草稿）→ v0.1.1 补齐成品文件交付

Task: 用户手动 dispatch chapter2-pathtrace 绿勾但运行页找不到 jar（"绿勾了没看见哪里有 0.1"）→ 取证归因 → 修复交付通道

Work Log:
- 取证（GitHub API + 运行日志）：①14:37 push 运行被取消时 `gh release create` 已建**草稿**未挂资产未发布——Releases 页不可见（草稿区不显眼），且 `gh release view` 查不到草稿 ②用户 15:13 手动 dispatch 又取消；16:20 再 dispatch 绿勾——运行日志实证 release 步骤走 view 失败→create 成功路径，16:21:23 发布 pathtrace-v0.1 + jar（下载计数 0——用户没找到）③根因：chapter2-pathtrace.yml **遗漏 Upload artifacts 步骤**（2.0 metal-probe / 2.1 scene-ingestion 均有：metal-rt-probe-jar / scene-ingestion-jar）——绿勾运行页 Artifacts 区为空
- 修复：chapter2-pathtrace.yml 对齐 2.0/2.1 模式补 `actions/upload-artifact@v4`（name: pathtrace-jar；jar + dylib + metallib；if-no-files-found: error）——每次绿勾运行页必有成品就地可下；Release 仍为正式交付（双轨）；Release 步骤加草稿自愈注释（create 分支已被 16:21 日志实证可自愈）
- 补 chapter2/pathtrace/README.md（首发漏写）：双通道取件说明（运行页 Artifacts / Release 页）+ 使用 + 判定表 + 架构
- WORKLOG 断更回填（九~十一，v0.4.0 / v0.5.0 / pathtrace v0.1 三段）
- 注意：本次推送触发 chapter2-pathtrace（自身 yml + pathtrace/**）与 chapter2-metal-probe（chapter2/** 通配）两条产线，均为幂等 clobber 重传

Stage Summary:
- 交付通道修复：绿勾 = 运行页 Artifacts 区可直接下载 pathtrace-jar（zip 含 jar/dylib/metallib）；jar 亦在 https://github.com/TYXH2024/Radiance-ios/releases/tag/pathtrace-v0.1
- 事故链定谳：取消运行 → 草稿不可见 + 漏 artifact 步骤 → 运行页无成品；两者均已处置
- 待办：①新运行过绿 + Artifacts 区确认 ②用户下载 pathtrace-0.1.0.jar 真机首跑 → 回收报告 + 首帧 PNG

## 2026-10-28（十三）· pathtrace v0.1 真机回收判读：PLACEMENT_FAIL 双重根因追踪（静态比对穷尽=数学等价）→ v0.2 判别仪器版

Task: 回收 v0.1 真机报告（六向全 miss + S7 被闸）→ 严谨归因 → v0.2

Work Log:
- 真机报告存证 docs/chapter2-pathtrace-field/M3-v0.1.0-report.txt + README 判读：S0-S5 全绿（66 符号/600 槽位/顶点格式取证 stride=32 colorOff=12）、哨兵 3/3 PASS、36 重建/69014 三角/TLAS×6，但 S6 六向全 miss（脚下地板=无）→ PLACEMENT_FAIL
- 归因①（定案）：摄取覆盖塔缩 36/157——y 带直方图铁证（v0.5 覆盖 y=-64..112 全 12 层，v0.1 仅 y=48..112 顶 5 层）；机制=首轮 poll 在世界加载未完成时低 y 层槽位被试成空 → invalidate 置 generation → neverIngested 视为已试 → 永不再试（2.1 时序运气好）
- 归因②（静态穷尽）：摆放全 miss 无法用代码差异解释——逐函数 diff（提取 Java+native/build_blas/encode_instance_matrix/trace_ray/scene_trace_kernel MSL 逐行/S6 电池/JNI 签名对位/结构体布局/build.sh 编译参数）全部等价；摆放代数恒等（local+t=origin ≡ local−origin+2·origin）；相机区块 verts=2344 与 2.1 一致且在最终 TLAS（36 实例对账零净失效）；哨兵同 encode 矩阵真机命中 → 按"零粉饰"纪律不盲改，装判别仪器
- v0.2 实现：①SceneSlot 恢复 raw-AABB 记录（2.1 v0.5 仪器，fork 曾丢失）②新 native sceneAuditNative：raw 审计 + 相机区块顶点回读 + live 实例矩阵回读（flat[9..11]）+ 隔离单实例 TLAS 对照电池（ISO vs LIVE 并排同组 ray——ISO 命中+LIVE miss ⇒ live TLAS 组装缺陷；ISO 也 miss ⇒ BLAS 内容缺陷）③新 native renderIsolatedNative：隔离渲染诊断通道（相机区块单实例 TLAS + 同管线同 shading 池；成图=渲染链全链健康=第一帧光）④渲染核心重构 pt_render_core 共享（live/iso 同一代码路径）⑤SceneProxy 空试重试（每 4 poll 一次、上限 6 次——覆盖修复）⑥判定新增 ISO_RENDER_OK；S6.5 审计段；PLACEMENT_FAIL 消息指向 [audit] 行
- ecj 冒烟 smoke_pathtrace_v02.sh：10 类零错误；JNI 符号自检 14 入口；ObjC 括号/结构平衡自检通过；g_tlas 空守卫补齐
- 版本链 bump 0.2.0（build.sh/fabric.mod.json/报告头）；workflow → pathtrace-v0.2（+2 JNI 验证步骤）；README 判定表更新；field README 判读存档

Stage Summary:
- v0.2 = 仪器版：一次真机运行必出定谳证据（四台仪器互相交叉判别），同时携带覆盖修复
- 隔离渲染通道若成图 → 渲染链全链健康 + 用户看到第一帧光（相机区块真实路径追踪画面）
- 待办：CI 过绿 + Release pathtrace-v0.2 → 真机复跑 → 按 [audit] 行定谳 → v0.3 定点修复

## 2026-10-28（十四）· pathtrace v0.2 真机回收判读：四台仪器定谳（场景/渲染链全部正确，S6 探针通道自身缺陷）→ v0.3 探测线程重锚 + A/B 复测自证 + albedo 回读仪器

Task: 回收 v0.2 真机报告 + 首帧 PNG（PLACEMENT_FAIL + ISO 帧）→ 仪器判读定谳 → v0.3

Work Log:
- 真机报告/首帧存证 docs/chapter2-pathtrace-field/（M3-v0.2.0-report.txt + M3-v0.2.0-frame.png + README 判读）：摄取链全健康（174 重建/600 区块/157 BLAS/168,982 三角/TLAS×10——v0.2 覆盖修复实证生效，y=-64..112 全 12 层；COLOR 全量提取）；哨兵 3/3 PASS；S6 六向全 miss → PLACEMENT_FAIL → 隔离通道出图（106.8ms / 6,665,304 queries / 62.40 Mray/s）
- **S6.5 四台仪器定谳**：raw AABB=SECTION-LOCAL（2.1 契约成立）；相机区块顶点回读=真实地形；live 矩阵 flat[9..11]=(16,80,0) 正确；**ISO 与 LIVE 电池完全一致**（3/6 命中、3 条整数格线、脚下地板 1.62——hitPoint 27.00/91.00/6.00 与 2.1 v0.5 完全一致）⇒ LIVE TLAS/BLAS/矩阵/摆放全部正确，S6 的 0/6 是探针通道自身缺陷，PLACEMENT_FAIL 为误报
- S6 矛盾归因（离线穷尽）：castRayNative/trace_ray/normalize_dir 与 2.1 逐字节 diff 一致（排除代码缺陷）；JNI 声明 8×float 对位（排除签名炸弹）；SceneProxy.run() 内联调用（排除跨线程竞态）；PNG 像素级分析（370 精确色/纵向单调微梯度/±1 nibble 噪声）+ queries=6.67M/3.11M 采样=2.14 段/采样 ⇒ **≥57% 相机 ray 命中**——渲染链全链健康。三轮真机铁律：2.1 v0.5 电池前总有探测线程 TLAS 重建（setPlacementMode+frameTick）→ 9/14 命中；2.2 v0.1/v0.2 直查摄取期 TLAS → 0/6 ×2（确定性）；v0.2 审计（前面有 build_iso_tlas）→ 3/6。**判别变量 = 探测线程上是否刚有 AS build（驱动层，离线不可复现）**
- v0.3 实现：①refreshTlasNative（探测线程强制重建 TLAS+pools，复刻 2.1 已验证模式）②S6 A/B 复测自证（首轮全 miss → 重锚 → 复测，两轮行都在报告——机制当场坐实或证伪）③placementOk 分支入口重锚（中心 ray 交叉验证 + live 渲染全部走重锚后状态——renderPathtraceNative 直查 g_tlas，与 S6 同通道，不重锚会渲染出纯天空帧）④sceneAuditNative 新增 COLOR 回读（相机区块顶点色 min/max/mean/distinct/white/greenish）+ 池条目回读（顶点色→池→kernel 读取链闭环——2.3 atlas 采样决策的证据行）⑤pathtrace_kernel 双 atomic（rayStats[0]=queries、[1]=相机段命中）→ [render] 行新增 camHit% ⑥审计电池标签修正（dir[3]=-Y 曾误标 "+Y(上)"、dir[4]=+Z 误标 "-Y(下)"——物理 hitPoint 一直正确，标签说谎）+ LIVE 距离回显 ⑦analyzeImage 分带亮度 + ISO 诊断档门槛（单区块+任意视角可合法近平场；结构门槛仅对全场景帧生效——那才是交付物）
- 冒烟 smoke_pathtrace_v03.sh：ecj 8 类零错误（/home/z/my-project/scripts/）；ObjC/MSL 括号平衡；JNI 15 入口对位；双 atomic 一致性；YAML+heredoc bash -n 解析验证
- 版本链 bump 0.3.0（build.sh/fabric.mod.json/报告头/README）；workflow → pathtrace-v0.3（+refreshTlasNative 导出验证）；field README v0.2 判读存档

Stage Summary:
- **2.2 契约真机闭环**：摄取（600 区块/169k 三角/COLOR 提取）+ 摆放（ISO=LIVE 一致、整数格线、脚下地板）+ 渲染链（57%+ 相机命中、62.4 Mray/s）全部验证正确——距 PATHTRACE_OK 只差 S6 通道重锚
- v0.3 = 实证修复（非盲改）：重锚复刻三轮真机归纳的判别变量，A/B 复测让修复自带现场证明；albedo 疑点（无绿无结构）交给 COLOR 回读仪器，2.3 决策看证据
- 待办：①CI 过绿 + Release pathtrace-v0.3 ②真机复跑预期：首轮全 miss → [refresh] → 复测 3/6（27.00/91.00/6.00）→ 摆放 PASS → 全场景渲染 → PATHTRACE_OK + 第一帧光 ③若复测仍全 miss：审计判别照常，回报 [refresh]/[audit] 行 ④albedo 证据行回收 → 2.3 方向决策（顶点色 vs atlas UV）

## 2026-10-28（十五）· pathtrace v0.3 真机回收判读：PATHTRACE_OK——2.2 关闭

Task: 回收 v0.3 真机报告 + 首帧 PNG → 判读定谳 → 2.2 收口

Work Log:
- 真机报告存证（upload/pathtrace_report.txt + pathtrace_frame.png，03:25 CST）：S0–S3 全 PASS 66 项零漂移；157 BLAS / 168,990 三角；哨兵位码=7（3/3）
- **重锚机制当场坐实**：S6 首轮六向全 miss → [refresh] TLAS re-anchored on probe thread (instances=157, tlas builds=16) → 复测 +X/-Y/-Z 全命中整数格线（27.00/91.00/6.00 与 2.1 v0.5 完全一致），miss 三向 512 格内确无几何；ISO/LIVE 逐向一致——三轮真机归纳的判别变量（探测线程 AS build）被 A/B 复测现场证明
- S7 离屏渲染：480×270 spp=24 bounces=2 → 77.7 ms、queries=4,990,055、camHit=45.7%、64.19 Mray/s 实测（v0.3 双 atomic 仪器）；PNG 落盘 107,082 B；分带亮度相等、greenish=904 / white=0（顶点色寡色证据——2.3 atlas 决策输入）
- 最终判定: PATHTRACE_OK——2.2 全链（摄取→摆放→离屏路径追踪→出图）真机闭环
- 黄三角伪影：用户判定为原版太阳 × OSMesa 软渲染接缝，与渲染器无关，忽略
- 2.3 方向决策（据 COLOR 回读证据）：顶点色 distinct=16 寡色坐实 → atlas 采样路线

Stage Summary:
- 2.2 达成；2.3 开工：实时累积 + GL 合成 + atlas 采样

## 2026-10-28（十六）· pathtrace v0.4：2.3 实时累积 + GL 合成 + atlas 采样

Task: 2.3 三件套——离屏单帧 → 实时会话（渐进累积）→ 首次上屏叠进游戏画面

Work Log:
- LiveSession（RT 工作线程）：~30 Hz 相机姿态反射轮询（位移>0.02 格/转角>0.05° → liveResetNative 重置累积）；liveFrameNative 单命令缓冲异步提交（trace+present，store→accumulate 双模式，8ms 节流）；每 5s liveRefreshNative 工作线程 TLAS 重锚（v0.3 实证纪律）+ [live] 进度行；15s/55s 快照（pathtrace_live.png / pathtrace_live_final.png，收敛双证据）；60s 观察窗末写 S8 判定块；退出世界容错（world==null → 暂停，回世界自动恢复）
- 上屏链（渲染线程）：PathtraceWindowMixin（class_1041/method_15998 swapBuffers HEAD，intermediary 字面名 + remap=false 零 refmap，yarn 1.21.4+build.8 离线核验）→ PathtraceHook 四态状态机（OFF/ARMED/ACTIVE/DEAD，OFF 期一行 volatile 读零负担，异常一次性判死游戏零影响）→ livePresentNative 稳定快照 → GlComposer（GL 全反射调用无 LWJGL GL 类依赖）纹理上传 + 整帧 blit（480×270 → 窗口分辨率线性放大）；双保险 = mixins.json required:false + @Inject require=0 + 回调全 try/catch
- atlas 采样：GlComposer.captureAtlas 渲染线程 GL 回读方块 atlas → liveAtlasBufferNative 直写 native 环形缓冲 → uploadAtlasNative 入 MTLBuffer → pathtrace_kernel UV 采样替代顶点色寡色（distinct=16 → 连续纹理色）
- native：26 JNI 入口（live 会话族 11 个：begin/frame/reset/present/readback/stats/dims/atlasBuffer/uploadAtlas/refresh/end）；present_kernel 新增（累积 HDR → tonemap → 呈现缓冲）；live 帧 GPU 帧环双缓冲（稳定快照与进行中帧解耦）
- S8 判定七态：LIVE_OK / LIVE_OK_WITH_GL_ERRORS / LIVE_OK_NO_COMPOSE / COMPOSE_INACTIVE / COMPOSE_DEAD / ACCUM_STALLED / LIVE_FAIL；判死开关 -Dpathtrace.compose=false（累积照常仅不上屏，A/B 自证）
- 构建链：build.sh + sponge-mixin 编译依赖（版本回退链）+ mixin 类/pathtrace.mixins.json 入 JAR；workflow 11 个 live 符号 nm 校验 + 0.4.0 产物上传；fabric.mod.json mixins 声明 + v0.4 描述
- 版本链 bump 0.4.0；README 更新（2.3 使用指南/验证问题 Q1–Q5/实时判定表/架构增量 + v0.3 回收历史行）

Stage Summary:
- 2.3 首版完整：进世界 ~19s 摄取 → 离屏首帧（2.2 链路原样）→ 实时会话自动开启 → 游戏画面被路径追踪帧整帧替换 → 静止收敛/移动重置 → 60s 判定收口
- 待办：①CI 过绿 + Release pathtrace-v0.4 ②真机复跑：预期 LIVE_OK（blit>0、GL 错误=0、15s→55s 快照收敛、重置无鬼影）③atlas 采样效果与 greenish 底色对照 ④回收后判定 2.3 是否收口或装下一轮仪器

## 2026-10-28（十七）· pathtrace v0.4 真机回收判读：COMPOSE_DEAD + live 全天空——四缺陷定谳（report + PNG 像素级分析）→ v0.4.1

Task: 回收 v0.4 真机产物（pathtrace_report.txt×2 次运行 + latest.log + pathtrace_frame/live/live_final.png，操作序列 20静止>20旋转>20静止>20旋转）→ 像素级判读 → v0.4.1 修复发布

Work Log:
- 报告判读：S7 离屏首帧 PATHTRACE_OK（camHit=100%、64.81 Mray/s、帧 PNG=体素地形 ✓）；S8 实时会话开启后 [compose] 合成器立即判死：NoSuchMethodException: GL11.glGetTexLevelParameteriv(int,int,int)——LWJGL 3.3.3 无此重载（出参是第 4 个 int[]）；atlas 采集随之未执行（atlas=顶点色回退）
- PNG 像素级分析（自写 PNG 解码器分带/分列/非天空密度统计）：live 15s 快照=纯平灰蓝 (163-170,171-175,182)≈着色器地平线色 tonemap 精确值 (172,176,182)——俯视地板的射线全部穿透地板逃逸成天空；live 55s 快照=标准上下渐变=水平视角（pitch≈0）却 0.0% 地形（地形在脚下 1.6 格、80 格半径内必命中）；帧 PNG（S7 同姿态）=地形 ✓——live 帧查询的 TLAS 是坏的
- 逐 5s [live] 行反推：上帧 ~550k 查询=518.4k 主射线+6% 阴影/弹射 ⇒ ~2-3% 射线命中（快照角落小三角碎片）——TLAS 大面积失效而非全空
- 线程归责证据链：TLAS#8（探针线程构建，摄取）→ S6.5 LIVE 电池 3/6 命中 ✓；TLAS#9（探针线程 refreshTlasNative）→ S7 camHit=100% ✓；TLAS#10+（worker 线程 liveRefreshNative 每 5s 重建）→ 同场景同姿态全 miss ✗——唯一变量=构建线程。v0.3"重锚纪律"的原始全 miss 已被定谳为矩阵编码回归（十四号条目），纪律本身从未被隔离验证；v0.4 把重锚搬到 worker 线程才暴露
- 附带发现①：camHit% 分母误用累计 sppTotal（W×H×772）——真命中率被抹成 0.0%（上帧 camHits 至多 518k，显示 <0.05%）；v0.4 现场显示值与 PNG 矛盾的元凶之一
- 附带发现②：每 5s liveRefreshNative 强制重建 → g_stats[3] 前进 → native 自检判"场景变化"→ 累积重置——spp 在 300-800 打转永不收敛（用户应看到的 60s 收敛被 5s 周期切断）；且每次重建重分配全部 4 着色池+TLAS+实例缓冲（[retired] releasing 6 objects/次）
- 附带发现③：pt_grid 把 padded 总网格 (480,272) 当 dispatchThreadgroups: 的组数传 → 7680×4352 线程 ≈ 33M 空转/帧（256× 过量派发；bounds guard 保正确性但费 20-30ms/帧）
- 附带发现④：f/s 显示 38.5→3.8 假降级——frames 计数随重置清零但分母用总时长
- 签名审计：下载 lwjgl-opengl-3.3.3.jar + lwjgl-3.3.3.jar（Maven Central），反射枚举 GL11/GL13/GL30 全部 22 个被查签名——GlComposer 方法表仅 glGetTexLevelParameteriv 一处错（其余 21 个精确匹配）
- v0.4.1 修复：①签名改 (int,int,int,int[])+int[1] 出参；②TLAS 构建职责移交探针线程（LiveSession 零重建 + PathtraceMod 等待环 30s watchdog refreshTlasNative=[S8.4] A/B 后半段）+ worker 侧哨兵电池 castRayNative 六轴 2s/12s/32s=[live-sentinel] 行（通道健康度仪器——哨兵命中+camHit≈0⇒帧派发缺陷；双 miss⇒TLAS/线程层）；③camHit 分母改单帧样本数+f/s 改距上次重置；④pt_grid 改组数 ceil(w/16)×ceil(h/16)；⑤报告行首 10s 每秒（A/B 密度）；⑥liveRefreshNative 保留为诊断通道（live 循环不再调用）
- 版本链 bump 0.4.1（fabric.mod.json/build.sh/报告头/workflow TAG+jar 名+release notes/README 取件链接与 2.3 指南/架构图增量）；本地 ecj 3.36 + fabric-loader/sponge-mixin 同款 classpath 全 14 类编译零错误后推送

Stage Summary:
- v0.4 真机判读定谳：合成从未上屏（签名雷）+ live 渲染全天空（worker 重建 TLAS 失效，机制未定谳但实证组合明确：探针线程构建+任意线程查询）——用户全程看原版画面与报告/PNG 矛盾的解释闭环
- v0.4.1 交付：pathtrace-v0.4.1（四修复+哨兵电池仪器+30s 探针重锚 A/B）——下一轮真机回收的判读焦点：[live] camHit（真值）、[live-sentinel] 三次、[S8.4] 前后对比、[compose]/[S8.1]/[S8.2]
- 若 [live-sentinel] 命中而 [live] camHit≈0 → live 帧派发自身缺陷（v0.5 定点）；若双 miss 且 [S8.4] 后恢复 → worker 构建毒害定谳；若全恢复 → 2.3 收口进 2.4（质量+事件驱动摄取）

## 2026-10-28（十八）· pathtrace v0.4.2/v0.4.3 真机两连回收：camHit 35.7% 恢复→SIGSEGV 定谳→黑屏+TLAS 毒化 → v0.4.4

Task: v0.4.1 真机回收判读（LIVE_OK_WITH_GL_ERRORS + 永久全 miss）→ v0.4.2 仪器+防御包 → 回收（camHit 恢复但闪退）→ v0.4.3 SKIP 归一修复 → 回收（黑块+几何消失）→ v0.4.4

Work Log:
- v0.4.2（仪器+防御包）：哨兵姿态回显 + atlas epoch 括号 + 1.5s 加密点；探针交叉复测 [probe-sentinel]（worker 0/6 → probe 同 ray 换线程）；场景锁 g_sceneLock（castRayNative 此前无锁读 g_tlas=UAF 窗口）；atlas 二分开关 -Dpathtrace.atlas=false；GL 错误分类学 + 首帧几何行；墙钟统计（重置风暴免疫）；开关语义修复（Boolean.getBoolean 反转）
- v0.4.2 真机回收（17:55 run1）：**camHit 恢复 35.7%**（TLAS 职责迁移坐实；假说 B/C 双排除——atlas 前后哨兵均 3/6）+ 102 f/s / 82.89 Mray/s 真噪点出屏——但 live ~1.5s SIGSEGV：渲染线程 GlComposer.present → glTexSubImage2D → OSMesa/zink _platform_memmove 读源侧 SEGV_ACCERR；寄存器拼图 317216+201184=518400=整帧字节——源指针被正向平移出缓冲尾部；根因=v0.4.2 只重置 ROW_LENGTH/ALIGNMENT 漏 SKIP_*（GL 规范 SKIP 平移源指针；MC 图集路径遗留 skipRows=112）——v0.4 底部花屏条与 v0.4.2 崩溃同源
- v0.4.3（四重防御）：UNPACK/PACK 五元组归零（含 SKIP_PIXELS/SKIP_ROWS/SWAP_BYTES）+ Java 容量闸门 + 分段上传 ≤64 行/段（122,880B < zink 128KB 分块粒度）+ native staging 头尾 64KB guard pad；[compose] 捕获时像素传输状态行（现行犯取证）+ 256 帧心跳
- v0.4.3 真机回收（18:20 run2）：防崩生效（进程存活；skipRows=112 当场捕获坐实）——**两个新 P0**：
  ①黑屏：每帧 chunked texSubImage2D 首段 GL_INVALID_ENUM → break 弃帧（texSubErr=2815/2816 帧全灭）→ RT 纹理永黑=用户"黑块"；根因=present 缺预排空——进队早于本帧的错误被段 0 后第一个 glGetError 误记到上传头上（v0.4.2 同采样 89s 仅 10 错 vs v0.4.3 每帧必错——时间线直指每帧新增五元组读写调用）
  ②TLAS 毒化：live ~1s 后全员归零（worker 1.5s 0/6 + probe 交叉复测 0/6=AS 内容坏）；30s 探针重建后 probe 2/6 恢复而 worker 32s 仍 0/6；同 518400 全 miss 查询 ~10ms→~77ms（慢 miss=垃圾遍历签名）；loopHz 101→13
- v0.4.4 修复+仪器（本条目主交付）：
  黑屏四件套：present 预排空（每帧 1 次 glGetError 清队）/ pname 掩码探测（SKIP_PIXELS/SKIP_ROWS/SWAP_BYTES 读+写各探错，被拒即不可能被污染→读重置恢复三连跳过）/ 首帧调用归因梯（重置段/bind/段0/段0重试/段1 每步独立采样——INVALID_ENUM 出生地一步定谳）/ 段级重试（首错排空后重打一次=误记自愈；重试仍错才计真失败且继续后续段——部分帧>>零帧）
  atlas 防御：captureAtlas PACK 重置后回读验证——状态不可控即中止采集回退顶点色（glReadPixels 写目标平移越界量级可远超 64KB guard=TLAS 毒化悬案头号嫌疑，宁缺毋滥）
  TLAS 追凶仪器：g_verifiedTlas 双句柄 A/B（castRayVerifiedNative——同 ray 换句柄来源：g_tlas=0+verified>0⇒句柄陈旧；双 0⇒毒化/GPU 层；双>0⇒渲染通路）+ tlasIdentityNative（build#+指针身份）+ [live-sentinel] 六射线耗时（慢+全 miss=毒化签名）+ [live] 行 tlas=build#N（camHit 衰减与 build 年龄相关性）+ 34s 延迟复测（毒化半衰期）+ 交叉复测 5s 冷却（可重复）
  自愈重锚 [S8.7]：camHit 连续归零 + 哨兵双死（g_tlas 与 verified 均 0/6——排除合法天空场景与句柄陈旧）→ 探针线程立即重建（不等 30s）；持续型毒化下用户看到周期性真噪点而非永久黑屏
- 现场归档：docs/chapter2-pathtrace-field/M3-v0.4.3-report.txt（run2 完整 1230 行）；版本链 bump 0.4.4（fabric.mod.json/build.sh/报告头/workflow 11 处+release notes+新 JNI 符号校验/README）；本地 ecj 全类编译零错误（仅入口类缺 fabric 依赖属预期）

Stage Summary:
- v0.4.3 判读定谳：黑屏=GL 错误误归因+整帧弃帧（break）；TLAS 毒化=未定谳但已装齐判决仪器（双句柄 A/B/身份/耗时/半衰期/自愈）
- v0.4.4 交付：pathtrace-v0.4.4——黑屏四件套大概率直接修复上屏（预排空消误记+重试自愈）；TLAS 毒化下轮真机的判读焦点：[compose] 首帧调用归因梯（INVALID_ENUM 出生地）、[live-sentinel] verified 栏与耗时、[S8.7] 自愈频率、[live] tlas=build# 与 camHit 相关性、atlas=false 二分轮
- 二分协议：默认轮 + -Dpathtrace.atlas=false 轮各 60s——atlas=false 轮 TLAS 全程存活 ⇒ atlas 采集链（glReadPixels 写目标平移打坏 GPU 堆邻居）凶手坐实 ⇒ v0.4.5 定点修

## 2026-10-28（十九）· pathtrace v0.4.4 真机回收判读：LIVE_OK（黑屏修复坐实）+ TLAS 毒化定谳【时间级】→ v0.4.5 三仪定谳包

Task: 回收 v0.4.4 真机产物（A20 Pro 19:45 run：report + live/live_final PNG，用户症状"灰蒙渐变+中途一次重置再渐变"）→ 像素级+时间线判读 → v0.4.5

Work Log:
- **设备更正**：v0.4.3/0.4.4 两轮现场实为 **A20 Pro**（S8.1 行 renderer=zink(Apple A20 Pro GPU) 为准；归档文件名"M3-"误标——用户已提醒切机，此前未察觉）。本轮起回报文件名按设备命名，归档改名 A20Pro-v0.4.4-*
- **P0-1 黑屏：修复坐实 ✓**——首帧归因梯全 0x0000（重置/bind/段0/段0重试/段1 每步净）；pname 掩码 0x0；心跳全程 texSubErr=0 blitErr=0 chunkFail=0；retryOk=4862 全部为排空前遗留误记（无一真失败）；blit=4753 帧上屏、进程全程存活、atlas 采集 PASS（skip=0——v0.4.3 的 SKIP 污染路径未再现，回读验证生效或环境变化）
- **P0-2 TLAS 毒化：定谳为时间级而非线程级**——决定性证据链：①同一 TLAS#30（探针线程 30s 重建）：探针 30.05s 查询 2/6 命中（格线2+-Y 脚下）→ **32s 探针（交叉复测#3）+worker 双 0/6**；②TLAS#29 同轨迹：epoch=0/1 即时 worker 2/6（atlas 上传后仍活）→ 1.5s 双亡 → 2s/12s 持续双亡；③六射线耗时 7ms（活）→3ms（死）= 快 miss 空遍历签名（v0.4.3 为 77ms 垃圾遍历——两轮毒化形态不同或渐进）。**结论：新建 AS 在 ~2s 内被原地打坏，与查询线程无关——v0.4.1"线程归责"框架正式出局，v0.4.3"探针 2/6 vs worker 0/6"实为新鲜度差异非线程差异**
- **残存命中解剖**：camHit 卡 0.6%（~3.1k/521.6k 查询）↔ 快照 0.40% 非平坦像素（自写 PNG 解码器分带/分列统计）：相机正下方一小片带状几何（y∈[135,216]、x∈[69,472]，15s/55s 同形）——毒化后为**稳定部分态**（个别实例/子树幸存，非全灭）
- **用户症状闭环**："渐变到灰色朦胧" = camHit 1s 内 1.9%→0.6% 归零，pitch=90 俯视地面全屏变地平线色 (167,174,181)（tonemap 精确值）；"中间一次重置再渐变" = S8.4 30s 探针重锚（唯一 resets=1）+ TLAS#30 建成即衰亡；**非 S8.7 自愈——阈值 bug：触发条件 camHit<0.5 被现场 0.6% 残存卡死，自愈全程未触发**
- **离线代码审计**（找 OOB 写手，均排除）：MSL 内核入口 bounds guard、accum/image 写全部有界、atlas 只读采样；trace_ray/liveFrame 全走共享 g_device/g_queue（无线程私有状态）；atlas 上传 = 私有纹理+精确 blit+同步等待（时序上也被 epoch=1 即时命中排除即时毒源）；make_texture_from_bytes 归一。嫌疑收敛到：atlas 链延迟效应 / 合成器 GL 链 / zink-MoltenVK GPU 侧写手——**留给三轮二分现场定谳**
- v0.4.5 三仪（把"一小片"展开定谳）：①**instanceCensusNative** 每实例 -Y 金丝瑞射线（盒内顶部中心向下 16 格）→ 157 实例幸存/阵亡地图——连续索引区段=TLAS 实例记录线性扫写 / 空间聚集=距离选择性失效 / 散布=BLAS 层；Java 五点编排（基线 t≈0.15s / 稳态 3s / S8.4 新鲜 30.6s / 再衰减 33s / 晚窗 58s）+ 幸存 XY 盒 + 基线相对分析（合法 miss 列不误报）
- ②**sharedAuditNative**：实例缓冲（SHARED 存储）vs 构建时影子副本 memcmp（首异偏移+32B hexdump=写手签名：SKIP 平移=行步长图案 / 归零=memset / 0xAA=驱动 poison）+ 64KB 确定性图案金丝瑞（liveBegin 分配）——共享侧越界写手当场落网；双净而 TLAS 死 ⇒ GPU 私有内存写手
- ③**[tlas-health] 健康监视器**：相机正下方 -Y 金丝瑞射线 250ms 密度（首 10s），仅变迁打行 + 死亡时刻事件关联（atlas 上传 Δ/首帧 ATLAS_RGB Δ——native [live] first ATLAS_RGB frame dispatched 标记+Java st[7] 检测 / compose 帧序——GlComposer 静态计数器）；变迁即刻 [audit]
- v0.4.5 修复：S8.7 阈值 camHit<0.5→**<2.0**（0.6% 残存卡死 bug）+ 5s 自愈冷却（防重建风暴）——毒化仍在时用户看到周期性 ~1-2s 真画面（每次自愈=一次半衰期测量）；[addr] 地址图（TLAS obj/实例缓冲 mem/影子/金丝瑞/会话缓冲——邻居越界写布局证据）
- 冒烟 smoke_pathtrace_v045.sh：ecj 14 类零错误（-proc:none——sponge-mixin 注解处理器缺 ASM 依赖且无需激活）/ 括号平衡 414:414 / JNI 30:30 对位（含两新入口）/ YAML 6 步 / 9 仪器符号在场；**自查抓获一处编辑事故**：liveBeginNative 的 g_liveW/H 赋值行被吞（会致零渲染线程）——已补回+复测
- 版本链 bump 0.4.5（fabric.mod.json/build.sh/报告头/workflow TAG+2 新 JNI nm 校验+release notes/README 三轮二分协议+设备命名纪律）；现场归档 A20Pro-v0.4.4-{report,live15s,live55s} + 判读存档

Stage Summary:
- v0.4.4 判读定谳：黑屏修复闭环（四件套全胜）；毒化=时间级（新建 AS ~2s 内被原地打坏，线程无关）+ 稳定部分态（相机本地一小片幸存）；S8.7 未触发=阈值 bug 而非条件错
- v0.4.5 交付：pathtrace-v0.4.5——普查/审计/健康监视器三仪 + 自愈阈值修复；下一轮真机判读焦点：[tlas-health] 死亡时刻与事件关联、[census] 幸存地图模式、[audit] 共享侧 vs GPU 私有侧、[S8.7] 自愈周期、三轮二分（默认/atlas=false/compose=false）存活分流
- 待办：①CI 过绿 + Release pathtrace-v0.4.5 ②用户三轮 60s 真机（同存档同姿态）③据 [census]/[audit]/二分结果定 v0.4.6 定点修复（若 atlas 链坐实→修采集；若 GPU 私有侧→考虑分配隔离/驱动层绕行）④M3 复测一轮对照（设备特异性排查）

## 2026-10-29（二十）· pathtrace v0.4.5 六份真机回收判读：相机段被数组序饿死（同根因三症状定谳）→ v0.4.6 摄取优先级重写

Task: 回收 v0.4.5 真机产物（归档.zip：A20 Pro×3 + M3×3 六份 report/log/快照，用户观察"视角附近四五格的方块不渲染，难道就是之前检测不到脚底的原因？"）→ 判读 → v0.4.6

Work Log:
- **v0.4.5 CI/Release 遗留闭环**：workflow 40694441254 success；pathtrace-v0.4.5 Release 资产 pathtrace-0.4.5.jar（111911 B）已上传 ✓
- **M3_1 无 report**：日志终于 00:20:37 GL_INVALID_ENUM（OSMesa 已知噪声）+ fatal_trace exit(0) backtrace=用户未进世界即手动关闭；有效报告 5 份（A20×3 + M3×2）。设备命名本轮准确（[scene] 行 device=Apple A20 Pro GPU / Apple M3 GPU 各自验证）
- **三症状同根因定谳**：①用户"眼前四五格不渲染" ②v0.4.4 起"检测不到脚底" ③[tlas-health] t=0.01s 死亡（-Y 金丝雀 miss）——同源：**相机自己所在的区块段从未进入 TLAS**。证据链：相机 pos=(2.50,81.62,-5.50)→相机段 (0,80,-16)，六份报告全部 `camera section (0,80,-16) not ready — nearest ready origin=(0,80,0) @ 16.0 blocks`（M3 相机 (8.50,77.62,-5.50)→(0,64,-16) 同样 not ready）；探针电池 +Z 命中点 (2.41,81.62,1.00) 恰踩缺失条带边界 z=0（相机 z=-5.41，眼前 5.4 格 void 与用户"四五格"精确吻合）；-Y 脚下地板在 (0,64..80,-16) 段内永不命中
- **z 覆盖铁证**：五份报告 rebuild origin 的 z 值全部 ≥0（0/16/32/…/96 随运行时长外扩），**零负 z**；x 却对称覆盖 -128..+128——不对称性指向 vanilla 槽位布局而非世界内容
- **槽位布局反推（index↔origin 拟合）**：1.21.4 BuiltChunkStorage 实测 index = relZ·408 + relY·17 + relX（z 为最外层轴！），relX/relZ 均为 floorMod 回绕（slot 0..8=center+0..+8，slot 9..16=center-8..-1——负半轴排行尾）。存储中心 z=0 而相机 chunk z=-1 ⇒ 相机段落 relZ=16=**整个数组最后 408 槽**（x=0 对称纯属相机恰好居中 x）
- **饿死机制**：SceneProxy 按数组序扫描、每轮取前 16 个 dirty（含沿途 mesh 空区块）；真机 meshing 实测吞吐 ~5-6 段/s，35s FORCE_READY 收口时积压 5648-6667（六份"摄取窗口完成"行），推进仅到 index≈1300（z=+96 行）——relZ 9..16 行（z=-128..-16，含相机段/脚下段）永不摄取。旧判读修正：v0.4.4"残存 camHit 0.6%=相机本地幸存带"其实是反的——**幸存的是远处带，死的是相机带**
- **v0.4.6 修复（SceneProxy 摄取选择重写）**：扫描阶段只收集 dirty 候选（保序=数组序回退），选择阶段按【相机到 section AABB [origin,origin+16) 的 clamp 距离】近先排序（相机自身段=0 必然首轮入选、26 邻居次之、远处按距离补齐；对 vanilla 中心漂移/回绕布局免疫）；origin 带 index 级缓存（sectionPos 变化即失效，首轮 ~30ms 后近零开销）；预算按成功数计（空试不占名额）；新增证据行 [camera-first]（相机段摄取即回显，真机直接验证）
- 冒烟：ecj 14 类零错误（-source 8 与产线一致）/ 剥离注释字符串后括号平衡 101:101、313:313 / workflow 引用全 0.4.6 零残留；版本链 bump 0.4.6（fabric.mod.json/build.sh/报告头/workflow TAG+jar 名+release notes）；现场归档 A20Pro-v0.4.5-run{1,2,3} + M3-v0.4.5-run{2,3}

Stage Summary:
- v0.4.5 判读定谳：相机段饿死（数组序+回绕布局+35s 窗口三件套）；"眼前 void/脚底检测不到/tlas-health t=0 死亡"三症状同根因；[census]/[audit] 毒化追凶仪器的既望结论需按此重读（"幸存带"方向修正）
- v0.4.6 交付：pathtrace-v0.4.6——摄取相机距离近先；预期真机：[camera-first] 行出现、S6 `camera section not ready` 消失、-Y 金丝雀命中、眼前 void 消失、camHit 稳定不归零
- 待办：①CI 过绿 + Release pathtrace-v0.4.6 ②用户同存档同姿态 60s 真机回报（重点 [camera-first]/[tlas-health]/[live] camHit）③毒化判读若仍现（camHit 归零），用 v0.4.5 三仪在**修复后的相机带**上重新定谳 ④2.4 事件驱动摄取（mixin 化 SceneProxy——TLAS 冻结于摄取窗口的架构限制一并解除）
