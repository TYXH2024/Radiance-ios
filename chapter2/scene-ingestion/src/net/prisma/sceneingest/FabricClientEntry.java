package net.prisma.sceneingest;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 客户端入口（client entrypoint）。
 *
 * 注册 CLIENT_STARTED 事件（metal-rt-probe 同款反射 + Proxy 方案，保持零
 * Minecraft 依赖）。2.1 的摄取在游戏进入世界后由 SceneProxy 轮询启动，
 * 因此本入口只负责"游戏稳定存活后"起后台摄取线程。
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
                            startIngestion();
                        }
                        return null;
                    });
            event.getMethod("register", Object.class).invoke(clientStartedEvent, listener);
        } catch (Throwable t) {
            // fabric-lifecycle-events 不在场（suggests 而非 depends）：静默，
            // FabricEntry 的 90 秒 watchdog 兜底。
        }
    }

    private static void startIngestion() {
        Thread t = new Thread(() -> SceneIngestionMod.runAll("CLIENT_STARTED"),
                "SceneIngest");
        t.setDaemon(true);
        t.start();
    }
}
