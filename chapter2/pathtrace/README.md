# pathtrace — 第二章 2.2/2.3/2.4 阶段交付物（Metal 直连 · 实时路径追踪 + GL 合成 + 事件驱动摄取）

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
> **v0.4.8 双机回收（M3 79s + A20 Pro 完整 90s 窗，2026-10-29）：ping-pong 时代双机零死亡持续收敛——2.2/2.3 工程闭环；孪生判词分岔（M3 孪生 0/6 死=外部写手 / A20 Pro 孪生存活=射线接触是杀伤必要条件）——两代驱动毒化机制不同，ping-pong 同等压制。**M3：spp 16744、峰值 108.92 Mray/s；A20 Pro：spp 22172、峰值 172.98 Mray/s（双破纪录）、wall 93.3 f/s。用户首张真机屏摄三问（阴影✓ / 材质模糊 / 噪点）+ VLM 屏摄鉴定 → v0.4.9 画质轮。现场记录：`docs/chapter2-pathtrace-field/M3-v0.4.8-report.txt` + `A20Pro-v0.4.8-report.txt`
> **v0.4.9 CLARITY（画质轮）：四刀——①分辨率 480×270 → 720×窗口比（像素密度 ×1.84、射线预算守恒：spp/帧 4→2；宽高比随窗消 16:9→19.5:9 横向拉伸）②atlas LINEAR → NEAREST（原版 MC 像素质感回归 + 消格间 bleed；远处走样由累积抖动带限）③噪点双修（早期收敛加档：重启后头 512 spp ×4 档/帧；firefly 拆分：间接逃逸射线不再采样太阳盘——NEE 已计太阳直射）④tonemap：Reinhard extended 白点 W=2（消屏摄“灰雾/发闷”判词）。孪生/ping-pong 协议不变。**
> **v0.4.9 真机回收（A20 Pro 完整 90s 窗，2026-10-29 18:11）：LIVE_OK——画质四刀全部生效，用户判词「画质可以了（原来的感觉像抗锯齿）」、阴影 ✓。**ping-pong 时代（30-104s）零死亡、camHit 恒 99.7-100%、spp 6404 仍单调收敛、稳态吞吐 ~107-110 Mray/s（加档窗峰值 194.6）、resets 30s 后冻结 2430、compose 心跳全零错。基线期（0-30s）5 次死亡（年龄 1.32/1.25/1.26/1.78/1.48s——定时器签名第 8 轮）、死亡屏幕 episode 1.6/3.9/6.3/9.0/~4s = 用户反馈「开场闪几次、每次 5-10s、灰蒙/天空色」的实锚；孪生 0/6（与 M3 同型——本轮接触无关型，与 v0.4.8 A20 接触必要性型并列归档）。剩余两问（树叶不透明 / 开场闪屏）→ v0.5.0 CANOPY。现场记录：`docs/chapter2-pathtrace-field/A20Pro-v0.4.9-report.txt` + 三 PNG
> **v0.5.0 CANOPY：两刀——①树叶/玻璃/草丛 alpha 镂空（cutoutTrace：命中后采样 atlas alpha，<0.5 纹素视为孔洞，射线推进 tMin 重查 ≤8 轮；阴影射线同样处理 ≤6 轮——树影斑驳、树冠见孔；UV 哨兵层/白纹理回退天然不透明；scene_trace_kernel 仪器逐字不动）②ping-pong 自 t=0（孪生默认关——双机定谳已归档；-Dpathtrace.twin=true 恢复 90s 取证协议）——开场闪屏（基线期死亡循环）自本版起不存在。噪点按用户指示不动（性能优先）。**
> **v0.5.0 真机回收（M3 完整 90s 窗，2026-10-29 19:30）：LIVE_OK——用户判词「没看出啥毛病」，画质三问全部收口。**resets=0 全程（会话统计 frames=1594 spp=3572）——开局闪屏根除实锚（对照 v0.4.9 同类会话 resets 冻结 2430）；窗内 canary 死亡 0、census 五轮零新亡、1.0-2.2s 定时器死亡签名零出场；树叶镂空生效（树冠见孔、树影斑驳）；M3 稳态 45-49 Mray/s（cutout 重查全计入）、camHit 68.1% 恒定、wall ~20 f/s；compose 心跳 #256 全零错、SKIP 污染首帧自愈。2.3 三件事闭环（渐进累积/GL 合成/atlas albedo）→ 2.4：事件驱动摄取（探针转正）、移动相机累积、GI/emissive、实体 BLAS。现场记录：`docs/chapter2-pathtrace-field/M3-v0.5.0-report.txt` + 三 PNG
> **v0.5.1 EVENT-DRIVEN（2.4 第一刀：探针转正）：一刀——事件驱动摄取。**①mixin 事件泵：PathtraceBuiltChunkMixin 钩 ChunkBuilder$BuiltChunk.setData（class_851#method_60908 HEAD——vanilla 发布新 mesh 的唯一写点，即旧轮询 dirty 判定观察的同一信号源；intermediary 字面名 + remap=false 零 refmap 直命中，yarn 1.21.4+build.8 核验），发布即入无锁队列（渲染线程 O(1)，OFF 态一次 volatile 读）——发现延迟 500ms → ≤50ms；②摄取线程常驻：窗口结束后不再 return——Pathtrace-Ingest daemon 无限运行（事件批 ≤8/轮 + 20ms 节流 ≈ 400 段/s 防切渲染距离 flood；TLAS tick 1s 聚合；世界退出挂起、重进后哨兵全量重扫）——世界变化持续入库：rebuild → epoch 递增 → live 累积按需重收敛（物理正确）；RT 世界覆盖从开局 ~208 段 → 全量（v0.5.0 真机：208/6936 入库、积压 6572 = 窗口死亡的证据链）；③2s 哨兵兜底：全量 dirty 扫描（重定位/失效/未摄取远段——事件通道不覆盖的三类信号；事件成功后同步槽位快照防重复摄取）；mixin 未命中自动升回 500ms 全量轮询 = v0.5.0 等价回退，**永不退化**；④零 native 改动（rebuildSingleNative/invalidate/frameTickNative 任意时刻可调——S8.4 重锚与 ping-pong 已实证 live 期间并行安全；S7 离屏帧与 scene_trace_kernel 逐字不动）。**

> **v0.5.2 PACE（可玩性轮——回应用户真机判词「玩不了，M3 上 1～2 帧」）：四刀断根因链。**定谳（数字账，M3 实测锚 45-49 Mray/s）：**①移动即加档**——相机一动 → 累积清零 → 早期加档窗自动再装填 → 每帧按 8 spp 派发（720×332×8 样本 ×3.5-7 射线/样本（树冠 cutout 重查）= 6.7-13.4M 射线/帧 ≈ 137-290ms/帧 = 3-7 FPS，且下一帧又清零——4× 预算纯白烧）；**②v0.5.1 摄取 flood**——进世界即全量 ~6936 段 BLAS 构建（@≤400 段/s），恰好撞上玩家开始走动的时刻，GPU 进一步被挤压 → 1-2 帧；**③渲染线程硬等**——livePresentNative waitUntilCompleted + worker 持锁横跨等待——swap 被拖成 RT 节奏，输入都卡。（澄清：live RT 并非 4K——720×窗口比；但分辨率确是本版拿下的杠杆之一。）四刀（PACE 步调控制器，native+Java 双侧）：**刀一 移动降档 spp**——相机在动 → spp/帧封顶 1（native livePaceNative；站定自动解除——早期加档窗恢复全功率收敛，v0.4.9 噪点对策原样保留）；**刀二 动态分辨率梯子**（用户点名）——帧时间 EMA（α=0.25）引导五档：rtW 顶档（默认 720）→ ×0.75 → ×0.5625 → ×0.4375 → ×0.3125 底档（随窗比定高）；超预算（默认 33ms = ≥30 FPS）连续 3 评估降档、富余（<55%）且站定连续 12 评估升档、换档 3s 冷却防质量泵动；换档 = native liveResizeNative realloc + resets++（诚实记账）+ 旧缓冲两段式延迟释放（渲染线程 direct ByteBuffer 悬垂读风险归零——v0.4.2 SIGSEGV 家族预防）；**刀三 摄取让路**——EMA 超 1.5× 预算 → 摄取线程暂停 BLAS 构建（事件不丢只积压，站定富余自动解除后按批补）——进世界 flood 从「挤死渲染」变「自适应让路」；**刀四 呈现非阻塞**——livePresentNative 改 completed 探查 + 快照序列号（RT 慢于 swap 时跳过重复上传只置 blit——GlComposer skipUpload 计数）；liveFrameNative 等待移出 g_liveLock——渲染线程与 RT 节奏彻底解耦，输入不再卡。预期（M3 锚定）：移动 1 spp ≈ 0.84-1.67M 射线/帧 ≈ 18-37ms → **27-45 FPS**；重场景自动落 315-405 档保 30；站定 2 spp 稳态累积、DRS 自动择档。A20 Pro（172 Mray/s）一切顶档足够——DRS 基本不动，无回退。**

## 取成品（两个通道，内容相同）

| 通道 | 位置 | 说明 |
|---|---|---|
| **A · 工作流运行页成品文件** | Actions → `chapter2-pathtrace` → 任意绿勾运行 → 页面底部 **Artifacts** 区 → `pathtrace-jar` | 每次绿勾必有；zip 内含 `pathtrace-0.5.2.jar` + dylib + metallib。**运行中途被取消的运行没有 artifact**（也不会发布 Release——可能遗留草稿） |
| **B · GitHub Release（正式交付）** | https://github.com/TYXH2024/Radiance-ios/releases/tag/pathtrace-v0.5.2 → `pathtrace-0.5.2.jar` | 幂等 `--clobber` 重传；若 Releases 页看不到，先检查页面底部"Drafts"区（被取消的运行可能遗留未发布草稿） |

> v0.1 首发 workflow 遗漏了 artifact 上传步骤（2.0/2.1 均有）——导致"绿勾了但运行页看不到 jar"。v0.1.1 已补齐：**以后看绿勾运行页最下方的 Artifacts 即可直接下载**。

## 使用（2.3 实时版）

1. 删除 `mods/` 内旧探针 JAR（scene-ingestion / metal-rt-probe / rt-probe / 旧版 pathtrace），放入 `pathtrace-0.5.2.jar`——同存档、同启动器档位（Vulkan/MoltenVK 档，OSMesa 宿主实测兼容）。**回报文件名按设备命名（如 A20Pro-default.txt）**——v0.4.3/0.4.4 的"M3-"前缀实为 A20 Pro 运行（设备以报告 S8.1 行 renderer 为准），教训立此存照。
2. 进入世界后：摄取约 19s → 离屏首帧（2.2 链路原样跑一遍，落 `pathtrace_frame.png`）→ **实时会话自动开启（S8）——游戏画面自此被路径追踪帧整帧替换**（720×窗口比渲染；树叶/玻璃/草丛 alpha 镂空——树冠见孔、树影斑驳）。
3. **v0.5.2 起为【可玩协议】：进世界即稳定（v0.5.0）+ 世界变化持续入库（v0.5.1）+ 【动起来不卡】（本版）——验收点就是移动帧率：进世界后走两步、转转视角，帧率应明显回升（M3 预期 27-45 FPS；站定后 DRS 自动升档持续收敛，移动画面噪点增多属帧率取舍——性能优先）。**回退开关：`-Dpathtrace.drs=false`（固定档 = v0.5.1 行为）。
   3. **v0.5.1 起为【活世界协议】：进世界即稳定（v0.5.0 已交付）+ 世界变化持续入库——本版重点恰是【动起来】：挖掘/放置/走远，画面应在 ≤2s 内反映变化（重收敛短暂噪点属物理正确）。回退开关：无（mixin 未命中时哨兵自动等价回退 v0.5.0 行为）。**
   **v0.5.0 起为【进世界即稳定协议】：ping-pong 自 t=0 在岗（孪生默认关）——开场不再有基线期死亡闪屏（v0.4.x 前 30s 周期性全 miss 天空色帧属旧协议预期，本版起不应出现）；若仍出现即回归，回报。**静止同一姿态，保持游戏开着——画面应从噪点单调收敛（camHit 应恒定 ~99-100%）。会话无限运行（探针线程维护环活到游戏退出），随时退出均可。
4. 停留 **≥90s**（观察窗收口判定块写入），想看收敛质量可多留（spp 持续增长）。
5. 回报 game 目录下四个文件：**`pathtrace_report.txt`**（**v0.5.2 新增重点行：[pace] spp/帧封顶（移动 1 / 超预算稳态档 / 0 的变迁序列——移动降档直接证据）、[drs] RT 分辨率（梯位变迁含 EMA 与理由）+ native [drs] resize 行（resets 计数诚实递增）、[pace] 摄取让路 开启/解除（flood 让路自平衡证据）、[ingest] 心跳 …让路 N 轮、[compose] 心跳 skipUpload=N realloc=N（序列号跳上传与 RT 纹理重建）、[S8.0] PACE 参数回显；另请屏摄【移动中画面】一帧——移动噪点与帧率的取舍即本版交付证据**；（**v0.5.1 新增重点行：[ingest] mixin 事件命中（事件通道激活直接证据——未出现即 mixin 未命中、哨兵回退 500ms 轮询，功能零损失但请回报）、[ingest] 摄取线程转常驻（窗口结束后摄取持续）、[ingest] 心跳（事件 +N / 哨兵重建 +N / 队列深度 / 模式 EVENT+SENTRY 或 SENTRY(回退)）、[S8soft] method_60908 行（钩点符号运行时存在性取证）；挖掘验证：挖/放几个方块，画面应 ≤2s 反映变化**；v0.5.0 新增/更名重点行：**[pingpong] 激活 @0s**——开场闪屏消亡直接证据、**[pingpong 收口]**（旧称 [v0.4.8 收口]）——窗内 canary 死亡应为 0；既有重点行照旧：[live] camHit 恒定 / spp 持续增长 / epoch= 栏、[tlas-health] 行（v0.5.0 起全程应无死亡行）、[compose] 首帧几何行 blit_src 应为 720×~332）+ **`pathtrace_frame.png`** + **`pathtrace_live.png`**（**8s**——旧死亡窗内的稳定画面证据）+ **`pathtrace_live_final.png`**（55s——**两张快照对比 + 树冠/玻璃屏摄（树叶应见孔洞、树影应斑驳、玻璃应透）即本版交付证据**）。**若闪退**：iOS Documents 根目录的 `hs_err_pid*.log` + `fatal_trace.txt` + `ame273_crash_report.txt` 一并回报。
6. **开关**：`-Dpathtrace.twin=true`（恢复 v0.4.8/0.4.9 的 90s 取证协议——t=10s 孪生装填、t=30s 定谳）/ `-Dpathtrace.pingpong=false`（关替换件——死亡循环行为保留，对照用）/ `-Dpathtrace.rtW=480`（**v0.5.2 起语义 = DRS 梯顶**；配 drs=false 即旧固定档行为）/ `-Dpathtrace.drs=false`（**v0.5.2 新增**：关动态分辨率——固定档回退）/ `-Dpathtrace.paceBudgetMs=50`（**v0.5.2 新增**：帧预算放宽到 20 FPS 目标——重设备）/ `-Dpathtrace.spp=4`（回退旧稳态样本）；`-Dpathtrace.bisect=true`（复现 v0.4.7 五段梯实验）；旧 `-Dpathtrace.atlas=false` / `-Dpathtrace.compose=false` 仍有效。

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
