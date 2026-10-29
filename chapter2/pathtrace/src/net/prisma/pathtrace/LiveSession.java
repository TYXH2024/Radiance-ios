package net.prisma.pathtrace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 实时累积会话（2.3 v0.4.1）—— RT 工作线程。
 *
 * v0.4.1 重锚职责重划（v0.4 真机判读驱动——report + PNG 像素级分析）：
 *   - 本线程【不再重建 TLAS】。v0.4 现场证据：worker 线程重建的 TLAS
 *     （builds≥10）对 live 相机射线 ~100% miss（15s 快照=纯平地平线色、
 *     55s=水平视角却 0% 地形），而探针线程构建的 TLAS（builds 8/9）同
 *     场景同姿态 100% 命中（S6.5 电池 / S7 渲染）。唯一变量 = 构建线程。
 *     机制未定谳，但实证组合已明确：TLAS 构建 = 探针线程（refreshTlasNative
 *     同款），射线派发 = 本线程。
 *   - 探针线程 30s 定时重锚一次（PathtraceMod 等待环 watchdog，[S8.4] 行）
 *     ——A/B 后半段：探针构建 + worker 查询，前后 [live] 行 camHit 对比
 *     即自证。
 *   - worker 侧哨兵电池（castRayNative 六轴，t≈2s/12s/32s，
 *     [live-sentinel] 行）：直接回答"本线程的求交通道是否看到 TLAS"，
 *     与 [live] 行 camHit 互为印证——若哨兵命中而 camHit≈0，缺陷在 live
 *     帧派发；若两者皆 miss，缺陷在 TLAS/线程层。
 *
 * v0.4.2 增量（v0.4.1 真机判读驱动——camHit 1s=100% → (1s,2s) 窗口内永久归零）：
 *   - 哨兵电池升级：每行回显相机姿态（NaN/漂移直接现形）；新增 atlas
 *     epoch 括号——PathtraceHook 采集完成（成功/降级/跳过均计）后，本线程
 *     下一轮循环（≤8ms）立即打一轮电池，atlas 前后钉在同一时间线；
 *     1.5s 早期点加密括号密度（毒化窗口 (1s,2s) 内必有至少两轮电池）。
 *   - 哨兵 0/6 → 置 PathtraceMod.probeBatteryReq：探针线程交叉复测
 *     （[probe-sentinel] 行）——同一 TLAS 同一组 ray 仅换派发线程，一步
 *     分清“worker 派发路径坏”vs“AS 内容坏”。v0.4.1 全程探针线程在
 *     S6.5 之后再没打过任何 ray，这正是缺失的关键 A/B。
 *   - stats 墙钟化：v0.4.1 的 since-reset 分母在重置风暴下产出 2000/
 *     89000 f/s 假值（分母 <1ms）；新增 wall f/s（Java 侧计帧、墙钟差分，
 *     免疫重置）+ loopHz（循环频率——GPU 锁死/线程饥饿直接现形），
 *     since-reset span 下限 200ms。
 *
 * v0.4.5 增量（v0.4.4 真机判读驱动——A20 Pro 上毒化为【时间级】定谳：
 * 同一 TLAS#30 探针线程 30.05s 命中 2/6、32s 双线程双亡；0.6% 残存
 * camHit 与快照 0.40% 非平坦像素 = 相机本地一小片幸存实例）：
 *   - 健康监视器（[tlas-health] 行）：相机正下方 -Y 金丝瑞射线，首 10s
 *     每 250ms——死亡时刻钉到 ±0.25s 并与 atlas 上传/首帧 ATLAS_RGB/
 *     合成帧序一行关联；变迁即刻共享内存审计。
 *   - 实例普查（[census] 行）：每实例一条 -Y 射线展开 157 实例幸存/
 *     阵亡地图——连续索引区段=TLAS 实例记录线性扫写 / 空间聚集=距离
 *     选择性失效 / 散布=BLAS 层；五点编排（基线/稳态/S8.4 新鲜/再衰减/晚窗）。
 *   - 共享内存审计（[audit] 行 + native hexdump）：实例缓冲 vs 构建时
 *     影子 memcmp + 64KB 确定性图案金丝瑞——共享侧越界写手当场落网。
 *   - S8.7 阈值修复：camHit<0.5 → <2.0（v0.4.4 现场 0.6% 残存命中把
 *     自愈触发器卡死——用户全程只见灰蒙）；+5s 自愈冷却防重建风暴。
 *
 * 循环（~30 Hz 相机轮询 + Metal 异步帧）：
 *   - 相机姿态反射读取（与 S6/S7 同链路）；位移/转角超阈值 → liveResetNative
 *     （下一帧 store 模式覆写累积，静止后重新收敛）
 *   - liveFrameNative 累积一帧（trace+present 单命令缓冲异步提交；上一帧
 *     统计于等待点自然回读）
 *   - 报告行：首 10s 每秒一行（A/B 密度），此后每 5s。camHit% 分母 =
 *     单帧样本数（v0.4 误用累计 sppTotal——真命中率被抹成 0.0%）；
 *     f/s = 距上次重置的帧率（v0.4 除以总时长，重置后假降级 38→3.8）
 *   - 快照（15s/55s 两张 PNG 证据）+ 60s 观察窗收口 → S8.6 判定块
 *     （LIVE_OK / COMPOSE_INACTIVE / COMPOSE_DEAD / ACCUM_STALLED /
 *     LIVE_FAIL）→ windowDone 置位；会话继续运行直到游戏退出
 *
 * 退出世界容错：mc.world == null / 相机读失败 → 暂停累积（报告一次性
 * 注记），回到世界自动恢复（累积重置）。
 */
final class LiveSession implements Runnable {

    /** 观察窗时长（ms）——报告判定块在此窗口末写入。v0.5.0: 90s 全程
     *  ping-pong 在岗（孪生默认关——双机定谳已归档；-Dpathtrace.twin=true
     *  恢复 v0.4.8/0.4.9 的 90s 取证协议：0-10s 基线 + 10-30s 孪生 +
     *  30s+ ping-pong）。 */
    private static final long LIVE_WINDOW_MS = 90_000L;
    /** v0.4.8: 孪生零接触窗口——t=10s 装填，t=30s 定谳 + ping-pong 激活。
     *  v0.5.0 起仅取证复现模式（-Dpathtrace.twin=true）使用。 */
    private static final long TWIN_ARM_MS = 10_000L;
    private static final long TWIN_CHECK_MS = 30_000L;
    /** 快照时刻（ms，窗内）：早期收敛证据 + 高 spp 收敛证据。v0.5.0:
     *  SNAP1 8s——落在 v0.4.x 基线死亡窗内（旧协议 0-30s 的死亡闪屏期），
     *  ping-pong 自 t=0 在岗后该时刻应已有稳定画面（开场无闪屏证据）。 */
    private static final long SNAP1_MS = 8_000L;
    private static final long SNAP2_MS = 55_000L;
    /** 工作线程帧间隔（ms）——Metal 侧自身串行化，此为节流。 */
    private static final long FRAME_SLEEP_MS = 8L;
    /** 相机移动阈值（格）：低于此视为静止（累积继续）。 */
    private static final float POS_EPS = 0.02f;
    /** 相机转角阈值（度）。 */
    private static final float ROT_EPS = 0.05f;
    /** 周期行间隔（ms）：首 10s 每秒（A/B 密度），此后 5s。 */
    private static final long REPORT_EARLY_MS = 1_000L;
    private static final long REPORT_EVERY_MS = 5_000L;
    /** 哨兵电池时刻（ms）：atlas 前基线（首循环）+ 1.5s 早期 + 重锚前基线 ×2 + 探针 30s 重锚后 ×1。
     * v0.4.4: +34s 延迟复测——毒化半衰期测量（探针 30s 重锚后 2s/4s 两点，
     * 若 32s 双电池命中而 34s 归零 ⇒ TLAS 被反复毒化 = 持续型写入凶手）。 */
    private static final long SENTINEL_EARLY_MS = 1_500L;
    private static final long SENTINEL_1_MS = 2_000L;
    private static final long SENTINEL_2_MS = 12_000L;
    private static final long SENTINEL_3_MS = 32_000L;
    private static final long SENTINEL_4_MS = 34_000L;
    /** v0.4.4: 连续全 miss 报告行数 → 自愈重锚（早期 1s 行距 ⇒ 毒化后 ~2-3s 即重建）。 */
    private static final int MISS_STREAK_TRIGGER = 2;
    /** v0.4.4: 交叉复测请求冷却（ms）——每轮死电池都能拿到新鲜 probe 对照。 */
    private static final long PROBE_REQ_COOLDOWN_MS = 5_000L;
    // ---- v0.4.5 毒化定谳仪器（v0.4.4 现场定谳：时间级毒化——同一 TLAS
    // 探针线程重建后 0.05s 命中 2/6、32s 双线程双亡；S8.7 未触发系阈值
    // bug：camHit<0.5 而现场卡在 0.6%——残存命中=相机本地一小片实例）----
    /** 金丝瑞 -Y 射线检查密度（ms）：首 10s 每次 250ms，此后 1s。 */
    private static final long HEALTH_EARLY_MS = 250L;
    private static final long HEALTH_LATE_MS = 1_000L;
    /** v0.4.5: 自愈重锚最小间隔（ms）——防止阈值放宽后的重建风暴，
     * 每次自愈给用户 ~1-2s 真画面 + 一次毒化半衰期测量。 */
    private static final long HEAL_COOLDOWN_MS = 5_000L;
    /** v0.4.5: camHit 归零阈值（%）——v0.4.4 的 0.5 被现场 0.6% 残存命中
     * 卡住（S8.7 全程未触发）；2.0 覆盖残存命中量级，仍远低于健康值。 */
    private static final double CAMHIT_DEAD_PCT = 2.0;

    // ---- v0.4.7: 毒化写手五段二分梯（单调摘除，换段即重锚）----
    /** 观察窗（ms）：二分梯=五段+尾量；否则 v0.4.6 原 60s。 */
    private final long windowMs = PathtraceMod.bisectEnabled()
            ? PathtraceMod.bisectWindowMs() : LIVE_WINDOW_MS;
    /** 二分梯是否启用（构造时定格——与探针线程 deadline 一致）。 */
    private final boolean bisectOn = PathtraceMod.bisectEnabled();
    /** 当前段（0=A … 4=E；收口后重5）。 */
    private int bisectPhase = 0;
    /** 收口标志（E 段结束打总收口行）。 */
    private boolean bisectDone = false;
    /** 段内 canary 采样分母/分子（healthCheck 每次 tick 计数）。 */
    private int phaseHealthTicks = 0;
    private int phaseHealthDeadTicks = 0;
    /** 段内 camHit 归零采样（reportProgress 每 1s/5s 行计数；E 段不适用）。 */
    private int phaseCamHitSamples = 0;
    private int phaseCamHitZeros = 0;
    /** 各段 canary 死亡比快照（收口行汇总用）。 */
    private final int[] phaseDeadPct = new int[6];

    private final ProbeReport r;
    private final long startNs = System.nanoTime();

    /** 探针线程等待的报告完成标志（观察窗结束）。 */
    volatile boolean windowDone;

    private float lastX;
    private float lastY;
    private float lastZ;
    private float lastYaw;
    private float lastPitch;
    private boolean poseValid = false;
    private boolean worldPauseEchoed = false;

    /** Java 侧镜像 native 帧计数（检测重置时刻——f/s 分母修复）。 */
    private long lastNativeFrames = -1;
    private long lastResetMs = 0;

    // ---- v0.4.2 墙钟 stats（免疫重置风暴）----
    /** Java 侧 liveFrameNative 调用计数（从不重置——wall f/s 分子）。 */
    private long wallFrames = 0;
    /** 上一报告行的墙钟时刻（ms）。 */
    private long lastReportWallMs = 0;
    /** 循环迭代计数（loopHz 分子）。 */
    private long loopIters = 0;
    /** 已见过的 atlas epoch（变化 → 括号电池）。 */
    private int lastAtlasEpoch = 0;
    /** 首轮 atlas 前基线电池是否已打。 */
    private boolean preAtlasSentinelDone = false;

    // ---- v0.4.5 健康监视器/普查/审计状态 ----
    private long lastHealthMs = -1;
    private boolean healthAlive = true;
    private boolean healthEchoed = false;
    /** atlas epoch 变化时刻（死亡行关联事件）。 */
    private long atlasEpochMs = -1;
    /** 首帧 ATLAS_RGB 时刻（st[7]==1 首次为真——死亡行关联事件）。 */
    private long firstAtlasFrameMs = -1;
    private boolean atlasFrameSeen = false;
    /** 基线普查（atlas 前）结果。 */
    private int[] censusBaseline = null;
    private long censusBaselineMs = -1;
    private boolean censusBaselineDone = false;
    private boolean censusSteadyDone = false;
    private boolean censusFreshDone = false;
    private boolean censusDecayDone = false;
    private boolean censusLateDone = false;
    /** 最近一次自愈重锚请求时刻（冷却用）。 */
    private long lastHealReqMs = -1;

    // ---- v0.4.8: 孪生零接触窗口 + ping-pong 激活 ----
    private boolean twinArmed = false;
    private boolean twinChecked = false;
    /** 会话内 canary 死亡次数（孪生定谳行的对照数据）。 */
    private int windowDeaths = 0;
    /** 孪生定谳回显（finishWindow 收口行引用）。 */
    private String twinEcho = "未运行";

    // ---- v0.5.2 PACE：步调控制器（移动降档 / 动态分辨率 / 摄取让路）----
    /** 帧时间 EMA（α=0.25，ms）——含 liveFrameNative 内部等待上一帧 ≈ GPU 帧时间代理。 */
    private double paceFrameMs = 0.0;
    /** 已下发的 spp 封顶（-1 = 未下发——首帧即同步一次）。 */
    private int paceSppCapSent = -1;
    /** 摄取让路状态（变迁打行 + 写 PathtraceMod.gpuPaceBusy）。 */
    private boolean paceBusy = false;
    /** DRS 梯位（0 = 顶档 rtW）。 */
    private int paceRung = 0;
    /** 梯位宽度表（降序；顶档 = pathtrace.rtW，以下 ×0.75/0.5625/0.4375/0.3125，<192 弃置）。 */
    private final int[] paceRungW;
    /** 会话宽高比（梯位高 = 宽 × 此比；liveDimsNative 起步取）。 */
    private float paceAspect = 405f / 720f;
    /** 评估节流（每 8 帧评估一次梯子）。 */
    private int paceEvalCount = 0;
    /** 降档滞留计数（连续 3 次评估超预算才降——防喋喋）。 */
    private int paceDownStrikes = 0;
    /** 升档滞留计数（连续 12 次评估富余且站定才升）。 */
    private int paceUpStrikes = 0;
    /** 上次梯位变更时刻（ms）——变更后 3s 冷却（防升降振荡的质量泵动）。 */
    private long paceLastRungMs = -10_000L;

    private static final int PACE_EVAL_EVERY = 8;
    private static final int PACE_DOWN_STRIKES = 3;
    private static final int PACE_UP_STRIKES = 12;
    private static final double PACE_EMA_ALPHA = 0.25;
    /** 梯位变更冷却（ms）。 */
    private static final long PACE_RUNG_COOLDOWN_MS = 3_000L;

    LiveSession(ProbeReport r) {
        this.r = r;
        // v0.5.2 PACE：分辨率梯子（顶档 = rtW 属性；TreeSet 去重防小 rtW 折叠）
        int top = PathtraceMod.LIVE_W;
        java.util.TreeSet<Integer> rungs = new java.util.TreeSet<>();
        for (double f : new double[]{1.0, 0.75, 0.5625, 0.4375, 0.3125}) {
            int w = (int) Math.round(top * f);
            if (w >= 192) {
                rungs.add(w);
            }
        }
        Integer[] desc = rungs.descendingSet().toArray(new Integer[0]);
        this.paceRungW = new int[desc.length];
        for (int i = 0; i < desc.length; i++) {
            this.paceRungW[i] = desc[i];
        }
        try {
            int[] dims = Native.liveDimsNative();
            if (dims != null && dims[0] > 0 && dims[1] > 0) {
                this.paceAspect = (float) ((double) dims[1] / (double) dims[0]);
            }
        } catch (Throwable ignored) {
            // liveBegin 先于构造（正常序）；防御性容错
        }
    }

    @Override
    public void run() {
        try {
            runLoop();
        } catch (Throwable t) {
            r.fail("[live] 工作线程异常终止: " + t);
            r.fail("[live] 累积停止（已累积画面保留在 native 会话中）");
        }
    }

    private void runLoop() throws InterruptedException {
        // v0.4.1：本线程零 TLAS 重建（v0.4 的 worker 重锚是 live 全 miss 的
        // 头号嫌疑——TLAS 构建职责移交探针线程，[S8.4] 30s 定时重锚）。
        r.pass("[S8.3] 实时累积开始（TLAS = 探针线程构建，本线程零重建——"
                + "v0.4.1 实证模式；哨兵电池见 [live-sentinel] 行）");

        boolean snap1Done = false;
        boolean snap2Done = false;
        boolean sentEarlyDone = false;
        boolean sent1Done = false;
        boolean sent2Done = false;
        boolean sent3Done = false;
        boolean sent4Done = false;
        long lastReportMs = -1;

        while (true) {
            Thread.sleep(FRAME_SLEEP_MS);
            long elapsedMs = elapsed();
            loopIters++;

            // ---- 相机跟踪（世界退出容错：暂停而非终止）----
            float[] pose;
            try {
                pose = PathtraceMod.cameraPose();
            } catch (Throwable t) {
                if (!worldPauseEchoed) {
                    worldPauseEchoed = true;
                    r.info("[live] 相机不可读（退出世界/切换场景？）——累积暂停，"
                            + "已累积画面保留；回到世界自动恢复");
                }
                if (elapsedMs >= windowMs && !windowDone) {
                    finishWindow();
                }
                continue;
            }
            worldPauseEchoed = false;

            final boolean moving = poseValid && moved(pose);
            if (moving) {
                Native.liveResetNative();
            }
            lastX = pose[0];
            lastY = pose[1];
            lastZ = pose[2];
            lastYaw = pose[3];
            lastPitch = pose[4];
            poseValid = true;

            // ---- 累积一帧（v0.4.7: E 段派发停——累积冻结，仅监视仪器在岗）----
            if (!PathtraceMod.bisectLiveOff) {
                // v0.5.2 PACE：帧时间实测（含等待上一帧 = GPU 帧时间代理）
                // → 步调控制器（spp 封顶 / 分辨率梯子 / 摄取让路）
                long paceT0 = System.nanoTime();
                Native.liveFrameNative(pose[0], pose[1], pose[2], pose[3], pose[4]);
                paceTick(moving, (System.nanoTime() - paceT0) / 1_000_000L);
            }
            wallFrames++;

            // ---- v0.4.2: atlas epoch 括号（首循环前基线 + epoch 变化即时）----
            if (!preAtlasSentinelDone) {
                preAtlasSentinelDone = true;
                sentinelBattery("atlas 前基线（t≈0，epoch=0）");
            }
            if (PathtraceMod.atlasEpoch != lastAtlasEpoch) {
                lastAtlasEpoch = PathtraceMod.atlasEpoch;
                atlasEpochMs = elapsedMs;
                sentinelBattery("atlas 后即时（epoch=" + lastAtlasEpoch + "）");
            }

            // ---- v0.4.5: TLAS 健康监视器（金丝瑞 -Y 射线，250ms 密度）----
            // 仅变迁打行（附死亡时刻事件关联）；变迁即刻同步做共享内存审计。
            long healthCadence = elapsedMs < 10_000L ? HEALTH_EARLY_MS : HEALTH_LATE_MS;
            if (lastHealthMs < 0 || elapsedMs - lastHealthMs >= healthCadence) {
                lastHealthMs = elapsedMs;
                healthCheck(elapsedMs);
            }

            // ---- v0.4.7: 毒化写手二分梯（A 基线→B 关atlas→C 关合成→D 停摄取
            // →E 停派发；每段 40s，换段即 census+重锚+段判定行）----
            if (bisectOn && !bisectDone) {
                bisectTick(elapsedMs);
            }

            // ---- v0.4.8: 孪生零接触窗口（t=10s 装填 → t=30s 定谳 + ping-pong
            // 激活；bisect=true 复现模式不跑——两协议互斥）----
            if (!bisectOn) {
                twinTick(elapsedMs);
            }

            // ---- v0.4.5: 实例普查编排（基线→稳态→新鲜→再衰减→晚窗）----
            if (!censusBaselineDone && elapsedMs >= 150L) {
                censusBaselineDone = true;
                censusBaselineMs = elapsedMs;
                census("基线（atlas 前）", elapsedMs);
            }
            if (!censusSteadyDone && elapsedMs >= 3_000L) {
                censusSteadyDone = true;
                census("稳态毒化后", elapsedMs);
            }
            if (!censusFreshDone && elapsedMs >= 30_600L) {
                censusFreshDone = true;
                census(PathtraceMod.pingPongActive
                        ? "pingpong 起步（30s 替换件在岗——预期全命中）"
                        : "S8.4 重锚后新鲜", elapsedMs);
            }
            if (!censusDecayDone && elapsedMs >= 33_000L) {
                censusDecayDone = true;
                census(PathtraceMod.pingPongActive
                        ? "pingpong 稳态（33s 预期零新亡）"
                        : "再衰减后", elapsedMs);
            }
            if (!censusLateDone && elapsedMs >= 58_000L) {
                censusLateDone = true;
                census("晚窗", elapsedMs);
            }

            // ---- 报告行（首 10s 每秒，此后 5s）----
            long cadence = elapsedMs < 10_000L ? REPORT_EARLY_MS : REPORT_EVERY_MS;
            if (lastReportMs < 0 || elapsedMs - lastReportMs >= cadence) {
                lastReportMs = elapsedMs;
                reportProgress(elapsedMs);
            }

            // ---- worker 侧哨兵电池（A/B 仪器：本线程求交通道健康度）----
            if (!sentEarlyDone && elapsedMs >= SENTINEL_EARLY_MS) {
                sentEarlyDone = true;
                sentinelBattery("1.5s 早期（毒化窗口加密点）");
            }
            if (!sent1Done && elapsedMs >= SENTINEL_1_MS) {
                sent1Done = true;
                sentinelBattery("2s 重锚前基线");
            }
            if (!sent2Done && elapsedMs >= SENTINEL_2_MS) {
                sent2Done = true;
                sentinelBattery("12s 重锚前基线");
            }
            if (!sent3Done && elapsedMs >= SENTINEL_3_MS) {
                sent3Done = true;
                sentinelBattery("32s 探针重锚后");
            }
            // v0.4.4: 延迟复测——与 32s 电池差 2s：若 32s 命中而 34s 归零，
            // TLAS 在重建后 4s 内被再毒化（持续型写入凶手坐实，非构建问题）。
            if (!sent4Done && elapsedMs >= SENTINEL_4_MS) {
                sent4Done = true;
                sentinelBattery("34s 延迟复测（毒化半衰期）");
            }

            // ---- 快照（证据 PNG）----
            if (!snap1Done && elapsedMs >= SNAP1_MS) {
                snap1Done = true;
                snapshot("pathtrace_live.png", "S8.5 早期收敛快照",
                        String.format("%.0fs", SNAP1_MS / 1000.0));
            }
            if (!snap2Done && elapsedMs >= SNAP2_MS) {
                snap2Done = true;
                snapshot("pathtrace_live_final.png", "S8.5 高 spp 收敛快照",
                        String.format("%.0fs", SNAP2_MS / 1000.0));
            }

            // ---- 观察窗收口（报告判定块；会话继续）----
            if (elapsedMs >= windowMs && !windowDone) {
                finishWindow();
            }
        }
    }

    // ------------------------------------------------------------------
    // v0.4.8: 孪生零接触窗口 + ping-pong 激活（毒化写手工程解）
    // ------------------------------------------------------------------

    /**
     * 孪生窗口编排：t=10s twinArmNative（服役件同步刷新至同生同刻，孪生
     * 零接触 parked）→ t=30s twinProbeNative 一锤定谳 + ping-pong 激活。
     * 读法：孪生 0/6 命中 ⇒ 零接触死亡 ⇒ 外部写手（驱动层）铁证；
     * 孪生 ≥3/6 ⇒ 射线接触是杀伤必要条件（查询链归责）。
     * v0.5.0: 孪生默认关（M3 0/6 外部写手 / A20 Pro 两型均已归档——
     * ping-pong 同等压制）→ ping-pong 于首个 tick（t≈0）即激活：
     * 替换件年龄恒 <0.5s，基线期死亡循环（= 用户反馈的开场闪屏：
     * 全 miss 帧渲染成天空色铺满，每次 5-10s）自本版起不存在。
     */
    private void twinTick(long elapsedMs) {
        if (!PathtraceMod.twinEnabled()) {
            // v0.5.0 默认路径：t≈0 即保护（探针线程 500ms 节拍 ≤0.5s 后
            // 开始替换；t=0 时 AS 为新鲜构建——见 [live] 0s 行 age≈0.2s）。
            if (!twinChecked) {
                twinChecked = true;
                activatePingPong(elapsedMs,
                        "twin 默认关闭（v0.5.0 协议：t=0 即保护）");
            }
            return;
        }
        if (!twinArmed && elapsedMs >= TWIN_ARM_MS) {
            twinArmed = true;
            try {
                boolean ok = Native.twinArmNative();
                r.pass(ok
                        ? String.format("[twin] 窗口开启 @%.0fs：孪生已装填（服役件"
                                + "同步刷新——同生同刻；未来 20s 孪生零接触，"
                                + "服役件照常生活——预计照旧 1-2s 死亡，对照即定谳）",
                                elapsedMs / 1000.0)
                        : String.format("[twin] 装填失败 @%.0fs（场景未就绪）——"
                                + "ping-pong 将于 t=30s 直接激活", elapsedMs / 1000.0));
            } catch (Throwable t) {
                r.fail("[twin] 装填异常: " + t);
            }
            return;
        }
        if (twinArmed && !twinChecked && elapsedMs >= TWIN_CHECK_MS) {
            twinChecked = true;
            twinVerdict(elapsedMs);
        } else if (!twinArmed && !twinChecked && elapsedMs >= TWIN_CHECK_MS) {
            // 装填未成功（异常/拒绝）——不再等，直接激活
            twinChecked = true;
            activatePingPong(elapsedMs, "孪生未装填");
        }
    }

    /** 孪生定谳行 + ping-pong 激活。 */
    private void twinVerdict(long elapsedMs) {
        int hits = -1;
        int ageMs = -1;
        int dispatched = 0;
        try {
            int[] res = Native.twinProbeNative(lastX, lastY, lastZ);
            if (res != null && res.length >= 3) {
                hits = res[0];
                ageMs = res[1];
                dispatched = res[2];
            }
        } catch (Throwable t) {
            r.fail("[twin] 定谳电池异常: " + t);
        }
        String verdict;
        if (dispatched == 0) {
            verdict = "电池未派发（异常）——对照 [tlas-health] 行";
            twinEcho = "电池异常";
        } else if (hits == 0) {
            verdict = "孪生零接触死亡 ⇒ 外部写手定谳（驱动层：zink/MoltenVK/"
                    + "原版渲染/AS 私有内存管理——Radiance 四条自有 GPU 管线已"
                    + "在 v0.4.7 五段梯全部洗清）；ping-pong 替换件是唯一工程解";
            twinEcho = "零接触死亡（外部写手定谳）";
        } else if (hits >= 3) {
            verdict = "孪生零接触存活 ⇒ 射线接触是杀伤必要条件（与既有“查询"
                    + "剂量无关”签名并存——接触本身即触发，仪器/查询链归责）";
            twinEcho = "零接触存活（接触必要性成立）";
        } else {
            verdict = "孪生部分命中（边界态）——对照 [tlas-health] 与 "
                    + "[live-sentinel] 行";
            twinEcho = "部分命中（边界态）";
        }
        r.pass(String.format(
                "[twin] 定谳 @%.0fs：六轴电池 %d/6 命中（孪生年龄 %.1fs，"
                        + "服役件同期死亡 %d 次）——%s",
                elapsedMs / 1000.0, hits, ageMs / 1000.0, windowDeaths, verdict));
        activatePingPong(elapsedMs, "孪生定谳完成");
    }

    /** ping-pong 激活行（twin=false / 孪生定谳后共用）。 */
    private void activatePingPong(long elapsedMs, String why) {
        if (!PathtraceMod.pingPongEnabled()) {
            r.info(String.format(
                    "[pingpong] 保持关闭（-Dpathtrace.pingpong=false）——%s；"
                            + "死亡循环行为保留（对照用），毒帧守卫与自愈重锚"
                            + "仍在岗", why));
            return;
        }
        PathtraceMod.pingPongActive = true;
        r.pass(String.format(
                "[pingpong] 激活 @%.0fs（%s）：探针线程 500ms 强制同内容重建——"
                        + "替换件年龄恒 <0.5s（v0.4.7 真机死亡签名最小 1.0s，"
                        + "写手永远来不及得手）；同内容重建不清累积（场景纪元"
                        + "豁免）+ 毒帧守卫防死帧冲刷——画面从此持续收敛",
                elapsedMs / 1000.0, why));
    }

    // ------------------------------------------------------------------
    // v0.4.7: 毒化写手五段二分梯（单调摘除——单次真机运行定谳）
    // ------------------------------------------------------------------

    /**
     * 换段调度：段边界（40s/80s/120s/160s/200s，可调 pathtrace.bisect.phaseMs）。
     * 每次换段：①上一段判定行（canary 死亡采样 + camHit 归零采样）；
     * ②摘除写手（置位 + native 灬活）；③census 智查（幸存分布随段对照）；
     * ④reanchorReq=true（探针线程重建 TLAS——新段从已知存活态起步，
     * retired 里刚退休的 atlas 资源也随本次构建 flush）。
     */
    private void bisectTick(long elapsedMs) {
        long pms = PathtraceMod.bisectPhaseMs();
        int phase = (int) Math.min(5L, elapsedMs / pms);
        if (phase <= bisectPhase) {
            return;
        }
        phaseVerdict((char) ('A' + bisectPhase), elapsedMs);
        bisectPhase = phase;
        phaseHealthTicks = 0;
        phaseHealthDeadTicks = 0;
        phaseCamHitSamples = 0;
        phaseCamHitZeros = 0;
        if (phase >= 5) {
            bisectDone = true;
            StringBuilder recap = new StringBuilder();
            for (int i = 0; i < 5; i++) {
                if (i > 0) { recap.append('，'); }
                recap.append((char) ('A' + i)).append("=")
                        .append(phaseDeadPct[i]).append('%');
            }
            r.pass("[bisect] 五段梯收口 @" + (5 * pms / 1000) + "s——canary 死亡比："
                    + recap + "。死亡比首次归零的段 ⇒ 凶手=该段摘除的写手；"
                    + "全部高 ⇒ 凶手在仅存仪器（金丝瑞/哨兵射线派发或 TLAS 构建"
                    + "编码器本身）——驱动层/内核 OOB 考虑范围。会话继续运行");
            return;
        }
        switch (phase) {
            case 1: // A→B: atlas 链灭活
                PathtraceMod.bisectAtlasOff = true;
                try {
                    Native.setAtlasEnabledNative(false);
                } catch (Throwable t) {
                    r.fail("[bisect] setAtlasEnabledNative 异常: " + t);
                }
                r.pass(String.format(
                        "[bisect] A→B @%.0fs：atlas 链灭活（MTLTexture+暂存退休、"
                                + "上传拒绝、albedo=顶点色）——B 段死亡停止 ⇒"
                                + " 写手=atlas 上传/驻留链；继续死 ⇒ 向上摘除",
                        elapsedMs / 1000.0));
                break;
            case 2: // B→C: 合成链关停
                PathtraceMod.bisectComposeOff = true;
                r.pass(String.format(
                        "[bisect] B→C @%.0fs：合成链关停（present 内核/GPU 回读/"
                                + "glTexSubImage2D/blit 全停——画面回落原版帧）——"
                                + "C 段死亡停止 ⇒ 写手=合成链；继续死 ⇒ 向上摘除",
                        elapsedMs / 1000.0));
                break;
            case 3: // C→D: 摄取暂停
                PathtraceMod.bisectIngestOff = true;
                r.pass(String.format(
                        "[bisect] C→D @%.0fs：摄取暂停（BLAS 构建/池写入全停，"
                                + "TLAS 冻结）——D 段死亡停止 ⇒ 写手=摄取链"
                                + "（池写/BLAS 构建/实例增长）；继续死 ⇒ 向上摘除",
                        elapsedMs / 1000.0));
                break;
            case 4: // D→E: live 派发停
                PathtraceMod.bisectLiveOff = true;
                r.pass(String.format(
                        "[bisect] D→E @%.0fs：live 派发停（累积冻结——仅金丝瑞/"
                                + "哨兵/census 触碰 TLAS；[live] 行 camHit 栏"
                                + "本段不适用，以 canary 为准）——E 段死亡停止 ⇒"
                                + " 写手=每帧 pathtrace 派发（内核 OOB 首要嫌疑）；"
                                + "继续死 ⇒ 凶手在仅存仪器/驱动层",
                        elapsedMs / 1000.0));
                break;
            default:
        }
        // 换段普查（重锚前打——测量上一段末状态）+ TLAS 重锚（新段从存活态起步）
        census("bisect " + (char) ('A' + phase - 1) + "→" + (char) ('A' + phase)
                + " 换段", elapsedMs);
        PathtraceMod.reanchorReq = true;
    }

    /** 段判定行：canary 死亡采样为主判（全段适用），camHit 归零采样为副证
     * （E 段无派发不适用）。死亡比首次归零的段 = 凶手被摘除的那一段。 */
    private void phaseVerdict(char phase, long elapsedMs) {
        int idx = phase - 'A';
        int pct = phaseHealthTicks > 0
                ? (int) Math.round(phaseHealthDeadTicks * 100.0 / phaseHealthTicks)
                : -1;
        if (idx >= 0 && idx < phaseDeadPct.length) {
            phaseDeadPct[idx] = pct;
        }
        String removed;
        switch (phase) {
            case 'A': removed = "无（基线——全部写手在岗）"; break;
            case 'B': removed = "atlas 链"; break;
            case 'C': removed = "合成链（present/回读/GL 上传）"; break;
            case 'D': removed = "摄取（BLAS/池写）"; break;
            default:  removed = "live 派发（pathtrace kernel）"; break;
        }
        String interp;
        if (pct < 0) {
            interp = "本段无 canary 采样（异常——对照 [tlas-health] 行）";
        } else if (pct <= 5) {
            interp = "死亡停止 ⇒ 凶手坐实=本段摘除的 " + removed;
        } else if (pct >= 50) {
            interp = "死亡持续 ⇒ " + removed + " 洗清，凶手在后续段候选中";
        } else {
            interp = "部分死亡（边界态）——对照 [tlas-health] 行死亡时刻与 age=";
        }
        String camHitNote = phase == 'E'
                ? "（无 live 派发——camHit 栏不适用）"
                : String.format("，camHit 归零 %d/%d 采样",
                        phaseCamHitZeros, phaseCamHitSamples);
        r.pass(String.format(
                "[bisect] Phase %c 段判定 @%.0fs（本段已摘除：%s）：canary 死亡"
                        + " %d/%d 采样（%d%%）%s —— %s",
                phase, elapsedMs / 1000.0, removed, phaseHealthDeadTicks,
                phaseHealthTicks, pct, camHitNote, interp));
    }

    private boolean moved(float[] pose) {
        return Math.abs(pose[0] - lastX) > POS_EPS
                || Math.abs(pose[1] - lastY) > POS_EPS
                || Math.abs(pose[2] - lastZ) > POS_EPS
                || Math.abs(pose[3] - lastYaw) > ROT_EPS
                || Math.abs(pose[4] - lastPitch) > ROT_EPS;
    }

    /**
     * v0.5.2 PACE 步调控制器（RT 工作线程，每帧）：
     * 1) spp 封顶——移动 → 1（累积反正每帧都在清，早期加档的 4× 预算
     *    纯白烧——M3「1～2 帧」根因①）；帧超预算 → 稳态档；富余 → 解除
     *    （早期加档窗恢复全功率收敛）。变迁才发 native（[pace] 行）。
     * 2) 摄取让路——EMA 超 1.5× 预算 → PathtraceMod.gpuPaceBusy（v0.5.1
     *    摄取 flood 抢 GPU 是根因②；事件不丢只积压，站定富余自动解除）。
     * 3) 动态分辨率梯子——每 8 帧评估：EMA 超预算连续 3 次 → 降档；
     *    富余（<55%）连续 12 次且站定 → 升档（移动中不升——防抖动）；
     *    换档后 3s 冷却（防升降振荡的质量泵动）。换档 = native realloc
     *    + resets++（诚实记账）+ [drs] 行。
     */
    private void paceTick(boolean moving, long frameMs) {
        paceFrameMs = paceFrameMs <= 0.0
                ? (double) frameMs
                : paceFrameMs * (1.0 - PACE_EMA_ALPHA)
                        + (double) frameMs * PACE_EMA_ALPHA;

        // 1) spp 封顶
        final int cap = moving ? 1
                : (frameMs > PathtraceMod.PACE_BUDGET_MS ? PathtraceMod.LIVE_SPP : 0);
        if (cap != paceSppCapSent) {
            Native.livePaceNative(cap);
            r.pass(String.format(
                    "[pace] spp/帧封顶 %s → %d（%s；帧 %dms / EMA %.0fms / 预算 %dms）",
                    paceSppCapSent < 0 ? "未设" : String.valueOf(paceSppCapSent),
                    cap,
                    moving ? "移动——累积每帧在清，加档白烧"
                            : frameMs > PathtraceMod.PACE_BUDGET_MS
                                    ? "帧超预算——稳态档"
                                    : "富余——解除封顶",
                    frameMs, paceFrameMs, PathtraceMod.PACE_BUDGET_MS));
            paceSppCapSent = cap;
        }

        // 2) 摄取让路（v0.5.1 争用的对症刀）
        final double busyThreshold = PathtraceMod.PACE_BUDGET_MS * 1.5;
        final boolean busy = paceFrameMs > busyThreshold;
        if (busy != paceBusy) {
            paceBusy = busy;
            PathtraceMod.setGpuPaceBusy(busy);
            r.info(String.format(
                    "[pace] 摄取让路 %s（EMA %.0fms %s 阈值 %.0fms）",
                    busy ? "开启：BLAS 构建暂停（事件积压不丢，解除后补）"
                            : "解除：摄取恢复",
                    paceFrameMs, busy ? "超" : "回落至", busyThreshold));
        }

        // 3) 动态分辨率梯子
        if (!PathtraceMod.PACE_DRS) {
            return;
        }
        if (++paceEvalCount < PACE_EVAL_EVERY) {
            return;
        }
        paceEvalCount = 0;
        if (elapsed() - paceLastRungMs < PACE_RUNG_COOLDOWN_MS) {
            return;   // 冷却期——刚换过档，让 EMA 重新稳定
        }
        if (paceRung < paceRungW.length - 1
                && paceFrameMs > PathtraceMod.PACE_BUDGET_MS) {
            if (++paceDownStrikes >= PACE_DOWN_STRIKES) {
                paceDownStrikes = 0;
                paceUpStrikes = 0;
                applyPaceRung(paceRung + 1, String.format(
                        "EMA %.0fms 超预算 %dms", paceFrameMs,
                        PathtraceMod.PACE_BUDGET_MS));
            }
        } else {
            paceDownStrikes = 0;
        }
        if (paceRung > 0 && !moving
                && paceFrameMs < PathtraceMod.PACE_BUDGET_MS * 0.55) {
            if (++paceUpStrikes >= PACE_UP_STRIKES) {
                paceUpStrikes = 0;
                paceDownStrikes = 0;
                applyPaceRung(paceRung - 1, String.format(
                        "EMA %.0fms 持续富余且站定", paceFrameMs));
            }
        } else {
            paceUpStrikes = 0;
        }
    }

    /** v0.5.2 PACE：切换分辨率梯位（native realloc + [drs] 行）。 */
    private void applyPaceRung(int rung, String why) {
        paceRung = rung;
        paceLastRungMs = elapsed();
        final int w = paceRungW[rung];
        final int h = Math.max(120, Math.round(w * paceAspect));
        Native.liveResizeNative(w, h);
        r.pass(String.format(
                "[drs] RT 分辨率 → %d×%d（梯位 %d/%d，顶档 %d；%s；EMA %.0fms）",
                w, h, rung + 1, paceRungW.length, paceRungW[0], why, paceFrameMs));
    }

    private void reportProgress(long elapsedMs) {
        try {
            long[] st = Native.liveStatsNative();
            if (st == null || st.length < 8) {
                return;
            }
            // v0.4.1: frames 计数随累积重置清零——f/s 以"距上次重置"为分母
            //（v0.4 除以总时长：重置后的行假降级 38→3.8 f/s）。
            if (st[0] < lastNativeFrames) {
                lastResetMs = elapsedMs;
            }
            lastNativeFrames = st[0];
            // v0.4.2: span 下限 200ms——重置风暴下分母 <1ms 产出 2000/89000
            // f/s 假值（v0.4.1 现场即如此）。真速率看后面的 wall f/s。
            double spanMs = Math.max(200, elapsedMs - lastResetMs);
            double framesPerSec = st[0] * 1000.0 / spanMs;
            // v0.4.2: 墙钟速率——Java 侧计帧差分（分子分母均从不重置，免疫
            // 重置风暴）；loopHz = 循环频率（GPU 锁死/锁饥饿/线程饥饿直接现形）。
            long wallSpanMs = Math.max(1, elapsedMs - lastReportWallMs);
            double wallFps = (wallFrames - lastWallFramesAtReport) * 1000.0 / wallSpanMs;
            double loopHz = loopIters * 1000.0 / Math.max(1, elapsedMs);
            lastReportWallMs = elapsedMs;
            lastWallFramesAtReport = wallFrames;
            loopIters = 0;
            // v0.4.1: camHit% 分母 = 单帧样本数（W×H×spp/帧）。v0.4 误用累计
            // sppTotal——静止收敛后真命中率被抹成 0.0%（v0.4 现场即如此）。
            // v0.4.9: 分母随 native 上报的末帧有效 spp（早期加档窗内=early
            // 档，否则稳态档；st[11]=0（尚无帧）回退 LIVE_SPP 常量）。
            long sppEffFrame = (st.length >= 12 && st[11] > 0)
                    ? st[11] : PathtraceMod.LIVE_SPP;
            double frameSamples = (double) rtW() * rtH() * sppEffFrame;
            double camHit = frameSamples > 0
                    ? st[6] * 100.0 / Math.max(1, (long) frameSamples) : 0;
            // v0.4.2: Mray/s 用墙钟速率（since-reset 帧率在风暴下无意义）。
            double mrays = st[5] * wallFps / 1e6;
            // v0.4.4: tlas build 号上桌——camHit 衰减与 build 年龄的相关性
            // 一行可见（同 build 内从非零滑到零 = 被打坏而非构建问题；
            // v0.4.3 现场的 "探针 2/6 / worker 0/6" 分歧与查询变慢 7 倍
            // 都指向这一栏）。
            String tlasTag = st.length >= 9 ? String.format(" tlas=build#%d", st[8]) : "";
            if (st.length >= 10 && st[9] >= 0) {
                tlasTag += String.format(" age=%.1fs", st[9] / 1000.0);
            }
            if (st.length >= 11) {
                tlasTag += String.format(" epoch=%d", st[10]);
            }
            // v0.4.7: 二分梯段内 camHit 归零采样（段判定行副证；E 段无派发不计）。
            if (bisectOn && !bisectDone && !PathtraceMod.bisectLiveOff) {
                phaseCamHitSamples++;
                if (camHit < CAMHIT_DEAD_PCT) {
                    phaseCamHitZeros++;
                }
            }
            r.info(String.format(
                    "[live] %.0fs: frames=%d (%.1f f/s) wall=%.1f f/s loop=%.0fHz"
                            + " spp=%d resets=%d%s | 上帧 %d 查询 camHit=%.1f%%"
                            + " (%.2f Mray/s) | %s",
                    elapsedMs / 1000.0, st[0], framesPerSec, wallFps, loopHz,
                    st[1], st[2], tlasTag, st[5], camHit, mrays,
                    st[7] == 1 ? "atlas=ATLAS_RGB" : "atlas=顶点色回退"));
            // ---- v0.4.4: 自愈重锚——camHit 连续 MISS_STREAK_TRIGGER 行≈0 且
            // 查询确实在发生（st[5]>0，排除首帧）⇒ 前置电池确认双死（g_tlas
            // 与 verified 均 0/6——排除合法天空场景与句柄陈旧）才请求探针
            // 线程重建（worker 不建——v0.4.1 实证）。重建触发 [live] reset：
            // 若持续型毒化凶手仍在，用户也能看到周期性真噪点而非永久黑屏。----
            if (st[5] > 0 && camHit < CAMHIT_DEAD_PCT) {
                missStreak++;
            } else if (camHit >= CAMHIT_DEAD_PCT) {
                missStreak = 0;
            }
            if (missStreak >= MISS_STREAK_TRIGGER) {
                missStreak = 0;
                sentinelBattery("自愈前置（camHit 连续归零）");
                if (sentinelBothDead
                        && (lastHealReqMs < 0 || elapsedMs - lastHealReqMs >= HEAL_COOLDOWN_MS)) {
                    lastHealReqMs = elapsedMs;
                    healCount++;
                    PathtraceMod.reanchorReq = true;
                    r.info(String.format(
                            "[live] 自愈重锚请求 #%d（camHit 连续 %d 行≈0 且电池"
                                    + "双死坐实）——探针线程 0.5s 内重建 TLAS"
                                    + " + [live] reset；若重建后恢复又再度归零 ⇒"
                                    + " 持续型毒化（对照各行 tlas=build# 与"
                                    + " [live-sentinel] 耗时栏）",
                            healCount, MISS_STREAK_TRIGGER));
                } else {
                    r.info("[live] camHit 连续归零但电池未双死（天空场景或"
                            + "句柄陈旧）——不重锚，累积保留");
                }
            }
        } catch (Throwable t) {
            r.fail("[live] 统计读取异常: " + t);
        }
    }

    /** 上次报告行时的 wallFrames 快照（墙钟 f/s 分子差分用）。 */
    private long lastWallFramesAtReport = 0;

    /**
     * worker 侧哨兵电池（v0.4.1 A/B 仪器）：六轴 castRayNative 直查当前
     * TLAS。与 [live] 行 camHit 互证：哨兵命中 + camHit≈0 ⇒ 缺陷在 live
     * 帧派发；两者皆 miss ⇒ TLAS/线程层。
     *
     * v0.4.2：①姿态回显（NaN/漂移在此行现形——"相机数据污染"假说一步杀）；
     * ②0/6 时置 PathtraceMod.probeBatteryReq——探针线程交叉复测（只触发
     * 一次，[probe-sentinel] 行）——worker 派发坏 vs AS 内容坏的终极 A/B。
     *
     * v0.4.4：③六射线耗时回显——v0.4.3 现场同 518400 条全 miss 查询从
     * ~10ms 涨到 ~77ms（查询变慢 7 倍）＝遍历被腐蚀成垃圾结构的签名；
     * 单条 sentinel dispatch 正常 &lt;2ms，慢 miss 即坐实"毒化而非空"。
     * ④verified 双电池：同一组 ray 再查最近换装句柄（g_verifiedTlas）——
     * 与 g_tlas 唯一变量 = 句柄来源，三分歧一步定谳：
     *   g_tlas=0 + verified&gt;0 ⇒ worker 的 g_tlas 读出陈旧句柄；
     *   双 0（探针线程同对象刚命中）⇒ GPU/驱动层线程现象；
     *   双 &gt;0 ⇒ TLAS 健康，all-miss 另寻他因（相机基/uniforms）。
     * ⑤TLAS 句柄身份（build 号 + 指针）随行回显；⑥交叉复测冷却 5s——
     * 每轮死电池都拿新鲜 probe 对照（v0.4.3 只有一次，30s 重锚后对照缺失）。
     */
    private void sentinelBattery(String tag) {
        try {
            float[] pose = PathtraceMod.cameraPose();
            float[][] dirs = {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
            };
            // v0.4.4: 句柄身份先行（电池前后漂移与 build 号对照）
            long[] id = null;
            try {
                id = Native.tlasIdentityNative();
            } catch (Throwable ignored) {
            }
            int hits = 0;
            float downDist = -1;
            int downInst = -1;
            long t0 = System.nanoTime();
            for (float[] d : dirs) {
                float[] res = Native.castRayNative(pose[0], pose[1], pose[2],
                        d[0], d[1], d[2], 512f);
                if (res != null && res[0] > 0.5f) {
                    hits++;
                    if (d[1] < 0) {
                        downDist = res[1];
                        downInst = (int) res[2];
                    }
                }
            }
            long rayMs = Math.max(1L, (System.nanoTime() - t0) / 1_000_000L);
            // v0.4.4: verified 双电池（同 ray 换句柄来源）
            int vHits = 0;
            try {
                for (float[] d : dirs) {
                    float[] res = Native.castRayVerifiedNative(pose[0], pose[1],
                            pose[2], d[0], d[1], d[2], 512f);
                    if (res != null && res[0] > 0.5f) {
                        vHits++;
                    }
                }
            } catch (Throwable ignored) {
            }
            String down = downDist > 0
                    ? String.format("，-Y 脚下 %.2f 格（instance=%d）", downDist, downInst)
                    : "";
            String ident = id != null && id.length >= 4
                    ? String.format(" | tlas=build#%d ptr=0x%X verified=build#%d",
                            id[2], id[0], id[3])
                    : "";
            r.pass(String.format(
                    "[live-sentinel %s] worker 直查 TLAS：%d/6 命中%s | verified："
                            + "%d/6 | 六射线 %d ms（慢+全miss=毒化签名）%s | "
                            + "cam=(%.2f, %.2f, %.2f) yaw=%.1f pitch=%.1f —— "
                            + "与 [live] camHit 互证；g_tlas 与 verified 分歧 ⇒ "
                            + "句柄陈旧，双 0 ⇒ 毒化/GPU 层",
                    tag, hits, down, vHits, rayMs, ident,
                    pose[0], pose[1], pose[2], pose[3], pose[4]));
            long now = elapsed();
            if (hits == 0 && now - lastProbeReqMs >= PROBE_REQ_COOLDOWN_MS) {
                lastProbeReqMs = now;
                PathtraceMod.probeBatteryReq = true;
                r.info("[live-sentinel] 零命中 → 已请求探针线程交叉复测"
                        + "（[probe-sentinel] 行约 0.5s 内出现——同 TLAS 同射线"
                        + "仅换线程：probe 命中=worker 派发坏；双 miss=AS 内容坏）");
            }
            // v0.4.4: 双死判定（自愈前置电池的消费点）——g_tlas 与 verified
            // 均 0/6 才算真毒化（天空场景 -Y 脚下必命中；句柄陈旧时 verified
            // 会活着）。
            sentinelBothDead = (hits == 0 && vHits == 0);
        } catch (Throwable t) {
            r.fail("[live-sentinel " + tag + "] 异常: " + t);
        }
    }

    /** v0.4.4: 上次交叉复测请求时刻（冷却代替一次性——每轮死电池都要新鲜对照）。 */
    private long lastProbeReqMs = -PROBE_REQ_COOLDOWN_MS;
    /** v0.4.4: 连续 camHit≈0 的报告行数（自愈重锚触发器）。 */
    private int missStreak = 0;
    /** v0.4.4: 自愈重锚已请求次数。 */
    private int healCount = 0;
    /** v0.4.4: 最近一轮电池双死（g_tlas 与 verified 均 0/6）——自愈前置条件。 */
    private boolean sentinelBothDead = false;

    // ------------------------------------------------------------------
    // v0.4.5: TLAS 健康监视器（金丝瑞 -Y 射线）+ 实例普查 + 共享内存审计
    // ------------------------------------------------------------------

    /**
     * 健康检查：相机正下方一条 -Y 射线（脚下 1.62 格内必有地板——v0.4.4
     * 现场 cam=(4.50,70.62,22.50) 基线 2/6 含 -Y 命中）。250ms 密度把死亡
     * 时刻钉到 ±0.25s，与 atlas 上传/首帧 ATLAS_RGB/合成帧序一行关联；
     * 变迁即刻做共享内存审计（写手若在共享侧当场落网）。
     */
    private void healthCheck(long elapsedMs) {
        try {
            // 首帧 ATLAS_RGB 时刻（st[7]==1 首次为真）——死亡行关联事件。
            long[] st = Native.liveStatsNative();
            if (!atlasFrameSeen && st != null && st.length >= 8 && st[7] == 1) {
                atlasFrameSeen = true;
                firstAtlasFrameMs = elapsedMs;
            }
            // v0.4.7: 当前 TLAS 年龄（死亡/复活行 age=——“重建后 ~1s 即死”
            // 签名直接量化；换段对照的硬指标）。
            String ageTag = (st != null && st.length >= 10 && st[9] >= 0)
                    ? String.format(" | build 年龄=%.2fs", st[9] / 1000.0) : "";
            float[] pose = PathtraceMod.cameraPose();
            float[] res = Native.castRayNative(pose[0], pose[1], pose[2],
                    0f, -1f, 0f, 512f);
            boolean alive = res != null && res[0] > 0.5f;
            // v0.4.7: 二分梯段内 canary 采样计数（段判定行分子/分母）。
            if (bisectOn && !bisectDone) {
                phaseHealthTicks++;
                if (!alive) {
                    phaseHealthDeadTicks++;
                }
            }
            if (alive != healthAlive || !healthEchoed) {
                healthAlive = alive;
                healthEchoed = true;
                if (!alive) {
                    windowDeaths++;   // v0.4.8: 孪生定谳行对照（会话内死亡次数）
                }
                String ev = deathContext(elapsedMs);
                if (alive) {
                    r.pass(String.format(
                            "[tlas-health] t=%.2fs 复活（-Y 脚下 %.2f 格命中）"
                                    + "%s | %s",
                            elapsedMs / 1000.0, res != null ? res[1] : -1f,
                            ageTag, ev));
                } else {
                    r.fail(String.format(
                            "[tlas-health] t=%.2fs 死亡（-Y 金丝瑞 miss——相机"
                                    + "正下方本有地板）%s | %s",
                            elapsedMs / 1000.0, ageTag, ev));
                }
                sharedAudit(elapsedMs);
            }
        } catch (Throwable t) {
            r.fail("[tlas-health] 异常: " + t);
        }
    }

    /** 死亡/复活行的事件关联串（相对时刻 + 合成帧序）。 */
    private String deathContext(long elapsedMs) {
        StringBuilder sb = new StringBuilder("事件关联:");
        if (atlasEpochMs >= 0) {
            sb.append(String.format(" atlas上传=%+.2fs",
                    (atlasEpochMs - elapsedMs) / 1000.0));
        } else {
            sb.append(" atlas未上传");
        }
        if (firstAtlasFrameMs >= 0) {
            sb.append(String.format(" 首帧ATLAS_RGB=%+.2fs",
                    (firstAtlasFrameMs - elapsedMs) / 1000.0));
        } else {
            sb.append(" 仍顶点色帧");
        }
        sb.append(" compose=").append(GlComposer.composeFrames()).append("帧");
        if (censusBaselineMs >= 0) {
            sb.append(String.format(" 基线普查=%+.2fs",
                    (censusBaselineMs - elapsedMs) / 1000.0));
        }
        return sb.toString();
    }

    /**
     * 实例普查（v0.4.5 核心仪器）：把 v0.4.4 快照里 0.40% 的幸存几何带
     * 展开成 157 实例的幸存/阵亡地图。判读法：
     *   幸存集=连续索引区段 ⇒ TLAS 实例记录被线性扫写（配 [audit] 首异偏移）
     *   幸存集=空间聚集（相机本地） ⇒ 遍历层距离选择性失效
     *   幸存集=散布且重建后变化 ⇒ BLAS 层逐个被打坏
     *   重建后幸存集与旧 TLAS 完全一致 ⇒ 毒化确定性命中同一批记录
     */
    private void census(String tag, long elapsedMs) {
        try {
            int[] cur = Native.instanceCensusNative();
            if (cur == null || cur.length == 0) {
                r.info("[census " + tag + "] 场景未就绪（无实例）");
                return;
            }
            final int n = cur.length;
            if (censusBaseline == null || censusBaseline.length != n) {
                if (censusBaseline != null) {
                    r.info("[census " + tag + "] 实例数变化（"
                            + censusBaseline.length + "→" + n + "）——基线重置");
                }
                censusBaseline = cur.clone();
            }
            final int[] base = censusBaseline;
            int alive = 0;
            int selfHits = 0;
            int survivors = 0;
            int newlyDead = 0;
            StringBuilder ranges = new StringBuilder();
            int runStart = -1;
            for (int i = 0; i <= n; i++) {
                boolean hit = i < n && cur[i] > 0;
                if (hit) {
                    alive++;
                    if (cur[i] == i + 1) {
                        selfHits++;
                    }
                }
                boolean survivor = i < n && base[i] > 0 && cur[i] > 0;
                if (i < n && base[i] > 0 && cur[i] == 0) {
                    newlyDead++;
                }
                if (survivor) {
                    survivors++;
                    if (runStart < 0) {
                        runStart = i;
                    }
                } else {
                    if (runStart >= 0) {
                        if (ranges.length() > 0) {
                            ranges.append(',');
                        }
                        ranges.append(runStart == i - 1
                                ? String.valueOf(runStart)
                                : runStart + "-" + (i - 1));
                        runStart = -1;
                    }
                }
            }
            // 幸存集空间包围盒（相机本地聚集 vs 全域散布一步可辨）。
            String spatial = "";
            if (survivors > 0 && survivors <= n) {
                float minx = Float.MAX_VALUE, maxx = -Float.MAX_VALUE;
                float minz = Float.MAX_VALUE, maxz = -Float.MAX_VALUE;
                for (int i = 0; i < n; i++) {
                    if (base[i] > 0 && cur[i] > 0) {
                        int[] o = Native.instanceOriginNative(i);
                        if (o != null && o.length >= 3) {
                            minx = Math.min(minx, o[0]);
                            maxx = Math.max(maxx, o[0]);
                            minz = Math.min(minz, o[2]);
                            maxz = Math.max(maxz, o[2]);
                        }
                    }
                }
                if (minx <= maxx) {
                    spatial = String.format(" 幸存XY盒=%dx%d格",
                            (int) (maxx - minx), (int) (maxz - minz));
                }
            }
            String rangeStr = ranges.length() > 96
                    ? ranges.substring(0, 96) + "…" : ranges.toString();
            r.pass(String.format(
                    "[census %s] t=%.2fs: 命中列=%d/%d 自身=%d | 幸存=%d "
                            + "新亡=%d%s | 幸存索引=%s",
                    tag, elapsedMs / 1000.0, alive, n, selfHits,
                    survivors, newlyDead, spatial, rangeStr));
            sharedAudit(elapsedMs);
        } catch (Throwable t) {
            r.fail("[census " + tag + "] 异常: " + t);
        }
    }

    /** 共享内存审计（实例缓冲影子 memcmp + 64KB 金丝瑞图案）行。 */
    private void sharedAudit(long elapsedMs) {
        try {
            long[] a = Native.sharedAuditNative();
            if (a == null || a.length < 8) {
                return;
            }
            String inst = a[0] < 0 ? "无影子" : a[0] == 1 ? "干净"
                    : String.format("被改写(首异@+%d)", a[1]);
            String canary = a[4] < 0 ? "无金丝瑞" : a[4] == 1 ? "干净"
                    : String.format("被改写(首异@+%d, %dB)", a[5], a[6]);
            r.info(String.format(
                    "[audit] t=%.2fs build#%d: 实例缓冲=%s 金丝瑞=%s —— "
                            + "双净而 TLAS 死 ⇒ GPU 私有内存写手（二分轮收口）",
                    elapsedMs / 1000.0, a[7], inst, canary));
        } catch (Throwable t) {
            r.fail("[audit] 异常: " + t);
        }
    }

    private int rtW() {
        try {
            int[] d = Native.liveDimsNative();
            return d != null ? d[0] : 480;
        } catch (Throwable t) {
            return 480;
        }
    }

    private int rtH() {
        try {
            int[] d = Native.liveDimsNative();
            return d != null ? d[1] : 270;
        } catch (Throwable t) {
            return 270;
        }
    }

    private void snapshot(String fileName, String tag, String when) {
        try {
            int[] d = Native.liveDimsNative();
            if (d == null) {
                return;
            }
            int[] argb = Native.liveReadbackNative();
            if (argb == null) {
                r.info("[" + tag + "] 回读不可用（会话未活跃）—— 跳过 " + fileName);
                return;
            }
            Path png = Paths.get(System.getProperty("user.dir", "."), fileName);
            PngWriter.write(png, d[0], d[1], argb);
            r.pass(String.format("[" + tag + "] PNG 落盘: %s (%d B, %dx%d, @%s)",
                    png, Files.size(png), d[0], d[1], when));
        } catch (Throwable t) {
            r.fail("[" + tag + "] 快照失败: " + t);
        }
    }

    /** 观察窗收口：合成/累积判定块（报告的实时会话最终判定）。 */
    private void finishWindow() {
        windowDone = true;
        try {
            long[] st = Native.liveStatsNative();
            int swaps = PathtraceHook.swapCount;
            int blits = PathtraceHook.blitCount;
            int glErrs = PathtraceHook.glErrors;

            r.stage("S8.6 实时会话判定（" + (windowMs / 1000) + "s 观察窗收口）");
            if (bisectOn) {
                r.info("[bisect] 二分梯模式：本判定块基于整窗常规统计（E 段无派发"
                        + "时 camHit/blit 栏为冻结值）——毒化定谳以上方 [bisect]"
                        + " 各段判定行与收口行为准");
            }
            if (!bisectOn) {
                // v0.5.0 收口行：孪生状态 + ping-pong 状态 + 窗内死亡对照
                // （ping-pong 自 t=0 在岗时基线期不存在——窗内死亡应恒零）
                r.pass(String.format(
                        "[pingpong 收口] 孪生：%s | ping-pong：%s | 窗内 canary "
                                + "死亡 %d 次（ping-pong 自 t=0 在岗应恒零）"
                                + " | resets=%d（场景纪元/相机移动/"
                                + "毒帧守卫） | spp=%d",
                        twinEcho,
                        PathtraceMod.pingPongActive ? "在岗（500ms 替换件）" : "关闭",
                        windowDeaths, st[2], st[1]));
            }
            long sppEffFinal = (st.length >= 12 && st[11] > 0)
                    ? st[11] : PathtraceMod.LIVE_SPP;
            double frameSamples = (double) rtW() * rtH() * sppEffFinal;
            double lastCamHit = frameSamples > 0
                    ? st[6] * 100.0 / Math.max(1, (long) frameSamples) : 0;
            double wallFps = wallFrames * 1000.0 / Math.max(1, elapsed());
            r.info(String.format(
                    "会话统计: frames=%d spp=%d resets=%d refreshes=%d | "
                            + "上帧 camHit=%.1f%% | wall=%.1f f/s | hook: swap=%d "
                            + "blit=%d glErrors=%d",
                    st[0], st[1], st[2], st[3], lastCamHit, wallFps, swaps,
                    blits, glErrs));

            String verdict;
            String message;
            if (st[0] == 0) {
                verdict = "LIVE_FAIL";
                message = "累积零帧（native 会话未产生任何帧——见 [live] 行与 "
                        + "[scene] FATAL 行归因）。";
            } else if (!PathtraceHook.hookArmed) {
                verdict = "LIVE_OK_NO_COMPOSE";
                message = "累积健康（frames=" + st[0] + " spp=" + st[1]
                        + "，见 [live] 行与 PNG 快照）——合成被 "
                        + "pathtrace.compose=false 主动关闭（运行开关，非缺陷）。";
            } else if (blits == 0 && swaps == 0) {
                verdict = "COMPOSE_INACTIVE";
                message = "mixin 钩子从未触发（swap=0）：pathtrace.mixins.json 未被"
                        + "应用（required=false 软失败）或方法名漂移。累积本身健康"
                        + "（见 [live] 行与 PNG 快照）——上屏通道未建立，回报此报告。";
            } else if (blits == 0 && swaps > 0) {
                verdict = "COMPOSE_DEAD";
                message = "钩子触发（swap=" + swaps + "）但零合成——GL 合成器异常"
                        + "判死（见 [compose]/[S8.1] 行）。累积健康，回报归因行。";
            } else if (glErrs > 0) {
                verdict = "LIVE_OK_WITH_GL_ERRORS";
                message = "实时路径追踪已上屏（blit=" + blits + "），但 GL 层出现 "
                        + glErrs + " 次错误计数——画面可能异常（错误码与几何"
                        + "见 [compose] 行，首帧几何行直指底部花屏归因）。"
                        + "camHit 与线程归责见 [live]/[live-sentinel]/"
                        + "[probe-sentinel] 行。回报本报告与目检描述。";
            } else if (st[0] < 30) {
                verdict = "ACCUM_STALLED";
                message = "60s 内仅 " + st[0] + " 帧（预期 >100）——GPU 派发阻塞"
                        + "或工作线程饥饿，见 [live] 行的 f/s。";
            } else {
                verdict = "LIVE_OK";
                message = "2.3 三件事闭环：渐进累积（静止收敛 spp=" + st[1]
                        + "，移动 resets=" + st[2] + " 自动重置）+ GL 合成"
                        + "（blit=" + blits + " 帧，整帧替换游戏画面）+ "
                        + (st[7] == 1 ? "atlas albedo（ATLAS_RGB × 顶点色）"
                                      : "albedo 顶点色回退（atlas 未采集，"
                                        + "看 [S8.2] 行）")
                        + "。v0.4.2 证据链：camHit 见 [live] 行（分母已修——"
                        + "v0.4 显示 bug 曾把真命中率抹成 0.0%）、TLAS 线程归责"
                        + "见 [live-sentinel]/[probe-sentinel]/[S8.4] 行（探针"
                        + "构建 + 双线程查询 A/B）、GL 错误分类学见 [compose] 行。"
                        + "会话继续运行直到游戏退出——眼见为实。"
                        + "下一步（2.4）：反射/GI 质量与阴影 ray 复用、事件驱动"
                        + "摄取（mixin 化 SceneProxy）。";
            }
            r.raw("");
            r.raw("==============================================================");
            r.raw("实时会话判定: " + verdict);
            r.raw(message);
            r.raw("==============================================================");
        } catch (Throwable t) {
            r.fail("[live] 判定块写入异常: " + t);
        }
    }

    private long elapsed() {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
