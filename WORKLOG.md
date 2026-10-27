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

