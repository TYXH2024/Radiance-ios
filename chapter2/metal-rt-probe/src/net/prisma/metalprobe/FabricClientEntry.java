package net.prisma.metalprobe;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 客户端入口（client entrypoint）。
 *
 * 注册 CLIENT_STARTED 事件（rt-probe v1.2 同款反射 + Proxy 方案，保持零
 * Minecraft 依赖）：MinecraftClient 完全启动后在后台线程执行 Metal RT 探针。
 *
 * 与第一章 rt-probe 的差异：本探针不触碰 Vulkan/MoltenVK —— 它通过 JNI
 * 直接驱动 Metal Ray Tracing（自建 MTLDevice，与 zink/MoltenVK 渲染主链
 * 零共享、零竞争）。延迟执行的意义从"避免与 GL 初始化竞争"变为"确保游戏
 * 已完整存活到稳定状态，取证窗口干净"。
 */
public final class FabricClientEntry implements ClientModInitializer {

    private static final String CLS_EVENTS =
            "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents";
    private static final String CLS_STARTED =
            "net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents$ClientStarted";
    private static final String CLS_EVENT = "net.fabricmc.fabric.api.event.Event";

    @Override
    public void onInitializeClient() {
        try {
            Class<?> events = Class.forName(CLS_EVENTS);
            Class<?> started = Class.forName(CLS_STARTED);
            Class<?> event = Class.forName(CLS_EVENT);
            Object clientStartedEvent = events.getField("CLIENT_STARTED").get(null);
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    FabricClientEntry.class.getClassLoader(),
                    new Class<?>[]{started},
                    (proxy, method, args) -> {
                        if ("onClientStarted".equals(method.getName())) {
                            startProbe();
                        }
                        return null;
                    });
            // Event<T>.register(T) 擦除后为 register(Object)
            event.getMethod("register", Object.class).invoke(clientStartedEvent, listener);
        } catch (Throwable t) {
            // fabric-lifecycle-events 不在场（suggests 而非 depends）：静默，
            // FabricEntry 的 90 秒 watchdog 兜底。
        }
    }

    private static void startProbe() {
        Thread t = new Thread(() -> MetalProbeMod.runAll("CLIENT_STARTED"), "MetalProbe");
        t.setDaemon(true);
        t.start();
    }
}
