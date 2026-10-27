package net.prisma.sceneingest;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 场景摄取主控 —— Radiance 移植第二章 2.1 阶段交付物。
 *
 * 验证问题（docs/chapter2-scene-ingestion-design.md §6）：
 *   Q1 真实 MC 世界几何能否进入 Metal 加速结构（BLAS/TLAS 动态重建）？
 *   Q2 摄取计数与 GL 侧 mesh 是否一致（区块/三角形量级合理性）？
 *   Q3 场景求交是否命中（TLAS instance + ray_query 全链路正确性）？
 *
 * 执行序（报告 S 段）：
 *   S0 环境快照 → S1 解包 dylib/metallib → S2 System.load →
 *   S3 反射符号审计（轮询路线防错段）→ S4 网格发现 → S5 摄取与构建 →
 *   S6 场景求交验证 → VERDICT。
 *
 * 归因开关：-Dsceneingest.disabled=true / SCENEINGEST_DISABLED=1
 * → 只写环境快照，零原生调用（闪退 A/B 对照）。
 *
 * 与 2.0 的关键差异：本阶段在**进入世界后**持续运行（轮询摄取），因此
 * CLIENT_STARTED 只是启动等待线程；真正的摄取在 mc.world != null 后开始。
 */
public final class SceneIngestionMod {

    public static void main(String[] args) {
        runAll("standalone-main");
    }

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    static void runAll(String triggerSource) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        ProbeReport r = new ProbeReport("scene_ingestion_report.txt");
        r.header("场景摄取探针 (scene-ingestion 0.5.0)");
        r.info("触发方式: " + triggerSource);

        if (isDisabled()) {
            r.stage("S0 环境快照（探针已禁用: sceneingest.disabled）");
            envSnapshot(r);
            r.finish("-", "探针被 sceneingest.disabled 禁用，本次未解包/未装载/未执行任何原生调用。"
                    + "用途：闪退归因对照。");
            return;
        }

        r.stage("S0 环境快照");
        envSnapshot(r);

        // ---- S1 解包 ----
        r.stage("S1 解包原生资产（JAR -> game 目录）");
        Path dir = Paths.get(System.getProperty("user.dir", "."), "sceneingest");
        Path dylib;
        Path metallib;
        try {
            Files.createDirectories(dir);
            dylib = extract("/sceneingest/libradiance_scene.dylib", dir);
            metallib = extract("/sceneingest/scene_trace.metallib", dir);
            r.pass("解包完成: " + dylib + " (" + Files.size(dylib) + " B)");
            r.info("metallib: " + metallib + " (" + Files.size(metallib) + " B)");
        } catch (Throwable t) {
            r.fail("S1 解包失败（JAR 资产缺失或目录不可写）", t);
            r.finish("EXTRACT_FAIL", "原生资产解包失败。附整份报告回报。");
            return;
        }

        // ---- S2 装载 ----
        r.stage("S2 System.load 装载 dylib（投送通道验证）");
        try {
            System.load(dylib.toAbsolutePath().toString());
            r.pass("dylib 装载成功（与 2.0 相同的受信装载链）");
        } catch (Throwable t) {
            r.fail("S2 dylib 装载失败", t);
            r.finish("LOAD_FAIL", "System.load 失败。附整份报告回报。");
            return;
        }

        // ---- S3 反射符号审计 ----
        r.stage("S3 反射符号审计（intermediary 符号表逐项解析）");
        boolean symbolsOk;
        try {
            symbolsOk = Mappings.resolveAll(r);
        } catch (Throwable t) {
            r.fail("S3 符号审计异常", t);
            symbolsOk = false;
        }
        if (!symbolsOk) {
            r.finish("REFLECT_FAIL", "存在未解析符号（共 " + Mappings.FAILURES.size()
                    + " 个，报告已逐项列出；参数类型类（含 World）亦在审计列表内）。"
                    + "v0.2 已修复 v0.1 的参数类解析缺陷（World/class_1937 未注册导致"
                    + " method_39969 误报）——若此判定复现，即为 yarn 1.21.4+build.8 "
                    + "符号表与真机运行时的真实不一致（MC 版本非 1.21.4）。附整份报告回报。");
            return;
        }
        r.pass("符号表全量解析通过（" + Mappings.RESOLVED.size() + " 项）");

        // ---- S4/S5 网格发现 + 摄取（SceneProxy 主循环阻塞运行）----
        r.stage("S4/S5 网格发现与摄取（轮询 500ms，等待进入世界）");
        SceneProxy proxy = new SceneProxy(r, metallib.toAbsolutePath().toString());
        try {
            proxy.run();
        } catch (Throwable t) {
            r.fail("摄取主循环异常", t);
            r.finish("NATIVE_FAIL", "摄取循环异常终止。附整份报告回报。");
            return;
        }

        // ---- 统计与 S6 ----
        r.stage("S5 统计汇总");
        long[] stats;
        try {
            stats = Native.sceneStatsNative();
        } catch (Throwable t) {
            r.fail("sceneStatsNative 异常", t);
            r.finish("NATIVE_FAIL", "场景统计读取失败。附整份报告回报。");
            return;
        }
        long readyBlas = stats[0];
        long totalTris = stats[1];
        long totalVerts = stats[2];
        long tlasBuilds = stats[3];
        long blasBuilds = stats[4];
        long vertexBytes = stats[5];
        long indexBytes = stats[6];
        long tlasBytes = stats[7];
        r.info(String.format("Java 侧: polls=%d rebuilds=%d invalidations=%d relocations=%d "
                        + "非空区块=%d",
                proxy.polls, proxy.rebuilds, proxy.invalidations, proxy.relocations,
                proxy.nonEmptyChunks));
        r.info(String.format("Native 侧: readyBLAS=%d BLAS构建=%d TLAS重建=%d",
                readyBlas, blasBuilds, tlasBuilds));
        r.info(String.format("几何: 三角形=%d 顶点=%d 顶点缓冲=%.1f KB 索引缓冲=%.1f KB "
                        + "TLAS=%.1f KB",
                totalTris, totalVerts, vertexBytes / 1024.0, indexBytes / 1024.0,
                tlasBytes / 1024.0));

        r.stage("S6 场景求交验证（v0.5 诊断矩阵：回显/哨兵/AABB 包围盒判定/双假设锦标赛/选优终验）");
        float[] cam = null;
        try {
            cam = cameraPos();
        } catch (Throwable t) {
            r.info("直读相机失败（" + t.getMessage() + "）——改用摄取期缓存位置");
        }
        if (cam == null) {
            // v0.3：回退到摄取期每轮刷新的缓存（用户可能在窗口结束后立即
            // 退出世界；ray 验证为纯 native 操作不依赖 MC 存活）
            cam = proxy.lastCameraPos;
            if (cam != null) {
                r.pass(String.format("使用缓存相机位置: (%.2f, %.2f, %.2f)",
                        cam[0], cam[1], cam[2]));
            }
        }

        boolean cameraOk = cam != null;
        boolean sentinelOk = false;
        boolean extractionGarbage = false;
        int aabbAbs = 0;
        int aabbFam2 = 0;
        float rX = 0, rY = 0, rZ = 0;
        int hitsAbs = -1;
        int hitsMeasured = -1;
        int winner = -1;
        int hits = 0;

        if (cameraOk && tlasBuilds > 0 && readyBlas > 0) {
            r.pass(String.format("相机位置: (%.2f, %.2f, %.2f)", cam[0], cam[1], cam[2]));

            // ---- S6.0/S6.2 场景信息与 raw-AABB 审计 ----
            // 测定提取顶点的空间（v0.5：包围盒判定优先——远槽全局 raw AABB
            // ⊆ [-1..17]³ 即 SECTION-LOCAL，R=(0,0,0) 精确；中位数仅作回退。
            // v0.4 的中位数估计器被部分高度 section 偏置到 (0,-2.07,0)，
            // 使胜出摆的所有 Y 法向面带 +2.07 格系统偏移——整数面取证已证）
            // native 侧已逐槽 rp() 回显证据行并缓存实测 R。
            try {
                float[] info = Native.sceneInfoNative(cam[0], cam[1], cam[2]);
                if (info != null && info.length >= 8) {
                    aabbAbs = (int) info[4];
                    aabbFam2 = (int) info[5];
                    int garbage = (int) info[6];
                    rX = info[0];
                    rY = info[1];
                    rZ = info[2];
                    extractionGarbage = garbage > 0;
                    r.info(String.format(
                            "AABB 审计汇总: ABS票=%d 局部族票=%d 垃圾槽位=%d "
                                    + "实测 R=(%.1f, %.1f, %.1f)",
                            aabbAbs, aabbFam2, garbage, info[0], info[1], info[2]));
                    if (garbage == 0) {
                        r.pass("顶点提取数据健康（无 NaN/巨跨度/越界坐标）");
                    } else {
                        r.fail(garbage + " 个槽位的提取顶点呈异常值 —— 顶点格式/stride 嫌疑");
                    }
                }
            } catch (Throwable t) {
                r.fail("sceneInfoNative 异常: " + t);
            }

            // ---- S6.1 哨兵求交（隔离 TLAS：单位实例 + 平移实例 + 阴性对照）----
            // v0.5 修复：v0.4 的哨兵顶点按 float[3][3]（36 B）打包而
            // vertexStride=16 B——第三个顶点越界读 12 B，BLAS 编码出垃圾三角形
            // （真机位码=4 的根因；仪器缺陷，非实例机制问题——真实场景
            // 锦标赛 5/6 + 终验 13/14 才是实例机制的实证）。v0.5 顶点改
            // simd_float3[3]（48 B）并新增 GPU 读回回显行。
            try {
                int bits = Native.sentinelProbeNative();
                sentinelOk = (bits & 7) == 7;
                if (sentinelOk) {
                    r.pass("哨兵求交 PASS：TLAS 实例机制 + 矩阵平移编码真机验证成立");
                } else {
                    r.fail("哨兵求交 FAIL（位码=" + bits + "，b0=单位实例 b1=平移实例 "
                            + "b2=阴性对照）—— v0.5 已修复顶点缓冲尺寸/stride 不匹配；"
                            + "若此判定复现，即为实例机制/矩阵编码的真实问题，请回报");
                }
            } catch (Throwable t) {
                r.fail("sentinelProbeNative 异常: " + t);
            }

            // ---- S6.3 双假设锦标赛（6 向 ray × 2 种实例布局模式）----
            // 模式 ABS：t=origin（顶点为世界绝对坐标的假设，v0.1–v0.3 设计假设）
            // 模式 MEASURED：t=2*origin-R（顶点为区块局部+常数偏移 R 的假设，
            // MC 1.18+ 渲染侧 translate(sectionOrigin−camera) 的已知行为）。
            try {
                Native.setPlacementModeNative(0);
                Native.frameTickNative();
                hitsAbs = castBattery(r, cam, AXIS_DIRS, AXIS_NAMES, "ABS", false);
                r.info(String.format("锦标赛 ABS（t=origin，世界绝对假设）: %d/6 命中", hitsAbs));
            } catch (Throwable t) {
                r.fail("锦标赛 ABS 异常: " + t);
            }
            try {
                Native.setPlacementModeNative(1);
                Native.frameTickNative();
                hitsMeasured = castBattery(r, cam, AXIS_DIRS, AXIS_NAMES, "MEASURED", false);
                r.info(String.format("锦标赛 MEASURED（t=2·origin−R，区块局部假设）: %d/6 命中",
                        hitsMeasured));
            } catch (Throwable t) {
                r.fail("锦标赛 MEASURED 异常: " + t);
            }

            // 选优：命中多者胜；平局（含双 0）按 AABB 审计倾向。
            if (hitsAbs >= 0 || hitsMeasured >= 0) {
                if (hitsAbs > hitsMeasured) {
                    winner = 0;
                } else if (hitsMeasured > hitsAbs) {
                    winner = 1;
                } else {
                    winner = (aabbAbs >= aabbFam2) ? 0 : 1;
                }
                r.pass(String.format(
                        "选优: winner=%s（锦标赛 ABS %d : MEASURED %d；AABB 审计 ABS %d : 局部 %d）",
                        winner == 0 ? "ABS" : "MEASURED", hitsAbs, hitsMeasured,
                        aabbAbs, aabbFam2));
            }

            // ---- S6.4 选优终验（14 ray 全向电池 + 命中区块归因）----
            if (winner >= 0) {
                try {
                    Native.setPlacementModeNative(winner);
                    Native.frameTickNative();
                    hits = castBattery(r, cam, FULL_DIRS, FULL_NAMES, "", true);
                    r.info(String.format("终验电池: %d/%d 命中（6 轴向 + 8 对角，maxDist 512）",
                            hits, FULL_DIRS.length));
                } catch (Throwable t) {
                    r.fail("终验电池异常: " + t);
                }
            }
        } else if (!cameraOk) {
            r.fail("相机不可读（直读与缓存均失败）——S6 未执行（注意：本次未发射任何 ray）");
        } else {
            r.info("TLAS 尚未构建（无就绪几何）——跳过 S6");
        }

        // ---- 判定 ----
        String verdict;
        String message;
        if (!cameraOk) {
            verdict = "CAMERA_FAIL";
            message = "相机位置不可读（直读与摄取期缓存均失败）但几何已摄取："
                    + "ready BLAS=" + readyBlas + "，三角形=" + totalTris
                    + "。本次未发射任何 ray（与 NATIVE_MISMATCH 的全 miss 不同）。"
                    + "请回报。";
        } else if (readyBlas < 1 || totalTris <= 0) {
            verdict = "SCENE_EMPTY";
            message = "摄取窗口内无就绪 BLAS（未进入世界，或区块 mesh 始终为空）。"
                    + "请进入存档后重试并等待 30 秒。";
        } else if (hits >= 1) {
            verdict = "SCENE_INGEST_OK";
            message = "真实世界几何已进入 Metal 加速结构且世界空间求交成立：ready BLAS="
                    + readyBlas + "，三角形=" + totalTris + "，终验命中 " + hits + "/"
                    + FULL_DIRS.length + "，胜出布局=" + (winner == 0
                            ? "ABS（t=origin，顶点为世界绝对坐标）"
                            : "MEASURED（t=2·origin−R，顶点为 section 局部坐标，R=("
                                    + String.format("%.1f, %.1f, %.1f", rX, rY, rZ) + ")）")
                    + "。命中面坐标应落在整数格线上（hitPoint 行自证零系统偏移）。"
                    + (sentinelOk ? "哨兵求交 PASS。" : "⚠哨兵未过但真实求交命中——异常状态，请回报。")
                    + (extractionGarbage ? "注意：存在异常 AABB 槽位（详见 [aabb] 行）。" : "")
                    + "2.1 场景摄取成立 —— 2.2 路径追踪引擎绿灯。";
        } else if (!sentinelOk) {
            verdict = "SENTINEL_FAIL";
            message = "哨兵求交失败（隔离 TLAS 的单位/平移实例验证未通过）——"
                    + "实例机制或矩阵编码问题，与场景数据无关。"
                    + "请附整份报告回报（[sentinel] 行）。";
        } else if (extractionGarbage) {
            verdict = "EXTRACTION_GARBAGE";
            message = "顶点提取存在异常数据（NaN/巨跨度/越界坐标）且双假设锦标赛均未命中 ——"
                    + "顶点格式偏移/stride 嫌疑（详见 [aabb] 行）。请附整份报告回报。";
        } else {
            verdict = "NATIVE_MISMATCH";
            message = "哨兵通过 + AABB 健康 + 双假设锦标赛均 0 命中（ABS " + hitsAbs
                    + " : MEASURED " + hitsMeasured + "）——顶点空间既非世界绝对也非"
                    + "区块局部(±R)，或存在更深的实例摆放问题。请附整份报告回报"
                    + "（[aabb]/[sentinel]/锦标赛行均已在场）。";
        }
        r.finish(verdict, message);

        // ---- 收尾释放 ----
        try {
            Native.shutdownNative();
        } catch (Throwable ignored) {
        }
    }

    // ---- v0.4 诊断矩阵的 ray 电池 ----
    private static final float[][] AXIS_DIRS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
    };
    private static final String[] AXIS_NAMES = {"+X", "-X", "+Y(上)", "-Y(下)", "+Z", "-Z"};
    private static final float[][] FULL_DIRS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
        {1, 1, 1}, {1, 1, -1}, {1, -1, 1}, {1, -1, -1},
        {-1, 1, 1}, {-1, 1, -1}, {-1, -1, 1}, {-1, -1, -1},
    };
    private static final String[] FULL_NAMES = {
        "+X", "-X", "+Y(上)", "-Y(下)", "+Z", "-Z",
        "对角+++", "对角++-", "对角+-+", "对角+--",
        "对角-++", "对角-+-", "对角--+", "对角---",
    };

    /**
     * 发射一组 ray（native 侧归一化方向）；tag 非空时附加在每行尾部用于
     * 锦标赛区分；attribute=true 时对命中微 instance 归因到 section origin。
     * v0.5：命中行回显 hitPoint 世界坐标——正确摆放下命中面应落在整数格线
     * （报告内直接目检零系统偏移；v0.4 的 +2.07 格 Y 偏移正是靠这组整数面
     * 取证发现的）。
     */
    private static int castBattery(ProbeReport r, float[] cam, float[][] dirs,
            String[] names, String tag, boolean attribute) {
        int hits = 0;
        for (int i = 0; i < dirs.length; i++) {
            String label = tag.isEmpty() ? names[i] : names[i] + " [" + tag + "]";
            try {
                float[] res = Native.castRayNative(cam[0], cam[1], cam[2],
                        dirs[i][0], dirs[i][1], dirs[i][2], 512f);
                if (res != null && res[0] > 0.5f) {
                    hits++;
                    float len = (float) Math.sqrt(dirs[i][0] * dirs[i][0]
                            + dirs[i][1] * dirs[i][1] + dirs[i][2] * dirs[i][2]);
                    float px = cam[0] + res[1] * dirs[i][0] / len;
                    float py = cam[1] + res[1] * dirs[i][1] / len;
                    float pz = cam[2] + res[1] * dirs[i][2] / len;
                    String attr = "";
                    if (attribute) {
                        int[] org = Native.instanceOriginNative((int) res[2]);
                        if (org != null) {
                            attr = String.format(" → 命中区块 section=(%d,%d,%d)",
                                    org[0], org[1], org[2]);
                        }
                    }
                    r.pass(String.format(
                            "ray %s: HIT distance=%.2f hitPoint=(%.2f, %.2f, %.2f)"
                                    + " (instance=%d)%s",
                            label, res[1], px, py, pz, (int) res[2], attr));
                } else {
                    r.info("ray " + label + ": miss（512 格内无几何）");
                }
            } catch (Throwable t) {
                r.fail("castRay " + label + " 异常: " + t);
            }
        }
        return hits;
    }

    private static float[] cameraPos() throws Exception {
        Object mc = Mappings.call(null, Mappings.C_MC, Mappings.M_MC_GET_INSTANCE);
        Object gameRenderer = Mappings.get(mc, Mappings.C_MC, Mappings.F_MC_GAME_RENDERER);
        Object camera = Mappings.get(gameRenderer, Mappings.C_GAME_RENDERER, Mappings.F_CAMERA_POS);
        Object pos = Mappings.call(camera, Mappings.C_CAMERA, Mappings.M_CAMERA_GET_POS);
        float x = ((Double) Mappings.get(pos, Mappings.C_VEC3D, Mappings.F_VEC3D_X)).floatValue();
        float y = ((Double) Mappings.get(pos, Mappings.C_VEC3D, Mappings.F_VEC3D_Y)).floatValue();
        float z = ((Double) Mappings.get(pos, Mappings.C_VEC3D, Mappings.F_VEC3D_Z)).floatValue();
        return new float[] {x, y, z};
    }

    private static boolean isDisabled() {
        return Boolean.getBoolean("sceneingest.disabled")
                || "1".equals(System.getenv("SCENEINGEST_DISABLED"));
    }

    private static void envSnapshot(ProbeReport r) {
        r.info("java.version  = " + System.getProperty("java.version"));
        r.info("java.vm.name  = " + System.getProperty("java.vm.name"));
        r.info("os.name       = " + System.getProperty("os.name") + " / "
                + System.getProperty("os.arch"));
        r.info("user.dir      = " + System.getProperty("user.dir"));
        r.info("AMETHYST_RENDERER = " + System.getenv("AMETHYST_RENDERER"));
        r.info("GALLIUM_DRIVER     = " + System.getenv("GALLIUM_DRIVER"));
        r.info("pojav.lwjgl.version = " + System.getProperty("pojav.lwjgl.version"));
        r.info("org.lwjgl.opengl.libname = " + System.getProperty("org.lwjgl.opengl.libname"));
        r.info("org.lwjgl.vulkan.libname = " + System.getProperty("org.lwjgl.vulkan.libname"));
    }

    private static Path extract(String resource, Path dir) throws Exception {
        Path out = dir.resolve(Paths.get(resource).getFileName());
        try (InputStream in = SceneIngestionMod.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("JAR 资产缺失: " + resource);
            }
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }
}
