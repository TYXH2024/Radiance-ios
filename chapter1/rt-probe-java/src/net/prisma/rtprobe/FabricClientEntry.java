package net.prisma.rtprobe;

import net.fabricmc.api.ClientModInitializer;

/**
 * Fabric 客户端入口（client entrypoint）—— v1.2 新增。
 *
 * 注册 CLIENT_STARTED 事件：MinecraftClient 完全启动（Window/GL 上下文/
 * MobileGlues 翻译层全部就绪）后才在后台线程执行探针，消除 v1.1 与宿主
 * GL 初始化并发竞争 MoltenVK 的崩溃源。
 *
 * 实现说明：事件注册走反射 + java.lang.reflect.Proxy —— CLIENT_STARTED 的
 * 回调签名引用 net.minecraft.client.Minecraft，直接编译引用会迫使探针携带
 * Minecraft 版本依赖（与 fabric.mod.json 的零 depends 设计冲突）。运行时
 * fabric-api 在场则注册成功；缺失或任何异常则静默，由 FabricEntry 的
 * 90 秒 watchdog 兜底触发（此时 GL 初始化同样早已完成，安全性等价）。
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
            // fabric-lifecycle-events 不在场（suggests 而非 depends）：
            // 静默，FabricEntry watchdog 兜底。
        }
    }

    private static void startProbe() {
        Thread t = new Thread(() -> RTProbeMod.runAll("CLIENT_STARTED"), "RTProbe");
        t.setDaemon(true);
        t.start();
    }
}
