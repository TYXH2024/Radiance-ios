package net.prisma.pathtrace;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 路径追踪主控 —— Radiance 移植第二章 2.2 阶段交付物（path tracing 引擎首帧）
 * + 2.3 v0.4 实时会话（渐进累积 + GL 合成 + atlas albedo）。
 *
 * 验证问题（docs/chapter2-pathtrace-design.md）：
 *   Q1 摄取链在 2.2 摆放契约（免 recentring + t=origin）下是否复现 2.1 的
 *      健康度（600 区块 / ~169k 三角 / 零积压）？
 *   Q2 简化摆放是否真机等价（整数格线抽查 + 哨兵）？
 *   Q3 全链路径追踪能否渲染出一帧可目检的图（相机 ray → TLAS → albedo →
 *      太阳直射+阴影 ray → 漫反射弹射 → 累积 → tonemap → PNG）？
 *   Q4 实测吞吐（atomic 计数器，非估算）是多少 Mray/s？
 *   Q5（2.3）渐进累积 + GL 合成能否把 RT 画面真正叠进游戏帧（眼见为实）？
 *   Q6（2.3）atlas 采样能否补全 albedo 色彩（v0.3 证据：distinct=16 寡色）？
 *
 * 执行序（报告 S 段）：
 *   S0 环境快照 → S1 解包 dylib/metallib → S2 System.load →
 *   S3 反射符号审计 → S4/S5 网格发现与摄取 → S5 统计汇总 →
 *   S6 场景摆放抽查（哨兵 + 相机姿态 + 整数格线）→
 *   S7 离屏路径追踪渲染（PNG 落盘 + 统计自证）→ VERDICT →
 *   S8 实时会话（mixin swap 钩子 + GL 合成器 + atlas 采集 + 累积循环 +
 *   快照 + 实时判定；会话持续到游戏退出，不 shutdown）。
 *
 * 归因开关：-Dpathtrace.disabled=true / PATHTRACE_DISABLED=1
 * → 只写环境快照，零原生调用（闪退 A/B 对照）。
 * 合成开关：-Dpathtrace.compose=false → S8 累积/快照照常，仅不上屏
 * （mixin 已注入但钩子永远 OFF）。
 */
public final class PathtraceMod {

    public static void main(String[] args) {
        runAll("standalone-main");
    }

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);

    // ---- S7 渲染参数（v0.1 固定；2.2 后续版本做渐进/实时）----
    private static final int RENDER_W = 480;
    private static final int RENDER_H = 270;
    private static final int RENDER_SPP = 24;
    private static final int RENDER_BOUNCES = 2;
    // 2.3 实时会话参数（可系统属性调优）。
    private static final int LIVE_W = intProp("pathtrace.rtW", 480);
    private static final int LIVE_H = intProp("pathtrace.rtH", 270);
    /** 包内可见（LiveSession 的 camHit 分母——v0.4.1 分母修复）。 */
    static final int LIVE_SPP = intProp("pathtrace.spp", 4);

    static int intProp(String name, int dflt) {
        try {
            return Integer.parseInt(System.getProperty(name, "" + dflt).trim());
        } catch (Throwable t) {
            return dflt;
        }
    }

    /**
     * v0.4.2: 布尔开关语义修正。v0.4/v0.4.1 的 composeEnabled() 用
     * Boolean.getBoolean("pathtrace.compose")——它返回的是属性字符串的布尔
     * 【值】（"false"→false），取反后 -Dpathtrace.compose=false 实际上把合成
     * 【打开】了：文档承诺的归因开关从未真正关掉过（两轮真机均未触发此路径，
     * 故未污染现场，但仪器是坏的）。统一改为字符串判定：false/0/off = 关。
     */
    static boolean flagProp(String name, boolean dflt) {
        String v = System.getProperty(name, dflt ? "true" : "false").trim();
        return !("false".equalsIgnoreCase(v) || "0".equals(v) || "off".equalsIgnoreCase(v));
    }

    static void runAll(String triggerSource) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        ProbeReport r = new ProbeReport("pathtrace_report.txt");
        r.header("路径追踪探针 (pathtrace 0.4.8)");
        r.info("触发方式: " + triggerSource);

        if (isDisabled()) {
            r.stage("S0 环境快照（探针已禁用: pathtrace.disabled）");
            envSnapshot(r);
            r.finish("-", "探针被 pathtrace.disabled 禁用，本次未解包/未装载/未执行任何原生调用。"
                    + "用途：闪退归因对照。");
            return;
        }

        r.stage("S0 环境快照");
        envSnapshot(r);

        // ---- S1 解包 ----
        r.stage("S1 解包原生资产（JAR -> game 目录）");
        Path dir = Paths.get(System.getProperty("user.dir", "."), "pathtrace");
        Path dylib;
        Path metallib;
        try {
            Files.createDirectories(dir);
            dylib = extract("/pathtrace/libradiance_pathtrace.dylib", dir);
            metallib = extract("/pathtrace/pathtrace.metallib", dir);
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
            r.pass("dylib 装载成功（与 2.0/2.1 相同的受信装载链）");
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
                    + " 个，报告已逐项列出）。2.2 新增符号（VertexFormatElement.COLOR/"
                    + "getOffset/has、Camera yaw/pitch）若在此失败，即为 mappings 与真机"
                    + "运行时的真实不一致。附整份报告回报。");
            return;
        }
        r.pass("符号表全量解析通过（" + Mappings.RESOLVED.size() + " 项）");

        // ---- S4/S5 网格发现 + 摄取（SceneProxy 主循环阻塞运行）----
        r.stage("S4/S5 网格发现与摄取（轮询 500ms，等待进入世界；顶点+COLOR 双提取）");
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
        r.info(String.format("Native 侧: readyBLAS=%d BLAS构建=%d TLAS重建=%d（含 shading pools）",
                readyBlas, blasBuilds, tlasBuilds));
        r.info(String.format("几何: 三角形=%d 顶点=%d 顶点缓冲=%.1f KB 索引缓冲=%.1f KB "
                        + "TLAS=%.1f KB",
                totalTris, totalVerts, vertexBytes / 1024.0, indexBytes / 1024.0,
                tlasBytes / 1024.0));

        // =================================================================
        // S6 场景摆放抽查（2.2 契约：顶点原样入库 + t=origin）
        // =================================================================
        r.stage("S6 场景摆放抽查（哨兵 + 相机姿态 + 整数格线）");
        float[] pose = null;
        try {
            pose = cameraPose();
        } catch (Throwable t) {
            r.fail("相机姿态读取异常", t);
        }
        boolean cameraOk = pose != null;
        if (cameraOk) {
            r.pass(String.format("相机姿态: pos=(%.2f, %.2f, %.2f) yaw=%.1f pitch=%.1f",
                    pose[0], pose[1], pose[2], pose[3], pose[4]));
        } else {
            r.fail("相机姿态不可读（直读失败）——S6/S7 未执行");
        }

        boolean sentinelOk = false;
        int sentinelBits = 0;
        try {
            sentinelBits = Native.sentinelProbeNative();
            sentinelOk = sentinelBits == 7;
            if (sentinelOk) {
                r.pass("哨兵求交 PASS（位码=7：单位实例/平移实例/阴性对照全部正确——"
                        + "矩阵平移编码 flat[9..11] 维持 2.1 v0.5 真机结论）");
            } else {
                r.fail("哨兵求交 FAIL（位码=" + sentinelBits + "）——"
                        + "实例机制/矩阵编码回归，与 2.1 结论冲突，请回报。");
            }
        } catch (Throwable t) {
            r.fail("哨兵求交异常", t);
        }

        // 整数格线抽查：6 轴向 ray；正确摆放下命中面必落在整数格线
        //（水面/植被等非立方几何除外——与 2.1 v0.5 的 8/9 一致性预期）。
        // v0.3 A/B 复测仪器：v0.1/v0.2 两轮真机首轮均全 miss（0/6），而
        // v0.2 审计电池在同一 TLAS 上、前面有 build_iso_tlas 时命中
        // 3/6（hitPoint 与 v0.5 完全一致）——判别变量 = 探测线程上是否刚
        // 有新鲜 AS build。首轮全 miss ⇒ refreshTlasNative 重锚后复测：
        // 复测命中 ⇒ 机制当场坐实（报告两轮行即 A/B 证据）；仍全 miss ⇒
        // S6.5 审计照常判别（证据链见 metal_pathtrace.m refreshTlasNative）。
        int[] spot = {0, 0, 0};
        if (cameraOk && tlasBuilds > 0 && readyBlas > 0) {
            spot = castAxisBattery(r, pose, "");
            if (spot[0] == 0) {
                r.info("首轮六向全 miss —— v0.3 仪器：探测线程重锚 TLAS 后复测"
                        + "（复测命中即当场坐实机制；仍全 miss 走 S6.5 审计判别）");
                try {
                    Native.refreshTlasNative();
                    spot = castAxisBattery(r, pose, "复测 ");
                } catch (Throwable t) {
                    r.fail("refreshTlasNative 异常: " + t);
                }
            }
        } else if (!cameraOk) {
            // already reported above
        } else {
            r.fail("TLAS 尚未构建（无就绪几何）——S6/S7 未执行");
        }
        int spotHits = spot[0];
        int integerHits = spot[1];
        boolean downHit = spot[2] > 0;

        boolean placementOk = cameraOk && tlasBuilds > 0 && readyBlas > 0
                && downHit && integerHits >= 2;
        if (cameraOk && tlasBuilds > 0 && readyBlas > 0 && !placementOk) {
            r.fail(String.format("摆放抽查未达标（命中=%d 整数格线=%d 脚下地板=%s）——"
                            + "v0.3 已携带 A/B 复测与 S6.5 审计；请附整份报告回报"
                            + "（v0.5 同场景：-Y 1.62 命中地板、8/9 整数格线；"
                            + "v0.2 审计已证 LIVE TLAS 内容正确）",
                    spotHits, integerHits, downHit ? "有" : "无"));
        } else if (placementOk) {
            r.pass(String.format("摆放抽查 PASS（%d 命中 / %d 整数格线 / 脚下地板命中——"
                            + "免 recentring + t=origin 与 2.1 已验证布局等价）",
                    spotHits, integerHits));
        }

        // =================================================================
        // S6.5 判别审计（v0.2 新增：PLACEMENT_FAIL 根因分离仪器）
        // =================================================================
        int[] audit = null;
        if (cameraOk && tlasBuilds > 0 && readyBlas > 0) {
            r.stage("S6.5 判别审计（raw AABB / 顶点回读 / 矩阵回读 / 隔离 TLAS 对照电池）");
            try {
                audit = Native.sceneAuditNative(pose[0], pose[1], pose[2]);
                if (audit != null && audit.length >= 8) {
                    String rawV = audit[0] == 1 ? "SECTION-LOCAL（2.1 契约成立）"
                            : audit[0] == 2 ? "非 section-local（顶点空间异常）"
                            : audit[0] == 3 ? "GARBAGE（提取字节坏）" : "无有效槽位";
                    r.info(String.format(
                            "审计汇总: raw=%s 相机槽位=%d live矩阵=%s "
                                    + "ISO命中=%d/6(格线%d) LIVE命中=%d/6",
                            rawV, audit[1],
                            audit[2] == 1 ? "正确" : "异常（非 translate(origin)！）",
                            audit[3], audit[4], audit[6]));
                }
            } catch (Throwable t) {
                r.fail("sceneAuditNative 异常", t);
            }
        }

        // =================================================================
        // S7 离屏路径追踪渲染
        // =================================================================
        r.stage("S7 离屏路径追踪渲染（相机 ray → TLAS → albedo → 太阳直射+阴影 → "
                + "弹射 → 累积 → tonemap → PNG）");
        int[] image = null;
        boolean isoRender = false;
        if (placementOk) {
            // v0.3: 本分支内所有 g_tlas 查询（中心 ray 交叉验证 + live 渲染）
            // 都走 S6 同通道——先重锚（毫秒级，幂等），证据行与渲染同判。
            // 若首轮电池未触发重锚（直接命中的路径），这里补上；若 A/B 复测
            // 已重锚，重建同一就绪集，无副作用。
            try {
                Native.refreshTlasNative();
            } catch (Throwable t) {
                r.fail("渲染前 refreshTlasNative 异常: " + t);
            }

            // 中心 ray 交叉验证：单 ray 仪器（scene_trace_kernel）与渲染 kernel
            // 使用同一 TLAS——中心像素内容应与此预测一致（证据行，不参与判定）。
            try {
                float yawR = (float) Math.toRadians(pose[3]);
                float pitchR = (float) Math.toRadians(pose[4]);
                float fx = (float) (-Math.sin(yawR) * Math.cos(pitchR));
                float fy = (float) (-Math.sin(pitchR));
                float fz = (float) (Math.cos(yawR) * Math.cos(pitchR));
                float[] res = Native.castRayNative(pose[0], pose[1], pose[2], fx, fy, fz, 1024f);
                if (res != null && res[0] > 0.5f) {
                    r.pass(String.format(
                            "中心 ray 预测: HIT distance=%.2f hitPoint=(%.2f, %.2f, %.2f)"
                                    + " —— 画面中心应为几何表面",
                            res[1], pose[0] + res[1] * fx, pose[1] + res[1] * fy,
                            pose[2] + res[1] * fz));
                } else {
                    r.info("中心 ray 预测: miss —— 画面中心应为天空（相机朝天/朝空）");
                }
            } catch (Throwable t) {
                r.fail("中心 ray 交叉验证异常: " + t);
            }

            try {
                image = Native.renderPathtraceNative(pose[0], pose[1], pose[2],
                        pose[3], pose[4], RENDER_W, RENDER_H, RENDER_SPP, RENDER_BOUNCES);
                if (image == null) {
                    r.fail("渲染派发失败（native 返回 null；详见 [render] 行）");
                }
            } catch (Throwable t) {
                r.fail("renderPathtraceNative 异常", t);
            }
        } else if (cameraOk && tlasBuilds > 0 && readyBlas > 0) {
            // v0.2 诊断渲染通道：live 摆放未过——用隔离单实例 TLAS（相机区块
            // BLAS + translate(origin)，同一管线/同一 shading 池）渲染同一帧。
            // 成图 ⇒ 渲染链全链健康，缺陷精确定位到 multi-instance TLAS 组装。
            r.info("摆放抽查未通过——启动隔离渲染诊断通道（相机区块单实例 TLAS，"
                    + "同一 BLAS/管线/池；或即第一帧光，或坐实 BLAS 内容缺陷）");
            try {
                image = Native.renderIsolatedNative(pose[0], pose[1], pose[2],
                        pose[3], pose[4], RENDER_W, RENDER_H, RENDER_SPP, RENDER_BOUNCES);
                isoRender = image != null;
                if (image == null) {
                    r.fail("隔离渲染派发失败（native null；详见 [render-iso]/[render] 行）");
                }
            } catch (Throwable t) {
                r.fail("renderIsolatedNative 异常", t);
            }
        } else if (cameraOk) {
            r.info("场景空——渲染跳过");
        }

        // ---- PNG 落盘 + 图像统计自证 ----
        Path pngPath = Paths.get(System.getProperty("user.dir", "."), "pathtrace_frame.png");
        boolean imageOk = false;
        if (image != null) {
            try {
                PngWriter.write(pngPath, RENDER_W, RENDER_H, image);
                r.pass("PNG 落盘: " + pngPath + " (" + Files.size(pngPath) + " B, "
                        + RENDER_W + "x" + RENDER_H + ", spp=" + RENDER_SPP
                        + ", bounces=" + RENDER_BOUNCES + ")"
                        + (isoRender ? "——隔离诊断帧（相机区块，非全场景）" : ""));
                imageOk = analyzeImage(r, image, RENDER_W, RENDER_H, isoRender);
            } catch (Throwable t) {
                r.fail("PNG 写盘/统计异常", t);
            }
        }

        // =================================================================
        // 判定（零粉饰纪律：每一档都有明确的失败语义与回报请求）
        // =================================================================
        String verdict;
        String message;
        if (!cameraOk) {
            verdict = "CAMERA_FAIL";
            message = "相机姿态不可读——S6/S7 未执行。附整份报告回报。";
        } else if (tlasBuilds == 0 || readyBlas == 0) {
            verdict = "SCENE_EMPTY";
            message = "TLAS 未构建或零就绪 BLAS——摄取未完成（对比 2.1 v0.5: 157 ready/"
                    + "169k 三角）。附整份报告回报。";
        } else if (!sentinelOk) {
            verdict = "SENTINEL_FAIL";
            message = "哨兵求交失败（位码=" + sentinelBits + "）——实例机制或矩阵编码回归，"
                    + "与 2.1 v0.5 真机结论冲突。请附整份报告回报（[sentinel] 行）。";
        } else if (!placementOk && isoRender && imageOk) {
            verdict = "ISO_RENDER_OK";
            message = "渲染链全链健康（隔离单实例 TLAS 出图 + 图像统计自证）——"
                    + "live 摆放未过但缺陷已精确隔离：结合 [audit] 行判读"
                    + "（ISO命中=" + (audit != null ? audit[3] : -1) + "/6 vs LIVE命中="
                    + (audit != null ? audit[6] : -1) + "/6）。若 ISO 命中而 LIVE 全"
                    + " miss ⇒ multi-instance TLAS 组装缺陷（2.3 修复目标即此）；"
                    + "若 ISO 也 miss ⇒ 相机 BLAS 内容缺陷（看 raw AABB 与顶点回读行）。"
                    + "pathtrace_frame.png 为相机区块的真实路径追踪画面（隔离通道）。";
        } else if (!placementOk) {
            verdict = "PLACEMENT_FAIL";
            message = "摆放抽查未达标（脚下地板缺失或整数格线不足 2）。"
                    + "v0.2 判别仪器已运行：看 [audit] 行（raw AABB 判定 / 顶点回读 / "
                    + "矩阵回读 / ISO-LIVE 对照电池）定位根因后回报。";
        } else if (image == null) {
            verdict = "RENDER_FAIL";
            message = "摆放与哨兵均通过，但路径追踪派发失败（native null / 异常；"
                    + "详见 [render] 行）。请附整份报告回报。";
        } else if (!imageOk) {
            verdict = "IMAGE_DEGENERATE";
            message = "渲染完成但图像统计退化（全黑/平场/颜色数不足——详见 S7 统计行）。"
                    + "请附整份报告与 pathtrace_frame.png 回报。";
        } else {
            verdict = "PATHTRACE_OK";
            message = "2.2 路径追踪引擎首帧成立：摄取链在简化摆放契约下复现健康度，"
                    + "哨兵与整数格线维持 2.1 结论，离屏路径追踪渲染出可目检图像"
                    + "（pathtrace_frame.png，眼见为实；camHit%/分带亮度见 "
                    + "[render]/图像统计行）。实测吞吐见 [render] 行"
                    + "（atomic 计数，非估算）。—— 2.2 达成：下一步渐进积累/"
                    + "实时化与 GL 管线合成（2.3）。";
        }
        r.finish(verdict, message);

        // =================================================================
        // S8 实时会话（2.3 v0.4：渐进累积 + GL 合成 + atlas albedo）
        // =================================================================
        // 门槛：相机可读 + TLAS 已构建 + 有就绪 BLAS（摆设抽查失败也允许——
        // 实时画面本身即诊断证据：出世界图 = 渲染链健康；纯天空 = 摆放问题
        // 延续。哨兵失败除外——实例机制坏了，画面无意义）。
        boolean liveEligible = cameraOk && tlasBuilds > 0 && readyBlas > 0
                && sentinelOk;
        boolean liveStarted = false;
        if (liveEligible) {
            r.stage("S8 实时会话（渐进累积 + GL 合成 + atlas albedo——眼见为实）");
            try {
                liveStarted = Native.liveBeginNative(LIVE_W, LIVE_H, LIVE_SPP,
                        RENDER_BOUNCES);
            } catch (Throwable t) {
                r.fail("liveBeginNative 异常: " + t);
            }
            if (liveStarted) {
                r.pass(String.format("[S8.0] 实时会话开启: %dx%d spp/帧=%d bounces=%d"
                        + "（静止收敛 / 移动重置；场景变化 native 自检重置）",
                        LIVE_W, LIVE_H, LIVE_SPP, RENDER_BOUNCES));
                if (composeEnabled()) {
                    PathtraceHook.arm(r);
                } else {
                    r.info("[S8.0] 合成关闭（pathtrace.compose=false）——累积与"
                            + "快照照常，仅不上屏；mixin 钩子保持 OFF");
                }
                LiveSession live = new LiveSession(r);
                Thread t = new Thread(live, "Pathtrace-Live");
                t.setDaemon(true);
                long liveStartNs = System.nanoTime();
                t.start();
                // 等观察窗收口（报告完整性）+ 30s 探针线程定时重锚（v0.4.1：
                // TLAS 构建职责在探针线程——v0.4 真机判读 worker 重建的
                // TLAS 对 live 射线全 miss；本次为 A/B 后半段：探针构建 +
                // worker 查询，[S8.4] 行留证，[live]/[live-sentinel] 行
                // 前后对比自证）。
                // v0.4.7: 二分梯模式下观察窗扩到五段（+尾量）——deadline
                // 同步加长，保证重锚/交叉复测消费器活到梯子收口。
                long watchDeadlineMs = PathtraceMod.bisectEnabled()
                        ? PathtraceMod.bisectWindowMs() + 30_000L
                        : 120_000L;   // v0.4.8: 90s 观察窗 + 30s 余量（随后进入 ping-pong 维护环）
                long deadline = System.nanoTime() + watchDeadlineMs * 1_000_000L;
                boolean anchored = false;
                int healExecuted = 0; // v0.4.4: 自愈重锚执行计数（[S8.7] 行序号）
                while (!live.windowDone && System.nanoTime() < deadline) {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (!anchored
                            && System.nanoTime() - liveStartNs >= 30_000_000_000L) {
                        anchored = true;
                        try {
                            Native.refreshTlasNative();
                            r.pass("[S8.4] 探针线程 30s 定时重锚（A/B 后半段："
                                    + "探针构建 + worker 查询——对比 [live] 行"
                                    + " 30s 前后的 camHit）");
                            // v0.4.2: 重锚后探针线程自己也打一轮电池——v0.3
                            // 的实证模式（新鲜 AS build 后同线程查询）。若
                            // probe 命中而 worker 仍 0/6（[live-sentinel 32s]），
                            // 线程归责一步定谳；若 probe 也 miss，则重锚没用
                            // 上（旧 TLAS 本身已毒化，见 [probe-sentinel] 行）。
                            probeBattery(r, "S8.4 重锚后（探针线程）");
                        } catch (Throwable tx) {
                            r.fail("[S8.4] 重锚异常: " + tx);
                        }
                    }
                    // v0.4.2→v0.4.4: worker 哨兵 0/6 → 探针线程交叉复测（冷却
                    // 式，5s 一发——30s 重锚后仍有新鲜对照）。
                    if (probeBatteryReq) {
                        probeBatteryReq = false;
                        probeCrossCount++;
                        probeBattery(r, "worker 0/6 交叉复测#" + probeCrossCount);
                    }
                    // v0.4.4: 自愈重锚——LiveSession 检出 camHit 连续归零 + 哨兵
                    // 双死后置位。探针线程重建（v0.4.1 实证职责划分）；重建
                    // 触发 [live] reset，恢复即真噪点，再归零即持续型毒化铁证。
                    if (reanchorReq) {
                        reanchorReq = false;
                        healExecuted++;
                        try {
                            Native.refreshTlasNative();
                            r.pass(String.format(
                                    "[S8.7] 自愈重锚 #%d（探针线程执行——camHit"
                                            + " 连续归零 + 哨兵双死触发；对比重建"
                                            + " 前后 [live] 行 tlas=build# 与"
                                            + " camHit）", healExecuted));
                            probeBattery(r, "自愈重锚后（探针线程）");
                        } catch (Throwable tx) {
                            r.fail("[S8.7] 自愈重锚异常: " + tx);
                        }
                    }
                    // v0.4.8: ping-pong 替换件节拍（本循环 500ms 一拍——节拍即循环体；
                    // 同内容重建不清累积 + 毒帧守卫在岗）
                    if (pingPongActive && pingPongEnabled()) {
                        try {
                            Native.pingpongNative();
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (!live.windowDone) {
                    r.info("[S8.6] 观察窗 " + (watchDeadlineMs / 1000)
                            + "s 未收口（异常）——实时判定块可能延迟，会话仍在运行");
                }
                // v0.4.8: ping-pong 维护环——观察窗收口后探针线程不再退出
                //（v0.4.7 及以前：deadline 后 reanchorReq 无人消费，TLAS 永久
                // 死亡直到重进世界）。本环活到游戏退出（daemon 线程）：ping-pong
                // 节拍 + 自愈重锚兜底 + 交叉复测消费。
                while (true) {
                    try {
                        Thread.sleep(500L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    if (pingPongActive && pingPongEnabled()) {
                        try {
                            Native.pingpongNative();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (probeBatteryReq) {
                        probeBatteryReq = false;
                        probeCrossCount++;
                        try {
                            probeBattery(r, "窗口后交叉复测#" + probeCrossCount);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (reanchorReq) {
                        reanchorReq = false;
                        try {
                            Native.refreshTlasNative();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } else {
                r.fail("[S8.0] liveBeginNative 拒绝（见 [live] 行）——实时会话"
                        + "未开启");
            }
        } else {
            r.info("S8 跳过（相机/哨兵/场景门槛未过——见前序判定）");
        }

        // ---- 收尾释放（仅当实时会话未开启；开启则会话持续到游戏退出） ----
        if (!liveStarted) {
            try {
                Native.shutdownNative();
            } catch (Throwable ignored) {
            }
        } else {
            r.info("[S8] 会话继续运行（不上屏即熄）——原生资源随游戏进程退出"
                    + "释放");
        }
    }

    // ---- 工具 ----

    /**
     * v0.4.2：探针线程交叉复测电池（[probe-sentinel] 行）。与 worker 侧
     * [live-sentinel] 同一组六轴 ray、同一 TLAS，仅派发线程不同——
     * 读法：probe 命中 + worker 0/6 ⇒ worker 派发路径坏（线程归责）；
     * 两者皆 miss ⇒ AS 内容坏（BLAS/TLAS 毒化）。姿态一并回显
     * （NaN/漂移在此行直接现形，一步杀掉"相机数据污染"假说）。
     */
    private static void probeBattery(ProbeReport r, String tag) {
        try {
            float[] pose = cameraPose();
            int[] res = castAxisBattery(r, pose, "probe");
            r.pass(String.format(
                    "[probe-sentinel %s] 探针线程直查 TLAS：%d/6 命中"
                            + "（格线 %d，-Y 脚下 %d）| cam=(%.2f, %.2f, %.2f)"
                            + " yaw=%.1f pitch=%.1f —— 与 [live-sentinel] 行互证",
                    tag, res[0], res[1], res[2], pose[0], pose[1], pose[2],
                    pose[3], pose[4]));
        } catch (Throwable t) {
            r.fail("[probe-sentinel " + tag + "] 异常: " + t);
        }
    }

    /**
     * v0.3: 六轴 ray 电池（原 S6 内联逻辑提取）——返回 int[3]：
     * [0] 命中数 [1] 整数格线数 [2] -Y(脚下)命中数。tag 非空时附加在每行
     * 尾部（首轮/复测 A/B 回显）。
     */
    private static int[] castAxisBattery(ProbeReport r, float[] pose, String tag) {
        float[][] dirs = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
        };
        String[] names = {"+X", "-X", "+Y(上)", "-Y(下)", "+Z", "-Z"};
        int hits = 0;
        int grid = 0;
        int down = 0;
        for (int i = 0; i < dirs.length; i++) {
            String label = tag.isEmpty() ? names[i] : names[i] + " [" + tag + "]";
            try {
                float[] res = Native.castRayNative(pose[0], pose[1], pose[2],
                        dirs[i][0], dirs[i][1], dirs[i][2], 512f);
                if (res != null && res[0] > 0.5f) {
                    hits++;
                    float px = pose[0] + res[1] * dirs[i][0];
                    float py = pose[1] + res[1] * dirs[i][1];
                    float pz = pose[2] + res[1] * dirs[i][2];
                    boolean ix = nearInteger(px);
                    boolean iy = nearInteger(py);
                    boolean iz = nearInteger(pz);
                    boolean g = ix || iy || iz;
                    if (g) {
                        grid++;
                    }
                    if (i == 3) {
                        down++;
                    }
                    String gridTag = g
                            ? String.format("整数格线 %s%s%s",
                                    ix ? "x" : "", iy ? "y" : "", iz ? "z" : "")
                            : "非整数（非立方几何？）";
                    int[] org = Native.instanceOriginNative((int) res[2]);
                    String attr = org != null
                            ? String.format(" → section=(%d,%d,%d)", org[0], org[1], org[2])
                            : "";
                    r.pass(String.format(
                            "ray %s: HIT distance=%.2f hitPoint=(%.2f, %.2f, %.2f)"
                                    + " [%s] (instance=%d)%s",
                            label, res[1], px, py, pz, gridTag, (int) res[2], attr));
                } else {
                    r.info("ray " + label + ": miss（512 格内无几何）");
                }
            } catch (Throwable t) {
                r.fail("castRay " + label + " 异常: " + t);
            }
        }
        return new int[] {hits, grid, down};
    }

    private static boolean nearInteger(float v) {
        float d = Math.abs(v - Math.round(v));
        return d < 0.05f;
    }

    /**
     * 图像统计自证（零粉饰）：亮度分布 + 量化颜色多样性 + 主色占比 +
     * v0.3 分带亮度（上/中/下 1/3——天空/地形分布的直接证据行）。
     * 返回 true = 图像非退化。
     *
     * v0.3 ISO 诊断档：ISO 帧只做"渲染链健康性"判定（非黑场/非单色），
     * 不做结构判定——单区块 + 任意视角可合法地近乎平场（v0.2 实测：相机
     * 仰视 21°，ISO 帧 = 天空渐变 + 底缘地板，370 精确色但 4-bit 量化仅
     * 6 色）。结构门槛（range/distinct）仅对全场景帧生效——那才是交付物。
     */
    private static boolean analyzeImage(ProbeReport r, int[] argb, int w, int h,
            boolean isoFrame) {
        double sum = 0;
        double min = 1;
        double max = 0;
        int[] buckets = new int[4096];
        double[] bandSum = new double[3];
        int[] bandCnt = new int[3];
        for (int y = 0; y < h; y++) {
            int band = Math.min(2, y * 3 / h);
            for (int x = 0; x < w; x++) {
                int p = argb[y * w + x];
                int rr = (p >> 16) & 0xFF;
                int gg = (p >> 8) & 0xFF;
                int bb = p & 0xFF;
                double l = (0.2126 * rr + 0.7152 * gg + 0.0722 * bb) / 255.0;
                sum += l;
                bandSum[band] += l;
                bandCnt[band]++;
                if (l < min) { min = l; }
                if (l > max) { max = l; }
                buckets[((rr >> 4) << 8) | ((gg >> 4) << 4) | (bb >> 4)]++;
            }
        }
        double mean = sum / argb.length;
        int distinct = 0;
        for (int b : buckets) {
            if (b > 0) { distinct++; }
        }
        r.info(String.format("图像统计: 平均亮度=%.3f 范围=[%.3f..%.3f] 量化颜色数=%d",
                mean, min, max, distinct));
        r.info(String.format("分带亮度: 上1/3=%.3f 中1/3=%.3f 下1/3=%.3f",
                bandSum[0] / bandCnt[0], bandSum[1] / bandCnt[1],
                bandSum[2] / bandCnt[2]));
        // 主色 top-4（证据行：天空色/地形色应可辨识）
        int[] topIdx = {-1, -1, -1, -1};
        for (int k = 0; k < 4; k++) {
            int best = -1;
            int bestCount = -1;
            for (int i = 0; i < buckets.length; i++) {
                if (buckets[i] > bestCount) {
                    boolean already = false;
                    for (int j = 0; j < k; j++) {
                        if (topIdx[j] == i) { already = true; }
                    }
                    if (!already) {
                        best = i;
                        bestCount = buckets[i];
                    }
                }
            }
            topIdx[k] = best;
        }
        StringBuilder top = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            if (topIdx[k] < 0 || buckets[topIdx[k]] <= 0) { continue; }
            if (top.length() > 0) { top.append("  "); }
            top.append(String.format("#%03X %.1f%%",
                    topIdx[k], buckets[topIdx[k]] * 100.0 / argb.length));
        }
        r.info("主色 top-4（4-bit 量化）: " + top);
        boolean ok;
        if (isoFrame) {
            ok = mean > 0.02 && mean < 0.98 && distinct >= 2 && (max - min) >= 0.005;
            if (ok) {
                r.pass("图像自证 PASS（ISO 诊断帧：非黑场/非单色——渲染链健康；"
                        + "结构判定留给全场景帧，camHit% 见 [render] 行）");
            } else {
                r.fail("图像自证 FAIL（ISO 诊断帧黑场/单色——渲染链缺陷）");
            }
        } else {
            ok = mean > 0.02 && mean < 0.98 && (max - min) >= 0.08 && distinct >= 16;
            if (ok) {
                r.pass("图像自证 PASS（非全黑/非平场/颜色多样性足够）");
            } else {
                r.fail("图像自证 FAIL（mean=" + String.format("%.3f", mean)
                        + " range=" + String.format("%.3f..%.3f", min, max)
                        + " distinct=" + distinct + "）");
            }
        }
        return ok;
    }

    static float[] cameraPose() throws Exception {
        Object mc = Mappings.call(null, Mappings.C_MC, Mappings.M_MC_GET_INSTANCE);
        Object gameRenderer = Mappings.get(mc, Mappings.C_MC, Mappings.F_MC_GAME_RENDERER);
        Object camera = Mappings.get(gameRenderer, Mappings.C_GAME_RENDERER, Mappings.F_CAMERA_POS);
        Object pos = Mappings.call(camera, Mappings.C_CAMERA, Mappings.M_CAMERA_GET_POS);
        float x = ((Double) Mappings.get(pos, Mappings.C_VEC3D, Mappings.F_VEC3D_X)).floatValue();
        float y = ((Double) Mappings.get(pos, Mappings.C_VEC3D, Mappings.F_VEC3D_Y)).floatValue();
        float z = ((Double) Mappings.get(pos, Mappings.C_VEC3D, Mappings.F_VEC3D_Z)).floatValue();
        float yaw = (Float) Mappings.call(camera, Mappings.C_CAMERA, Mappings.M_CAMERA_GET_YAW);
        float pitch = (Float) Mappings.call(camera, Mappings.C_CAMERA, Mappings.M_CAMERA_GET_PITCH);
        return new float[] {x, y, z, yaw, pitch};
    }

    private static boolean isDisabled() {
        return Boolean.getBoolean("pathtrace.disabled")
                || "1".equals(System.getenv("PATHTRACE_DISABLED"));
    }

    /** 2.3：GL 合成开关（v0.4.2 语义修正——-Dpathtrace.compose=false 真正关闭）。 */
    private static boolean composeEnabled() {
        return flagProp("pathtrace.compose", true);
    }

    /**
     * 2.3 v0.4.2：atlas 采集二分开关。-Dpathtrace.atlas=false → 跳过
     * captureAtlas（albedo 保持顶点色，2.2 等价路径）。v0.4.1 真机时间线：
     * camHit 1s=100%（该帧编码于 atlas 上传之后）→ (1s,2s) 窗口内永久归零；
     * atlas 绑定本身已被排除为【即时】毒源，但上传链路（GL readback +
     * Metal 纹理创建 + 锁内 GPU wait）仍是关联事件——本开关把整条链
     * 一次切开：camHit 恢复非零 ⇒ 上传链凶手坐实；仍归零 ⇒ 触发点在
     * 别处（GL 合成/哨兵派发/驱动层），继续看 [probe-sentinel] 行。
     */
    static boolean atlasEnabled() {
        return flagProp("pathtrace.atlas", true);
    }

    // ---- v0.4.7: 毒化写手五段二分梯（LiveSession 换段置位；单调摘除）----
    // A 基线（全部写手在岗）→ B atlas 链灭活 → C 合成链关停 → D 摄取暂停
    // → E live 派发停。每段 40s；换段即重锚 TLAS（新段从已知存活态起步）
    // + census 普查 + 段判定行（canary 死亡采样 + camHit 归零采样）。
    // 死亡在哪一段首次停止 ⇒ 凶手=该段摘除的写手集合。
    /** Phase B: atlas 链灭活（native 纹理/暂存退休 + 上传拒绝 + 顶点色回退）。 */
    static volatile boolean bisectAtlasOff = false;
    /** Phase C: 合成链关停（present kernel/GPU 回读/glTexSubImage2D/blit 全停）。 */
    static volatile boolean bisectComposeOff = false;
    /** Phase D: 摄取暂停（SceneProxy 空转——BLAS 构建/池写入全停，TLAS 冻结）。 */
    static volatile boolean bisectIngestOff = false;
    /** Phase E: live 派发停（累积冻结——仅金丝瑞/哨兵/census 触碰 TLAS）。 */
    static volatile boolean bisectLiveOff = false;

    /** 二分梯总开关（v0.4.8：梯子已在 v0.4.7 真机收口——默认关，改走孪生+
     * ping-pong 协议；-Dpathtrace.bisect=true 可复现五段梯实验）。 */
    static boolean bisectEnabled() {
        return flagProp("pathtrace.bisect", false);
    }

    /** 每段时长（ms，默认 40s）。 */
    static long bisectPhaseMs() {
        return Long.getLong("pathtrace.bisect.phaseMs", 40_000L);
    }

    /** 二分梯观察窗（五段 + 10s 尾量；LiveSession 判定块与探针线程 deadline 用）。 */
    static long bisectWindowMs() {
        return 5 * bisectPhaseMs() + 10_000L;
    }

    // ---- v0.4.8: ping-pong 替换件 + 孪生零接触（毒化写手工程解）----
    /** ping-pong 激活（LiveSession t=30s 定谳后置位；twin=false 时 t=10s）。
     *  探针线程 500ms 强制同内容重建——替换件年龄恒 <0.5s，写手 1.0s 最小
     *  得手延迟永远追不上；同内容重建不清累积（场景纪元豁免）+ 毒帧守卫。 */
    static volatile boolean pingPongActive = false;

    /** -Dpathtrace.pingpong=false：禁用替换件（保留死亡循环行为——对照用）。 */
    static boolean pingPongEnabled() {
        return flagProp("pathtrace.pingpong", true);
    }

    /** -Dpathtrace.twin=false：跳过孪生窗口（ping-pong 提前至 t=10s 激活）。 */
    static boolean twinEnabled() {
        return flagProp("pathtrace.twin", true);
    }

    // ---- v0.4.2 探针线程交叉复测通道（worker→probe 的单向请求）----
    // live 哨兵 0/6 时置位；PathtraceMod watchdog 轮询消费。同一 TLAS、
    // 同一射线电池，从探针线程重打一遍：probe 命中 + worker 全 miss ⇒
    // worker 派发路径坏（线程归责定谳）；两者皆 miss ⇒ AS 内容坏
    // （BLAS/TLAS 毒化——重锚后的复测再分新旧）。v0.4.1 全程缺失的
    // 关键 A/B：S6.5 之后探针线程再没打过任何一条 ray。
    // v0.4.4：冷却式重复触发（每 5s 可再请求一次，v0.4.3 一次性导致
    // 30s 重锚后无对照）；探针侧带序号消费。
    static volatile boolean probeBatteryReq = false;
    /** v0.4.4: 交叉复测序号（[probe-sentinel ...#N] 行——多轮对照可区分）。 */
    static volatile int probeCrossCount = 0;
    /** v0.4.4: 自愈重锚请求（LiveSession 检出 camHit 连续归零 + 哨兵双死
     * ⇒ 置位；本类探针线程轮询消费：refreshTlasNative 重建（worker 不建
     * ——v0.4.1 实证）。重建后 [live] reset + 前置电池恢复即用户可见噪点。 */
    static volatile boolean reanchorReq = false;
    /** atlas 采集完成计数（成功/降级/跳过均 +1——LiveSession 据此括号探测）。 */
    static volatile int atlasEpoch = 0;

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
        try (InputStream in = PathtraceMod.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("JAR 资产缺失: " + resource);
            }
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }
}
