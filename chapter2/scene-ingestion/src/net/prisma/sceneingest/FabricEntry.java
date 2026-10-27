package net.prisma.sceneingest;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric 通用入口（main entrypoint）。
 *
 * 仅起 watchdog 兜底（90s）：正常路径由 FabricClientEntry 的 CLIENT_STARTED
 * 触发。SceneIngestionMod 内 AtomicBoolean 保证两条路径只跑一次。
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
            SceneIngestionMod.runAll("fallback-timeout(90s)");
        }, "SceneIngest-Watchdog");
        t.setDaemon(true);
        t.start();
    }
}
