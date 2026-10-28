# pathtrace — 第二章 2.2/2.3 阶段交付物（Metal 直连 · 实时路径追踪 + GL 合成）

> 设计文档：`docs/chapter2-pathtrace-design.md`
> 前置：2.1 `scene-ingestion` v0.5 真机判定 SCENE_INGEST_OK——摄取契约已盖章。
> **v0.1 真机回收（2026-10-28 01:08）：判 PLACEMENT_FAIL**（六向全 miss、哨兵 3/3、相机区块已摄取且 verts=2344 与 2.1 一致）——离线逐函数比对确认 fork 与 2.1 数学等价，v0.2 按零粉饰纪律装判别仪器（不盲改）。
> **v0.2 真机回收（2026-10-28 02:23）：仍判 PLACEMENT_FAIL，但仪器已定谳**——LIVE TLAS/BLAS/矩阵/摆放全部正确（ISO=LIVE 一致，3/6 命中、脚下地板 1.62、hitPoint 与 v0.5 完全一致），S6 全 miss 是探针通道自身缺陷；隔离渲染出图 + 62.40 Mray/s。**v0.3：探测线程重锚 + A/B 复测自证 + albedo 回读仪器。**现场记录：`docs/chapter2-pathtrace-field/`
> **v0.3 真机回收（2026-10-28 03:25）：判 PATHTRACE_OK——2.2 关闭。**重锚机制当场坐实：首轮六向全 miss → 探测线程 TLAS 重锚（refreshTlasNative）→ 复测 3/6 命中（+X/-Y/-Z 整数格线全中，miss 三向 512 格内确无几何），ISO/LIVE 逐向一致（TLAS 组装健康）。离屏首帧 480×270 / 24 spp / 2 bounces → 77.7 ms、4,990,055 queries、camHit=45.7%、**64.19 Mray/s 实测**。**2.3 开工：实时累积 + GL 合成 + atlas 采样（本版）。**
> **v0.4 真机回收（2026-10-28 14:22/14:24）：判 COMPOSE_DEAD + live 全天空——四缺陷定谳（report + PNG 像素级分析）→ v0.4.1。**
> ① GL 合成器初始化即死：`glGetTexLevelParameteriv` 反射签名误写 `(int,int,int)`（LWJGL 3.3.3 无此重载，出参是第 4 个 `int[]`）→ 屏幕从未上屏；② live 相机射线 ~100% miss：worker 线程重建的 TLAS（builds≥10）全 miss，同场景同姿态探针线程构建的（8/9）100% 命中（S6.5/S7）——TLAS 构建职责移交探针线程；③ camHit% 分母误用累计 sppTotal → 真命中率被抹成 0.0%；④ `dispatchThreadgroups:` 把总尺寸当组数传 → 256× 线程过量派发。现场记录：`docs/chapter2-pathtrace-field/`

## 取成品（两个通道，内容相同）

| 通道 | 位置 | 说明 |
|---|---|---|
| **A · 工作流运行页成品文件** | Actions → `chapter2-pathtrace` → 任意绿勾运行 → 页面底部 **Artifacts** 区 → `pathtrace-jar` | 每次绿勾必有；zip 内含 `pathtrace-0.4.1.jar` + dylib + metallib。**运行中途被取消的运行没有 artifact**（也不会发布 Release——可能遗留草稿） |
| **B · GitHub Release（正式交付）** | https://github.com/TYXH2024/Radiance-ios/releases/tag/pathtrace-v0.4.1 → `pathtrace-0.4.1.jar` | 幂等 `--clobber` 重传；若 Releases 页看不到，先检查页面底部"Drafts"区（被取消的运行可能遗留未发布草稿） |

> v0.1 首发 workflow 遗漏了 artifact 上传步骤（2.0/2.1 均有）——导致"绿勾了但运行页看不到 jar"。v0.1.1 已补齐：**以后看绿勾运行页最下方的 Artifacts 即可直接下载**。

## 使用（2.3 实时版）

1. 删除 `mods/` 内旧探针 JAR（scene-ingestion / metal-rt-probe / rt-probe / 旧版 pathtrace），放入 `pathtrace-0.4.1.jar`——同存档、同启动器档位（Vulkan/MoltenVK 档，OSMesa 宿主实测兼容）。
2. 进入世界后：摄取约 19s → 离屏首帧（2.2 链路原样跑一遍，落 `pathtrace_frame.png`）→ **实时会话自动开启（S8）——游戏画面自此被路径追踪帧整帧替换**（480×270 线性放大至窗口分辨率）。
3. 建议动作序列（60s 观察窗内）：**静止 ~20s** 看噪点随累积收敛 → **缓慢转动视角** 看累积自动重置（重新变噪）→ **再静止 ~20s** 看重新收敛。移动整个身体（位移）同样触发重置。v0.4.1 注：30s 处有一次刻意的探针线程重锚（[S8.4]）→ 累积重置一次，属预期仪器行为（A/B 后半段）。
4. 停留 **≥75s**（60s 观察窗 + 判定收口），随后退出世界/游戏均可（会话资源随进程退出释放，报告在 windowDone 时已写全）。
5. 回报 game 目录下四个文件：**`pathtrace_report.txt`**（全量报告，重点行：[live] 的 camHit（v0.4.1 分母已修）、[live-sentinel] 2s/12s/32s 三次哨兵电池、[S8.4] 重锚行、[compose]/[S8.1]/[S8.2] 合成与 atlas 行）+ **`pathtrace_frame.png`**（离屏首帧）+ **`pathtrace_live.png`**（实时 15s 快照）+ **`pathtrace_live_final.png`**（实时 55s 快照）。

> 若合成疑似干扰排查：加 `-Dpathtrace.compose=false` 启动——累积/快照照常，仅不上屏（判定 `LIVE_OK_NO_COMPOSE`，语义即自证 A/B）。

## 验证问题（2.3）

| Q | 内容 |
|---|---|
| Q1 | **实时累积**：静止相机下 `pathtrace_live.png`（~15s）→ `pathtrace_live_final.png`（~55s）信噪比是否单调改善（spp 计数与图像方差双证据）？ |
| Q2 | **累积重置**：相机位移 >0.02 格或转角 >0.05° 是否正确触发重置（无拖影/鬼影，新姿态从噪点重新收敛）？ |
| Q3 | **GL 合成**：mixin swapBuffers HEAD 注入是否稳定上屏（blit 帧计数 >0、GL 错误数 =0、判定 `LIVE_OK` 而非 `COMPOSE_DEAD`/`COMPOSE_INACTIVE`）？ |
| Q4 | **atlas 采样**：GL 回读的方块 atlas 经 `uploadAtlasNative` 入 GPU 后，pathtrace_kernel 的 albedo 是否摆脱顶点色 distinct=16 寡色（对照 2.2 帧 greenish=904/white=0 的底色）？ |
| Q5 | **实时吞吐与节流**：live 会话稳态 Mray/s、帧间隔（8ms 节流 + Metal 异步串行化）与 60s 窗内总 spp？ |

## 判定表（2.2 离屏首帧——v0.3 已回收 PATHTRACE_OK，历史保留）

| 判定 | 语义 |
|---|---|
| `PATHTRACE_OK` | 摄取健康 + 摆放等价 + 渲染成功 + 图像非退化（含统计自证） |
| `ISO_RENDER_OK` | **v0.2 新增**：live 摆放未过但隔离渲染通道（相机区块单实例 TLAS）出图——渲染链全链健康，缺陷隔离到 multi-instance TLAS 组装；看 [audit] 行判读 ISO/LIVE 对照 |
| `CAMERA_FAIL` | 相机姿态读取失败（S6 前置） |
| `SCENE_EMPTY` | 摄取后无就绪 BLAS |
| `SENTINEL_FAIL` | 哨兵求交失败（实例机制/矩阵编码异常，与场景数据无关） |
| `PLACEMENT_FAIL` | 整数格线抽查失败（v0.2 起 [audit] 行已判别根因方向） |
| `RENDER_FAIL` | 离屏渲染派发/回读失败 |
| `IMAGE_DEGENERATE` | 渲染成功但图像统计退化（全黑/全白/无方差） |

归因开关：`-Dpathtrace.disabled=true` 或 `PATHTRACE_DISABLED=1` → 只写环境快照、零原生调用（闪退 A/B 对照用）；`-Dpathtrace.compose=false` → 实时累积照常、仅不上屏（合成通道 A/B 对照用）。

## 2.3 实时判定表（S8 观察窗末写入报告）

| 判定 | 语义 |
|---|---|
| `LIVE_OK` | 累积单调 + 合成活跃（blit>0）+ 零 GL 错误——2.3 全链成立 |
| `LIVE_OK_WITH_GL_ERRORS` | 累积与上屏成立但 glGetError 非零累计 >0（blit 照常，看 [compose] 行归因） |
| `LIVE_OK_NO_COMPOSE` | compose=false 主动关闭：累积/快照照常，仅不上屏（A/B 自证语义） |
| `COMPOSE_INACTIVE` | 合成钩子未武装/未激活（mixin 未命中或 swap 心跳缺失——看 swapCount 证据计数） |
| `COMPOSE_DEAD` | GL 合成器异常判死（游戏零影响，累积继续，仅上屏通道关闭） |
| `ACCUM_STALLED` | 相机静止但 spp 不增（累积停滞——native 侧帧环或 TLAS 重锚异常） |
| `LIVE_FAIL` | liveBeginNative 拒绝或会话未能开启（看 [live] 行根因） |

## 2.3 架构增量

```
S8 实时会话（RT 工作线程，~30 Hz 相机轮询 + 8ms 帧节流）：
  相机姿态反射读取（S6/S7 同链路）→ 位移>0.02格/转角>0.05° → liveResetNative
  liveFrameNative — trace+present 单命令缓冲异步提交（store→accumulate 双模式）
  v0.4.1：本线程零 TLAS 重建（worker 重建 TLAS 全 miss——构建职责在探针线程）
  [live] 报告行：首 10s 每秒（A/B 密度），此后 5s；camHit 分母 = 单帧样本数
  [live-sentinel] 哨兵电池（2s/12s/32s）：worker 直查 TLAS 六轴——求交通道健康度
  15s/55s 快照 PNG（收敛双证据）→ 60s 观察窗末写判定块
探针线程 30s watchdog 重锚（[S8.4]）：refreshTlasNative——A/B 后半段留证
上屏（渲染线程，mixin swapBuffers HEAD）：
  PathtraceWindowMixin → PathtraceHook（OFF/ARMED/ACTIVE/DEAD 状态机）
  → livePresentNative() 取稳定快照 → GlComposer（GL 反射，无 LWJGL GL 类依赖）
  → 纹理上传 + 整帧 blit（480×270 → 窗口分辨率线性放大）
  双保险：mixins.json required=false + @Inject require=0 + 回调全 try/catch 判死
atlas 采样：
  GlComposer.captureAtlas — 渲染线程 GL 回读方块 atlas（glGetTexLevelParameteriv
  出参 int[]——v0.4.1 签名修复）→ liveAtlasBufferNative
  → uploadAtlasNative 入 MTLBuffer → pathtrace_kernel UV 采样替代顶点色寡色
```

## v0.3 实证修复 + 证据补齐（基于 v0.2 仪器定谳）

| 变更 | 依据 |
|---|---|
| **refreshTlasNative（探测线程重锚）** | 三轮真机铁律：2.1 v0.5 每次电池前总有探测线程 TLAS 重建（setPlacementMode+frameTick）→ 9/14 命中；2.2 v0.1/v0.2 直查摄取期 TLAS → 0/6 ×2（确定性）；v0.2 审计在同一 g_tlas 上、前面有 build_iso_tlas → 3/6 命中。判别变量 = 探测线程上是否刚有 AS build（驱动层，离线不可复现；trace_ray 等已逐字节 diff 排除代码缺陷） |
| **A/B 复测自证** | 首轮全 miss → 重锚 → 复测：两轮行都在报告里——复测命中 = 机制当场坐实；仍 miss = 审计照常判别（零粉饰） |
| **live 渲染前同样重锚** | renderPathtraceNative 直查 g_tlas（与 S6 同通道）——不重锚会渲染出纯天空帧 |
| **COLOR/albedo 回读仪器** | v0.2 帧 ≥57% 相机命中但无绿无结构——顶点色+池条目直接回读，2.3 的 atlas 采样决策看这行证据 |
| **camHit% 相机命中率**（第二 atomic） | 一眼区分"全天空帧"与"贴脸山坡"（v0.2 靠 queries 反推的量，v0.3 直接测） |
| 审计电池标签修正 + LIVE 距离回显 + 分带亮度 + ISO 诊断档门槛 | dir[3]=-Y 曾误标 +Y(上)；ISO 帧（单区块+任意视角）可合法近平场，结构门槛仅对全场景帧生效 |

## v0.2 判别仪器（S6.5，PLACEMENT_FAIL 根因分离）

| 仪器 | 判什么 |
|---|---|
| raw-AABB 审计 | 提取顶点是否仍为 section-local（v0.1 fork 丢了 2.1 的这台仪器） |
| 相机区块顶点回读 | BLAS 里到底是什么字节（眼见为实） |
| live 实例矩阵回读 | flat[9..11] 是否 = translate(origin) |
| 隔离 TLAS 对照电池 | 同一 BLAS 同一 trace_ray，ISO vs LIVE 并排打同一组 ray：ISO 命中+LIVE miss ⇒ live TLAS 组装缺陷；ISO 也 miss ⇒ BLAS 内容缺陷 |
| 隔离渲染通道 | live 未过时用相机区块单实例 TLAS 渲染同一帧——成图即渲染链健康（即第一帧光） |

另含摄取覆盖修复：v0.1 真机实测 36/157 覆盖塔缩（低 y 层首轮空试后永不再试）；v0.2 空试槽位窗口内每 2s 重试（上限 6 次）。**v0.2 真机实证：174 重建/157 BLAS/168,982 三角，y=-64..112 全 12 层——覆盖修复生效。**

## 架构

```
Fabric CLIENT_STARTED → 后台摄取线程（500ms 轮询，零 mixin/refmap，2.1 骨架 fork）
  SectionBuilder.build → 顶点层提取（坐标 + COLOR 双通道）
  COLOR 偏移 = VertexFormat.getOffset(COLOR) 运行时解析（无硬编码）
  JNI → libradiance_pathtrace.dylib：
    顶点 section-local 原样入库（免 recentring）
    QUADS → uint32 索引展开 → 三角 BLAS
    TLAS instance = translate(origin)（2.1 已验证布局的等价简化）
    TLAS 随建 shading pools：逐三角 3 顶点色 + CPU 法线 + 实例基址表
  pathtrace.metallib（metal3 ray_query，三 kernel）：
    scene_trace_kernel — 2.1 原样（哨兵/抽查复用）
    pathtrace_kernel — 相机 ray → TLAS → 重心插值 albedo → 太阳直射 + 阴影 ray
      → cosine 加权漫反射弹射 → spp 累积（2.3 起累积模式 + atlas UV 采样）→ Reinhard + gamma → RGBA8
      atomic 计数器回读吞吐（Mray/s 实测）
    present_kernel — 2.3 新增：累积 HDR → 呈现缓冲（tonemap + 上屏布局）
  PngWriter — 手写零依赖 PNG 编码器（不依赖 java.desktop）
```

## 现场

真机运行记录存档于 `docs/chapter2-pathtrace-field/`（回收后写入）。
