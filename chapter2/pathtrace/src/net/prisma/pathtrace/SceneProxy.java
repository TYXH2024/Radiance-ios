package net.prisma.pathtrace;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 场景摄取代理 —— 上游 ChunkProxy 的 Metal 版降维实现（轮询驱动）。
 *
 * 摄取链路（每轮 POLL_MS=500ms）：
 *   MinecraftClient.world → ClientWorld.worldRenderer → WorldRenderer.chunks
 *   (BuiltChunkStorage) → chunks[] (ChunkBuilder$BuiltChunk)
 *   脏判定：BuiltChunk.data（AtomicReference<ChunkData>）引用变化 = MC 已发布
 *   新 mesh；sectionPos 变化 = 重定位（上游 setSectionPos mixin 的轮询等价物）。
 *   重建（每轮限额 MAX_REBUILD_PER_POLL）：SectionBuilder.build(自有 ThreadLocal
 *   BlockBufferAllocatorStorage) → Map<RenderLayer, BuiltBuffer> → 顶点
 *   ByteBuffer + vertexCount + stride → JNI rebuildSingleNative。
 *
 * 与上游的刻意差异（设计 §2）：轮询 vs mixin 事件；同步拷贝后即 close vs
 * takeChunk 所有权转移；单线程限量 vs 双池重要度调度。
 *
 * v0.4.6 根因修复（v0.4.5 六份真机日志定谳）：摄取选择从【数组序】改为
 * 【相机距离近先】。vanilla BuiltChunkStorage 的槽位索引是 floorMod 回绕
 * 布局（实测 1.21.4：index = relZ*408 + relY*17 + relX，relX/relZ 各自把
 * 负半轴排在行尾）——相机在 z=-5.5（section z=-16）而存储中心 z=0 时，
 * 相机段落在 relZ=16 即整个数组的最后 408 槽。旧逻辑按数组序每轮取前
 * 16 个 dirty，真机 35s 窗口只推进到 index≈1300（z=+96 行）：相机段 /
 * 脚下段（(0,80,-16)/(0,64,-16)）永不摄取 ⇒ 眼前 z∈[-16,0) void（用户
 * 实测“眼前四五格不渲染”＝相机 z=-5.41 到条带边界 z=0 的 5.4 格）、-Y
 * 金丝雀 miss（“检测不到脚底”）、[tlas-health] t=0.01s 死亡——三者同源。
 * 修复后按 origin 的 AABB-clamp 距离排序，相机段距离 0 必然首轮入库，
 * 周围 26 邻居次之，远处按距离补齐（对 vanilla 中心漂移/回绕免疫）。
 *
 * v0.5.1 事件驱动摄取（探针转正，2.4 第一刀）：发现时机从【500ms 轮询
 * 扫描】升级为【mixin 事件 + 低频哨兵】双通道。事件源 =
 * PathtraceBuiltChunkMixin 钩 BuiltChunk.setData（class_851#method_60908
 * HEAD——vanilla 发布新 mesh 的唯一写点，即旧轮询 dirty 判定观察的同一
 * 信号）；哨兵 = 保留的全量 dirty 扫描（频率自适应：mixin 命中 2s /
 * 未命中 500ms——后者 = v0.5.0 等价回退，永不退化）。摄取内容不变
 * （仍自建 SectionBuilder.build，2.1 契约）。窗口语义不变（S4/S5 阻塞
 * 至 ready 判定）——窗口结束后【摄取线程转常驻】（Pathtrace-Ingest
 * daemon）：事件批处理 + 哨兵 + TLAS tick 活到游戏退出，世界重进后哨兵
 * 全量重扫。旧 v0.4.x 的【窗口结束即死】（6572/6936 段永远进不了 RT
 * 世界）自本版不存在。native 侧零改动：rebuildSingleNative/invalidate/
 * frameTickNative 任意时刻可调（S8.4 重锚与 ping-pong 已实证 live 期间
 * 探针线程 rebuild 并行安全）；content 变化 → epoch 递增 → live 累积
 * 按需清零（物理正确：场景变了就该重收敛）。
 */
final class SceneProxy {

    /** 轮询间隔（ms）。 */
    private static final long POLL_MS = 500L;
    /** 每轮最大重建区块数（v0.3：8→16，全量 600 槽位排空约 19s）。 */
    private static final int MAX_REBUILD_PER_POLL = 16;
    /** v0.5.1：事件批上限（每轮循环消费的最大 mixin 事件数——防 flood
     * （切渲染距离）压死 native；批后 20ms 节流 ≈ 400 段/s 上限）。 */
    private static final int EVENT_BATCH = 8;
    /** v0.5.1：哨兵扫描间隔（mixin 命中时——重定位/失效/未摄取远段的
     * 兜底通道；未命中时自动回退 POLL_MS = v0.5.0 等价行为）。 */
    private static final long SENTRY_MS = 2_000L;
    /** v0.5.1：[ingest] 心跳间隔（ms）。 */
    private static final long INGEST_HEARTBEAT_MS = 10_000L;
    /** v0.5.1：事件批后节流（ms）。 */
    private static final long EVENT_BATCH_THROTTLE_MS = 20L;
    /** v0.5.1：空转睡眠（ms）——事件驱动响应粒度。 */
    private static final long IDLE_SLEEP_MS = 50L;
    /** 摄取运行下限（ms）——S6 验证前的最短积累窗口（v0.3：20s→15s）。 */
    private static final long MIN_INGEST_MS = 15_000L;
    /** 摄取运行上限（ms）——超时强制进入 S6（SCENE_EMPTY 候选）。 */
    private static final long MAX_INGEST_MS = 150_000L;
    /** 强制收口时间（ms）——积压未排空也进入 S6（v0.3 新增，防毒丸阻塞）。 */
    private static final long FORCE_READY_MS = 35_000L;
    /** 静置阈值（ms）——重建活动停止后视为已收敛（v0.3 新增）。 */
    private static final long IDLE_SETTLE_MS = 6_000L;
    /** TLAS tick 节流（ms）。 */
    private static final long TLAS_TICK_MS = 1000L;

    /** builtChunk index → 上次摄取状态（data 引用 identity + sectionPos + generation）。 */
    private final Map<Integer, SlotState> slots = new ConcurrentHashMap<>();
    private final ProbeReport r;
    private final String metallibPath;
    private volatile boolean running = true;
    private volatile boolean gridInited = false;

    /** v0.3：摄取期相机位置缓存（每轮刷新；S6 容错——用户可能在窗口结束后
     * 立即退出世界，此时直读相机可能拿到重置后的 Camera）。 */
    volatile float[] lastCameraPos;

    /** v0.4.6：距离优先策略回显（一次）。 */
    private boolean priorityEchoed = false;
    /** v0.4.6：相机段首次摄取回显（一次）。 */
    private boolean cameraSectionEchoed = false;
    /** v0.4.6：槽位 origin 缓存（index → {ox,oy,oz}；sectionPos 变化时失效）。 */
    private final Map<Integer, int[]> originCache = new java.util.concurrent.ConcurrentHashMap<>();

    // 统计
    int polls = 0;
    int rebuilds = 0;
    /** v0.4.7: bisect Phase D 摄取暂停已回显（一次性行）。 */
    private boolean ingestPauseEchoed = false;
    private boolean colorOffsetEchoed = false;
    private boolean uvElemEchoed = false;
    int invalidations = 0;
    int relocations = 0;
    int nonEmptyChunks = 0;
    long firstReadyMs = -1;
    long lastActivityMs = 0;
    private final long startNs = System.nanoTime();
    private final AtomicLong generation = new AtomicLong(1);

    // ---- v0.5.1 事件驱动统计 ----
    /** 已消费的 mixin 事件总数（含窗口内）。 */
    long eventsProcessed = 0;
    /** 事件批数（心跳行用）。 */
    private long eventBatches = 0;
    /** 哨兵扫描轮数（含窗口内每轮）。 */
    private long sweepCount = 0;
    /** 常驻摄取线程（窗口结束时创建，幂等）。 */
    private Thread ingestThread;
    /** [ingest] mixin 命中回显（一次）。 */
    private boolean meshHitEchoed = false;
    /** [ingest] 常驻模式回显（一次）。 */
    private boolean residentEchoed = false;
    /** [ingest] 心跳累计器。 */
    private long lastIngestHeartbeatMs = 0;
    /** 心跳窗口内事件计数（打完归零）。 */
    private long heartbeatEvents = 0;
    /** 心跳窗口内哨兵重建计数（打完归零）。 */
    private int heartbeatSweepRebuilds = 0;

    private static final class SlotState {
        Object lastDataRef;
        long lastSectionPos;
        long generation;
        boolean ready;
        // v0.2 覆盖修复：v0.1 真机实测 36/157 覆盖塔缩的根因——首轮 poll 在
        // 世界加载未完成时把低 y 层槽位试成空（region null / 空 buffers）→
        // invalidate 把 generation 置位 → 永不再试。空试的槽位在窗口内
        // 保持可重试（限次限频），直至出几何或窗口结束。
        int emptyAttempts;
        int lastEmptyPoll;
    }

    SceneProxy(ProbeReport r, String metallibPath) {
        this.r = r;
        this.metallibPath = metallibPath;
        // v0.5.1：事件通道开启（mixin 回调自此刻起入队；OFF 态零副作用）。
        // 窗口内与常驻期共用同一队列——窗口主循环每轮先 drain 事件再哨兵。
        PathtraceHook.armMeshEvents();
    }

    void stop() {
        running = false;
    }

    /**
     * 主循环：返回判定所需统计（阻塞至 MIN/MAX 窗口内 ready 判据满足或超时）。
     */
    void run() {
        Object mc = Mappings.call(null, Mappings.C_MC, Mappings.M_MC_GET_INSTANCE);
        long lastTlasTick = 0;
        boolean announced = false;

        while (running) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            polls++;
            if (!running) {
                break;
            }

            Object world = Mappings.get(mc, Mappings.C_MC, Mappings.F_MC_WORLD);
            if (world == null) {
                if (!announced) {
                    r.info("等待进入世界（mc.world == null）…");
                    announced = true;
                }
                continue;
            }
            if (announced) {
                r.pass("已进入世界，开始网格发现");
                announced = false;
            }

            // v0.4.7: bisect Phase D——摄取暂停（BLAS 构建/池写入全停，TLAS
            // 冻结在换段重锚态）。轮询空转保持响应（世界退出/恢复检测不变）。
            if (PathtraceMod.bisectIngestOff) {
                if (!ingestPauseEchoed) {
                    ingestPauseEchoed = true;
                    r.pass("[bisect] SceneProxy 摄取暂停（Phase D——BLAS/池写入"
                            + "全停，TLAS 冻结；轮询空转）");
                }
                continue;
            }

            // v0.3：相机位置缓存（S6 容错；失败不影响摄取）
            try {
                lastCameraPos = readCameraPos();
            } catch (Throwable ignored) {
                // 相机尚不可读（菜单/加载中）——下轮重试
            }

            // WorldRenderer → BuiltChunkStorage
            Object worldRenderer = Mappings.get(world, Mappings.C_CLIENT_WORLD,
                    Mappings.F_CW_WORLD_RENDERER);
            if (worldRenderer == null) {
                continue;
            }
            Object storage = Mappings.get(worldRenderer, Mappings.C_WORLD_RENDERER,
                    Mappings.F_WR_CHUNKS);
            if (storage == null) {
                continue;
            }

            Object chunkArr = Mappings.get(storage, Mappings.C_STORAGE, Mappings.F_ST_CHUNKS);
            if (!(chunkArr instanceof Object[])) {
                continue;
            }
            Object[] chunks = (Object[]) chunkArr;
            if (chunks.length == 0) {
                continue;
            }

            // 首次发现网格：initNative（幂等：gridInited 守卫）
            if (!gridInited && chunks.length > 0) {
                firstGridInit(chunks.length, storage);
            }

            // v0.5.1：事件批消费（窗口内已事件驱动——mixin 命中时发现
            // 延迟从 ≤500ms 降至 ≤50ms；哨兵轮只负责它独有的三类信号：
            // 重定位 / 失效 / 从未摄取远段）。成功后同步槽位状态，哨兵
            // 不会重复摄取同一批段。
            int eventBatch = drainMeshEvents(EVENT_BATCH);
            if (eventBatch > 0) {
                eventsProcessed += eventBatch;
                lastActivityMs = elapsedMs();
            }

            // 哨兵全量扫描（v0.4.6 距离优先逻辑原样；mixin 命中后由常驻
            // 线程降频到 2s，窗口期内仍每轮跑——ready 判定的积压计数
            // 依赖全量视角）
            int[] s = sweep(chunks);
            int rebuiltThisPoll = s[0];
            int pendingDirty = s[1];
            int nonEmpty = s[2];
            if (rebuiltThisPoll > 0 || eventBatch > 0) {
                lastActivityMs = elapsedMs();
            }
            if (nonEmpty != this.nonEmptyChunks) {
                this.nonEmptyChunks = nonEmpty;
            }

            // TLAS tick（节流）
            long elapsedMs = elapsedMs();
            if (elapsedMs - lastTlasTick > TLAS_TICK_MS) {
                lastTlasTick = elapsedMs;
                try {
                    Native.frameTickNative();
                } catch (Throwable t) {
                    r.fail("frameTickNative 异常: " + t);
                }
            }

            // ready 判据（v0.3：积压排空 / 静置 / 强制 三条件之一 + 最短窗口）
            boolean settled = pendingDirty == 0
                    || (elapsedMs - lastActivityMs) > IDLE_SETTLE_MS
                    || elapsedMs >= FORCE_READY_MS;
            if (firstReadyMs < 0 && rebuilds >= 8 && elapsedMs >= MIN_INGEST_MS && settled) {
                firstReadyMs = elapsedMs;
            }
            if (firstReadyMs >= 0 && elapsedMs - firstReadyMs > 4000L) {
                r.pass(String.format("摄取窗口完成（%d 次重建 / %d 非空区块 / 积压 %d / %.1fs）",
                        rebuilds, nonEmpty, pendingDirty, elapsedMs / 1000.0));
                // v0.5.1：窗口结束不再终止摄取——转常驻（事件驱动 +
                // 哨兵），活到游戏退出。旧 v0.4.x 此处 return 即线程死：
                // 6572/6936 段永远进不了 RT 世界。
                startIngestLoop("窗口完成");
                return;
            }
            if (elapsedMs > MAX_INGEST_MS) {
                r.info("摄取超时上限（150s）——进入验证段");
                startIngestLoop("窗口超时兜底");
                return;
            }
        }
    }

    // ==================================================================
    // v0.5.1 事件驱动摄取：哨兵扫描 / 事件消费 / 常驻摄取线程
    // ==================================================================

    /**
     * 哨兵全量扫描（v0.4.6 距离优先逻辑原样抽出——run() 窗口期每轮、
     * 常驻线程 mixin 命中 2s / 未命中 500ms）。
     *
     * @return {本轮哨兵重建数, 积压 dirty 数, 非空区块数}
     */
    private int[] sweep(Object[] chunks) {
        sweepCount++;
        int nonEmpty = 0;
        int rebuilt = 0;
        int pendingDirty = 0;
        List<Candidate> candidates = new ArrayList<>(64);

        for (Object builtChunk : chunks) {
            if (builtChunk == null) {
                continue;
            }
            int index = (Integer) Mappings.get(builtChunk, Mappings.C_BUILT_CHUNK,
                    Mappings.F_BC_INDEX);
            long sectionPos = (Long) Mappings.call(builtChunk, Mappings.C_BUILT_CHUNK,
                    Mappings.M_BC_GET_SECTION_POS);
            Object data = Mappings.get(builtChunk, Mappings.C_BUILT_CHUNK, Mappings.F_BC_DATA);
            // data 是 AtomicReference（JDK 类，直接 cast）
            Object chunkData = data instanceof java.util.concurrent.atomic.AtomicReference
                    ? ((java.util.concurrent.atomic.AtomicReference<?>) data).get() : null;

            SlotState st = slots.computeIfAbsent(index, k -> new SlotState());
            boolean dataChanged = chunkData != st.lastDataRef;
            boolean moved = sectionPos != st.lastSectionPos;
            // v0.3：从未摄取（generation==0）的槽位永远视为脏——v0.2 的
            // 根因是首轮扫描把全部 600 个 data 引用快照进 lastDataRef，
            // 每轮 8 个 cap 截流后其余 592 个被标记为"干净"永不摄取
            //（真机实测 30/600 的机制）。
            boolean neverIngested = st.generation == 0;
            // v0.2：空试重试（世界加载时序容错）。首次空试不致命——
            // 区块源可能尚未就绪；窗口内每 4 poll（2s）重试一次，上限 6 次。
            boolean retryEmpty = st.generation > 0 && !st.ready
                    && st.emptyAttempts > 0 && st.emptyAttempts < 6
                    && chunkData != null
                    && (polls - st.lastEmptyPoll) >= 4;
            st.lastDataRef = chunkData;
            st.lastSectionPos = sectionPos;

            if (chunkData != null) {
                nonEmpty++;
            }

            if (moved) {
                // v0.4.6：重定位即失效 origin 缓存（不论世代——未摄取的
                // 候选也可能在两轮之间移动，距离排序需新鲜 origin）
                originCache.remove(index);
            }

            if (moved && st.generation > 0) {
                // 重定位（网格平移）：首版直接按重建处理（rebuild 更新 origin）
                relocations++;
            }

            boolean dirty = (dataChanged || moved || neverIngested || retryEmpty)
                    && chunkData != null;
            if (dirty) {
                pendingDirty++;
                candidates.add(new Candidate(builtChunk, index));
            } else if (dataChanged && chunkData == null && st.generation > 0) {
                invalidate(builtChunk, index);
            }
        }

        // v0.4.6：候选按相机距离近先挑选（相机段距离 0 必然首轮入选）。
        float[] cam = lastCameraPos;
        if (cam != null && !candidates.isEmpty()) {
            resolveOrigins(candidates, cam);
            if (!priorityEchoed) {
                priorityEchoed = true;
                r.info("v0.4.6 摄取优先级：相机距离近先（数组序把相机段饿死"
                        + "在 relZ 尾行的根因修复；本轮候选 " + candidates.size() + "）");
            }
            if (candidates.size() > MAX_REBUILD_PER_POLL) {
                candidates.sort(CANDIDATE_ORDER);
            }
        }
        // v0.4.6：预算按成功数计（空试不占名额——与 v0.4.5 语义一致，
        // 近处空区块跳过后名额顺延给更远候选）
        for (int ci = 0; ci < candidates.size()
                && rebuilt < MAX_REBUILD_PER_POLL; ci++) {
            Candidate c = candidates.get(ci);
            if (rebuild(c.chunk, c.index)) {
                rebuilt++;
                if (!cameraSectionEchoed && cam != null && c.hasOrigin) {
                    int tx = (int) Math.floor(cam[0] / 16.0) * 16;
                    int ty = (int) Math.floor(cam[1] / 16.0) * 16;
                    int tz = (int) Math.floor(cam[2] / 16.0) * 16;
                    if (c.ox == tx && c.oy == ty && c.oz == tz) {
                        cameraSectionEchoed = true;
                        r.pass(String.format(
                                "[camera-first] 相机段 (%d,%d,%d) 已摄取（rebuild #%d）"
                                + "——近景几何与 -Y 脚下金丝雀从此有据可依",
                                c.ox, c.oy, c.oz, rebuilds));
                    }
                }
            }
        }
        return new int[] {rebuilt, pendingDirty, nonEmpty};
    }

    /**
     * v0.5.1：事件批消费（mixin setData HEAD → PathtraceHook 队列）。
     * 每事件 = MC 已重建该 section（新 ChunkData 已发布）——直接重建
     * 摄取；成功后同步槽位状态（哨兵不重复摄取）。region 未就绪等容错
     * 沿用 rebuild() 既有路径（noteEmptyAttempt + invalidate）。
     *
     * @return 本批实际消费事件数
     */
    private int drainMeshEvents(int max) {
        int n = 0;
        while (n < max) {
            Object bc = PathtraceHook.pollMeshEvent();
            if (bc == null) {
                break;
            }
            n++;
            if (PathtraceMod.bisectIngestOff) {
                continue; // Phase D：事件丢弃（与哨兵同语义——摄取暂停）
            }
            if (!meshHitEchoed) {
                meshHitEchoed = true;
                r.pass("[ingest] mixin 事件命中（class_851#method_60908 setData"
                        + " HEAD——MC mesh 发布点直连）：发现延迟 ≤50ms，哨兵降频 2s"
                        + "（兜底重定位/失效/远段）");
            }
            try {
                int index = (Integer) Mappings.get(bc, Mappings.C_BUILT_CHUNK,
                        Mappings.F_BC_INDEX);
                if (rebuild(bc, index)) {
                    syncSlotState(bc, index);
                }
            } catch (Throwable t) {
                r.fail("[ingest] 事件处理异常（队列继续）: " + t);
            }
        }
        if (n > 0) {
            eventBatches++;
        }
        return n;
    }

    /**
     * v0.5.1：事件处理后同步槽位状态（哨兵 dirty 判定的两个快照字段——
     * 不同步则哨兵会把刚处理过的段再次视为 dirty 重复摄取）。
     */
    private void syncSlotState(Object builtChunk, int index) {
        try {
            long sectionPos = (Long) Mappings.call(builtChunk, Mappings.C_BUILT_CHUNK,
                    Mappings.M_BC_GET_SECTION_POS);
            Object data = Mappings.get(builtChunk, Mappings.C_BUILT_CHUNK, Mappings.F_BC_DATA);
            Object chunkData = data instanceof java.util.concurrent.atomic.AtomicReference
                    ? ((java.util.concurrent.atomic.AtomicReference<?>) data).get() : null;
            SlotState st = slots.get(index);
            if (st != null) {
                st.lastDataRef = chunkData;
                st.lastSectionPos = sectionPos;
                originCache.remove(index);
            }
        } catch (Throwable ignored) {
            // 状态同步失败最多导致哨兵重复摄取一次（rebuild 幂等）——无害
        }
    }

    /**
     * v0.5.1：窗口结束 → 摄取线程转常驻（幂等）。双通道：事件（mixin
     * 命中，≤50ms 响应）+ 哨兵（2s 全量兜底；mixin 未命中自动升回
     * 500ms = v0.5.0 等价回退）。TLAS tick 1s 聚合重建；世界退出挂起
     * 等重进（哨兵全量重扫）。活到游戏退出（daemon）。
     */
    private void startIngestLoop(String reason) {
        if (ingestThread != null) {
            return; // 幂等（窗口完成/超时两路只启一次）
        }
        boolean mixinArmed = PathtraceHook.meshEventArmed;
        r.pass(String.format(
                "[ingest] 摄取线程转常驻（%s）：事件驱动%s + %dms 哨兵全量兜底——"
                + "世界变化持续入库（rebuild→epoch 递增→live 累积按需重收敛），"
                + "活到游戏退出；世界重进后哨兵全量重扫",
                reason,
                mixinArmed ? "（mixin 已命中）" : "（mixin 未命中——哨兵维持 500ms 全量轮询，v0.5.0 等价回退）",
                mixinArmed ? SENTRY_MS : POLL_MS));
        residentEchoed = true;
        lastIngestHeartbeatMs = elapsedMs();
        ingestThread = new Thread(() -> {
            try {
                ingestLoop();
            } catch (Throwable t) {
                r.fail("[ingest] 常驻摄取线程致命异常（已退出——哨兵通道随线程消亡）: " + t);
            }
        }, "Pathtrace-Ingest");
        ingestThread.setDaemon(true);
        ingestThread.start();
    }

    /** 常驻摄取主循环（Pathtrace-Ingest 线程体）。 */
    private void ingestLoop() {
        long lastSweepMs = elapsedMs();
        long lastTlasTick = 0;
        boolean worldLostEchoed = false;
        while (running) {
            try {
                Object mc = Mappings.call(null, Mappings.C_MC, Mappings.M_MC_GET_INSTANCE);
                Object world = Mappings.get(mc, Mappings.C_MC, Mappings.F_MC_WORLD);
                if (world == null) {
                    if (!worldLostEchoed) {
                        worldLostEchoed = true;
                        r.info("[ingest] 世界已退出——事件队列清空，挂起等待重进"
                                + "（重进后哨兵全量重扫，slots 世代沿 data 引用变化自然失效）");
                    }
                    PathtraceHook.clearMeshEvents();
                    sleepQuiet(500L);
                    continue;
                }
                worldLostEchoed = false;
                if (PathtraceMod.bisectIngestOff) {
                    sleepQuiet(500L);
                    continue;
                }
                // 相机缓存（哨兵距离排序用；失败容忍）
                try {
                    lastCameraPos = readCameraPos();
                } catch (Throwable ignored) {
                    // 菜单/加载中——哨兵本轮退回数组序（既有容错路径）
                }

                // 1) 事件批（mixin setData HEAD → 无锁队列）
                int batch = drainMeshEvents(EVENT_BATCH);

                // 2) 哨兵全量扫描（节奏自适应：命中 2s / 未命中 500ms）
                long now = elapsedMs();
                boolean mixinArmed = PathtraceHook.meshEventArmed;
                long sweepPeriod = mixinArmed ? SENTRY_MS : POLL_MS;
                if (now - lastSweepMs >= sweepPeriod) {
                    lastSweepMs = now;
                    Object[] chunks = readChunksArray(mc, world);
                    if (chunks != null && chunks.length > 0) {
                        int[] s = sweep(chunks);
                        heartbeatSweepRebuilds += s[0];
                        if (s[2] != this.nonEmptyChunks) {
                            this.nonEmptyChunks = s[2];
                        }
                    }
                }

                // 3) TLAS tick（1s 节流聚合——事件批后的 BLAS 变更在下一
                // tick 进 TLAS；epoch 递增自动通知 live 累积重收敛）
                if (now - lastTlasTick > TLAS_TICK_MS) {
                    lastTlasTick = now;
                    try {
                        Native.frameTickNative();
                    } catch (Throwable ignored) {
                        // ping-pong 维护环在同周期会重锚——此处静默容忍
                    }
                }

                // 4) 心跳（10s；仅在有活动或模式切换后打——不刷屏）
                if (now - lastIngestHeartbeatMs >= INGEST_HEARTBEAT_MS) {
                    long hbEvents = heartbeatEvents;
                    int hbSweep = heartbeatSweepRebuilds;
                    heartbeatEvents = 0;
                    heartbeatSweepRebuilds = 0;
                    lastIngestHeartbeatMs = now;
                    if (hbEvents > 0 || hbSweep > 0 || eventsProcessed > 0) {
                        r.info(String.format(
                                "[ingest] 心跳 t=%.0fs: 事件 +%d（累计 %d，批 %d）/ 哨兵重建 +%d"
                                + "（扫描 %d 轮）/ 总 rebuild %d / 队列 %d / 模式 %s",
                                now / 1000.0, hbEvents, eventsProcessed, eventBatches,
                                hbSweep, sweepCount, rebuilds,
                                PathtraceHook.meshQueueDepth(),
                                PathtraceHook.meshEventArmed ? "EVENT+SENTRY" : "SENTRY(回退)"));
                    }
                }
                heartbeatEvents += batch;
                eventsProcessed += batch;
                if (batch > 0) {
                    lastActivityMs = elapsedMs();
                    sleepQuiet(EVENT_BATCH_THROTTLE_MS);
                } else {
                    sleepQuiet(IDLE_SLEEP_MS);
                }
            } catch (Throwable t) {
                r.fail("[ingest] 摄取轮异常（继续）: " + t);
                sleepQuiet(500L);
            }
        }
    }

    /** mc+world → BuiltChunkStorage.chunks[]（失败 null）。 */
    private Object[] readChunksArray(Object mc, Object world) {
        Object worldRenderer = Mappings.get(world, Mappings.C_CLIENT_WORLD,
                Mappings.F_CW_WORLD_RENDERER);
        if (worldRenderer == null) {
            return null;
        }
        Object storage = Mappings.get(worldRenderer, Mappings.C_WORLD_RENDERER,
                Mappings.F_WR_CHUNKS);
        if (storage == null) {
            return null;
        }
        Object chunkArr = Mappings.get(storage, Mappings.C_STORAGE, Mappings.F_ST_CHUNKS);
        if (chunkArr instanceof Object[] && ((Object[]) chunkArr).length > 0) {
            return (Object[]) chunkArr;
        }
        return null;
    }

    /** 吞中断的睡眠（常驻线程标准姿势）。 */
    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean firstGridInit(int numChunks, Object storage) {
        try {
            int sizeX = (Integer) Mappings.get(storage, Mappings.C_STORAGE, Mappings.F_ST_SIZE_X);
            int sizeY = (Integer) Mappings.get(storage, Mappings.C_STORAGE, Mappings.F_ST_SIZE_Y);
            int sizeZ = (Integer) Mappings.get(storage, Mappings.C_STORAGE, Mappings.F_ST_SIZE_Z);
            Native.initNative(numChunks, sizeX, sizeY, sizeZ, 0, metallibPath, r.getFile());
            r.pass(String.format("网格初始化: %d 槽位 (sizeX=%d sizeY=%d sizeZ=%d)",
                    numChunks, sizeX, sizeY, sizeZ));
            gridInited = true;
            return true;
        } catch (Throwable t) {
            r.fail("网格初始化失败: " + t);
            running = false;
            return false;
        }
    }

    /**
     * 单区块重建：SectionBuilder.build → 逐 layer 顶点收集 → JNI。
     */
    private boolean rebuild(Object builtChunk, int index) {
        long t0 = System.nanoTime();
        try {
            Object origin = Mappings.call(builtChunk, Mappings.C_BUILT_CHUNK,
                    Mappings.M_BC_GET_ORIGIN);
            int ox = (Integer) Mappings.call(origin, Mappings.C_VEC3I, Mappings.M_VEC3I_GET_X);
            int oy = (Integer) Mappings.call(origin, Mappings.C_VEC3I, Mappings.M_VEC3I_GET_Y);
            int oz = (Integer) Mappings.call(origin, Mappings.C_VEC3I, Mappings.M_VEC3I_GET_Z);

            // WorldRenderer.chunkBuilder → ChunkBuilder.sectionBuilder
            Object world = Mappings.get(Mappings.call(null, Mappings.C_MC,
                    Mappings.M_MC_GET_INSTANCE), Mappings.C_MC, Mappings.F_MC_WORLD);
            Object worldRenderer = Mappings.get(world, Mappings.C_CLIENT_WORLD,
                    Mappings.F_CW_WORLD_RENDERER);
            Object chunkBuilder = Mappings.get(worldRenderer, Mappings.C_WORLD_RENDERER,
                    Mappings.F_WR_CHUNK_BUILDER);
            Object sectionBuilder = Mappings.get(chunkBuilder, Mappings.C_CHUNK_BUILDER,
                    Mappings.F_CB_SECTION_BUILDER);
            if (sectionBuilder == null) {
                return false;
            }

            // region
            Object sectionPosObj = Mappings.call(null, Mappings.C_SECTION_POS,
                    Mappings.M_SP_FROM_BLOCKPOS, origin);
            ConstructorRef regionBuilderCtor = ctorRegionBuilder();
            Object regionBuilder = regionBuilderCtor.newInstance();
            Object region = Mappings.call(regionBuilder, Mappings.C_REGION_BUILDER,
                    Mappings.M_RB_BUILD, world, sectionPosObj);
            if (region == null) {
                // 区块源未加载 → 失效（v0.2：记空试，窗口内可重试）
                noteEmptyAttempt(index);
                invalidate(builtChunk, index);
                return false;
            }

            // 自有 ThreadLocal allocator storage（上游同款纪律：BufferAllocator
            // 必须归我们，MC 的会被复用）
            Object storage = allocStorage.get();
            Object sorter = Mappings.get(null, Mappings.C_VERTEX_SORTER, Mappings.F_VS_BY_DISTANCE);
            Object renderData = Mappings.call(sectionBuilder, Mappings.C_SECTION_BUILDER,
                    Mappings.M_SB_BUILD, sectionPosObj, region, sorter, storage);

            Object buffersMap = Mappings.get(renderData, Mappings.C_RENDER_DATA,
                    Mappings.F_RD_BUFFERS);
            if (!(buffersMap instanceof Map) || ((Map<?, ?>) buffersMap).isEmpty()) {
                Mappings.call(renderData, Mappings.C_RENDER_DATA, Mappings.M_RD_CLOSE);
                // v0.2：记空试（section 当前无可见几何或时序未就绪）——窗口内可重试
                noteEmptyAttempt(index);
                invalidate(builtChunk, index);
                return false;
            }

            Map<?, ?> buffers = (Map<?, ?>) buffersMap;
            List<ByteBuffer> vertexBuffers = new ArrayList<>(buffers.size());
            List<Integer> counts = new ArrayList<>(buffers.size());
            List<Integer> strides = new ArrayList<>(buffers.size());
            List<Integer> colorOffsets = new ArrayList<>(buffers.size());
            List<Integer> uvOffsets = new ArrayList<>(buffers.size());
            int totalVerts = 0;

            // VertexFormatElement.COLOR 静态实例（Mappings 审计过的 field_52108）。
            // MC 把方块染色（草绿/水蓝…）烘进顶点色 —— 它就是 2.2 的 albedo。
            // 2.3：UV_0 静态实例（field_52109，软符号）—— atlas 采样的 UV 源；
            // 任一缺失时该 layer uv=-1（kernel 回退纯顶点色 albedo）。
            Object colorElement = null;
            try {
                colorElement = Mappings.get(null, Mappings.C_VERTEX_ELEM, Mappings.F_VE_COLOR);
            } catch (Throwable t) {
                colorElement = null;
            }
            Object uvElement = null;
            try {
                java.lang.reflect.Field uvF = Mappings.resOpt(
                        Mappings.C_VERTEX_ELEM + "#" + Mappings.F_VE_UV);
                if (uvF != null) {
                    uvElement = uvF.get(null);
                } else if (!uvElemEchoed) {
                    r.info("UV_0 元素符号缺失（[S8soft] 行）—— uv 提取禁用，"
                            + "albedo 回退纯顶点色（2.3 atlas 采样不可用）");
                    uvElemEchoed = true;
                }
            } catch (Throwable t) {
                uvElement = null;
            }

            for (Object builtBuffer : buffers.values()) {
                Object params = Mappings.call(builtBuffer, Mappings.C_BUILT_BUFFER,
                        Mappings.M_BB_GET_PARAMS);
                int vertexCount = (Integer) Mappings.call(params, Mappings.C_DRAW_PARAMS,
                        Mappings.M_DP_VERTEX_COUNT);
                Object format = Mappings.call(params, Mappings.C_DRAW_PARAMS, Mappings.M_DP_FORMAT);
                int stride = (Integer) Mappings.call(format, Mappings.C_VERTEX_FORMAT,
                        Mappings.M_VF_VERTEX_SIZE);
                int colorOff = -1;
                if (colorElement != null) {
                    try {
                        Boolean has = (Boolean) Mappings.call(format, Mappings.C_VERTEX_FORMAT,
                                Mappings.M_VF_HAS, colorElement);
                        if (has != null && has) {
                            colorOff = (Integer) Mappings.call(format, Mappings.C_VERTEX_FORMAT,
                                    Mappings.M_VF_GET_OFFSET, colorElement);
                        }
                    } catch (Throwable t) {
                        colorOff = -1;
                    }
                }
                int uvOff = -1;
                if (uvElement != null) {
                    try {
                        Boolean has = (Boolean) Mappings.call(format, Mappings.C_VERTEX_FORMAT,
                                Mappings.M_VF_HAS, uvElement);
                        if (has != null && has) {
                            uvOff = (Integer) Mappings.call(format, Mappings.C_VERTEX_FORMAT,
                                    Mappings.M_VF_GET_OFFSET, uvElement);
                        }
                    } catch (Throwable t) {
                        uvOff = -1;
                    }
                }
                ByteBuffer buf = (ByteBuffer) Mappings.call(builtBuffer, Mappings.C_BUILT_BUFFER,
                        Mappings.M_BB_GET_BUFFER);
                if (vertexCount <= 0 || buf == null) {
                    continue;
                }
                if (vertexCount % 4 != 0) {
                    r.fail(String.format("非 QUADS 顶点数 (%d) @ index=%d —— 跳过该 layer",
                            vertexCount, index));
                    continue;
                }
                vertexBuffers.add(buf);
                counts.add(vertexCount);
                strides.add(stride);
                colorOffsets.add(colorOff);
                uvOffsets.add(uvOff);
                totalVerts += vertexCount;
            }

            if (colorOffsets.size() > 0 && !colorOffsetEchoed) {
                colorOffsetEchoed = true;
                StringBuilder ev = new StringBuilder();
                for (int i = 0; i < colorOffsets.size(); i++) {
                    if (ev.length() > 0) { ev.append("  "); }
                    ev.append(String.format("layer%d(stride=%d colorOff=%d uvOff=%d)",
                            i, strides.get(i), colorOffsets.get(i), uvOffsets.get(i)));
                }
                r.info("顶点格式取证: " + ev + "（COLOR/UV0 元素偏移由 VertexFormat"
                        + ".getOffset 动态解析——2.2 albedo 通路 + 2.3 atlas 采样通路）");
            }

            long gen = generation.incrementAndGet();
            boolean submitted = false;
            if (!vertexBuffers.isEmpty()) {
                Native.rebuildSingleNative(ox, oy, oz, index, gen, vertexBuffers.size(),
                        vertexBuffers.toArray(new ByteBuffer[0]),
                        toIntArray(counts), toIntArray(strides), toIntArray(colorOffsets),
                        toIntArray(uvOffsets));
                submitted = true;
                rebuilds++;
                SlotState st = slots.get(index);
                if (st != null) {
                    st.generation = gen;
                    st.ready = true;
                    st.emptyAttempts = 0;
                }
                double ms = (System.nanoTime() - t0) / 1e6;
                if (rebuilds <= 20 || rebuilds % 50 == 0) {
                    r.info(String.format("rebuild #%d: index=%d origin=(%d,%d,%d) "
                            + "layers=%d verts=%d (%.1f ms)",
                            rebuilds, index, ox, oy, oz, vertexBuffers.size(), totalVerts, ms));
                }
            }

            // 释放我们 allocator 的重建产物（顶点已在 native 侧同步拷贝）
            Mappings.call(renderData, Mappings.C_RENDER_DATA, Mappings.M_RD_CLOSE);
            return submitted;
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            r.fail(String.format("rebuild 异常 @ index=%d: %s", index, c));
            return false;
        }
    }

    /** v0.4.6：dirty 候选（chunk 引用 + 槽位号；相机可读时附 origin 与距离²）。 */
    private static final class Candidate {
        final Object chunk;
        final int index;
        int ox;
        int oy;
        int oz;
        boolean hasOrigin;
        double d2;

        Candidate(Object chunk, int index) {
            this.chunk = chunk;
            this.index = index;
        }
    }

    /** 距离²近先；同距按槽位号（确定性，等价数组序平局裁决）。 */
    private static final java.util.Comparator<Candidate> CANDIDATE_ORDER = (a, b) -> {
        int c = Double.compare(a.d2, b.d2);
        return c != 0 ? c : Integer.compare(a.index, b.index);
    };

    /**
     * v0.4.6：解析候选 origin（带 index 级缓存；getOrigin 为已审计符号），
     * 并计算相机到 section AABB [origin, origin+16) 的 clamp 距离²
     * （相机自身段为 0，脚下段 ≈ 眼高 1.62——天然最优先级）。
     */
    private void resolveOrigins(List<Candidate> list, float[] cam) {
        for (Candidate c : list) {
            int[] o = originCache.get(c.index);
            if (o == null) {
                o = readOrigin(c.chunk);
                if (o != null) {
                    originCache.put(c.index, o);
                }
            }
            if (o == null) {
                c.hasOrigin = false;
                c.d2 = Double.MAX_VALUE;
                continue;
            }
            c.ox = o[0];
            c.oy = o[1];
            c.oz = o[2];
            c.hasOrigin = true;
            double dx = Math.max(o[0], Math.min(cam[0], o[0] + 16.0)) - cam[0];
            double dy = Math.max(o[1], Math.min(cam[1], o[1] + 16.0)) - cam[1];
            double dz = Math.max(o[2], Math.min(cam[2], o[2] + 16.0)) - cam[2];
            c.d2 = dx * dx + dy * dy + dz * dz;
        }
    }

    /** BuiltChunk.getOrigin → {ox, oy, oz}（反射失败返回 null）。 */
    private static int[] readOrigin(Object builtChunk) {
        try {
            Object origin = Mappings.call(builtChunk, Mappings.C_BUILT_CHUNK,
                    Mappings.M_BC_GET_ORIGIN);
            return new int[] {
                    (Integer) Mappings.call(origin, Mappings.C_VEC3I, Mappings.M_VEC3I_GET_X),
                    (Integer) Mappings.call(origin, Mappings.C_VEC3I, Mappings.M_VEC3I_GET_Y),
                    (Integer) Mappings.call(origin, Mappings.C_VEC3I, Mappings.M_VEC3I_GET_Z)
            };
        } catch (Throwable t) {
            return null;
        }
    }

    /** v0.2：记录一次空试（region 未就绪 / 无几何），供窗口内重试调度。 */
    private void noteEmptyAttempt(int index) {
        SlotState st = slots.get(index);
        if (st != null) {
            st.emptyAttempts++;
            st.lastEmptyPoll = polls;
        }
    }

    private void invalidate(Object builtChunk, int index) {
        try {
            Object origin = Mappings.call(builtChunk, Mappings.C_BUILT_CHUNK,
                    Mappings.M_BC_GET_ORIGIN);
            int ox = (Integer) Mappings.call(origin, Mappings.C_VEC3I, Mappings.M_VEC3I_GET_X);
            int oy = (Integer) Mappings.call(origin, Mappings.C_VEC3I, Mappings.M_VEC3I_GET_Y);
            int oz = (Integer) Mappings.call(origin, Mappings.C_VEC3I, Mappings.M_VEC3I_GET_Z);
            long gen = generation.incrementAndGet();
            Native.invalidateSingle(index, ox, oy, oz, gen);
            invalidations++;
            SlotState st = slots.get(index);
            if (st != null) {
                st.generation = gen;
                st.ready = false;
            }
        } catch (Throwable t) {
            r.fail("invalidate 异常 @ index=" + index + ": " + t);
        }
    }

    private final ThreadLocal<Object> allocStorage = ThreadLocal.withInitial(() -> {
        try {
            java.lang.reflect.Constructor<?> ctor = Mappings.res(
                    "ctor:" + Mappings.C_ALLOC_STORAGE);
            return ctor.newInstance();
        } catch (Throwable t) {
            throw new IllegalStateException("BlockBufferAllocatorStorage 构造失败", t);
        }
    });

    private static ConstructorRef ctorRegionBuilder() {
        return new ConstructorRef();
    }

    private static final class ConstructorRef {
        Object newInstance() throws Exception {
            java.lang.reflect.Constructor<?> ctor = Mappings.res(
                    "ctor:" + Mappings.C_REGION_BUILDER);
            return ctor.newInstance();
        }
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] arr = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            arr[i] = list.get(i);
        }
        return arr;
    }

    /** v0.3：相机位置读取（与 PathtraceMod.cameraPos 同链路；全部符号
     * 已入 S3 审计）。失败由调用方吞掉（缓存下轮重试）。 */
    private float[] readCameraPos() {
        Object mc = Mappings.call(null, Mappings.C_MC, Mappings.M_MC_GET_INSTANCE);
        Object gameRenderer = Mappings.get(mc, Mappings.C_MC, Mappings.F_MC_GAME_RENDERER);
        Object camera = Mappings.get(gameRenderer, Mappings.C_GAME_RENDERER,
                Mappings.F_CAMERA_POS);
        Object pos = Mappings.call(camera, Mappings.C_CAMERA, Mappings.M_CAMERA_GET_POS);
        float x = ((Double) Mappings.get(pos, Mappings.C_VEC3D, Mappings.F_VEC3D_X)).floatValue();
        float y = ((Double) Mappings.get(pos, Mappings.C_VEC3D, Mappings.F_VEC3D_Y)).floatValue();
        float z = ((Double) Mappings.get(pos, Mappings.C_VEC3D, Mappings.F_VEC3D_Z)).floatValue();
        return new float[] {x, y, z};
    }

    private long elapsedMs() {
        return (System.nanoTime() - startNs) / 1_000_000L;
    }
}
