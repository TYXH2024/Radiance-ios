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

    /** 观察窗时长（ms）——报告判定块在此窗口末写入。 */
    private static final long LIVE_WINDOW_MS = 60_000L;
    /** 快照时刻（ms，窗内）：早期收敛证据 + 高 spp 收敛证据。 */
    private static final long SNAP1_MS = 15_000L;
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

    LiveSession(ProbeReport r) {
        this.r = r;
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
                if (elapsedMs >= LIVE_WINDOW_MS && !windowDone) {
                    finishWindow();
                }
                continue;
            }
            worldPauseEchoed = false;

            if (poseValid && moved(pose)) {
                Native.liveResetNative();
            }
            lastX = pose[0];
            lastY = pose[1];
            lastZ = pose[2];
            lastYaw = pose[3];
            lastPitch = pose[4];
            poseValid = true;

            // ---- 累积一帧 ----
            Native.liveFrameNative(pose[0], pose[1], pose[2], pose[3], pose[4]);
            wallFrames++;

            // ---- v0.4.2: atlas epoch 括号（首循环前基线 + epoch 变化即时）----
            if (!preAtlasSentinelDone) {
                preAtlasSentinelDone = true;
                sentinelBattery("atlas 前基线（t≈0，epoch=0）");
            }
            if (PathtraceMod.atlasEpoch != lastAtlasEpoch) {
                lastAtlasEpoch = PathtraceMod.atlasEpoch;
                sentinelBattery("atlas 后即时（epoch=" + lastAtlasEpoch + "）");
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
            if (elapsedMs >= LIVE_WINDOW_MS && !windowDone) {
                finishWindow();
            }
        }
    }

    private boolean moved(float[] pose) {
        return Math.abs(pose[0] - lastX) > POS_EPS
                || Math.abs(pose[1] - lastY) > POS_EPS
                || Math.abs(pose[2] - lastZ) > POS_EPS
                || Math.abs(pose[3] - lastYaw) > ROT_EPS
                || Math.abs(pose[4] - lastPitch) > ROT_EPS;
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
            double frameSamples = (double) rtW() * rtH() * PathtraceMod.LIVE_SPP;
            double camHit = frameSamples > 0
                    ? st[6] * 100.0 / Math.max(1, (long) frameSamples) : 0;
            // v0.4.2: Mray/s 用墙钟速率（since-reset 帧率在风暴下无意义）。
            double mrays = st[5] * wallFps / 1e6;
            // v0.4.4: tlas build 号上桌——camHit 衰减与 build 年龄的相关性
            // 一行可见（同 build 内从非零滑到零 = 被打坏而非构建问题；
            // v0.4.3 现场的 "探针 2/6 / worker 0/6" 分歧与查询变慢 7 倍
            // 都指向这一栏）。
            String tlasTag = st.length >= 9 ? String.format(" tlas=build#%d", st[8]) : "";
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
            if (st[5] > 0 && camHit < 0.5) {
                missStreak++;
            } else if (camHit >= 0.5) {
                missStreak = 0;
            }
            if (missStreak >= MISS_STREAK_TRIGGER) {
                missStreak = 0;
                sentinelBattery("自愈前置（camHit 连续归零）");
                if (sentinelBothDead) {
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

            r.stage("S8.6 实时会话判定（60s 观察窗收口）");
            double frameSamples = (double) rtW() * rtH() * PathtraceMod.LIVE_SPP;
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
