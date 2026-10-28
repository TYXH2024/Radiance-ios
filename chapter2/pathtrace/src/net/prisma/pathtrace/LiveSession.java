package net.prisma.pathtrace;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 实时累积会话（2.3 v0.4）—— RT 工作线程。
 *
 * 循环（~30 Hz 相机轮询 + Metal 异步帧）：
 *   - 相机姿态反射读取（与 S6/S7 同链路）；位移/转角超阈值 → liveResetNative
 *     （下一帧 store 模式覆写累积，静止后重新收敛）
 *   - liveFrameNative 累积一帧（trace+present 单命令缓冲异步提交；上一帧
 *     统计于等待点自然回读）
 *   - 每 5s：liveRefreshNative（工作线程 TLAS 重锚——v0.3 实证驱动层纪律：
 *     射线前新鲜 AS build）+ [live] 进度行 + 快照（15s/55s 两张 PNG 证据）
 *   - 60s 观察窗结束 → 实时会话判定块（LIVE_OK / COMPOSE_INACTIVE /
 *     COMPOSE_DEAD / ACCUM_STALLED / LIVE_FAIL）→ windowDone 置位（探针线程
 *     的 runAll 等待它写完整报告）；会话继续运行直到游戏退出（不上屏即熄）
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
    /** 周期行间隔（ms）。 */
    private static final long REPORT_EVERY_MS = 5_000L;

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
        // v0.3 实证纪律：本线程的射线派发前先在工作线程上做一次新鲜 AS
        // build（TLAS 重锚）——摄取线程构建的 TLAS 直查会确定性全 miss。
        Native.liveRefreshNative();
        r.pass("[S8.3] 工作线程 TLAS 重锚完成（v0.3 实证模式），实时累积开始");

        boolean snap1Done = false;
        boolean snap2Done = false;
        long lastReportMs = 0;
        long lastRefreshMs = 0;

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

            // ---- 周期重锚 + 进度行 ----
            if (elapsedMs - lastRefreshMs >= REPORT_EVERY_MS) {
                lastRefreshMs = elapsedMs;
                Native.liveRefreshNative();
            }
            if (elapsedMs - lastReportMs >= REPORT_EVERY_MS) {
                lastReportMs = elapsedMs;
                reportProgress(elapsedMs);
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
            double framesPerSec = st[0] * 1000.0 / Math.max(1, elapsedMs);
            double mrays = st[5] * 1000.0 / Math.max(1, REPORT_EVERY_MS) / 1e6;
            double samples = (double) (int) rtW() * rtH() * st[1];
            double camHit = samples > 0 ? st[6] * 100.0 / Math.max(1, samples) : 0;
            r.info(String.format(
                    "[live] %.0fs: frames=%d (%.1f f/s) spp=%d resets=%d | "
                            + "上帧 %.2f Mray/s camHit=%.1f%% | %s",
                    elapsedMs / 1000.0, st[0], framesPerSec, st[1], st[2],
                    mrays, camHit,
                    st[7] == 1 ? "atlas=ATLAS_RGB" : "atlas=顶点色回退"));
        } catch (Throwable t) {
            r.fail("[live] 统计读取异常: " + t);
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
            r.info(String.format(
                    "会话统计: frames=%d spp=%d resets=%d refreshes=%d | "
                            + "hook: swap=%d blit=%d glErrors=%d",
                    st[0], st[1], st[2], st[3], swaps, blits, glErrs));

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
                        + "。会话继续运行直到游戏退出——眼见为实。"
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
