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
