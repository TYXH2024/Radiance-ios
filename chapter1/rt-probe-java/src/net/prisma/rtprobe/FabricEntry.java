package net.prisma.rtprobe;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric 通用入口（main entrypoint）。
 *
 * v1.2 起本入口【不再立即执行探针】：
 * v1.1 在 mod 初始化阶段（MinecraftClient 构造前）的后台线程里创建 VkInstance，
 * 与 Render thread 上的 MobileGlues（GL->Vulkan 翻译层）初始化并发竞争 MoltenVK，
 * 现场取证（M3/A20Pro 2026-10-27）显示 MobileGlues 后端未完成、MC 的
 * GL.createCapabilities()->glGetError 撞 NULL 函数表 SIGSEGV。
 *
 * 现改为 90 秒 watchdog 兜底：正常情况下 FabricClientEntry 的 CLIENT_STARTED
 * 事件（GL/MobileGlues 完全就绪后的安全窗口）先触发探针；若 fabric-api 缺失
 * 或事件未注册成功，watchdog 超时后才执行（此时 GL 初始化也早已完成）。
 * runAll 内部的 AtomicBoolean 保证两条路径只跑一次。
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
            RTProbeMod.runAll("fallback-timeout(90s)");
        }, "RTProbe-Watchdog");
        t.setDaemon(true);
        t.start();
    }
}
