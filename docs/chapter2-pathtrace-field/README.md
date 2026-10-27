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
