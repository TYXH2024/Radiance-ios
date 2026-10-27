package net.prisma.metalprobe;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Metal RT 探针主类 —— Radiance 移植第二章 2.0 阶段交付物。
 *
 * 验证问题（docs/chapter2-route-decision.md §4.2 阶段 2.0）：
 *   Q1 免改包投送通道：mod JAR 内置 arm64 dylib → 解包到 game 目录 →
 *      System.load() 是否走通（LWJGL natives 同款受信装载链）？
 *   Q2 进程内 Metal RT 全链路：在游戏进程里自建 MTLDevice，BLAS/TLAS 构建、
 *      MSL ray_query kernel 调度、回读校验是否全部通过？
 *   Q3 首个真机硬件 RT 吞吐数据（Mrays/s，Apple9 专用交集硬件）。
 *
 * 双形态：Fabric mod（FabricEntry/FabricClientEntry）或独立 main。
 * 结果写入游戏目录 metal_rt_probe_report.txt；日志前缀 [MetalProbe]。
 *
 * 归因开关：-Dmetalprobe.disabled=true / METALPROBE_DISABLED=1
 * -> 只写环境快照，不解包不装载不执行任何原生调用（闪退 A/B 对照）。
 */
public class MetalProbeMod {

    /** 独立运行入口（调试用） */
    public static void main(String[] args) {
        runAll("standalone-main");
    }

    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static volatile String trigger = "unknown";

    static void runAll(String triggerSource) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        trigger = triggerSource;
        ProbeReport r = new ProbeReport("metal_rt_probe_report.txt");

        r.header("Metal RT 进程内探针 (metal-rt-probe 0.1)");
        r.info("触发方式: " + trigger);

        if (isDisabled()) {
            r.stage("S0 环境快照（探针已禁用: metalprobe.disabled）");
            envSnapshot(r);
            r.finish("-", "探针被 metalprobe.disabled 禁用，本次未解包/未装载/未执行任何原生调用。"
                    + "用途：闪退归因对照。");
            return;
        }

        r.stage("S0 环境快照");
        envSnapshot(r);

        // ---- S1 解包原生资产（dylib + metallib + MSL 源）----
        r.stage("S1 解包原生资产（JAR -> game 目录）");
        Path dir = Paths.get(System.getProperty("user.dir", "."), "metalprobe");
        Path dylib;
        Path metallib;
        Path mslSource;
        try {
            Files.createDirectories(dir);
            dylib = extract("/metalprobe/libradiance_metal_probe.dylib", dir);
            metallib = extract("/metalprobe/trace_kernel.metallib", dir);
            mslSource = extract("/metalprobe/trace_kernel.metal", dir);
            r.pass("解包完成: " + dylib + " (" + Files.size(dylib) + " B)");
            r.info("metallib: " + metallib + " (" + Files.size(metallib) + " B)");
        } catch (Throwable t) {
            r.fail("S1 解包失败（JAR 资产缺失或目录不可写）", t);
            r.finish("EXTRACT_FAIL", "原生资产解包失败 —— JAR 不完整或 game 目录不可写。附报告回报。");
            return;
        }

        // ---- S2 装载 dylib（LWJGL natives 同款通道）----
        r.stage("S2 System.load 装载 dylib（投送通道验证）");
        try {
            System.load(dylib.toAbsolutePath().toString());
            r.pass("dylib 装载成功（JVM dlopen 走启动器 hooked_dlopen 受信链）");
        } catch (Throwable t) {
            // 这是 2.0 阶段的核心观测点之一：投送通道若受阻，异常明文即证据
            r.fail("S2 dylib 装载失败（投送通道受阻）", t);
            r.finish("LOAD_FAIL", "System.load 失败 —— 投送通道未走通。"
                    + "常见原因：dylib 签名/平台位不匹配。附整份报告回报。");
            return;
        }

        // ---- S3 原生探针（Metal RT 全链路）----
        r.stage("S3 Metal RT 全链路（原生侧细节见下方原生段）");
        int rc;
        try {
            rc = Native.nativeRunProbe(
                    metallib.toAbsolutePath().toString(),
                    mslSource.toAbsolutePath().toString(),
                    r.getFile());
        } catch (Throwable t) {
            r.fail("S3 原生探针异常（JNI 调用层）", t);
            r.finish("NATIVE_ABORT", "JNI 调用抛异常 —— 原生侧报告段为准。附整份报告回报。");
            return;
        }

        String verdict;
        String note;
        switch (rc) {
            case 0:
                verdict = "METAL_RT_OK";
                note = "进程内 Metal RT 全链路通过（BLAS + TLAS + ray_query + 掩码剔除 + 距离校验）。"
                        + "第二章路线绿灯：投送通道与硬件 RT 同时成立，进入 2.1 场景摄取。";
                break;
            case 1:
                verdict = "METAL_RT_MISMATCH";
                note = "链路跑通但回读数值不符 —— 原生段 [mismatch] 行为准。附报告回报。";
                break;
            case 3:
                verdict = "METAL_RT_API_UNAVAILABLE";
                note = "设备 GPU 不支持 Metal Ray Tracing（supportsRaytracing == NO）。"
                        + "需要 Apple9 家族（A17 Pro / M3 起）。";
                break;
            default:
                verdict = "NATIVE_FATAL";
                note = "原生侧致命失败 —— 报告原生段的 FATAL 行为准（设备/BLAS/TLAS/管线/调度）。";
                break;
        }
        r.finish(verdict, note);
    }

    /** 从 classpath 解包资源到目标目录（已存在则覆盖，保证版本一致） */
    private static Path extract(String resource, Path dir) throws IOException {
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        Path target = dir.resolve(name);
        try (InputStream in = MetalProbeMod.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("JAR 内资产缺失: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    private static void envSnapshot(ProbeReport r) {
        try {
            r.info("java.version  = " + System.getProperty("java.version"));
            r.info("java.vm.name  = " + System.getProperty("java.vm.name"));
            r.info("os.name       = " + System.getProperty("os.name") + " / " + System.getProperty("os.arch"));
            r.info("user.dir      = " + System.getProperty("user.dir"));
            r.info("AMETHYST_RENDERER = " + System.getenv("AMETHYST_RENDERER"));
            r.info("GALLIUM_DRIVER     = " + System.getenv("GALLIUM_DRIVER"));
            r.info("pojav.lwjgl.version = " + System.getProperty("pojav.lwjgl.version"));
            r.info("org.lwjgl.opengl.libname = " + System.getProperty("org.lwjgl.opengl.libname"));
            r.info("org.lwjgl.vulkan.libname = " + System.getProperty("org.lwjgl.vulkan.libname"));
        } catch (Throwable t) {
            r.fail("环境快照中断（继续探针）", t);
        }
    }

    private static boolean isDisabled() {
        String p = System.getProperty("metalprobe.disabled");
        if (p == null || p.isEmpty()) {
            p = System.getenv("METALPROBE_DISABLED");
        }
        return "true".equalsIgnoreCase(p) || "1".equals(p);
    }
}
