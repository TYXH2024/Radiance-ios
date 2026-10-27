package net.prisma.pathtrace;

import java.nio.ByteBuffer;

/**
 * 原生绑定 —— libradiance_pathtrace.dylib 的 JNI 入口族。
 *
 * 装载由 PathtraceMod 控制（解包到 game 目录后 System.load），
 * 本类只声明 native 方法；不做任何 ClassLoader 级副作用。
 *
 * 与 2.1 scene-ingestion 的差异（docs/chapter2-pathtrace-design.md）：
 *   - rebuildSingleNative 增传 colorOffsets（逐 layer 的 COLOR 元素字节偏移；
 *     −1 = 该 layer 顶点格式无 COLOR，native 回退白色）
 *   - 顶点不再 recentre：SectionBuilder 顶点本就是 section 局部坐标
 *     （2.1 v0.5 真机定谳），BLAS 原样存储，TLAS instance = translate(origin)
 *   - 删除 sceneInfoNative/setPlacementModeNative（2.1 诊断矩阵已完成使命）
 *   - 新增 renderPathtraceNative：离屏路径追踪渲染一帧，返回 0xAARRGGBB
 */
final class Native {

    private Native() {}

    /** 网格初始化（装载 metallib + 建 ray_query / pathtrace 双管线）。 */
    static native void initNative(int numChunks, int sizeX, int sizeY, int sizeZ, int bottomY,
            String metallibPath, String reportPath);

    /**
     * 单区块重建：所有 RenderLayer 的顶点缓冲 → 拼接 section-local float3 +
     * 逐顶点 uchar4 COLOR → quad 索引展开 → 三角 BLAS → 槽位就绪，TLAS 置脏。
     *
     * @param generation 槽位代数（Java 侧维护，单调递增；native 拒绝过期写入）
     * @param colorOffsets 逐 layer COLOR 元素偏移（−1 = 无 COLOR → 白色回退）
     */
    static native void rebuildSingleNative(int originX, int originY, int originZ,
            long index, long generation, int layerCount,
            ByteBuffer[] vertices, int[] vertexCounts, int[] strides, int[] colorOffsets);

    /** 区块失效（空网格/卸载）。 */
    static native void invalidateSingle(long index, int originX, int originY, int originZ,
            long generation);

    /** 区块重定位（网格平移）。 */
    static native void relocateSingle(long index, int originX, int originY, int originZ,
            long generation);

    /** TLAS 脏时重建（含 2.2 shading pools：逐三角颜色/法线/实例基址）。 */
    static native void frameTickNative();

    /**
     * v0.3 仪器修复：在调用（探测）线程上强制重建 TLAS + shading pools。
     *
     * 实证链（三轮真机，同一存档）：2.1 v0.5 的每次 ray 电池前总有探测线程上的
     * TLAS 重建（setPlacementMode+frameTick）→ 9/14 命中；2.2 v0.1/v0.2 的 S6
     * 直查摄取期 TLAS（探测线程上无新鲜 AS build）→ 0/6 × 两次（确定性）；
     * v0.2 审计电池在同一 g_tlas 上、前面有 build_iso_tlas → 3/6 命中
     * （hitPoint 27.00/91.00/6.00 与 v0.5 完全一致）。判别变量 = 探测线程上
     * 是否刚有 AS build——机制为驱动层（同线程同 TLAS 同代码，离线不可复现），
     * 本调用复刻已验证模式。trace_ray/castRayNative/normalize_dir 与 2.1
     * 逐字节一致已由离线 diff 排除代码层缺陷。
     */
    static native void refreshTlasNative();

    /** 槽位是否持有就绪 BLAS。 */
    static native boolean isChunkReady(long index);

    /**
     * 场景统计（报告/判定用）。
     * 返回 [readyBLAS, totalTris, totalVerts, tlasBuilds, blasBuilds,
     *        vertexBytes, indexBytes, tlasBytes, retiredPending]
     */
    static native long[] sceneStatsNative();

    /**
     * 场景求交（world 空间 ray；TLAS instance = translate(origin)）。
     * 返回 [hitFlag(0/1), distance, instanceIndex]，未命中 hitFlag=0。
     */
    static native float[] castRayNative(float ox, float oy, float oz,
            float dx, float dy, float dz, float maxDist);

    /**
     * 哨兵求交（2.1 v0.5 真机验证的原样保留）：隔离的单三角 BLAS + 双实例
     * TLAS（单位 + 平移 K=(4096,0,4096)），三条 ray（两阳一阴）。
     * 返回位码：b0 单位实例命中 / b1 平移实例命中 / b2 阴性对照正确 miss。
     */
    static native int sentinelProbeNative();

    /** 命中归因：TLAS 实例索引 → 对应槽位的 section origin。返回 [ox, oy, oz]。 */
    static native int[] instanceOriginNative(int instanceIndex);

    /**
     * v0.2 S6.5 判别审计（v0.1 真机 PLACEMENT_FAIL 的根因分离仪器）：
     * raw-AABB 审计 + 相机区块顶点回读 + live TLAS 实例矩阵回读 +
     * 隔离单实例 TLAS 对照电池（与 live TLAS 并排打同一组 ray）。
     * 返回 [rawVerdict(0/1/2/3), cameraSlotFound, liveInstanceMatrixOk,
     *        isoHits, isoGridHits, isoDownHit, liveHits, liveDownHit]。
     */
    static native int[] sceneAuditNative(float camX, float camY, float camZ);

    /**
     * v0.2 诊断渲染通道：隔离单实例 TLAS（相机区块 BLAS + translate(origin)）
     * + 同一渲染管线与 shading 池。live 摆放抽查失败时调用——若成图即证明
     * 渲染链全链健康、缺陷定位到 multi-instance TLAS 组装。
     */
    static native int[] renderIsolatedNative(float camX, float camY, float camZ,
            float yawDeg, float pitchDeg, int width, int height, int spp, int bounces);

    /**
     * S7 离屏路径追踪渲染一帧。
     *
     * 相机约定（class_4184 访问器，mappings 已离线校验）：
     * yaw 0=+Z(南) 90=−X(西)；pitch −90=正上 +90=正下；70° 垂直视场角。
     *
     * @param spp      每像素采样数
     * @param bounces  最大漫反射弹射数（0 = 直接光 only）
     * @return 0xAARRGGBB 像素数组（width*height，行主序，alpha=255）；
     *         派发/资源失败返回 null（细节见报告 [render] 行）
     */
    static native int[] renderPathtraceNative(float camX, float camY, float camZ,
            float yawDeg, float pitchDeg, int width, int height, int spp, int bounces);

    /** 全资源释放（退役列表延迟释放后 shutdown）。 */
    static native void shutdownNative();
}
