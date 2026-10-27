# metal-rt-probe —— 第二章 2.0 阶段：进程内 Metal RT 探针

> 裁决背景见 `docs/chapter2-route-decision.md`。
> 第一章判 C（捆绑 MoltenVK 无 Vulkan RT 扩展）后的第二章首件交付物。

## 验证问题

| # | 问题 | 判定依据 |
|---|---|---|
| Q1 | 免改包投送通道：mod JAR 内置 arm64 dylib → 解包到 game 目录 → `System.load()` 是否走通 | 报告 S2 段（失败时含异常明文，判定 LOAD_FAIL） |
| Q2 | 进程内 Metal RT 全链路（BLAS → TLAS → MSL ray_query kernel → 回读校验） | 报告 S3 原生段（判定 METAL_RT_OK） |
| Q3 | 首个真机硬件 RT 吞吐数据（Mrays/s） | 报告 `[trace]` 行 |

## 结构

```
metal-rt-probe/
├── src/net/prisma/metalprobe/
│   ├── FabricEntry.java        # main entrypoint：90s watchdog 兜底
│   ├── FabricClientEntry.java  # client entrypoint：CLIENT_STARTED 触发（反射+Proxy）
│   ├── MetalProbeMod.java      # 主流程：解包 → System.load → JNI → 判定
│   ├── Native.java             # nativeRunProbe 声明
│   └── ProbeReport.java        # 增量落盘报告（每行 flush）
├── native/
│   ├── metal_probe.m           # JNI 化的 Metal RT 探针（chapter1 种子改造）
│   └── trace_kernel.metal      # MSL ray_query kernel（chapter1 同源）
├── fabric.mod.json             # id=metalprobe, 零 depends
└── build.sh                    # macOS 全链构建（dylib + metallib + jar）
```

## 构建

仅可在 macOS + Xcode toolchain 上构建（原生部分交叉编译 iphoneos arm64）：

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) bash build.sh
# 产物: metal-rt-probe-0.1.0.jar
```

CI 产线：`.github/workflows/chapter2-metal-probe.yml`（macos-26 runner，
构建 + 验证 + 上传 artifact + 幂等发预发布 Release `metal-rt-probe-v0.1`）。

## 使用（真机）

1. 从 Release 下载 `metal-rt-probe-0.1.0.jar` 放入 mods/（与 rt-probe 可共存）。
2. 渲染器保持 **zink** 档（第一章实测稳定档位）。
3. 启动游戏到标题屏，探针在 CLIENT_STARTED 后台线程自动执行。
4. 回传游戏目录下的 `metal_rt_probe_report.txt` 整份文件。

## 判定表

| 判定 | 含义 | 后续 |
|---|---|---|
| METAL_RT_OK | 投送通道 + 进程内硬件 RT 双成立 | 进入 2.1（场景摄取：MC 区块 → BLAS/TLAS） |
| LOAD_FAIL | dylib 装载受阻（签名/平台位） | 报告 S2 异常明文定因，迭代签名方案 |
| EXTRACT_FAIL | JAR 资产/目录问题 | 检查 JAR 完整性与 game 目录可写性 |
| METAL_RT_API_UNAVAILABLE | GPU 无 Metal RT | 换 A17 Pro / M3+ 设备 |
| METAL_RT_MISMATCH / NATIVE_FATAL | 原生段细节定因 | 以报告 [mismatch]/FATAL 行为准 |

## 设计红线（继承自第一章事故教训）

- **延迟执行**：CLIENT_STARTED（或 90s watchdog）后才动原生层，取证窗口干净。
- **零共享**：自建 MTLDevice/queue，不触碰 zink/MoltenVK 任何状态；ARC 全量释放。
- **增量落盘**：报告每行 flush，原生崩溃后已写内容仍是证据。
- **归因开关**：`-Dmetalprobe.disabled=true` 零原生调用，供闪退 A/B 对照。
