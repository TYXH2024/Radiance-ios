# pathtrace — 第二章 2.2/2.3 阶段交付物（Metal 直连 · 实时路径追踪 + GL 合成）

> 设计文档：`docs/chapter2-pathtrace-design.md`
> 前置：2.1 `scene-ingestion` v0.5 真机判定 SCENE_INGEST_OK——摄取契约已盖章。
> **v0.1 真机回收（2026-10-28 01:08）：判 PLACEMENT_FAIL**（六向全 miss、哨兵 3/3、相机区块已摄取且 verts=2344 与 2.1 一致）——离线逐函数比对确认 fork 与 2.1 数学等价，v0.2 按零粉饰纪律装判别仪器（不盲改）。
> **v0.2 真机回收（2026-10-28 02:23）：仍判 PLACEMENT_FAIL，但仪器已定谳**——LIVE TLAS/BLAS/矩阵/摆放全部正确（ISO=LIVE 一致，3/6 命中、脚下地板 1.62、hitPoint 与 v0.5 完全一致），S6 全 miss 是探针通道自身缺陷；隔离渲染出图 + 62.40 Mray/s。**v0.3：探测线程重锚 + A/B 复测自证 + albedo 回读仪器。**现场记录：`docs/chapter2-pathtrace-field/`
> **v0.3 真机回收（2026-10-28 03:25）：判 PATHTRACE_OK——2.2 关闭。**重锚机制当场坐实：首轮六向全 miss → 探测线程 TLAS 重锚（refreshTlasNative）→ 复测 3/6 命中（+X/-Y/-Z 整数格线全中，miss 三向 512 格内确无几何），ISO/LIVE 逐向一致（TLAS 组装健康）。离屏首帧 480×270 / 24 spp / 2 bounces → 77.7 ms、4,990,055 queries、camHit=45.7%、**64.19 Mray/s 实测**。**2.3 开工：实时累积 + GL 合成 + atlas 采样（本版）。**
> **v0.4 真机回收（2026-10-28 14:22/14:24）：判 COMPOSE_DEAD + live 全天空——四缺陷定谳（report + PNG 像素级分析）→ v0.4.1。**
> ① GL 合成器初始化即死：`glGetTexLevelParameteriv` 反射签名误写 `(int,int,int)`（LWJGL 3.3.3 无此重载，出参是第 4 个 `int[]`）→ 屏幕从未上屏；② live 相机射线 ~100% miss：worker 线程重建的 TLAS（builds≥10）全 miss，同场景同姿态探针线程构建的（8/9）100% 命中（S6.5/S7）——TLAS 构建职责移交探针线程；③ camHit% 分母误用累计 sppTotal → 真命中率被抹成 0.0%；④ `dispatchThreadgroups:` 把总尺寸当组数传 → 256× 线程过量派发。现场记录：`docs/chapter2-pathtrace-field/`
> **v0.4.1 真机回收（2026-10-28 17:11）：判 LIVE_OK_WITH_GL_ERRORS——合成签名修复生效（blit=3565，上屏管道活），但全 miss 转入新形态 → v0.4.2。**时间线：camHit 1s=100%（该帧编码于 atlas 上传之后——atlas 绑定被排除为即时毒源）→ (1s,2s) 窗口内永久归零；worker 哨兵 2s/12s/32s 全 0/6（含姿态健康、帧率正常）；探针 30s 重锚 TLAS#9 后仍全 miss；55s PNG=天空+太阳（pitch=90 俯视却见天=射线全穿透）。GL 侧：glErrors=10 实为计数器封顶值 + 屏幕底部 ~1/10 花/黑/彩条纹 + 视觉判读=游戏原画面透出（blit 覆盖不全）。v0.4.2 仪器+防御包：探针交叉复测 [probe-sentinel]、atlas 二分开关、GL 错误分类学+首帧几何、场景锁（castRayNative 此前无锁读 g_tlas = UAF 窗口）、开关语义修复（-Dpathtrace.compose=false 此前实际关不掉）、墙钟统计。现场记录：`docs/chapter2-pathtrace-field/`
> **v0.4.2 真机回收（2026-10-28 17:55）：camHit 恢复（35.7%）+ 真噪点出屏，但 live ~1.5s 后 SIGSEGV 闪退 → v0.4.3。**TLAS 构建职责迁移修复坐实：camHit=35.7%（v0.4.1 永久 0.0%→本版恢复）、atlas 上传前后哨兵均 3/6（bind 冲突/相机污染两假说双排除）、102 f/s / 82.89 Mray/s。闪退归因（hs_err 铁证）：渲染线程 GlComposer.present → glTexSubImage2D → OSMesa/zink `_platform_memmove` **读源侧** SEGV_ACCERR，寄存器拼图 317216+201184=518400 恰为整帧字节数——源指针被正向平移出缓冲尾部；LWJGL 3 不做 buffer 边界检查，native 越界直接杀进程。统一根因判读：v0.4.2 只重置 UNPACK ROW_LENGTH/ALIGNMENT、**漏 SKIP_PIXELS/SKIP_ROWS**——遗留 SKIP 把上传源正向平移：v0.4 底部花屏条（错位行）与 v0.4.2 SIGSEGV（平移撞保护页）同源。v0.4.3 四重防御：完整 UNPACK/PACK 五元组归零 + Java 容量闸门 + 分段上传（≤64 行/段）+ native staging 头尾 64KB guard pad。现场记录：`docs/chapter2-pathtrace-field/`
> **v0.4.3 真机回收（2026-10-28 17:55/18:20 双 run）：防崩生效（进程存活、skipRows=112 现场捕获坐实）但两新 P0 → v0.4.4。**①黑屏：每帧 chunked texSubImage2D 首段 GL_INVALID_ENUM → break 弃帧（texSubErr=2815/2816 帧全灭）→ RT 纹理永黑 = 用户"黑块"；根因：present 缺预排空——进队早于本帧的错误被段 0 后第一个 glGetError 误记到上传头上（v0.4.2 同采样 89s 仅 10 错 vs v0.4.3 每帧必错，时间线直指每帧新增的五元组读写）。②TLAS 毒化：live ~1s 后全员归零（worker+probe 双 0/6）；30s 探针重建后 probe 2/6 恢复而 worker 仍 0/6；同 518400 全 miss 查询从 ~10ms 涨到 ~77ms（慢 miss = 垃圾遍历签名）。v0.4.4：present 四件套（预排空/pname 掩码/首帧归因梯/段级重试）+ captureAtlas PACK 回读验证 + g_verifiedTlas 双句柄 A/B + TLAS 身份/耗时仪器 + 自愈重锚 [S8.7]。现场记录：`docs/chapter2-pathtrace-field/`
> **v0.4.4 真机回收（2026-10-28 19:45，A20 Pro——文件名"M3-"系误标，设备以 S8.1 行为准）：判 LIVE_OK——黑屏 P0-1 修复坐实；TLAS 毒化定谳为【时间级】非线程级 → v0.4.5。**①黑屏已修：归因梯全 0x0000、心跳零真错（texSubErr=0 chunkFail=0）、blit=4753 帧上屏、进程全程存活、retryOk=4862（全部为排空前遗留误记，无一真失败）——四件套直接闭环。②毒化定谳：同一 TLAS#30（探针 30s 重建）：探针线程 30.05s 查询 2/6 命中，**32s 探针（交叉复测 #3）+worker 双 0/6**；TLAS#29 同样 atlas 后 ~1s 由活转死（epoch=1 即时 2/6 → 1.5s 双亡）——**新建 AS 在 ~2s 内被原地打坏，与查询线程无关**；残存 camHit=0.6% 与快照 0.40% 非平坦像素（相机正下方一小片几何，15s/55s 同形）精确对应 = 稳定部分态。用户"灰蒙渐变+中途一次重置再渐变" = camHit 归零后全屏地平线色 + S8.4 30s 重锚（非 S8.7——阈值 bug：camHit<0.5 被现场 0.6% 残存卡死，自愈全程未触发）。v0.4.5：实例普查 [census] + 共享内存审计 [audit]（实例缓冲影子 memcmp + 64KB 金丝瑞）+ 健康监视器 [tlas-health]（250ms 金丝瑞射线+死亡时刻事件关联）+ S8.7 阈值 0.5→2.0 修复 + 5s 自愈冷却。现场记录：`docs/chapter2-pathtrace-field/`
> **v0.4.5 真机回收（六份：A20 Pro×3 + M3×3，归档.zip 2026-10-28 16:30）：三症状同根定谳——相机段被数组序饿死 → v0.4.6 摄取相机距离近先。**用户实测"视角附近四五格的方块不渲染"即 v0.4.4 起"检测不到脚底"同一件事：相机自身区块段从未进入 TLAS（index = relZ·408+relY·17+relX 回绕布局，相机段落数组尾 408 槽；旧摄取数组序 + 5-6 段/s + 35s 窗 ⇒ 永不抵达）。零负 z rebuild 铁证；探针 +Z 命中踩在缺失条带边界 z=1.00（眼前 5.4 格 void）。现场记录：`docs/chapter2-pathtrace-field/`
> **v0.4.6 真机回收（M3，2026-10-29 09:10）：近景 void 修复验证通过；TLAS 毒化显形为【重建后 ~1s 即死】精确定时器签名 → v0.4.7 五段二分梯。**①`[camera-first] 相机段 (0,64,-16) 已摄取（rebuild #1）`——首轮入库；每次重建后探针 4/6：**-Y 脚下 1.62 格命中**、±Z 1.5 格——用户两症状同根消除。②毒化真面目：复活 t=8.86→死 9.86、19.92→20.92……每次重建存活精确 ~1.0s（v0.4.1 已埋下同签名：camHit 1s=100% → (1s,2s) 归零）；audit 持续双净（实例缓冲+金丝雀）⇒ GPU 私有内存写手；census 445/752→0/752（2.4s 渐进全灭）⇒ 持续型写入；摄取 5-6 段/s 持续到会话尾（BLAS 池写同为候选）。**v0.4.7：五段单调摘除二分梯（A 基线→B 关 atlas→C 关合成→D 停摄取→E 停派发，每段 40s，换段即重锚+census+段判定行）——单次真机运行锁定写手**；死亡行新增 build 年龄=；`-Dpathtrace.bisect=false` 可关。现场记录：`docs/chapter2-pathtrace-field/M3-v0.4.6-report.txt`
> **v0.4.7 真机回收（M3，2026-10-29 13:15）：五段梯判词全出——四条自有 GPU 管线全部洗清，写手在驱动层/仅存仪器 → v0.4.8 跑赢死神。**B 段（关 atlas）canary 死亡 87%、C 段（关合成）83%、D 段（停摄取）85%、**E 段（停派发——仅剩金丝雀/哨兵/census 在岗）仍死**（build#49/#50/#51 各活 2.1/2.0/2.0s）——毒化与 Radiance 自身四条 GPU 管线无关；死亡年龄全程 1.0-2.2s、与查询剂量无关（census 803 射线不加速死亡）⇒ 时间门控外部写手（驱动层：zink/MoltenVK/原版渲染对 AS 私有内存踩踏）；AS 为驱动托管分配（newAccelerationStructureWithSize）——自配短寸不可能。用户屏上首次见到持续路径追踪画面（活窗 camHit 69.2%、峰值 99.95 Mray/s）——但靠自愈重锚硬撑（20+ 次循环）。**v0.4.8 工程解：①场景纪元（同内容重建不清累积）+ ②ping-pong 替换件（500ms 强制同内容重建——替换件年龄恒 <0.5s，写手 1.0s 最小得手延迟永远追不上）+ ③毒帧守卫（camHit<2% 即 store 覆写）+ ④孪生零接触终局实验（零接触第二 AS，20s 后单次电池定谳接触必要性）+ ⑤维护环（探针线程不再退出——旧版 deadline 后 TLAS 永久死亡 bug 顺带修复）。**现场记录：`docs/chapter2-pathtrace-field/M3-v0.4.7-report.txt`

## 取成品（两个通道，内容相同）

| 通道 | 位置 | 说明 |
|---|---|---|
| **A · 工作流运行页成品文件** | Actions → `chapter2-pathtrace` → 任意绿勾运行 → 页面底部 **Artifacts** 区 → `pathtrace-jar` | 每次绿勾必有；zip 内含 `pathtrace-0.4.8.jar` + dylib + metallib。**运行中途被取消的运行没有 artifact**（也不会发布 Release——可能遗留草稿） |
| **B · GitHub Release（正式交付）** | https://github.com/TYXH2024/Radiance-ios/releases/tag/pathtrace-v0.4.8 → `pathtrace-0.4.8.jar` | 幂等 `--clobber` 重传；若 Releases 页看不到，先检查页面底部"Drafts"区（被取消的运行可能遗留未发布草稿） |

> v0.1 首发 workflow 遗漏了 artifact 上传步骤（2.0/2.1 均有）——导致"绿勾了但运行页看不到 jar"。v0.1.1 已补齐：**以后看绿勾运行页最下方的 Artifacts 即可直接下载**。

## 使用（2.3 实时版）

1. 删除 `mods/` 内旧探针 JAR（scene-ingestion / metal-rt-probe / rt-probe / 旧版 pathtrace），放入 `pathtrace-0.4.8.jar`——同存档、同启动器档位（Vulkan/MoltenVK 档，OSMesa 宿主实测兼容）。**回报文件名按设备命名（如 A20Pro-default.txt）**——v0.4.3/0.4.4 的"M3-"前缀实为 A20 Pro 运行（设备以报告 S8.1 行 renderer 为准），教训立此存照。
2. 进入世界后：摄取约 19s → 离屏首帧（2.2 链路原样跑一遍，落 `pathtrace_frame.png`）→ **实时会话自动开启（S8）——游戏画面自此被路径追踪帧整帧替换**（480×270 线性放大至窗口分辨率）。
3. **v0.4.8 起为【孪生定谳 + ping-pong 稳定协议】（取代 v0.4.7 五段梯）：静止同一姿态，保持游戏开着——t=10s 孪生装填（零接触第二 TLAS），t=30s 孪生定谳 + ping-pong 激活——此后画面应持续收敛不再脉冲。**前 30s 仍是基线死亡循环（周期性噪点→冲刷，属预期——对照用）；**t=30s 起是 ping-pong 时代：替换件年龄恒 <0.5s，写手追不上，camHit 应恒 ~69%**。会话无限运行（探针线程维护环活到游戏退出），随时退出均可。
4. 停留 **≥90s**（观察窗收口判定块写入），想看收敛质量可多留（spp 持续增长）。
5. 回报 game 目录下四个文件：**`pathtrace_report.txt`**（v0.4.8 重点行：**[twin] 定谳**（孪生电池命中数——0/6 ⇒ 外部写手铁证；≥3/6 ⇒ 射线接触是杀伤必要条件）、**[pingpong] 激活** + 每 10s 一条同内容重建行、**[v0.4.8 收口]**、[live] 行 camHit 恒 ~69% / spp 持续增长 / **epoch=** 栏（内容变化才清累积）、[tlas-health] 基线期死亡行 age= 对照）+ **`pathtrace_frame.png`** + **`pathtrace_live.png`**（15s 基线期）+ **`pathtrace_live_final.png`**（55s ping-pong 时代——**两张快照的对比即本版交付证据**）。**若闪退**：iOS Documents 根目录的 `hs_err_pid*.log` + `fatal_trace.txt` + `ame273_crash_report.txt` 一并回报。
6. **开关**：`-Dpathtrace.pingpong=false`（关替换件——死亡循环行为保留，对照用）；`-Dpathtrace.twin=false`（跳过孪生窗口，ping-pong 提前至 t=10s 激活）；`-Dpathtrace.bisect=true`（复现 v0.4.7 五段梯实验）；旧 `-Dpathtrace.atlas=false` / `-Dpathtrace.compose=false` 仍有效。

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

> 归因开关（v0.4.2 语义修复——此前 `Boolean.getBoolean` 反转导致 =false 实际关不掉）：`-Dpathtrace.disabled=true` 或 `PATHTRACE_DISABLED=1` → 只写环境快照、零原生调用（闪退 A/B 对照用）；`-Dpathtrace.compose=false` → 实时累积照常、仅不上屏（合成通道 A/B 对照用）；`-Dpathtrace.atlas=false` → 跳过 atlas 采集（albedo 顶点色回退——atlas 上传链 A/B 对照用，v0.4.2 新增）。

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
