package net.prisma.metalprobe;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric 通用入口（main entrypoint）。
 *
 * 沿用 rt-probe v1.2 的安全时序设计：本入口不立即执行探针，仅起 90 秒
 * watchdog 兜底。正常路径由 FabricClientEntry 的 CLIENT_STARTED 事件触发
 * （游戏窗口/GL/MobileGlues 全部就绪后的安全窗口）；无 fabric-api 或事件
 * 注册失败时，超时兜底执行（此时初始化同样早已完成，安全性等价）。
 *
 * MetalProbeMod 内的 AtomicBoolean 保证两条路径只跑一次。
 */
public final class FabricEntry implements ModInitializer {

    @Override
    public void onInitialize() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(90_000L);
            } catch (InterruptedException e) {
                return;
            }
            MetalProbeMod.runAll("fallback-timeout(90s)");
        }, "MetalProbe-Watchdog");
        t.setDaemon(true);
        t.start();
    }
}
