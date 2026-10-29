package net.prisma.pathtrace;

import java.nio.ByteBuffer;

/**
 * 帧呈现钩子（2.3 v0.4）—— mixin 注入点 PathtraceWindowMixin 的唯一回调。
 *
 * 状态机（volatile int，渲染线程单写，探针线程 arm()）：
 *   OFF(0)    游戏启动即被调用但零副作用（一行 volatile 读即返回——
 *             S8 之前与 S8 失败路径的开销为零）
 *   ARMED(1)  PathtraceMod 在 liveBegin 成功后 arm()；下一次 swap 时初始化
 *             GL 合成器 + 尝试 atlas 采集 → ACTIVE
 *   ACTIVE(2) 每帧：livePresentNative() 取稳定快照 → GlComposer.present()
 *             上传 + blit 整帧替换游戏画面（480×270 → 窗口分辨率线性放大）
 *   DEAD(3)   任何异常一次性降级（游戏零影响），报告留 [compose] 行
 *
 * 判死开关：-Dpathtrace.compose=false → PathtraceMod 不 arm（mixin 已注入
 * 但永远 OFF，吞吐零影响）。（v0.4.2：开关语义修复——此前 Boolean.getBoolean
 * 反转导致 =false 实际关不掉。）
 *
 * v0.4.2 atlas 开关：-Dpathtrace.atlas=false → 跳过 captureAtlas，albedo
 * 保持顶点色（2.2 等价路径）。无论采集成功/降级/跳过，atlasEpoch 均递增
 * ——LiveSession 据此在 epoch 变化后的下一轮循环（≤8ms）立即打一轮哨兵
 * 电池，把“atlas 前后”括在同一时间线里。
 *
 * 线程：本类全部方法只在游戏渲染线程被调（swapBuffers 调用栈内）——GL
 * 上下文在该线程，所有 GL 反射调用天然合法。
 *
 * v0.5.1 事件泵（2.4 事件驱动摄取）—— mixin 注入点
 * PathtraceBuiltChunkMixin 的唯一回调 onChunkMeshPublished：
 * BuiltChunk.setData(ChunkData)（class_851#method_60908，HEAD）是
 * vanilla 发布新 mesh 的唯一写点，钩它 = “MC 已重建该 section”的
 * 精确时刻。回调用无锁队列把 (Object)BuiltChunk 转交摄取线程消费
 * （身份去重 putIfAbsent，消费侧 poll 后立刻 remove——竞态窗口极小
 * 且方向安全：宁可多重摄一次（rebuild 幂等），丢了也有 2s 哨兵
 * dirty 扫描兜底）。mixinArmed 标志供报告判据与哨兵节奏自适应
 * （未命中→哨兵维持 500ms 全量轮询 = v0.5.0 等价回退）。
 */
public final class PathtraceHook {

    private static final int OFF = 0;
    private static final int ARMED = 1;
    private static final int ACTIVE = 2;
    private static final int DEAD = 3;

    /** mixin 回调心跳（含 OFF 期——COMPOSE_INACTIVE 判定的证据计数）。 */
    static volatile int swapCount;
    /** 实际合成帧数。 */
    static volatile int blitCount;
    /** GL 错误累计（glGetError 非零次数）。 */
    static volatile int glErrors;
    /** 是否已武装（compose=false 时永远 false——判定语义用）。 */
    static volatile boolean hookArmed;

    // ---- v0.5.1 事件泵（mixin → 摄取线程）----
    /** 事件队列（身份去重，见类头）。元素为 BuiltChunk 实例（Object 视图）。 */
    private static final java.util.concurrent.ConcurrentLinkedQueue<Object> MESH_QUEUE =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    /** 在队标记（身份去重：同一 BuiltChunk 连续发布只保留一次）。 */
    private static final java.util.concurrent.ConcurrentHashMap<Object, Boolean> MESH_QUEUED =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 事件通道开启（SceneProxy 构造时 arm；OFF 态回调零副作用）。 */
    static volatile boolean meshEventsEnabled;
    /** mixin 首事件已命中（报告判据 + 哨兵节奏自适应）。 */
    static volatile boolean meshEventArmed;

    private static volatile int state = OFF;
    private static ProbeReport report;
    private static GlComposer composer;

    private PathtraceHook() {}

    /** v0.4.2: 静默报告（GlComposer 防御性留行用——不落盘仅 stdout）。 */
    static ProbeReport silentReport() {
        return ProbeReport.SILENT;
    }

    /** 探针线程：S8 武装（liveBegin 成功后调用）。 */
    static void arm(ProbeReport r) {
        report = r;
        hookArmed = true;
        r.pass("[S8.0] 合成钩子武装（mixin swapBuffers HEAD 回调已激活，"
                + "下一次游戏帧交换时初始化 GL 合成器）");
        state = ARMED;
    }

    static boolean isActive() {
        return state == ACTIVE;
    }

    /** 摄取线程：事件通道开启（SceneProxy 构造时调用；幂等）。 */
    static void armMeshEvents() {
        meshEventsEnabled = true;
    }

    /** mixin 回调（渲染线程，setData HEAD；跨包访问故 public）。
     * O(1) 无锁；OFF 态一次 volatile 读即返回；任何异常吞掉（游戏零影响）。 */
    public static void onChunkMeshPublished(Object builtChunk) {
        if (!meshEventsEnabled) {
            return;
        }
        try {
            if (!meshEventArmed) {
                meshEventArmed = true;
            }
            if (MESH_QUEUED.putIfAbsent(builtChunk, Boolean.TRUE) == null) {
                MESH_QUEUE.add(builtChunk);
            }
        } catch (Throwable ignored) {
            // 判死事件通道是摄取线程的职责（哨兵兜底），这里绝不外抛
        }
    }

    /** 摄取线程：取一个待处理事件（null = 空队）。消费即出队标记。 */
    static Object pollMeshEvent() {
        Object bc = MESH_QUEUE.poll();
        if (bc != null) {
            MESH_QUEUED.remove(bc);
        }
        return bc;
    }

    /** 摄取线程：当前队列深度（心跳行用）。 */
    static int meshQueueDepth() {
        return MESH_QUEUE.size();
    }

    /** 摄取线程：清空事件队列（世界退出时调用——旧 BuiltChunk 引用全部作废）。 */
    static void clearMeshEvents() {
        MESH_QUEUE.clear();
        MESH_QUEUED.clear();
    }

    /** mixin 回调（渲染线程，每帧一次；跨包访问故 public）。 */
    public static void onPreSwap() {
        swapCount++;
        final int s = state;
        if (s == OFF || s == DEAD) {
            return;
        }
        try {
            if (s == ARMED) {
                composer = new GlComposer();
                composer.init(report);
                if (PathtraceMod.atlasEnabled()) {
                    composer.captureAtlas(report);
                } else {
                    report.info("[S8.2] atlas 采集跳过（pathtrace.atlas=false ——"
                            + " albedo 保持顶点色；二分开关：若本轮 camHit 恢复"
                            + "非零 ⇒ atlas 上传链凶手坐实）");
                }
                PathtraceMod.atlasEpoch++;
                state = ACTIVE;
            }
            if (state == ACTIVE) {
                // v0.4.7: bisect Phase C——合成链关停（present 内核/GPU 回读/
                // glTexSubImage2D/blit 全停；画面回落原版帧）。零副作用直退。
                if (PathtraceMod.bisectComposeOff) {
                    return;
                }
                ByteBuffer px = Native.livePresentNative();
                if (px != null) {
                    composer.present(px);
                    blitCount++;
                }
            }
        } catch (Throwable t) {
            state = DEAD;
            if (report != null) {
                report.fail("[compose] 合成器异常 → 已判死（游戏不受影响，"
                        + "累积与会话继续，仅上屏通道关闭）: " + t);
            }
        }
    }
}
