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
    /** 哨兵电池时刻（ms）：重锚前基线 ×2 + 探针 30s 重锚后 ×1。 */
    private static final long SENTINEL_1_MS = 2_000L;
    private static final long SENTINEL_2_MS = 12_000L;
    private static final long SENTINEL_3_MS = 32_000L;

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
        boolean sent1Done = false;
        boolean sent2Done = false;
        boolean sent3Done = false;
        long lastReportMs = -1;

        while (true) {
            Thread.sleep(FRAME_SLEEP_MS);
            long elapsedMs = elapsed();

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

            // ---- 报告行（首 10s 每秒，此后 5s）----
            long cadence = elapsedMs < 10_000L ? REPORT_EARLY_MS : REPORT_EVERY_MS;
            if (lastReportMs < 0 || elapsedMs - lastReportMs >= cadence) {
                lastReportMs = elapsedMs;
                reportProgress(elapsedMs);
            }

            // ---- worker 侧哨兵电池（A/B 仪器：本线程求交通道健康度）----
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
            double spanMs = Math.max(1, elapsedMs - lastResetMs);
            double framesPerSec = st[0] * 1000.0 / spanMs;
            // v0.4.1: camHit% 分母 = 单帧样本数（W×H×spp/帧）。v0.4 误用累计
            // sppTotal——静止收敛后真命中率被抹成 0.0%（v0.4 现场即如此）。
            double frameSamples = (double) rtW() * rtH() * PathtraceMod.LIVE_SPP;
            double camHit = frameSamples > 0
                    ? st[6] * 100.0 / Math.max(1, (long) frameSamples) : 0;
            double mrays = st[5] * framesPerSec / 1e6;
            r.info(String.format(
                    "[live] %.0fs: frames=%d (%.1f f/s) spp=%d resets=%d | "
                            + "上帧 %d 查询 camHit=%.1f%% (%.2f Mray/s) | %s",
                    elapsedMs / 1000.0, st[0], framesPerSec, st[1], st[2],
                    st[5], camHit, mrays,
                    st[7] == 1 ? "atlas=ATLAS_RGB" : "atlas=顶点色回退"));
        } catch (Throwable t) {
            r.fail("[live] 统计读取异常: " + t);
        }
    }

    /**
     * worker 侧哨兵电池（v0.4.1 A/B 仪器）：六轴 castRayNative 直查当前
     * TLAS。与 [live] 行 camHit 互证：哨兵命中 + camHit≈0 ⇒ 缺陷在 live
     * 帧派发；两者皆 miss ⇒ TLAS/线程层。
     */
    private void sentinelBattery(String tag) {
        try {
            float[] pose = PathtraceMod.cameraPose();
            float[][] dirs = {
                {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
            };
            int hits = 0;
            float downDist = -1;
            int downInst = -1;
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
            String down = downDist > 0
                    ? String.format("，-Y 脚下 %.2f 格（instance=%d）", downDist, downInst)
                    : "";
            r.pass(String.format(
                    "[live-sentinel %s] worker 直查 TLAS：%d/6 命中%s —— "
                            + "本线程求交通道健康度（与 [live] camHit 互证）",
                    tag, hits, down));
        } catch (Throwable t) {
            r.fail("[live-sentinel " + tag + "] 异常: " + t);
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

            r.stage("S8.6 实时会话判定（60s 观察窗收口）");
            double frameSamples = (double) rtW() * rtH() * PathtraceMod.LIVE_SPP;
            double lastCamHit = frameSamples > 0
                    ? st[6] * 100.0 / Math.max(1, (long) frameSamples) : 0;
            r.info(String.format(
                    "会话统计: frames=%d spp=%d resets=%d refreshes=%d | "
                            + "上帧 camHit=%.1f%% | hook: swap=%d blit=%d glErrors=%d",
                    st[0], st[1], st[2], st[3], lastCamHit, swaps, blits, glErrs));

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
                        + glErrs + " 次错误计数——画面可能异常，回报本报告与目检"
                        + "描述。";
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
                        + "。v0.4.1 证据链：camHit 见 [live] 行（分母已修——"
                        + "v0.4 显示 bug 曾把真命中率抹成 0.0%）、TLAS 线程归责"
                        + "见 [live-sentinel]/[S8.4] 行（探针构建 + worker 查询"
                        + "A/B）。会话继续运行直到游戏退出——眼见为实。"
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
