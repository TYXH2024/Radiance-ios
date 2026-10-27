# chapter2/pathtrace field reports（2.2 真机现场）

## M3 / pathtrace v0.1.0 — 2026-10-28 01:08（判 PLACEMENT_FAIL）

**报告**: `M3-v0.1.0-report.txt`（同目录）

### 事实
- S0–S5 全绿：dylib 装载、66 项符号审计、600 槽位网格、顶点格式取证
  （stride=32 / colorOff=12 ×4 layers）、36 次重建 / 69014 三角 / TLAS×6。
- 哨兵 3/3 PASS（位码=7：单位实例 / 平移实例 / 阴性对照——矩阵编码
  flat[9..11] 真机再证）。
- **S6 六向全 miss**（脚下地板=无、整数格线=0）→ S7 被闸 → PLACEMENT_FAIL。

### 离线判读（严谨方向，非粉饰）
1. **摄取覆盖塔缩**：36/157（对比 2.1 v0.5 同场景 174 重建）。y 带直方图
   证据：v0.5 覆盖 y∈[-64..112] 全部 12 层，v0.1 只扫到 y∈[48..112] 顶 5 层。
   机制：首轮 poll 在世界加载未完成时把低 y 层槽位试成空（region null /
   空 buffers）→ invalidate 置 generation → v0.3 的 neverIngested 规则视其
   为"已试"→ 永不再试。2.1 运气好（首 poll 时世界数据已就绪），v0.1 时序
   不同即触发。**v0.2 修复：空试槽位窗口内每 2s 重试（上限 6 次）。**
2. **摆放全 miss 无法静态归因**：逐函数 diff 2.2 fork vs 2.1 v0.5 真机验证
   实现——提取（Java+native 字节级）、build_blas、encode_instance_matrix、
   trace_ray、scene_trace_kernel（MSL 逐行）、S6 电池——全部等价；摆放数学
   （存储 local + t=origin）与 2.1 MEASURED（存储 local−origin + t=2·origin）
   代数恒等。相机区块 (16,80,0) verts=2344 与 2.1 完全一致且在最终 TLAS
   （36 实例对账无净失效）。**静态分析已穷尽 → v0.2 装判别仪器**：
   raw-AABB 审计（v0.1 fork 丢失的 2.1 仪器）/ 相机 BLAS 顶点回读 /
   live 实例矩阵回读 / 隔离单实例 TLAS 对照电池 / 隔离渲染诊断通道
   （成图即渲染链全链健康 + 第一帧光）。

### 附注
- latest.log 无新增崩溃路径（01:08:56 Task251 为正常自退出看门狗）；
  fatal_trace 中 10-27 14:35/14:36 两次 abort 为 libmobileglues glGetError
  已知启动器固有崩溃（第一章 v1.2 已定案归因），与本探针无关。

## M3 / pathtrace v0.2.0 — 2026-10-28 02:23（判 PLACEMENT_FAIL；仪器定谳：场景与渲染链正确，S6 探针通道自身缺陷）

**报告**: `M3-v0.2.0-report.txt`（同目录）· **首帧**: `M3-v0.2.0-frame.png`（480×270/24spp/2bounce ISO 诊断帧）

### 事实
- S0–S5 全绿：66 符号、600 槽位、**174 重建 / 157 BLAS / 168,982 三角 / TLAS×10**
  ——v0.2 覆盖修复实证生效（y=-64..112 全 12 层，与 2.1 v0.5 同覆盖量）。
- 哨兵 3/3 PASS。COLOR 全量提取（每次 rebuild color=yes）。
- **S6 六向全 miss（0/6）**→ PLACEMENT_FAIL → 隔离渲染通道出图。
- **S6.5 四台仪器定谳**：raw AABB=SECTION-LOCAL（2.1 契约成立）；相机区块
  顶点回读=真实地形；live 实例矩阵 flat[9..11]=(16,80,0) 正确；**ISO 与 LIVE
  电池完全一致——3/6 命中、3 条整数格线、脚下地板 1.62**（hitPoint
  27.00/91.00/6.00 与 2.1 v0.5 完全一致）。⇒ **LIVE TLAS/BLAS/矩阵/摆放全部
  正确，S6 的 0/6 是探针通道自身缺陷，PLACEMENT_FAIL 为误报。**
- S7 ISO 渲染：106.8ms / 6,665,304 queries / **62.40 Mray/s 实测**；图像统计
  mean=0.627、range 0.594..0.672（旧门槛 FAIL——但 ISO 门槛不适用，见判读）。

### 离线判读（零粉饰）
1. **S6 vs S6.5 矛盾的归因**：同一 `trace_ray`（与 2.1 逐字节一致，离线 diff
   证实）、同一 g_tlas、同一原点同一方向——经 JNI `castRayNative` 调用全 miss
   （v0.1/v0.2 两轮确定性复现），同进程内 C 直调（审计电池，前面有
   build_iso_tlas）3/6 命中。对照 2.1 v0.5：其 ray 电池前总有
   `setPlacementModeNative+frameTickNative`（探测线程上的 TLAS 现场重建）→
   9/14 命中。**判别变量 = 探测线程上是否有新鲜 AS build**（驱动层行为，
   离线不可复现）。⇒ **v0.3：refreshTlasNative（探测线程重锚）+ A/B 复测
   自证（首轮 miss → 重锚 → 复测，两轮行都在报告里）+ live 渲染前同样重锚**
   （renderPathtraceNative 直查 g_tlas，与 S6 同通道——不重锚会渲染出纯天空帧）。
2. **首帧 PNG 判读**（离线像素级分析）：370 精确色（非 6 色——4-bit 量化才
   是 6）、纵向单调微梯度、±1 nibble 噪声场——与"天空渐变 + 底缘地板 +
   spp 抖动"一致；且 queries=6.67M/3.11M 采样=2.14 段/采样 ⇒ **≥57% 相机
   ray 命中**（配合审计证据：+X 0.5 格处有方块面——相机贴着山坡仰视 21°）。
   ⇒ **渲染链（相机 ray→TLAS→shading→tonemap→PNG）全链健康**；无绿无结构
   的疑点指向 albedo 内容（COLOR 元素究竟带了什么字节）——**v0.3 加 COLOR
   回读仪器 + camHit% 计数器**，2.3 的 atlas 采样决策看这一行证据。
3. **审计电池标签缺陷**（已修）：dir[3]=(0,-1,0) 曾误标 "+Y(上)"、dir[4]=(0,0,1)
   误标 "-Y(下)"——报告里 "+Y(up): HIT dist=1.62 hitPoint y=91.00" 物理上是
   -Y 向下命中地板（与 v0.5 一致），标签说谎但物理证据一直正确。

### 结论
场景摄取、BLAS/TLAS 组装、矩阵摆放、渲染管线——**全部真机验证正确**。
距"第一束光线"只差：v0.3 重锚修复让 S6 复测命中 → 摆放 PASS → 全场景
渲染跑起来（PATHTRACE_OK）。
