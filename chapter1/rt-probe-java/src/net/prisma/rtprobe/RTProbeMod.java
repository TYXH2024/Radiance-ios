package net.prisma.rtprobe;

/**
 * RT 探针主类 —— 双形态：
 * 1) Fabric mod：经 FabricEntry/FabricClientEntry 随游戏加载；
 *    v1.2 起默认延迟到 CLIENT_STARTED（GL/MobileGlues 完全初始化后的安全窗口）执行，
 *    90 秒 watchdog 兜底（无 fabric-api 时）；
 * 2) 独立 main：任何支持自定义 JVM 参数/类的控制台里直接运行。
 *
 * 结果写入游戏目录 rt_probe_report.txt，并同步打印 [RTProbe] 前缀日志。
 *
 * 归因开关（v1.2 新增）：-Drtprobe.disabled=true 或环境变量 RTPROBE_DISABLED=1
 * -> 探针只写环境快照，不执行任何原生 Vulkan 调用，用于闪退 A/B 对照实验。
 */
public class RTProbeMod {

    /** 独立运行入口：java -cp rt-probe-1.2.jar net.prisma.rtprobe.RTProbeMod */
    public static void main(String[] args) {
        runAll("standalone-main");
    }

    /** v1.1: 防重复守卫 —— Fabric 多入口/意外二次调用时只跑一次 */
    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** v1.2: 本次运行的触发来源（CLIENT_STARTED / fallback-timeout / standalone-main） */
    private static volatile String trigger = "unknown";

    static void runAll(String triggerSource) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        trigger = triggerSource;
        ProbeReport r = new ProbeReport();

        // v1.2: 归因对照开关 —— 禁用时零原生调用
        boolean disabled = isDisabled();
        if (disabled) {
            r.stage("S0 环境快照（探针已禁用: rtprobe.disabled）");
            try {
                EnvSnapshot.run(r);
            } catch (Throwable t) {
                r.fail("S0 环境快照中断", t);
            }
            r.info("触发方式: " + trigger);
            r.finish("-", "探针被 rtprobe.disabled 禁用，本次运行未执行任何原生 Vulkan 调用。"
                    + "用途：闪退归因对照（若禁用后仍闪退，则闪退与探针无关）。");
            return;
        }

        r.stage("总流程");
        r.info("触发方式: " + trigger);
        try {
            EnvSnapshot.run(r);
        } catch (Throwable t) {
            r.fail("S0 环境快照中断（继续 Vulkan 阶段）", t);
        }

        VulkanProbe.Ctx ctx = null;
        try {
            ctx = VulkanProbe.run(r);
        } catch (Throwable t) {
            r.fail("Vulkan 阶段异常", t);
            r.finish("E", "Vulkan 阶段抛出未捕获异常，附整份报告回报。");
        }

        if (ctx != null) {
            // RT 绑定类都在，才会走到这里（VulkanProbe 已做扩展+特性审计）
            try {
                Class.forName("org.lwjgl.vulkan.KHRAccelerationStructure");
                RtPipeline.run(r, ctx);
            } catch (Throwable t) {
                r.fail("RT 管线阶段异常", t);
                r.finish("B", "RT 管线无法启动（KHRAccelerationStructure 加载失败）"
                        + " -> 启动器内 LWJGL 缺少 AS 绑定类，需替换/升级 LWJGL jar（改包）。");
            }
        }
        r.info("探针结束。报告文件: " + r.getFile());
    }

    private static boolean isDisabled() {
        String p = System.getProperty("rtprobe.disabled");
        if (p == null || p.isEmpty()) {
            p = System.getenv("RTPROBE_DISABLED");
        }
        return "true".equalsIgnoreCase(p) || "1".equals(p);
    }
}
