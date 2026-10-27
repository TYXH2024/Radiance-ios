package net.prisma.rtprobe;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric 专用入口：把 ModInitializer 接口隔离在这个小类里，
 * 使 RTProbeMod 在无 fabric-loader 的独立 classpath 下也能直接 main 运行。
 */
public final class FabricEntry implements ModInitializer {

    @Override
    public void onInitialize() {
        Thread t = new Thread(RTProbeMod::runAll, "RTProbe");
        t.setDaemon(true);
        t.start();
    }
}
