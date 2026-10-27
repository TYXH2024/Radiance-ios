package net.prisma.sceneingest;

import java.nio.ByteBuffer;

/**
 * 原生绑定 —— libradiance_scene.dylib 的 JNI 入口族。
 *
 * 装载由 SceneIngestionMod 控制（解包到 game 目录后 System.load），
 * 本类只声明 native 方法；不做任何 ClassLoader 级副作用。
 *
 * JNI 协议（docs/chapter2-scene-ingestion-design.md §5，降维自上游
 * ChunkProxy.rebuildSingleNative——砍掉 geometryType/texture/groupName/
 * transferredBuffers/terrain）：
 *   - 顶点以 java.nio.ByteBuffer 直传（MC BufferAllocator 产物恒为
 *     direct buffer），native 侧 GetDirectBufferAddress 取址；
 *   - 顶点恒为 float3 @ offset 0，stride 由 Java 反射读取随传；
 *   - QUADS 非索引：native 按 quad 生成 uint32 索引；
 *   - recentring：native 侧平移 -origin 存 chunk-local，TLAS instance
 *     transform = translate(origin)（float32 大坐标精度纪律，§4）。
 */
final class Native {

    private Native() {}

    /** 网格初始化（BuiltChunkStorage.createChunks 对应物；装载 metallib + 建 ray 管线）。 */
    static native void initNative(int numChunks, int sizeX, int sizeY, int sizeZ, int bottomY,
            String metallibPath, String reportPath);

    /**
     * 单区块重建：所有 RenderLayer 的顶点缓冲 → 拼接 recentred float3 →
     * quad 索引展开 → 三角 BLAS → 槽位就绪，TLAS 置脏。
     *
     * @param generation 槽位代数（Java 侧维护，单调递增；native 拒绝过期写入）
     */
    static native void rebuildSingleNative(int originX, int originY, int originZ,
            long index, long generation, int layerCount,
            ByteBuffer[] vertices, int[] vertexCounts, int[] strides);

    /** 区块失效（空网格/卸载）。 */
    static native void invalidateSingle(long index, int originX, int originY, int originZ,
            long generation);

    /** 区块重定位（网格平移）。 */
    static native void relocateSingle(long index, int originX, int originY, int originZ,
            long generation);

    /** TLAS 脏时重建（节流由 Java 侧调用频率控制）。 */
    static native void frameTickNative();

    /** 槽位是否持有就绪 BLAS。 */
    static native boolean isChunkReady(long index);

    /**
     * 场景统计（报告/判定用）。
     * 返回 [readyBLAS, totalTris, totalVerts, tlasBuilds, blasBuilds,
     *        vertexBytes, indexBytes, tlasBytes, retiredPending]
     */
    static native long[] sceneStatsNative();

    /**
     * 场景求交验证（world 空间 ray；TLAS instance 已把 local 变换回 world）。
     * 返回 [hitFlag(0/1), distance, instanceIndex]，未命中 hitFlag=0。
     */
    static native float[] castRayNative(float ox, float oy, float oz,
            float dx, float dy, float dz, float maxDist);

    /** 全资源释放（退役列表延迟释放后 shutdown）。 */
    static native void shutdownNative();
}
