package net.prisma.rtprobe;

/**
 * RT 探针主类 —— 双形态：
 * 1) Fabric mod：经 FabricEntry 随游戏加载，后台线程执行，不阻塞渲染线程；
 * 2) 独立 main：任何支持自定义 JVM 参数/类的控制台里直接运行。
 *
 * 结果写入游戏目录 rt_probe_report.txt，并同步打印 [RTProbe] 前缀日志。
 */
public class RTProbeMod {

    /** 独立运行入口：java -cp rt-probe-1.1.jar net.prisma.rtprobe.RTProbeMod */
    public static void main(String[] args) {
        runAll();
    }

    /** v1.1: 防重复守卫 —— Fabric 多入口/意外二次调用时只跑一次 */
    private static final java.util.concurrent.atomic.AtomicBoolean STARTED =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    static void runAll() {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        ProbeReport r = new ProbeReport();
        try {
            r.stage("总流程");
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
}
