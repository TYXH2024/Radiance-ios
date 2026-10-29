//
//  metal_pathtrace.m
//  Radiance iOS Port — Chapter 2 — Stage 2.3 — Live Path Tracing (v0.4.2)
//
//  Scene manager behind net.prisma.pathtrace.Native (JNI). Fork of the 2.1
//  scene-ingestion manager (field-verified through v0.5 — SCENE_INGEST_OK)
//  with three surgical changes and one addition:
//
//    [fork, unchanged]  initNative / rebuildSingleNative / invalidate /
//                        relocate / frameTickNative / isChunkReady /
//                        sceneStatsNative / castRayNative / sentinelProbe /
//                        instanceOriginNative / shutdown + generation &
//                        retired-resource protocols.
//
//    [change 1]         Vertex COLOR extraction: rebuildSingleNative now also
//                        receives per-layer COLOR element offsets (resolved
//                        dynamically via VertexFormat.getOffset(COLOR) on the
//                        Java side — no hardcoded 12). MC bakes block tinting
//                        into vertex colors; they are the path tracer albedo.
//                        Stored per slot as uchar4 buffer. Layers whose format
//                        lacks COLOR fall back to white (offset < 0).
//
//    [change 2]         Placement simplification (2.1 v0.5 sealed contract):
//                        SectionBuilder vertices are SECTION-LOCAL, so the
//                        BLAS stores them VERBATIM (no recentring) and the TLAS
//                        instance transform = translate(origin). Equivalent to
//                        the verified recentring+translate(2*origin) pair, one
//                        subtraction less, semantics straight.
//
//    [change 3]         Shading pools rebuilt alongside every TLAS:
//                        triColors (3 x uchar4 per triangle, indexed through the
//                        quad-expanded index buffer), triNormals (one
//                        packed_float3 face normal per triangle, computed from
//                        the same vertex buffer the BLAS was built from), and
//                        instTriBase (first triangle index per TLAS instance).
//                        Pool order == instance order — the kernel indexes
//                        shading data by committed instance/primitive ids.
//
//    [addition]         renderPathtraceNative — offscreen path-traced frame:
//                        camera basis from (pos, yaw, pitch), dispatches
//                        pathtrace_kernel over the pixel grid, reads back the
//                        RGBA8 image + an atomic ray counter (MEASURED rays/s
//                        for the report, not an estimate).
//
//    [v0.4 additions]   2.3 live session (progressive accumulation + GL
//                        compositing + atlas albedo):
//                        - per-vertex UV0 extraction (like COLOR, offset
//                          resolved dynamically Java-side; (-1,-1) sentinel
//                          for layers without UV0) and a triUV shading pool
//                          (3 x float2 per triangle, rebuilt with every TLAS);
//                        - a persistent live render session: float4
//                          accumulation buffer + present (tonemapped RGBA8)
//                          image, refreshed by liveFrameNative on the RT worker
//                          thread (camera-move auto-reset, TLAS-rebuild
//                          auto-reset, per-thread TLAS re-anchor at start and
//                          periodically — the v0.3 field-proven driver
//                          pattern);
//                        - atlas upload: the Java GL compositor reads the MC
//                          block atlas back from GL into a native staging
//                          buffer (liveAtlasBufferNative) and
//                          uploadAtlasNative turns it into an MTLTexture
//                          (private storage + blit copy — universally
//                          supported); a 1x1 white fallback texture keeps
//                          the vertex-color path when the atlas is
//                          unavailable;
//                        - livePresentNative hands the GL compositor a stable
//                          snapshot (wait + memcpy to a staging buffer) so
//                          the GL upload can never tear against an in-flight
//                          present dispatch.
//
//    [removed vs 2.1]   The v0.4 diagnostic matrix (sceneInfoNative raw-AABB
//                        audit, setPlacementModeNative dual-hypothesis mode) —
//                        its questions were answered and sealed by the 2.1
//                        v0.5 field run; the sentinel + integer-grid spot
//                        check remain as permanent regression instruments.
//
//  Instance matrix layout — MTLPackedFloat4x3, translation at flat[9..11]
//  (column 3). Sentinel-verified on device (identity + translate instances,
//  v0.5: both positive rays hit at exactly 64.00). Full attribution in the
//  2.1 module header (chapter2/scene-ingestion/native/metal_scene.m).
//
//  Generation protocol (inherited from upstream ChunkProxy): every slot write
//  carries a Java-side monotonically increasing generation; stale writes are
//  rejected — protects the polling design against out-of-order rebuilds.
//
//  Retired-resource protocol: replaced BLAS/vertex/index buffers go into a
//  retired list which is flushed only after the next TLAS build completes —
//  an in-flight TLAS may still reference the old BLAS pointers.
//
//  Threading: all entry points are invoked from the single Java ingestion
//  thread (SceneProxy) — no locking required (documented contract).
//
//  Report plumbing mirrors metal_probe.m: every detail line is appended to
//  the Java-side report file (incremental flush — survives native crashes).
//

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <simd/simd.h>
#import <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
#include <float.h>
#include <stdarg.h>
#include <mach/mach_time.h>

// ------------------------- report plumbing -------------------------

static FILE *g_report = NULL;

static void rp(const char *fmt, ...) {
    if (g_report == NULL) { return; }
    va_list ap;
    va_start(ap, fmt);
    vfprintf(g_report, fmt, ap);
    va_end(ap);
    fputc('\n', g_report);
    fflush(g_report);
}

static double clockms(void) {
    static mach_timebase_info_data_t timebase;
    if (timebase.denom == 0) { mach_timebase_info(&timebase); }
    return (double)mach_absolute_time() * (double)timebase.numer /
           (double)timebase.denom / 1e6;
}

// ------------------------- scene state -------------------------

@interface SceneSlot : NSObject
@property (nonatomic, strong) id<MTLAccelerationStructure> blas;
@property (nonatomic, strong) id<MTLBuffer> vertexBuffer; // section-local float3
@property (nonatomic, strong) id<MTLBuffer> indexBuffer;  // uint32
@property (nonatomic, strong) id<MTLBuffer> colorBuffer;  // uchar4 per vertex (albedo tint)
@property (nonatomic, strong) id<MTLBuffer> uvBuffer;     // 2.3: float2 per vertex (atlas UV; (-1,-1)=no UV0)
@property (nonatomic) int32_t ox, oy, oz;
@property (nonatomic) int64_t generation;
@property (nonatomic) uint32_t vertexCount;
@property (nonatomic) uint32_t indexCount;
@property (nonatomic) BOOL ready;
@property (nonatomic) BOOL hasColor;         // any layer carried COLOR
@property (nonatomic) BOOL hasUv;           // 2.3: any layer carried UV0
// v0.2: raw (as-extracted, pre-everything) AABB per slot — the 2.1 v0.5
// vertex-space audit instrument, restored after the v0.1 field run lost it
// (six-way all-miss with sentinel PASS and byte-identical extraction code).
@property (nonatomic) float rawMinX, rawMinY, rawMinZ;
@property (nonatomic) float rawMaxX, rawMaxY, rawMaxZ;
@property (nonatomic) BOOL rawValid;
@end

@implementation SceneSlot
@end

static id<MTLDevice> g_device = nil;
static id<MTLCommandQueue> g_queue = nil;
static NSMutableArray<SceneSlot *> *g_slots = nil;
static NSMutableArray<id> *g_retired = nil;   // old BLAS/buffers (delayed release)
static id<MTLAccelerationStructure> g_tlas = nil;
static id<MTLBuffer> g_instanceBuffer = nil;
static BOOL g_tlasDirty = NO;
// v0.4.4: 最近一次成功构建并换装的 TLAS 句柄快照（rebuild_tlas_locked 内
// 与 g_tlas 同锁写入）。目的：判离 v0.4.3 现场的"探针 2/6 / worker 0/6"
// 悖论——worker 哨兵同时直查 g_tlas 与 g_verifiedTlas 并回显两个句柄的
// build 号与指针身份：
//   g_tlas=0/6 + verified=N/6 ⇒ worker 的 g_tlas 读出了旧/悬空句柄
//   （线程可见性/换装间隙问题——句柄传递方式需修）；
//   双 0/6 而探针线程同对象刚命中 ⇒ GPU/驱动层线程现象（升级战场）；
//   双 N/6 ⇒ TLAS 健康，all-miss 出在渲染通路（相机基/uniforms）。
static id<MTLAccelerationStructure> g_verifiedTlas = nil;
static uint64_t g_verifiedTlasBuild = 0;
static id<MTLLibrary> g_library = nil;
static id<MTLComputePipelineState> g_pipeline = nil;      // scene_trace_kernel
static id<MTLComputePipelineState> g_ptPipeline = nil;    // pathtrace_kernel
static id<MTLComputePipelineState> g_presentPipeline = nil; // 2.3: present_kernel
static id<MTLTexture> g_atlasTexture = nil;   // 2.3: MC block atlas (or nil)
static id<MTLTexture> g_whiteTexture = nil;  // 2.3: 1x1 white fallback
static id<MTLSamplerState> g_sampler = nil;  // 2.3: linear / clamp-to-edge

// ---- 2.2 shading pools (rebuilt with every TLAS; order == instance order) ----
static id<MTLBuffer> g_triColorPool = nil;   // 3 x uchar4 per triangle
static id<MTLBuffer> g_triNormalPool = nil;  // packed_float3 per triangle
static id<MTLBuffer> g_instTriBase = nil;    // uint per instance
static id<MTLBuffer> g_triUVPool = nil;      // 2.3: 3 x float2 per triangle
static uint32_t g_poolTris = 0;              // triangles covered by the pools
static uint64_t g_poolColorBytes = 0;
static uint64_t g_poolNormalBytes = 0;
static uint64_t g_ptRays = 0;                // last frame's measured ray count

// -------------------------------------------------------------------
// 2.3 live session state (progressive accumulation + presentation).
// Threading contract: liveFrameNative runs on the RT worker thread;
// livePresentNative / liveReadbackNative / liveStatsNative may run on the
// game render thread (the GL compositor hook). All live session entry
// points synchronize on g_liveLock — the only cross-thread Metal state.
// -------------------------------------------------------------------
static NSObject *g_liveLock = nil;
static BOOL g_liveActive = NO;
static uint32_t g_liveW = 0, g_liveH = 0, g_liveSpp = 4, g_liveBounces = 2;
static id<MTLBuffer> g_liveAccum = nil;      // float4 per pixel
static id<MTLBuffer> g_liveImage = nil;      // uchar4 per pixel (presented)
static id<MTLBuffer> g_liveStaging = nil;    // uchar4 per pixel (stable snapshot)
// v0.4.3: staging 有效数据起点（= contents + kGuardPad，头尾各 64KB
// guard）。GL 像素传输的源/目标指针可能被遗留的 UNPACK/PACK SKIP_*
// 状态或驱动内部分块路径平移出缓冲边界（v0.4.2 现场 SIGSEGV：hs_err
// 渲染线程 GlComposer.present → glTexSubImage2D → _platform_memmove
// 读源 SEGV_ACCERR，317216+201184=518400 恰为整帧字节数——源被平移
// 出尾部）。guard 让 ≤64KB 的残余平移落在有效内存内：脏像素可容忍，
// 进程死亡不可容忍。Java 侧同步四重防御（分段上传/容量闸门/完整
// 五元组重置/污染取证行）见 GlComposer.present。
static uint8_t *g_liveStagingData = NULL;
static id<MTLBuffer> g_liveUniform = nil;    // PtUniforms (shared)
static id<MTLBuffer> g_liveStats = nil;      // 2 x uint32 atomics (per frame)
static id<MTLCommandBuffer> g_liveCmd = nil; // last committed frame (accum+present)
static uint32_t g_liveFrames = 0;            // frames accumulated
static uint32_t g_liveSppTotal = 0;          // samples per pixel accumulated
static uint32_t g_liveResets = 0;            // camera/scene resets
static uint32_t g_liveRefreshes = 0;         // worker-thread TLAS re-anchors
static uint64_t g_liveTlasBuilds = 0;        // TLAS build count at last frame
static uint64_t g_liveTotalQueries = 0;
static uint32_t g_liveLastQueries = 0;       // last COMPLETED frame's stats
static uint32_t g_liveLastCamHits = 0;
static double g_liveLastMs = 0.0;
static int g_liveAtlasMode = 0;              // 0=white/vertex-color 1=atlas RGB
static id<MTLBuffer> g_atlasStaging = nil;   // GL readback target (shared)
static uint8_t *g_atlasStagingData = NULL;   // v0.4.3: contents + kGuardPad
static uint64_t g_atlasStagingBytes = 0;
// v0.4.7: bisect Phase B kill-switch —— 整条 atlas 链（纹理+暂存+上传
// 通道）可运行时灭活；TLAS 构建时刻记录供死亡行打 age=（“重建后 ~1s
// 即死”签名直接量化）。
static BOOL g_atlasEnabled = YES;
static double g_lastTlasBuildMs = -1.0;
/** v0.4.3: staging 头尾 guard pad（字节）。 */
static const NSUInteger kGuardPad = 64 * 1024;

// v0.4: per-instance slot origins of the live TLAS (hit attribution).
static int32_t (*g_instOrigins)[3] = NULL;
static uint32_t g_instOriginCount = 0;

// v0.4.5: TLAS 毒化定谳仪器。v0.4.4 现场已定谳：毒化是【时间级】而非
// 线程级——同一 TLAS#30 探针线程 30.05s 命中 2/6、32s 双线程双亡；残存
// camHit 0.6% 与快照里 0.40% 非平坦像素（相机本地一条几何带）对应。
// ①实例缓冲影子副本：构建时 CPU 侧字节快照——实例缓冲是 SHARED 存储，
// 驱动/邻居的越界写入可用 memcmp 直接现形（首异字节偏移 + hexdump =
// 写手签名）；②64KB 共享金丝雀：liveBegin 分配、确定性图案——任何
// 越过我们分配边界的共享内存写手都会在图案上留下可读签名；③instance-
// CensusNative：每实例一条 -Y 金丝瑞射线——幸存实例集合的【分布模式】
// （连续区段=线性扫写 TLAS 实例记录 / 空间聚集=相机本地 / 散布=BLAS 层）
// 一步指向毒化层。
static uint8_t *g_instanceShadow = NULL;
static uint64_t g_instanceShadowBytes = 0;
static id<MTLBuffer> g_sharedCanary = nil;
static const NSUInteger kCanaryBytes = 64 * 1024;
static uint32_t g_liveAtlasDispatched = 0;

// stats: [0]=readyBLAS [1]=totalTris [2]=totalVerts [3]=tlasBuilds
//        [4]=blasBuilds [5]=vertexBytes [6]=indexBytes [7]=tlasBytes
//        [8]=retiredPending
static uint64_t g_stats[9];

// v0.4.2: scene-state mutex — guards the {g_tlas, g_instanceBuffer,
// g_triColorPool, g_triNormalPool, g_instTriBase, g_triUVPool,
// g_instOrigins/g_instOriginCount} swap section in rebuild_tlas against
// unlocked readers (castRayNative read g_tlas with NO lock in <= v0.4.1 —
// a probe-thread rebuild could swap-and-retire the TLAS between the
// worker's pointer read and the encoder bind: a use-after-free window on
// the exact AS object whose queries went silently all-miss on device).
// Lock ORDER (deadlock audit): liveFrameNative takes g_liveLock THEN
// g_sceneLock (L→S). Nobody takes S→L: rebuild_tlas / castRayNative take
// g_sceneLock only; refreshTlasNative checks g_liveLock, RELEASES it, then
// rebuilds. uploadAtlas/livePresent take g_liveLock only.
// @synchronized(nil) is a documented no-op — pre-init callers (single-
// threaded by construction) degrade to the old unlocked behavior.
static NSObject *g_sceneLock = nil;

static void flush_retired(void) {
    if (g_retired != nil && g_retired.count > 0) {
        rp("[retired] releasing %llu deferred objects",
           (unsigned long long)g_retired.count);
        [g_retired removeAllObjects];
        g_stats[8] = 0;
    }
}

// Build BLAS for one slot from concatenated recentred vertices + indices.
// Assumes slot->vertexBuffer/indexBuffer already filled.
static BOOL build_blas(SceneSlot *slot) {
    MTLAccelerationStructureTriangleGeometryDescriptor *geometry =
        [MTLAccelerationStructureTriangleGeometryDescriptor descriptor];
    geometry.vertexBuffer = slot.vertexBuffer;
    geometry.vertexBufferOffset = 0;
    geometry.vertexStride = sizeof(simd_float3); // compact float3
    geometry.vertexFormat = MTLAttributeFormatFloat3;
    geometry.indexBuffer = slot.indexBuffer;
    geometry.indexBufferOffset = 0;
    geometry.indexType = MTLIndexTypeUInt32;
    geometry.triangleCount = slot.indexCount / 3;

    MTLPrimitiveAccelerationStructureDescriptor *blasDescriptor =
        [MTLPrimitiveAccelerationStructureDescriptor descriptor];
    blasDescriptor.geometryDescriptors = @[ geometry ];

    MTLAccelerationStructureSizes sizes =
        [g_device accelerationStructureSizesWithDescriptor:blasDescriptor];
    id<MTLAccelerationStructure> blas =
        [g_device newAccelerationStructureWithSize:sizes.accelerationStructureSize];
    id<MTLBuffer> scratch =
        [g_device newBufferWithLength:sizes.buildScratchBufferSize
                              options:MTLResourceStorageModeShared];
    if (blas == nil || scratch == nil) {
        rp("FATAL: BLAS allocation failed (size=%llu)",
           (unsigned long long)sizes.accelerationStructureSize);
        return NO;
    }

    id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
    id<MTLAccelerationStructureCommandEncoder> enc =
        [cmd accelerationStructureCommandEncoder];
    [enc buildAccelerationStructure:blas
                          descriptor:blasDescriptor
                       scratchBuffer:scratch
                   scratchBufferOffset:0];
    [enc endEncoding];
    [cmd commit];
    [cmd waitUntilCompleted];
    if (cmd.error != nil) {
        rp("FATAL: BLAS build failed for origin=(%d,%d,%d): %s",
           slot.ox, slot.oy, slot.oz, cmd.error.localizedDescription.UTF8String);
        return NO;
    }

    if (slot.blas != nil) { [g_retired addObject:slot.blas]; g_stats[8]++; }
    slot.blas = blas;
    g_stats[4]++;
    return YES;
}

// v0.4: doc-confirmed MTLPackedFloat4x3 encoding — identity + translate.
// Four packed float3 columns: rotation basis in columns 0-2, translation in
// column 3 (flat[9..11]). See the file header for the full attribution.
static void encode_instance_matrix(void *dst, float tx, float ty, float tz) {
    float *m = (float *)dst;
    m[0] = 1; m[1] = 0; m[2] = 0;      // column 0
    m[3] = 0; m[4] = 1; m[5] = 0;      // column 1
    m[6] = 0; m[7] = 0; m[8] = 1;      // column 2
    m[9] = tx; m[10] = ty; m[11] = tz; // column 3 = translation
}

// vsub: component-wise float3 subtraction. simd_sub exists only for matrix
// types in C (vector arithmetic is C++ operator territory — CI clang on the
// iphoneos SDK confirmed: no float3 overload). dot/cross/normalize DO have
// C overloads and are used as-is.
static inline simd_float3 vsub(simd_float3 a, simd_float3 b) {
    return simd_make_float3(a.x - b.x, a.y - b.y, a.z - b.z);
}

// Rebuild TLAS over all ready slots + the shading pools (same order). Flushes
// the retired list afterwards.
// v0.4.2: split into rebuild_tlas (public entry — scene-state swap under
// g_sceneLock) and rebuild_tlas_locked (original body unchanged). Readers
// on other threads (worker liveFrameNative / castRayNative) snapshot the
// TLAS + shading pools under the same mutex, closing the swap-retire-read
// use-after-free window (see g_sceneLock comment above).
static void rebuild_tlas_locked(void) {
    if (!g_tlasDirty) { return; }

    // Collect ready slots (instance order = slot scan order; the kernel's
    // committed instance id maps back to this order, recorded per instance).
    NSMutableArray<SceneSlot *> *ready = [NSMutableArray array];
    for (SceneSlot *s in g_slots) {
        if (s.ready && s.blas != nil) { [ready addObject:s]; }
    }
    if (ready.count == 0) {
        g_tlasDirty = NO;
        return;
    }

    // Instance descriptors: transform = translate(origin) — the 2.1 v0.5
    // sealed contract. Vertices are stored SECTION-LOCAL (verbatim), so
    // world = local + origin.
    const uint32_t n = (uint32_t)ready.count;
    uint32_t colorSlots = 0;   // function scope: reported in the [tlas] line
    size_t instBytes = n * sizeof(MTLAccelerationStructureInstanceDescriptor);
    MTLAccelerationStructureInstanceDescriptor *instances =
        (MTLAccelerationStructureInstanceDescriptor *)calloc(1, instBytes);
    if (instances == NULL) { return; }
    NSMutableArray<id<MTLAccelerationStructure>> *blasRefs =
        [NSMutableArray arrayWithCapacity:n];
    int32_t (*newOrigins)[3] =
        (int32_t (*)[3])calloc((size_t)n, sizeof(int32_t[3]));
    if (newOrigins == NULL) {
        free(instances);
        return;
    }
    for (uint32_t i = 0; i < n; i++) {
        SceneSlot *s = ready[i];
        encode_instance_matrix(&instances[i].transformationMatrix,
                                (float)s.ox, (float)s.oy, (float)s.oz);
        instances[i].options = MTLAccelerationStructureInstanceOptionNone;
        instances[i].mask = 0xFFFFFFFFu;
        instances[i].intersectionFunctionTableOffset = 0;
        instances[i].accelerationStructureIndex = i;
        newOrigins[i][0] = s.ox; newOrigins[i][1] = s.oy; newOrigins[i][2] = s.oz;
        [blasRefs addObject:s.blas];
    }

    id<MTLBuffer> instanceBuffer =
        [g_device newBufferWithBytes:instances length:instBytes
                              options:MTLResourceStorageModeShared];
    // v0.4.5: 影子副本（先于 free——sharedAuditNative 的 memcmp 基准）。
    {
        uint8_t *shadow = (uint8_t *)realloc(g_instanceShadow, instBytes);
        if (shadow != NULL) {
            memcpy(shadow, instances, instBytes);
            g_instanceShadow = shadow;
            g_instanceShadowBytes = instBytes;
        }
    }
    free(instances);
    if (instanceBuffer == nil) { free(newOrigins); return; }

    MTLInstanceAccelerationStructureDescriptor *tlasDescriptor =
        [MTLInstanceAccelerationStructureDescriptor descriptor];
    tlasDescriptor.instanceCount = n;
    tlasDescriptor.instanceDescriptorBuffer = instanceBuffer;
    tlasDescriptor.instanceDescriptorBufferOffset = 0;
    tlasDescriptor.instanceDescriptorStride = sizeof(MTLAccelerationStructureInstanceDescriptor);
    tlasDescriptor.instancedAccelerationStructures = blasRefs;

    double t0 = clockms();
    MTLAccelerationStructureSizes sizes =
        [g_device accelerationStructureSizesWithDescriptor:tlasDescriptor];
    id<MTLAccelerationStructure> tlas =
        [g_device newAccelerationStructureWithSize:sizes.accelerationStructureSize];
    id<MTLBuffer> scratch =
        [g_device newBufferWithLength:sizes.buildScratchBufferSize
                              options:MTLResourceStorageModeShared];
    if (tlas == nil || scratch == nil) {
        rp("FATAL: TLAS allocation failed");
        free(newOrigins);
        return;
    }

    id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
    id<MTLAccelerationStructureCommandEncoder> enc =
        [cmd accelerationStructureCommandEncoder];
    [enc buildAccelerationStructure:tlas
                          descriptor:tlasDescriptor
                       scratchBuffer:scratch
                   scratchBufferOffset:0];
    [enc endEncoding];
    [cmd commit];
    [cmd waitUntilCompleted];
    double ms = clockms() - t0;
    if (cmd.error != nil) {
        rp("FATAL: TLAS build failed (%u instances): %s",
           n, cmd.error.localizedDescription.UTF8String);
        free(newOrigins);
        return;
    }

    if (g_tlas != nil) { [g_retired addObject:g_tlas]; g_stats[8]++; }
    if (g_instanceBuffer != nil) { [g_retired addObject:g_instanceBuffer]; g_stats[8]++; }
    g_tlas = tlas;
    g_instanceBuffer = instanceBuffer;
    if (g_instOrigins != NULL) { free(g_instOrigins); }
    g_instOrigins = newOrigins;
    g_instOriginCount = n;
    g_stats[3]++;
    g_stats[7] = sizes.accelerationStructureSize;
    g_tlasDirty = NO;
    // v0.4.4: 换装即记 verified 快照（与 g_tlas 同锁同刻）——worker 哨兵的
    // 双句柄 A/B 基准点。
    g_verifiedTlas = tlas;
    g_verifiedTlasBuild = g_stats[3];
    // v0.4.5: 地址图（毒化若为邻居越界写，受害/幸存分配的相对布局即证据；
    // as/instBuf 列与 v0.4.4 的 ptr=0x7F7D… 系列可比对）。
    rp("[addr] TLAS build#%llu: as=%lluB obj=0x%llX instBuf(mem)=0x%llX "
       "instBytes=%llu shadow(mem)=0x%llX",
       (unsigned long long)g_stats[3],
       (unsigned long long)sizes.accelerationStructureSize,
       (unsigned long long)(uintptr_t)tlas,
       (unsigned long long)(uintptr_t)instanceBuffer.contents,
       (unsigned long long)instBytes,
       (unsigned long long)(uintptr_t)g_instanceShadow);

    // ---- 2.2 shading pools (same slot order as the instances above) ----
    // triColors: 3 x uchar4 per triangle (via the quad-expanded index buffer,
    // so barycentric interpolation in the kernel matches the BLAS triangles
    // by construction); triNormals: one packed_float3 per triangle computed
    // from the very vertex buffer the BLAS was built from; instTriBase:
    // first triangle index per instance.
    {
        uint64_t totalTris = 0;
        for (SceneSlot *s in ready) { totalTris += s.indexCount / 3; }
        uint8_t *colors = (uint8_t *)malloc((size_t)totalTris * 3 * 4);
        float *normals = (float *)malloc((size_t)totalTris * 3 * sizeof(float));
        float *uvs = (float *)malloc((size_t)totalTris * 3 * 2 * sizeof(float)); // 2.3
        uint32_t *bases = (uint32_t *)malloc((size_t)n * sizeof(uint32_t));
        if (colors == NULL || normals == NULL || bases == NULL || uvs == NULL) {
            free(colors); free(normals); free(bases); free(uvs);
            // Nil the pools: a stale pool would no longer match the new TLAS
            // instance order — rendering must refuse rather than shade wrong.
            g_triColorPool = nil; g_triNormalPool = nil; g_instTriBase = nil;
            g_triUVPool = nil;
            g_poolTris = 0;
            rp("FATAL: shading pool allocation failed (tris=%llu)",
               (unsigned long long)totalTris);
        } else {
            uint32_t triBase = 0;
            for (uint32_t i = 0; i < n; i++) {
                SceneSlot *s = ready[i];
                bases[i] = triBase;
                const uint32_t tris = s.indexCount / 3;
                const uint32_t *idx = (const uint32_t *)s.indexBuffer.contents;
                const simd_float3 *pos = (const simd_float3 *)s.vertexBuffer.contents;
                const uint8_t *col = (const uint8_t *)s.colorBuffer.contents;
                const float *uv = s.uvBuffer != nil
                    ? (const float *)s.uvBuffer.contents : NULL;
                for (uint32_t t = 0; t < tris; t++) {
                    const uint32_t i0 = idx[t * 3 + 0];
                    const uint32_t i1 = idx[t * 3 + 1];
                    const uint32_t i2 = idx[t * 3 + 2];
                    uint8_t *dstC = colors + ((size_t)(triBase + t) * 3) * 4;
                    float *dstUV = uvs + ((size_t)(triBase + t) * 3) * 2;
                    for (int k = 0; k < 3; k++) {
                        const uint32_t vi = (k == 0) ? i0 : (k == 1) ? i1 : i2;
                        dstC[k * 4 + 0] = col[(size_t)vi * 4 + 0];
                        dstC[k * 4 + 1] = col[(size_t)vi * 4 + 1];
                        dstC[k * 4 + 2] = col[(size_t)vi * 4 + 2];
                        dstC[k * 4 + 3] = 255;
                        // 2.3: per-triangle-vertex atlas UV; (-1,-1) sentinel
                        // when the slot carries no UV0 (kernel: vertex-color
                        // path — the sentinel is constant so barycentric
                        // interpolation preserves it exactly).
                        if (uv != NULL) {
                            dstUV[k * 2 + 0] = uv[(size_t)vi * 2 + 0];
                            dstUV[k * 2 + 1] = uv[(size_t)vi * 2 + 1];
                        } else {
                            dstUV[k * 2 + 0] = -1.0f;
                            dstUV[k * 2 + 1] = -1.0f;
                        }
                    }
                    simd_float3 e1 = vsub(pos[i1], pos[i0]);
                    simd_float3 e2 = vsub(pos[i2], pos[i0]);
                    simd_float3 nrm = simd_cross(e1, e2);
                    float len = sqrtf(simd_dot(nrm, nrm));
                    if (!(len > 1e-12f)) {
                        nrm = simd_make_float3(0.0f, 1.0f, 0.0f);
                        len = 1.0f;
                    }
                    float *dstN = normals + (size_t)(triBase + t) * 3;
                    dstN[0] = nrm.x / len; dstN[1] = nrm.y / len; dstN[2] = nrm.z / len;
                }
                triBase += tris;
                if (s.hasColor) { colorSlots++; }
            }
            id<MTLBuffer> colorPool =
                [g_device newBufferWithBytes:colors
                    length:(NSUInteger)(totalTris * 3 * 4)
                    options:MTLResourceStorageModeShared];
            id<MTLBuffer> normalPool =
                [g_device newBufferWithBytes:normals
                    length:(NSUInteger)(totalTris * 3 * sizeof(float))
                    options:MTLResourceStorageModeShared];
            id<MTLBuffer> basePool =
                [g_device newBufferWithBytes:bases
                    length:(NSUInteger)(n * sizeof(uint32_t))
                    options:MTLResourceStorageModeShared];
            id<MTLBuffer> uvPool =
                [g_device newBufferWithBytes:uvs
                    length:(NSUInteger)(totalTris * 3 * 2 * sizeof(float))
                    options:MTLResourceStorageModeShared];
            free(colors); free(normals); free(bases); free(uvs);
            if (colorPool == nil || normalPool == nil || basePool == nil
                    || uvPool == nil) {
                g_triColorPool = nil; g_triNormalPool = nil; g_instTriBase = nil;
                g_triUVPool = nil;
                g_poolTris = 0;
                rp("FATAL: shading pool MTLBuffer allocation failed");
            } else {
                if (g_triColorPool != nil) { [g_retired addObject:g_triColorPool]; }
                if (g_triNormalPool != nil) { [g_retired addObject:g_triNormalPool]; }
                if (g_instTriBase != nil) { [g_retired addObject:g_instTriBase]; }
                if (g_triUVPool != nil) { [g_retired addObject:g_triUVPool]; }
                g_triColorPool = colorPool;
                g_triNormalPool = normalPool;
                g_instTriBase = basePool;
                g_triUVPool = uvPool;
                g_poolTris = (uint32_t)totalTris;
                g_poolColorBytes = totalTris * 3 * 4;
                g_poolNormalBytes = totalTris * 3 * sizeof(float);
            }
        }
    }

    // Ready only after the new TLAS completed — safe to drop retired objects.
    flush_retired();
    g_lastTlasBuildMs = clockms(); // v0.4.7: build-age 基准（[tlas-health] 死亡行 age=）
    rp("[tlas] rebuilt in %.2f ms (instances=%u, as=%llu B, tris=%u, "
       "colorSlots=%u, pools=%llu+%llu B, total=%llu builds)",
       ms, n, (unsigned long long)sizes.accelerationStructureSize,
       g_poolTris, colorSlots,
       (unsigned long long)g_poolColorBytes, (unsigned long long)g_poolNormalBytes,
       (unsigned long long)g_stats[3]);
}

// v0.4.2 public entry: build + swap under the scene mutex. Early-out on the
// dirty flag stays unlocked (benign hint read — worst case one extra/missed
// no-op build across threads; the locked body re-checks).
static void rebuild_tlas(void) {
    if (!g_tlasDirty) { return; }
    @synchronized (g_sceneLock) {
        rebuild_tlas_locked();
    }
}

// ------------------------- JNI -------------------------

static const char *kMetallibPath = NULL;

static void open_report(const char *path) {
    if (g_report != NULL) { fclose(g_report); }
    g_report = path != NULL ? fopen(path, "a") : NULL;
}

JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_initNative(
        JNIEnv *env, jclass cls, jint numChunks, jint sizeX, jint sizeY, jint sizeZ,
        jint bottomY, jstring metallibPathJ, jstring reportPathJ) {
    (void)cls; (void)sizeX; (void)sizeY; (void)sizeZ; (void)bottomY;

    const char *reportPath = reportPathJ != NULL
        ? (*env)->GetStringUTFChars(env, reportPathJ, NULL) : NULL;
    open_report(reportPath);
    if (reportPath != NULL) { (*env)->ReleaseStringUTFChars(env, reportPathJ, reportPath); }

    if (g_device == nil) {
        g_device = MTLCreateSystemDefaultDevice();
        if (g_device == nil) {
            rp("FATAL: MTLCreateSystemDefaultDevice returned NULL");
            return;
        }
        g_queue = [g_device newCommandQueue];
        g_liveLock = [[NSObject alloc] init];   // 2.3: live session mutex
        if (g_sceneLock == nil) {
            g_sceneLock = [[NSObject alloc] init]; // v0.4.2: scene-state mutex
        }
        rp("[scene] device=%s raytracing=%s",
           g_device.name.UTF8String, g_device.supportsRaytracing ? "YES" : "NO");
        if (!g_device.supportsRaytracing) {
            rp("FATAL: device does not support ray tracing");
            return;
        }
        g_retired = [NSMutableArray array];
    }

    if (g_slots != nil && (jint)g_slots.count == numChunks) {
        rp("[scene] grid already initialized (%d slots) — reuse", numChunks);
    } else {
        // World reload: drop everything, start a fresh grid.
        if (g_slots != nil) {
            for (SceneSlot *s in g_slots) {
                s.ready = NO;
                if (s.blas != nil) { [g_retired addObject:s.blas]; }
                if (s.vertexBuffer != nil) { [g_retired addObject:s.vertexBuffer]; }
                if (s.indexBuffer != nil) { [g_retired addObject:s.indexBuffer]; }
                if (s.uvBuffer != nil) { [g_retired addObject:s.uvBuffer]; }
            }
        }
        g_slots = [NSMutableArray arrayWithCapacity:(NSUInteger)numChunks];
        for (jint i = 0; i < numChunks; i++) {
            [g_slots addObject:[[SceneSlot alloc] init]];
        }
        memset(g_stats, 0, sizeof(g_stats));
        rp("[scene] slot grid initialized: %d chunks", numChunks);
    }

    if (kMetallibPath != NULL) { free((void *)kMetallibPath); kMetallibPath = NULL; }
    if (metallibPathJ != NULL) {
        const char *tmp = (*env)->GetStringUTFChars(env, metallibPathJ, NULL);
        if (tmp != NULL) {
            kMetallibPath = strdup(tmp);
            (*env)->ReleaseStringUTFChars(env, metallibPathJ, tmp);
        }
    }

    // Lazy pipeline load (done here once so castRayNative is dispatch-only).
    if (g_pipeline == nil && kMetallibPath != NULL) {
        NSError *error = nil;
        g_library = [g_device newLibraryWithURL:
            [NSURL fileURLWithPath:[NSString stringWithUTF8String:kMetallibPath]]
            error:&error];
        if (g_library == nil) {
            rp("FATAL: cannot load metallib '%s': %s", kMetallibPath,
               error.localizedDescription.UTF8String);
            return;
        }
        id<MTLFunction> kernel = [g_library newFunctionWithName:@"scene_trace_kernel"];
        if (kernel == nil) {
            rp("FATAL: scene_trace_kernel not found in metallib");
            return;
        }
        g_pipeline = [g_device newComputePipelineStateWithFunction:kernel error:&error];
        if (g_pipeline == nil) {
            rp("FATAL: scene ray pipeline creation failed: %s",
               error.localizedDescription.UTF8String);
        } else {
            rp("[scene] ray_query pipeline ready (threadsPerThreadgroup=%u)",
               (uint32_t)g_pipeline.maxTotalThreadsPerThreadgroup);
        }
        // 2.2: the path tracing pipeline (loads best-effort here; a failure
        // is fatal only for S7 rendering, not for the ingestion stages).
        id<MTLFunction> pt = [g_library newFunctionWithName:@"pathtrace_kernel"];
        if (pt == nil) {
            rp("FATAL: pathtrace_kernel not found in metallib — S7 render unavailable");
            return;
        }
        g_ptPipeline = [g_device newComputePipelineStateWithFunction:pt error:&error];
        if (g_ptPipeline == nil) {
            rp("FATAL: pathtrace pipeline creation failed: %s",
               error.localizedDescription.UTF8String);
        } else {
            rp("[scene] pathtrace pipeline ready (threadsPerThreadgroup=%u)",
               (uint32_t)g_ptPipeline.maxTotalThreadsPerThreadgroup);
        }
    }
}

JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_rebuildSingleNative(
        JNIEnv *env, jclass cls, jint ox, jint oy, jint oz,
        jlong index, jlong generation, jint layerCount,
        jobjectArray vertices, jintArray counts, jintArray strides,
        jintArray colorOffsets, jintArray uvOffsets) {
    (void)cls;

    if (g_slots == nil || index < 0 || (NSUInteger)index >= g_slots.count) {
        rp("[rebuild] reject: slot index %lld out of grid", (long long)index);
        return;
    }
    SceneSlot *slot = g_slots[(NSUInteger)index];
    if (generation < slot.generation) {
        rp("[rebuild] reject: stale generation %lld < %lld @ index %lld",
           (long long)generation, (long long)slot.generation, (long long)index);
        return;
    }

    jint *cCounts = counts != NULL ? (*env)->GetIntArrayElements(env, counts, NULL) : NULL;
    jint *cStrides = strides != NULL ? (*env)->GetIntArrayElements(env, strides, NULL) : NULL;
    jint *cColorOff = colorOffsets != NULL
        ? (*env)->GetIntArrayElements(env, colorOffsets, NULL) : NULL;
    jint *cUvOff = uvOffsets != NULL
        ? (*env)->GetIntArrayElements(env, uvOffsets, NULL) : NULL;
    if (cCounts == NULL || cStrides == NULL || cColorOff == NULL || cUvOff == NULL
            || layerCount <= 0) {
        rp("[rebuild] reject: bad arrays @ index %lld", (long long)index);
        if (cCounts != NULL) { (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT); }
        if (cStrides != NULL) { (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT); }
        if (cColorOff != NULL) { (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT); }
        if (cUvOff != NULL) { (*env)->ReleaseIntArrayElements(env, uvOffsets, cUvOff, JNI_ABORT); }
        return;
    }

    // ---- Pass 1: total vertex count (validate along the way) ----
    uint64_t totalVerts = 0;
    for (jint i = 0; i < layerCount; i++) {
        if (cCounts[i] <= 0 || cStrides[i] < 12 || (cCounts[i] & 3) != 0) {
            rp("[rebuild] reject: layer %d bad geometry (count=%d stride=%d) @ index %lld",
               i, cCounts[i], cStrides[i], (long long)index);
            (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT);
            return;
        }
        totalVerts += (uint64_t)cCounts[i];
    }
    uint64_t totalIndices = totalVerts / 4 * 6;
    if (totalVerts == 0 || totalVerts > 16u * 1024u * 1024u) {
        rp("[rebuild] reject: totalVerts=%llu out of range @ index %lld",
           (unsigned long long)totalVerts, (long long)index);
        (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT);
        return;
    }

    // ---- Pass 2: compact float3 vertices (verbatim, section-local — the 2.1
    // v0.5 sealed contract: SectionBuilder vertices are already local, no
    // recentring) + per-vertex uchar4 colors (from each layer's COLOR element,
    // offset resolved dynamically Java-side; < 0 = layer has no COLOR → white)
    // + 2.3 per-vertex float2 UVs (UV0 element; < 0 = no UV0 → (-1,-1)
    // sentinel → kernel vertex-color path) + quad indices ----
    simd_float3 *verts =
        (simd_float3 *)malloc(totalVerts * sizeof(simd_float3));
    uint8_t *colors = (uint8_t *)malloc(totalVerts * 4);
    float *uvs = (float *)malloc(totalVerts * 2 * sizeof(float));
    uint32_t *idx = (uint32_t *)malloc(totalIndices * sizeof(uint32_t));
    if (verts == NULL || colors == NULL || idx == NULL || uvs == NULL) {
        rp("FATAL: OOM in rebuild (verts=%llu)", (unsigned long long)totalVerts);
        free(verts); free(colors); free(idx); free(uvs);
        (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, uvOffsets, cUvOff, JNI_ABORT);
        return;
    }

    uint64_t vBase = 0;
    BOOL anyColor = NO;
    BOOL anyUv = NO;
    float rmin[3] = { +INFINITY, +INFINITY, +INFINITY };
    float rmax[3] = { -INFINITY, -INFINITY, -INFINITY };
    for (jint i = 0; i < layerCount; i++) {
        jobject bb = (*env)->GetObjectArrayElement(env, vertices, (jsize)i);
        if (bb == NULL) {
            rp("[rebuild] reject: layer %d null ByteBuffer @ index %lld",
               i, (long long)index);
            free(verts); free(colors); free(idx);
            (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT);
            return;
        }
        const uint8_t *src = (const uint8_t *)(*env)->GetDirectBufferAddress(env, bb);
        jlong srcCap = (*env)->GetDirectBufferCapacity(env, bb);
        uint32_t vc = (uint32_t)cCounts[i];
        uint32_t stride = (uint32_t)cStrides[i];
        uint32_t colorOff = (uint32_t)cColorOff[i];   // 0xFFFFFFFF = no COLOR
        BOOL layerColor = colorOff != 0xFFFFFFFFu;
        if (layerColor) {
            // COLOR element must fit inside the vertex stride (4 x u8).
            if ((uint64_t)colorOff + 4 > stride) { layerColor = NO; }
        }
        if (src == NULL
                || (uint64_t)srcCap < (uint64_t)vc * stride) {
            rp("[rebuild] reject: layer %d non-direct/short buffer "
               "(cap=%lld need=%u) @ index %lld",
               i, (long long)srcCap, vc * stride, (long long)index);
            (*env)->DeleteLocalRef(env, bb);
            free(verts); free(colors); free(idx); free(uvs);
            (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, uvOffsets, cUvOff, JNI_ABORT);
            return;
        }
        uint32_t uvOff = (uint32_t)cUvOff[i];         // 0xFFFFFFFF = no UV0
        BOOL layerUv = uvOff != 0xFFFFFFFFu;
        if (layerUv) {
            // UV0 element must fit inside the vertex stride (2 x f32).
            if ((uint64_t)uvOff + 8 > stride) { layerUv = NO; }
        }
        // Copy verbatim (section-local per the 2.1 v0.5 sealed contract:
        // float3 @ offset 0, NO recentring) + per-vertex uchar4 COLOR
        // + 2.3 per-vertex float2 UV0 (or the (-1,-1) sentinel).
        for (uint32_t v = 0; v < vc; v++) {
            const uint8_t *base = src + (size_t)v * stride;
            const float *p = (const float *)base;
            verts[vBase + v] = simd_make_float3(p[0], p[1], p[2]);
            if (isfinite(p[0]) && isfinite(p[1]) && isfinite(p[2])) {
                if (p[0] < rmin[0]) { rmin[0] = p[0]; }
                if (p[1] < rmin[1]) { rmin[1] = p[1]; }
                if (p[2] < rmin[2]) { rmin[2] = p[2]; }
                if (p[0] > rmax[0]) { rmax[0] = p[0]; }
                if (p[1] > rmax[1]) { rmax[1] = p[1]; }
                if (p[2] > rmax[2]) { rmax[2] = p[2]; }
            }
            uint8_t *c = colors + ((size_t)(vBase + v)) * 4;
            if (layerColor) {
                c[0] = base[colorOff + 0];
                c[1] = base[colorOff + 1];
                c[2] = base[colorOff + 2];
                c[3] = 255;
                anyColor = YES;
            } else {
                c[0] = 255; c[1] = 255; c[2] = 255; c[3] = 255;
            }
            float *uv = uvs + ((size_t)(vBase + v)) * 2;
            if (layerUv) {
                const float *u = (const float *)(base + uvOff);
                uv[0] = u[0];
                uv[1] = u[1];
                if (isfinite(uv[0]) && isfinite(uv[1])) {
                    anyUv = YES;
                } else {
                    uv[0] = -1.0f; uv[1] = -1.0f;
                }
            } else {
                uv[0] = -1.0f; uv[1] = -1.0f;
            }
        }
        // QUADS → 2 triangles each.
        uint32_t *out = idx + (vBase / 4) * 6;
        for (uint32_t q = 0; q < vc / 4; q++) {
            uint32_t b = (uint32_t)(vBase) + q * 4;
            *out++ = b;     *out++ = b + 1; *out++ = b + 2;
            *out++ = b;     *out++ = b + 2; *out++ = b + 3;
        }
        vBase += vc;
        (*env)->DeleteLocalRef(env, bb);
    }
    (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT);
    (*env)->ReleaseIntArrayElements(env, uvOffsets, cUvOff, JNI_ABORT);

    // ---- Upload + BLAS ----
    id<MTLBuffer> vertexBuffer =
        [g_device newBufferWithBytes:verts length:(NSUInteger)(totalVerts * sizeof(simd_float3))
                             options:MTLResourceStorageModeShared];
    id<MTLBuffer> indexBuffer =
        [g_device newBufferWithBytes:idx length:(NSUInteger)(totalIndices * sizeof(uint32_t))
                             options:MTLResourceStorageModeShared];
    id<MTLBuffer> colorBuffer =
        [g_device newBufferWithBytes:colors length:(NSUInteger)(totalVerts * 4)
                             options:MTLResourceStorageModeShared];
    id<MTLBuffer> uvBuffer =
        [g_device newBufferWithBytes:uvs length:(NSUInteger)(totalVerts * 2 * sizeof(float))
                             options:MTLResourceStorageModeShared];
    free(verts); free(colors); free(idx); free(uvs);

    double t0 = clockms();
    slot.ox = ox; slot.oy = oy; slot.oz = oz;
    slot.generation = generation;
    slot.vertexBuffer = vertexBuffer;
    slot.indexBuffer = indexBuffer;
    slot.colorBuffer = colorBuffer;
    slot.uvBuffer = uvBuffer;
    slot.vertexCount = (uint32_t)totalVerts;
    slot.indexCount = (uint32_t)totalIndices;
    slot.hasColor = anyColor;
    slot.hasUv = anyUv;
    slot.rawMinX = rmin[0]; slot.rawMinY = rmin[1]; slot.rawMinZ = rmin[2];
    slot.rawMaxX = rmax[0]; slot.rawMaxY = rmax[1]; slot.rawMaxZ = rmax[2];
    slot.rawValid = (rmin[0] <= rmax[0]);

    if (vertexBuffer == nil || indexBuffer == nil || colorBuffer == nil
            || uvBuffer == nil || !build_blas(slot)) {
        slot.ready = NO;
        rp("[rebuild] FAILED @ index %lld origin=(%d,%d,%d)",
           (long long)index, ox, oy, oz);
        return;
    }
    slot.ready = YES;
    g_tlasDirty = YES;

    g_stats[1] += totalIndices / 3;   // tris added (approx; reset at stats time)
    g_stats[2] += totalVerts;
    g_stats[5] += totalVerts * sizeof(simd_float3);
    g_stats[6] += totalIndices * sizeof(uint32_t);
    rp("[rebuild] index=%lld origin=(%d,%d,%d) layers=%d verts=%llu tris=%llu "
       "color=%s uv=%s (blas %.2f ms, total builds=%llu)",
       (long long)index, ox, oy, oz, layerCount,
       (unsigned long long)totalVerts, (unsigned long long)(totalIndices / 3),
       anyColor ? "yes" : "WHITE", anyUv ? "yes" : "NONE",
       clockms() - t0, (unsigned long long)g_stats[4]);
}

JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_invalidateSingle(
        JNIEnv *env, jclass cls, jlong index, jint ox, jint oy, jint oz, jlong generation) {
    (void)env; (void)cls; (void)ox; (void)oy; (void)oz;
    if (g_slots == nil || index < 0 || (NSUInteger)index >= g_slots.count) { return; }
    SceneSlot *slot = g_slots[(NSUInteger)index];
    if (generation < slot.generation) { return; }
    if (slot.ready) {
        if (slot.blas != nil) { [g_retired addObject:slot.blas]; g_stats[8]++; }
        slot.blas = nil;
        slot.ready = NO;
        g_tlasDirty = YES;
        rp("[invalidate] index=%lld generation=%lld", (long long)index, (long long)generation);
    }
    slot.generation = generation;
}

JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_relocateSingle(
        JNIEnv *env, jclass cls, jlong index, jint ox, jint oy, jint oz, jlong generation) {
    // Polling design: relocation invalidates; the next poll rebuilds with the
    // new origin (rebuild is the authoritative writer of geometry).
    Java_net_prisma_pathtrace_Native_invalidateSingle(env, cls, index, ox, oy, oz, generation);
}

JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_frameTickNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (g_device == nil) { return; }
    rebuild_tlas();
}

JNIEXPORT jboolean JNICALL
Java_net_prisma_pathtrace_Native_isChunkReady(JNIEnv *env, jclass cls, jlong index) {
    (void)env; (void)cls;
    if (g_slots == nil || index < 0 || (NSUInteger)index >= g_slots.count) { return JNI_FALSE; }
    return g_slots[(NSUInteger)index].ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_net_prisma_pathtrace_Native_sceneStatsNative(JNIEnv *env, jclass cls) {
    (void)cls;
    // Recompute exact live counters from slots (g_stats accumulators are
    // additive lifetime counters; live values are authoritative for verdict).
    uint64_t ready = 0, tris = 0, verts = 0, vbytes = 0, ibytes = 0;
    if (g_slots != nil) {
        for (SceneSlot *s in g_slots) {
            if (s.ready) {
                ready++;
                tris += s.indexCount / 3;
                verts += s.vertexCount;
                vbytes += (uint64_t)s.vertexBuffer.length;
                ibytes += (uint64_t)s.indexBuffer.length;
            }
        }
    }
    jlong out[9] = {
        (jlong)ready, (jlong)tris, (jlong)verts,
        (jlong)g_stats[3], (jlong)g_stats[4],
        (jlong)vbytes, (jlong)ibytes, (jlong)g_stats[7],
        (jlong)g_stats[8],
    };
    jlongArray arr = (*env)->NewLongArray(env, 9);
    if (arr != NULL) { (*env)->SetLongArrayRegion(env, arr, 0, 9, out); }
    return arr;
}

// v0.4: shared single-ray tracer (used by castRayNative against the live
// TLAS and by sentinelProbeNative against its isolated TLAS). Direction is
// normalized by the caller. Returns YES on a clean dispatch (hitFlag/dist/
// instIdx filled from the GPU result), NO on dispatch failure.
static BOOL trace_ray(id<MTLAccelerationStructure> tlas,
                       simd_float3 origin, simd_float3 dir, float maxDist,
                       /*out*/ uint32_t *hitFlag, float *dist, uint32_t *instIdx) {
    typedef struct {
        simd_float3 origin;
        simd_float3 direction;
        float maxDistance;
        uint32_t pad;
    } SceneRay;
    typedef struct {
        float distance;
        uint32_t instanceIndex;
        uint32_t hit;
        uint32_t pad;
    } SceneHit;

    SceneRay ray;
    ray.origin = origin;
    ray.direction = dir;
    ray.maxDistance = maxDist;
    ray.pad = 0;

    id<MTLBuffer> rayBuffer =
        [g_device newBufferWithBytes:&ray length:sizeof(SceneRay)
                             options:MTLResourceStorageModeShared];
    id<MTLBuffer> hitBuffer =
        [g_device newBufferWithLength:sizeof(SceneHit)
                              options:MTLResourceStorageModeShared];
    if (rayBuffer == nil || hitBuffer == nil) { return NO; }

    id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cmd computeCommandEncoder];
    [enc setComputePipelineState:g_pipeline];
    [enc setBuffer:rayBuffer offset:0 atIndex:0];
    [enc setBuffer:hitBuffer offset:0 atIndex:1];
    [enc setAccelerationStructure:tlas atBufferIndex:2];
    [enc dispatchThreadgroups:MTLSizeMake(1, 1, 1)
         threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
    [enc endEncoding];
    [cmd commit];
    [cmd waitUntilCompleted];
    if (cmd.error != nil) {
        rp("FATAL: trace dispatch failed: %s", cmd.error.localizedDescription.UTF8String);
        return NO;
    }
    const SceneHit *h = (const SceneHit *)hitBuffer.contents;
    *hitFlag = h->hit ? 1u : 0u;
    *dist = h->distance;
    *instIdx = h->instanceIndex;
    return YES;
}

// v0.4: normalize a direction vector in place; returns NO for degenerate input.
static BOOL normalize_dir(float *dx, float *dy, float *dz) {
    float len = sqrtf(*dx * *dx + *dy * *dy + *dz * *dz);
    if (len < 1e-8f) { return NO; }
    *dx /= len; *dy /= len; *dz /= len;
    return YES;
}

// v0.4.4: castRayNative 的双句柄内核——useVerified=NO 查 g_tlas（现行），
// YES 查 g_verifiedTlas（最近换装快照）。同一 ray/同一锁/同一派发路径，
// 唯一变量是句柄来源——worker 哨兵的双电池直接判离"句柄陈旧 vs GPU 层
// 线程现象 vs 渲染通路"三假设。
static jfloatArray cast_ray_live(JNIEnv *env, BOOL useVerified,
                                 jfloat ox, jfloat oy, jfloat oz,
                                 jfloat dx, jfloat dy, jfloat dz, jfloat maxDist) {
    jfloat out[3] = {0.0f, -1.0f, -1.0f}; // hitFlag, distance, instanceIndex
    jfloatArray result = (*env)->NewFloatArray(env, 3);

    if (g_device == nil || g_pipeline == nil
            || (useVerified ? g_verifiedTlas : g_tlas) == nil) {
        if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 3, out); }
        return result;
    }

    if (!normalize_dir(&dx, &dy, &dz)) {
        if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 3, out); }
        return result;
    }

    uint32_t hitFlag = 0; float dist = -1.0f; uint32_t instIdx = 0xFFFFFFFFu;
    // v0.4.2: snapshot the TLAS under the scene mutex. <= v0.4.1 read g_tlas
    // with NO lock: a probe-thread rebuild_tlas could retire (and release)
    // the old TLAS between this read and the encoder bind — a use-after-free
    // on the exact AS object whose queries went silently all-miss on device.
    // The strong local ref covers the whole trace lifetime (the command
    // buffer additionally retains it once bound).
    id<MTLAccelerationStructure> tlas;
    @synchronized (g_sceneLock) {
        tlas = useVerified ? g_verifiedTlas : g_tlas;
    }
    if (tlas != nil
            && trace_ray(tlas, simd_make_float3(ox, oy, oz),
                         simd_make_float3(dx, dy, dz), maxDist,
                         &hitFlag, &dist, &instIdx)) {
        out[0] = hitFlag ? 1.0f : 0.0f;
        out[1] = dist;
        out[2] = (jfloat)instIdx;
    }
    if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 3, out); }
    return result;
}

JNIEXPORT jfloatArray JNICALL
Java_net_prisma_pathtrace_Native_castRayNative(
        JNIEnv *env, jclass cls, jfloat ox, jfloat oy, jfloat oz,
        jfloat dx, jfloat dy, jfloat dz, jfloat maxDist) {
    (void)cls;
    return cast_ray_live(env, NO, ox, oy, oz, dx, dy, dz, maxDist);
}

// v0.4.4: 直查最近换装的 verified TLAS（与 castRayNative 同锁同路径）。
JNIEXPORT jfloatArray JNICALL
Java_net_prisma_pathtrace_Native_castRayVerifiedNative(
        JNIEnv *env, jclass cls, jfloat ox, jfloat oy, jfloat oz,
        jfloat dx, jfloat dy, jfloat dz, jfloat maxDist) {
    (void)cls;
    return cast_ray_live(env, YES, ox, oy, oz, dx, dy, dz, maxDist);
}

// v0.4.4: TLAS 句柄身份（同一把 g_sceneLock 下快照）——[live-sentinel] 行
// 回显，句柄漂移/陈旧一眼定谳。返回 long[4]：
//   [0]=g_tlas 指针身份 [1]=g_verifiedTlas 指针身份
//   [2]=当前 build 计数 [3]=verified 快照的 build 号
JNIEXPORT jlongArray JNICALL
Java_net_prisma_pathtrace_Native_tlasIdentityNative(JNIEnv *env, jclass cls) {
    (void)cls;
    jlong out[4] = {0, 0, 0, 0};
    @synchronized (g_sceneLock) {
        out[0] = (jlong)(uintptr_t)g_tlas;
        out[1] = (jlong)(uintptr_t)g_verifiedTlas;
        out[2] = (jlong)g_stats[3];
        out[3] = (jlong)g_verifiedTlasBuild;
    }
    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result != NULL) { (*env)->SetLongArrayRegion(env, result, 0, 4, out); }
    return result;
}

// -------------------------------------------------------------------
// v0.4.5: TLAS 毒化定谳仪器（实例普查 + 共享内存审计）
// -------------------------------------------------------------------

// 实例普查：每个 TLAS 实例（section 槽位）一条 -Y 金丝瑞射线——起点在
// 该 section 自己的 16^3 盒内顶部中心（origin+8, oy+15.5, +8），向下贯穿
// 整盒（maxDist=16）。命中的实例号回显（正常 = 自身；异常 = 他人/miss）。
// v0.4.4 现场：毒化后幸存命中只剩相机本地一小片（快照 0.40% 非平坦带）。
// 本仪器把"一小片"展开成 157 个实例的幸存/阵亡地图：
//   幸存集 = 连续索引区段   ⇒ TLAS 实例记录被线性扫写（首异偏移对齐
//                              sharedAuditNative 的 instanceBuffer memcmp）
//   幸存集 = 空间聚集（相机本地）⇒ 遍历层按距离/AABB 选择性失效
//   幸存集 = 散布且重建后变化 ⇒ BLAS 层逐个被打坏
//   重建后幸存集与旧 TLAS 完全一致 ⇒ 毒化确定性命中同一批记录/BLAS
// 返回 jint[n]：0 = miss；>0 = 命中实例号+1（i 位 = 自身命中自检）。
// 代价 ~0.5ms/实例 × n（g_sceneLock 全程持有——一致性快照）。
JNIEXPORT jintArray JNICALL
Java_net_prisma_pathtrace_Native_instanceCensusNative(JNIEnv *env, jclass cls) {
    (void)cls;
    @synchronized (g_sceneLock) {
        const uint32_t n = g_instOriginCount;
        // n==0 → 空数组（Java 侧 length==0 分支：场景未就绪，不设基线）。
        jintArray result = (*env)->NewIntArray(env, (jsize)n);
        if (result == NULL) { return NULL; }
        if (n == 0 || g_tlas == nil || g_pipeline == nil) {
            return result;
        }
        jint *out = (jint *)calloc((size_t)n, sizeof(jint));
        if (out == NULL) {
            return result;
        }
        id<MTLAccelerationStructure> tlas = g_tlas;
        double t0 = clockms();
        for (uint32_t i = 0; i < n; i++) {
            const float ox = (float)g_instOrigins[i][0] + 8.0f;
            const float oy = (float)g_instOrigins[i][1] + 15.5f;
            const float oz = (float)g_instOrigins[i][2] + 8.0f;
            uint32_t hitFlag = 0; float dist = -1.0f; uint32_t instIdx = 0xFFFFFFFFu;
            if (trace_ray(tlas, simd_make_float3(ox, oy, oz),
                          simd_make_float3(0.0f, -1.0f, 0.0f), 16.0f,
                          &hitFlag, &dist, &instIdx)) {
                out[i] = hitFlag ? (jint)(instIdx + 1) : 0;
            }
        }
        rp("[census] %u 实例金丝瑞电池完成（%.0f ms，tlas=build#%llu）",
           (unsigned)n, clockms() - t0, (unsigned long long)g_stats[3]);
        (*env)->SetIntArrayRegion(env, result, 0, (jsize)n, out);
        free(out);
        return result;
    }
}

// FNV-1a 64（审计用——只求稳定性不求密码学）。
static uint64_t fnv1a64(const uint8_t *p, uint64_t len) {
    uint64_t h = 1469598103934665603ULL;
    for (uint64_t i = 0; i < len; i++) {
        h ^= (uint64_t)p[i];
        h *= 1099511628211ULL;
    }
    return h;
}

// 共享内存审计：实例缓冲（MTLBuffer SHARED 存储）vs 构建时影子副本 +
// 64KB 金丝瑞图案。毒化若发生在共享内存侧（CPU 或驱动 memcpy 越界），
// 这里直接现形——首异偏移 + 32B hexdump 就是写手签名（SKIP 平移=行步长
// 纹理图案 / 归零=memset 类 / 0xAA 类填充=驱动 poison 值）。若两项均
// 干净而 TLAS 仍死亡 ⇒ 毒化在 GPU 私有内存（AS 自身/BLAS）——战场升级
// 到 zink/MoltenVK GPU 侧写手（用户侧 atlas=false / compose=false 二分
// 轮收口）。
// 返回 jlong[8]：
//   [0] inst match(1)/mismatch(0)/no-shadow(-1)  [1] inst 首异字节偏移(-1=无)
//   [2] inst 影子 FNV                            [3] inst 现场值 FNV
//   [4] canary match(1)/mismatch(0)/none(-1)     [5] canary 首异偏移(-1=无)
//   [6] canary 被改字节数                        [7] 审计时 build#
JNIEXPORT jlongArray JNICALL
Java_net_prisma_pathtrace_Native_sharedAuditNative(JNIEnv *env, jclass cls) {
    (void)cls;
    jlong out[8] = { -1, -1, 0, 0, -1, -1, 0, 0 };
    @synchronized (g_sceneLock) {
        out[7] = (jlong)g_stats[3];
        // ---- 实例缓冲 vs 影子 ----
        if (g_instanceBuffer != nil && g_instanceShadow != NULL
                && g_instanceShadowBytes > 0) {
            const uint8_t *live = (const uint8_t *)g_instanceBuffer.contents;
            const uint8_t *shadow = g_instanceShadow;
            const uint64_t bytes = g_instanceShadowBytes;
            out[2] = (jlong)fnv1a64(shadow, bytes);
            out[3] = (jlong)fnv1a64(live, bytes);
            if (out[2] == out[3] && memcmp(live, shadow, (size_t)bytes) == 0) {
                out[0] = 1; out[1] = -1;
            } else {
                out[0] = 0;
                uint64_t diff = 0;
                uint64_t first = bytes;
                for (uint64_t i = 0; i < bytes; i++) {
                    if (live[i] != shadow[i]) {
                        if (i < first) { first = i; }
                        diff++;
                    }
                }
                out[1] = (jlong)first;
                rp("[audit] 实例缓冲被改写！首异 @+%llu/%llu B（共 %llu B "
                   "不同，build#%llu）——写手签名 hexdump：",
                   (unsigned long long)first, (unsigned long long)bytes,
                   (unsigned long long)diff, (unsigned long long)g_stats[3]);
                const uint64_t start = first > 16 ? first - 16 : 0;
                const uint64_t end = (first + 16 < bytes) ? first + 16 : bytes;
                for (uint64_t base = start; base < end; base += 16) {
                    char a[64], b[64];
                    int oa = 0, ob = 0;
                    for (uint64_t k = 0; k < 16 && base + k < end; k++) {
                        oa += snprintf(a + oa, sizeof(a) - oa, "%02X ",
                                       live[base + k]);
                        ob += snprintf(b + ob, sizeof(b) - ob, "%02X ",
                                       shadow[base + k]);
                    }
                    rp("[audit]   +%llu: live  %s", (unsigned long long)base, a);
                    rp("[audit]   +%llu: want  %s", (unsigned long long)base, b);
                }
            }
        }
        // ---- 金丝瑞图案 ----
        if (g_sharedCanary != nil) {
            const uint8_t *p = (const uint8_t *)g_sharedCanary.contents;
            uint64_t diff = 0;
            uint64_t first = kCanaryBytes;
            for (NSUInteger i = 0; i < kCanaryBytes; i++) {
                const uint8_t want = (uint8_t)(i * 31u + 7u);
                if (p[i] != want) {
                    if (i < first) { first = i; }
                    diff++;
                }
            }
            if (diff == 0) {
                out[4] = 1; out[5] = -1; out[6] = 0;
            } else {
                out[4] = 0; out[5] = (jlong)first; out[6] = (jlong)diff;
                rp("[audit] 金丝瑞被改写！首异 @+%llu/%llu B（共 %llu B "
                   "不同）——越界写手落网签名：",
                   (unsigned long long)first,
                   (unsigned long long)kCanaryBytes,
                   (unsigned long long)diff);
                const uint64_t start = first > 16 ? first - 16 : 0;
                const uint64_t end = ((uint64_t)first + 16 < (uint64_t)kCanaryBytes)
                    ? (uint64_t)first + 16 : (uint64_t)kCanaryBytes;
                for (uint64_t base = start; base < end; base += 16) {
                    char a[64];
                    int oa = 0;
                    for (uint64_t k = 0; k < 16 && base + k < end; k++) {
                        oa += snprintf(a + oa, sizeof(a) - oa, "%02X ",
                                       p[base + k]);
                    }
                    rp("[audit]   +%llu: live  %s (want %02X)",
                       (unsigned long long)base, a,
                       (unsigned)((base) * 31u + 7u) & 0xFFu);
                }
            }
        }
    }
    jlongArray result = (*env)->NewLongArray(env, 8);
    if (result != NULL) { (*env)->SetLongArrayRegion(env, result, 0, 8, out); }
    return result;
}

// -------------------------------------------------------------------
// v0.4 diagnostic matrix
// -------------------------------------------------------------------

// S6.1 sentinel: isolated TLAS with one triangle BLAS instanced twice —
// identity transform and translate(K) with the doc-confirmed encoding — plus
// three rays (two positive, one negative control). Validates the instance
// machinery and the matrix translation encoding with zero MC data involved.
//
// v0.5 fix (root cause of the v0.4 field failure, bits=4): the sentinel
// verts were packed as float[3][3] (36 B) while the geometry descriptor
// declared vertexStride = sizeof(simd_float3) = 16 B — vertex 2 read 12 B
// past the 36 B allocation (shared-memory OOB) and the BLAS encoded a
// garbage triangle, so both positive rays missed and the negative control
// "passed" vacuously. The real-scene path was never affected (it uploads
// exact simd_float3 arrays — the v0.4 tournament 5/6 + final battery 13/14
// are the field proof of the instance machinery). Verts now stored as
// simd_float3[3] (48 B) matching the stride, with a GPU readback echo line.
//
// Geometry: local triangle (0,0,0)-(1024,0,0)-(0,0,1024), plane y=0 covering
// x,z >= 0, x+z <= 1024. Instances: #0 identity (world 0..1024 area),
// #1 translate K=(4096,0,4096) (world 4096..5120 area).
// Rays:  (256,64,256)   -Y -> expect HIT @ ~64 on instance 0
//        (4352,64,4352) -Y -> expect HIT @ ~64 on instance 1
//        (256,64,256)   +Y -> expect MISS (negative control)
// Returns bits: b0 identity hit ok, b1 translated hit ok, b2 control miss ok.
JNIEXPORT jint JNICALL
Java_net_prisma_pathtrace_Native_sentinelProbeNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    jint bits = 0;
    if (g_device == nil || g_queue == nil) {
        rp("[sentinel] FATAL: device/queue not initialized");
        return 0;
    }
    if (g_pipeline == nil) {
        rp("[sentinel] FATAL: ray pipeline not loaded — cannot trace");
        return 0;
    }

    // -- sentinel triangle BLAS --
    // simd_float3[3] = 48 B — matches vertexStride = sizeof(simd_float3).
    _Static_assert(sizeof(simd_float3) == 16, "sentinel vertex stride must be 16 B");
    const simd_float3 kVerts[3] = {
        simd_make_float3(0.0f, 0.0f, 0.0f),
        simd_make_float3(1024.0f, 0.0f, 0.0f),
        simd_make_float3(0.0f, 0.0f, 1024.0f),
    };
    static const uint32_t kIdx[3] = {0, 1, 2};
    id<MTLBuffer> vb = [g_device newBufferWithBytes:kVerts length:sizeof(kVerts)
                                            options:MTLResourceStorageModeShared];
    id<MTLBuffer> ib = [g_device newBufferWithBytes:kIdx length:sizeof(kIdx)
                                            options:MTLResourceStorageModeShared];
    if (vb == nil || ib == nil) {
        rp("[sentinel] FATAL: buffer allocation failed");
        return 0;
    }
    // Readback echo — proves the bytes on the GPU-side buffer are the intended
    // triangle (guards the v0.4-class size/stride mismatch from ever recurring
    // silently; shared storage mode keeps contents CPU-readable).
    {
        const simd_float3 *echo = (const simd_float3 *)vb.contents;
        rp("[sentinel] vert readback: (%.1f, %.1f, %.1f) (%.1f, %.1f, %.1f) "
           "(%.1f, %.1f, %.1f) — stride %zu B, buffer %zu B",
           echo[0].x, echo[0].y, echo[0].z,
           echo[1].x, echo[1].y, echo[1].z,
           echo[2].x, echo[2].y, echo[2].z,
           sizeof(simd_float3), (size_t)vb.length);
    }
    MTLAccelerationStructureTriangleGeometryDescriptor *geo =
        [MTLAccelerationStructureTriangleGeometryDescriptor descriptor];
    geo.vertexBuffer = vb;
    geo.vertexBufferOffset = 0;
    geo.vertexStride = sizeof(simd_float3);
    geo.vertexFormat = MTLAttributeFormatFloat3;
    geo.indexBuffer = ib;
    geo.indexBufferOffset = 0;
    geo.indexType = MTLIndexTypeUInt32;
    geo.triangleCount = 1;

    MTLPrimitiveAccelerationStructureDescriptor *blasDesc =
        [MTLPrimitiveAccelerationStructureDescriptor descriptor];
    blasDesc.geometryDescriptors = @[ geo ];

    MTLAccelerationStructureSizes bSizes =
        [g_device accelerationStructureSizesWithDescriptor:blasDesc];
    id<MTLAccelerationStructure> blas =
        [g_device newAccelerationStructureWithSize:bSizes.accelerationStructureSize];
    id<MTLBuffer> bScratch =
        [g_device newBufferWithLength:bSizes.buildScratchBufferSize
                              options:MTLResourceStorageModeShared];
    if (blas == nil || bScratch == nil) {
        rp("[sentinel] FATAL: BLAS allocation failed");
        return 0;
    }
    {
        id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
        id<MTLAccelerationStructureCommandEncoder> enc =
            [cmd accelerationStructureCommandEncoder];
        [enc buildAccelerationStructure:blas descriptor:blasDesc
                            scratchBuffer:bScratch scratchBufferOffset:0];
        [enc endEncoding];
        [cmd commit];
        [cmd waitUntilCompleted];
        if (cmd.error != nil) {
            rp("[sentinel] FATAL: BLAS build failed: %s",
               cmd.error.localizedDescription.UTF8String);
            return 0;
        }
    }

    // -- two-instance TLAS: identity + translate(K) --
    MTLAccelerationStructureInstanceDescriptor inst[2];
    memset(inst, 0, sizeof(inst));
    encode_instance_matrix(&inst[0].transformationMatrix, 0, 0, 0);
    encode_instance_matrix(&inst[1].transformationMatrix, 4096, 0, 4096);
    for (int i = 0; i < 2; i++) {
        inst[i].options = MTLAccelerationStructureInstanceOptionNone;
        inst[i].mask = 0xFFFFFFFFu;
        inst[i].intersectionFunctionTableOffset = 0;
        inst[i].accelerationStructureIndex = 0; // both point at the sentinel BLAS
    }
    id<MTLBuffer> instBuf = [g_device newBufferWithBytes:inst length:sizeof(inst)
                                                 options:MTLResourceStorageModeShared];
    if (instBuf == nil) {
        rp("[sentinel] FATAL: instance buffer allocation failed");
        return 0;
    }
    MTLInstanceAccelerationStructureDescriptor *tlasDesc =
        [MTLInstanceAccelerationStructureDescriptor descriptor];
    tlasDesc.instanceCount = 2;
    tlasDesc.instanceDescriptorBuffer = instBuf;
    tlasDesc.instanceDescriptorBufferOffset = 0;
    tlasDesc.instanceDescriptorStride = sizeof(MTLAccelerationStructureInstanceDescriptor);
    tlasDesc.instancedAccelerationStructures = @[ blas ];

    MTLAccelerationStructureSizes tSizes =
        [g_device accelerationStructureSizesWithDescriptor:tlasDesc];
    id<MTLAccelerationStructure> tlas =
        [g_device newAccelerationStructureWithSize:tSizes.accelerationStructureSize];
    id<MTLBuffer> tScratch =
        [g_device newBufferWithLength:tSizes.buildScratchBufferSize
                              options:MTLResourceStorageModeShared];
    if (tlas == nil || tScratch == nil) {
        rp("[sentinel] FATAL: TLAS allocation failed");
        return 0;
    }
    {
        id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
        id<MTLAccelerationStructureCommandEncoder> enc =
            [cmd accelerationStructureCommandEncoder];
        [enc buildAccelerationStructure:tlas descriptor:tlasDesc
                            scratchBuffer:tScratch scratchBufferOffset:0];
        [enc endEncoding];
        [cmd commit];
        [cmd waitUntilCompleted];
        if (cmd.error != nil) {
            rp("[sentinel] FATAL: TLAS build failed: %s",
               cmd.error.localizedDescription.UTF8String);
            return 0;
        }
    }

    // -- three rays --
    uint32_t hit = 0; float dist = -1.0f; uint32_t instIdx = 0xFFFFFFFFu;

    BOOL ok0 = trace_ray(tlas, simd_make_float3(256, 64, 256),
                          simd_make_float3(0, -1, 0), 512, &hit, &dist, &instIdx);
    BOOL idOk = ok0 && hit == 1 && fabsf(dist - 64.0f) < 1.0f && instIdx == 0;
    rp("[sentinel] identity instance: ray (256,64,256)-Y -> %s "
       "(dist=%.2f instance=%u, expect HIT dist~64 instance=0)",
       (ok0 && hit == 1) ? "HIT" : "MISS",
       ok0 ? dist : -1.0f, ok0 ? instIdx : 0xFFFFFFFFu);
    if (idOk) { bits |= 1; }

    uint32_t hit1 = 0; float dist1 = -1.0f; uint32_t instIdx1 = 0xFFFFFFFFu;
    BOOL ok1 = trace_ray(tlas, simd_make_float3(4352, 64, 4352),
                          simd_make_float3(0, -1, 0), 512, &hit1, &dist1, &instIdx1);
    BOOL trOk = ok1 && hit1 == 1 && fabsf(dist1 - 64.0f) < 1.0f && instIdx1 == 1;
    rp("[sentinel] translated instance K=(4096,0,4096): ray (4352,64,4352)-Y -> %s "
       "(dist=%.2f instance=%u, expect HIT dist~64 instance=1)",
       (ok1 && hit1 == 1) ? "HIT" : "MISS",
       ok1 ? dist1 : -1.0f, ok1 ? instIdx1 : 0xFFFFFFFFu);
    if (trOk) { bits |= 2; }

    uint32_t hitC = 1; float distC = -1.0f; uint32_t instIdxC = 0xFFFFFFFFu;
    BOOL okC = trace_ray(tlas, simd_make_float3(256, 64, 256),
                          simd_make_float3(0, 1, 0), 512, &hitC, &distC, &instIdxC);
    BOOL ctrlOk = okC && hitC == 0;
    rp("[sentinel] negative control: ray (256,64,256)+Y -> %s (expect MISS)",
       (okC && hitC == 1) ? "HIT" : "MISS");
    if (ctrlOk) { bits |= 4; }

    rp("[sentinel] verdict: identity=%s translated=%s control=%s",
       (bits & 1) ? "OK" : "FAIL", (bits & 2) ? "OK" : "FAIL",
       (bits & 4) ? "OK" : "FAIL");
    return bits;
}
// ===================================================================
// v0.2 instruments — root-cause discrimination for the v0.1 field
// failure (six-way all-miss with sentinel PASS and code statically
// proven equivalent to the 2.1 v0.5 field-verified layout). Static
// analysis is exhausted; these instruments split the failure domain
// ON DEVICE in a single run:
//   raw-AABB audit     → are the extracted vertices section-local?
//   vertex readback    → what bytes are actually in the camera BLAS?
//   matrix readback    → what translation is actually in the live TLAS?
//   iso-TLAS battery   → single-instance TLAS from the camera BLAS:
//                        iso-HIT + live-MISS  => live TLAS assembly bug
//                        iso-MISS             => camera BLAS content bug
// ===================================================================

// Find the ready slot whose origin matches the camera's section
// (floor(cam/16)*16); falls back to the nearest ready slot (logged).
static SceneSlot *camera_section_slot(float cx, float cy, float cz) {
    if (g_slots == nil) { return nil; }
    const int32_t tx = (int32_t)floorf(cx / 16.0f) * 16;
    const int32_t ty = (int32_t)floorf(cy / 16.0f) * 16;
    const int32_t tz = (int32_t)floorf(cz / 16.0f) * 16;
    SceneSlot *exact = nil, *nearest = nil;
    double best = 1e30;
    for (SceneSlot *s in g_slots) {
        if (!s.ready || s.blas == nil) { continue; }
        if (s.ox == tx && s.oy == ty && s.oz == tz) { exact = s; break; }
        double d = (double)(s.ox - tx) * (s.ox - tx)
                 + (double)(s.oy - ty) * (s.oy - ty)
                 + (double)(s.oz - tz) * (s.oz - tz);
        if (d < best) { best = d; nearest = s; }
    }
    if (exact != nil) { return exact; }
    if (nearest != nil) {
        rp("[audit] camera section (%d,%d,%d) not ready — nearest ready "
           "origin=(%d,%d,%d) @ %.1f blocks",
           tx, ty, tz, nearest.ox, nearest.oy, nearest.oz, sqrtf((float)best));
    }
    return nearest;
}

// Pool base (first triangle index in the shading pools) of a ready slot —
// MUST mirror rebuild_tlas's ready-scan order exactly.
static uint32_t slot_pool_base(SceneSlot *target) {
    uint32_t base = 0;
    for (SceneSlot *s in g_slots) {
        if (!s.ready || s.blas == nil) { continue; }
        if (s == target) { return base; }
        base += s.indexCount / 3;
    }
    return 0;
}

// Single-instance TLAS from one slot's BLAS: transform = translate(origin).
static id<MTLAccelerationStructure> build_iso_tlas(SceneSlot *s) {
    MTLAccelerationStructureInstanceDescriptor inst;
    memset(&inst, 0, sizeof(inst));
    encode_instance_matrix(&inst.transformationMatrix,
                           (float)s.ox, (float)s.oy, (float)s.oz);
    inst.options = MTLAccelerationStructureInstanceOptionNone;
    inst.mask = 0xFFFFFFFFu;
    inst.intersectionFunctionTableOffset = 0;
    inst.accelerationStructureIndex = 0;
    id<MTLBuffer> instBuf =
        [g_device newBufferWithBytes:&inst length:sizeof(inst)
                              options:MTLResourceStorageModeShared];
    if (instBuf == nil) { return nil; }
    MTLInstanceAccelerationStructureDescriptor *d =
        [MTLInstanceAccelerationStructureDescriptor descriptor];
    d.instanceCount = 1;
    d.instanceDescriptorBuffer = instBuf;
    d.instanceDescriptorBufferOffset = 0;
    d.instanceDescriptorStride = sizeof(MTLAccelerationStructureInstanceDescriptor);
    d.instancedAccelerationStructures = @[ s.blas ];
    MTLAccelerationStructureSizes sizes =
        [g_device accelerationStructureSizesWithDescriptor:d];
    id<MTLAccelerationStructure> tlas =
        [g_device newAccelerationStructureWithSize:sizes.accelerationStructureSize];
    id<MTLBuffer> scratch =
        [g_device newBufferWithLength:sizes.buildScratchBufferSize
                              options:MTLResourceStorageModeShared];
    if (tlas == nil || scratch == nil) { return nil; }
    id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
    id<MTLAccelerationStructureCommandEncoder> enc =
        [cmd accelerationStructureCommandEncoder];
    [enc buildAccelerationStructure:tlas descriptor:d
                        scratchBuffer:scratch scratchBufferOffset:0];
    [enc endEncoding];
    [cmd commit];
    [cmd waitUntilCompleted];
    if (cmd.error != nil) {
        rp("[audit] FATAL: iso TLAS build failed: %s",
           cmd.error.localizedDescription.UTF8String);
        return nil;
    }
    return tlas;
}

// S6.5 audit. Returns jint[8]:
//   [0] rawVerdict  0=none 1=SECTION-LOCAL 2=WORLD-ABS 3=GARBAGE
//   [1] cameraSlotFound   [2] liveInstanceMatrixOk
//   [3] isoHits (0..6)    [4] isoGridHits    [5] isoDownHit
//   [6] liveHits (0..6)   [7] liveDownHit
JNIEXPORT jintArray JNICALL
Java_net_prisma_pathtrace_Native_sceneAuditNative(
        JNIEnv *env, jclass cls, jfloat cx, jfloat cy, jfloat cz) {
    (void)cls;
    jint out[8] = {0, 0, 0, 0, 0, 0, 0, 0};

    // ---- (a) raw-AABB audit (2.1 v0.5 instrument, restored) ----
    {
        float gmin[3] = { +INFINITY, +INFINITY, +INFINITY };
        float gmax[3] = { -INFINITY, -INFINITY, -INFINITY };
        uint32_t n = 0, garbage = 0;
        int printed = 0;
        for (SceneSlot *s in g_slots) {
            if (!s.ready || !s.rawValid) { continue; }
            n++;
            float lo[3] = { s.rawMinX, s.rawMinY, s.rawMinZ };
            float hi[3] = { s.rawMaxX, s.rawMaxY, s.rawMaxZ };
            for (int k = 0; k < 3; k++) {
                if (lo[k] < gmin[k]) { gmin[k] = lo[k]; }
                if (hi[k] > gmax[k]) { gmax[k] = hi[k]; }
            }
            if (!isfinite(lo[0]) || !isfinite(hi[0]) || hi[0] - lo[0] > 1e4f
                    || fabsf(lo[0]) > 1e5f || fabsf(hi[0]) > 1e5f) { garbage++; }
            if (printed < 5) {
                printed++;
                rp("[audit] slot (%d,%d,%d) raw=[%.2f..%.2f, %.2f..%.2f, %.2f..%.2f]",
                   s.ox, s.oy, s.oz, lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]);
            }
        }
        if (n == 0) {
            rp("[audit] raw AABB: no valid slots");
            out[0] = 0;
        } else {
            rp("[audit] global raw AABB over %u slots: [%.2f..%.2f, %.2f..%.2f, "
               "%.2f..%.2f] (garbage=%u)", n,
               gmin[0], gmax[0], gmin[1], gmax[1], gmin[2], gmax[2], garbage);
            BOOL local = gmin[0] >= -1.0f && gmax[0] <= 17.0f
                      && gmin[1] >= -1.0f && gmax[1] <= 17.0f
                      && gmin[2] >= -1.0f && gmax[2] <= 17.0f;
            if (garbage > 0) {
                rp("[audit] raw verdict: GARBAGE (%u/%u slots)", garbage, n);
                out[0] = 3;
            } else if (local) {
                rp("[audit] raw verdict: SECTION-LOCAL (within [-1..17]^3) — "
                   "2.1 v0.5 契约成立");
                out[0] = 1;
            } else {
                rp("[audit] raw verdict: NOT section-local — 顶点空间与 2.1 真机"
                   "结论不一致（这就是全 miss 的直接原因）");
                out[0] = 2;
            }
        }
    }

    // ---- (b)/(c)/(d) camera-section instruments ----
    SceneSlot *cam = camera_section_slot(cx, cy, cz);
    if (cam == nil) {
        rp("[audit] no ready slot near camera — BLAS 层仪器无法执行");
    } else {
        out[1] = 1;
        // (b) vertex readback from the camera BLAS buffer.
        {
            const simd_float3 *pos = (const simd_float3 *)cam.vertexBuffer.contents;
            const uint32_t n = cam.vertexCount;
            if (pos != NULL && n >= 3) {
                rp("[audit] camera slot (%d,%d,%d) verts=%u readback: "
                   "v0=(%.2f, %.2f, %.2f) v%d=(%.2f, %.2f, %.2f) "
                   "v%u=(%.2f, %.2f, %.2f) — stride 16 B",
                   cam.ox, cam.oy, cam.oz, n,
                   pos[0].x, pos[0].y, pos[0].z,
                   n / 2, pos[n / 2].x, pos[n / 2].y, pos[n / 2].z,
                   n - 1, pos[n - 1].x, pos[n - 1].y, pos[n - 1].z);
            }
        }
        // (b2) v0.3: COLOR readback — the albedo chain's missing evidence
        // line. MC bakes tint×shade into the COLOR element (grass green,
        // water blue, bare face shade for stone) — these bytes ARE the path
        // tracer's albedo input. The v0.2 field frame was a plausible but
        // structureless bluish field; this line settles whether the extracted
        // colors carry real variation (2.3 decides atlas-UV sampling vs
        // vertex-color on THIS evidence, not on guesses).
        {
            const uint8_t *col = (const uint8_t *)cam.colorBuffer.contents;
            const uint32_t n = cam.vertexCount;
            if (col != NULL && n >= 4 && cam.hasColor) {
                uint32_t statN = n > 4096 ? 4096 : n;
                uint32_t cmin[3] = {255, 255, 255}, cmax[3] = {0, 0, 0};
                double csum[3] = {0, 0, 0};
                uint32_t white = 0, greenish = 0;
                for (uint32_t v = 0; v < statN; v++) {
                    const uint8_t *c = col + (size_t)v * 4;
                    for (int k = 0; k < 3; k++) {
                        if (c[k] < cmin[k]) { cmin[k] = c[k]; }
                        if (c[k] > cmax[k]) { cmax[k] = c[k]; }
                        csum[k] += c[k];
                    }
                    if (c[0] >= 250 && c[1] >= 250 && c[2] >= 250) { white++; }
                    if (c[1] > c[0] && c[1] > c[2]) { greenish++; }
                }
                // exact-distinct colors over the prefix (O(n²), n ≤ 4096)
                uint32_t distinct = 0;
                for (uint32_t v = 0; v < statN; v++) {
                    const uint8_t *c = col + (size_t)v * 4;
                    BOOL dup = NO;
                    for (uint32_t u = 0; u < v; u++) {
                        const uint8_t *d = col + (size_t)u * 4;
                        if (c[0] == d[0] && c[1] == d[1] && c[2] == d[2]) {
                            dup = YES;
                            break;
                        }
                    }
                    if (!dup) { distinct++; }
                }
                rp("[audit] camera slot COLOR readback: v0=(%u,%u,%u) "
                   "v1=(%u,%u,%u) v2=(%u,%u,%u) v3=(%u,%u,%u) — 顶点色=albedo 输入",
                   col[0], col[1], col[2], col[4], col[5], col[6],
                   col[8], col[9], col[10], col[12], col[13], col[14]);
                rp("[audit] COLOR stats over %u/%u verts: R[%u..%u] G[%u..%u] "
                   "B[%u..%u] mean=(%.0f,%.0f,%.0f) distinct=%u white=%u "
                   "greenish=%u — distinct 偏低/全白 ⇒ albedo 通路缺陷（2.3 "
                   "换 atlas 采样）；有绿有方差 ⇒ 通路健康",
                   statN, n, cmin[0], cmax[0], cmin[1], cmax[1], cmin[2],
                   cmax[2], csum[0] / statN, csum[1] / statN, csum[2] / statN,
                   distinct, white, greenish);
            } else {
                rp("[audit] camera slot COLOR buffer unavailable "
                   "(hasColor=%d) — albedo 白色回退", cam.hasColor);
            }
        }
        // (b3) v0.3: shading-pool entry readback — the exact bytes the
        // kernel's barycentric albedo fetch reads for this slot's triangles
        // (closes the chain: vertex COLOR → pool entry → kernel fetch).
        if (g_triColorPool != nil && cam.indexCount >= 3) {
            const uint32_t base = slot_pool_base(cam);
            const uint32_t tris = cam.indexCount / 3;
            const uint8_t *pool = (const uint8_t *)g_triColorPool.contents;
            if (pool != NULL && tris > 0 && base + tris <= g_poolTris) {
                const uint32_t samples[3] = {0, tris / 2, tris - 1};
                for (int s = 0; s < 3; s++) {
                    const uint32_t t = base + samples[s];
                    const uint8_t *p = pool + (size_t)t * 12;
                    rp("[audit] pool tri #%u (slot tri %u): "
                       "(%u,%u,%u) (%u,%u,%u) (%u,%u,%u)",
                       t, samples[s],
                       p[0], p[1], p[2], p[4], p[5], p[6], p[8], p[9], p[10]);
                }
            }
        }
        // (c) live TLAS instance matrix readback for this slot's instance.
        if (g_instOrigins != NULL && g_instanceBuffer != nil) {
            int idx = -1;
            for (uint32_t i = 0; i < g_instOriginCount; i++) {
                if (g_instOrigins[i][0] == cam.ox && g_instOrigins[i][1] == cam.oy
                        && g_instOrigins[i][2] == cam.oz) { idx = (int)i; break; }
            }
            if (idx < 0) {
                rp("[audit] camera slot not found in live TLAS instances");
            } else {
                const MTLAccelerationStructureInstanceDescriptor *inst =
                    (const MTLAccelerationStructureInstanceDescriptor *)
                        g_instanceBuffer.contents;
                const float *m = (const float *)&inst[idx].transformationMatrix;
                rp("[audit] live TLAS instance #%d matrix: flat[9..11]=(%.1f, %.1f, "
                   "%.1f) — expect (%d, %d, %d)",
                   idx, m[9], m[10], m[11], cam.ox, cam.oy, cam.oz);
                out[2] = (m[9] == (float)cam.ox && m[10] == (float)cam.oy
                          && m[11] == (float)cam.oz) ? 1 : 0;
            }
        }
        // (d) iso-TLAS battery vs live-TLAS battery, side by side.
        id<MTLAccelerationStructure> iso = build_iso_tlas(cam);
        const float dirs[6][3] = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1},
        };
        // v0.3 label fix: the v0.2 build mislabeled dir[3]=(0,-1,0) as
        // "+Y(上)" and dir[4]=(0,0,1) as "-Y(下)" — the report's physical
        // hitPoints were always right (y decreased by 1.62), only the labels
        // lied. Labels now match the vectors 1:1.
        const char *names[6] = {"+X", "-X", "+Y(上)", "-Y(下)", "+Z", "-Z"};
        const char *nm2[6] = {"+X", "-X", "+Y(up)", "-Y(down)", "+Z", "-Z"};
        for (int i = 0; i < 6; i++) {
            // live
            uint32_t lh = 0; float ld = -1.0f; uint32_t li = 0xFFFFFFFFu;
            if (g_tlas != nil) {
                trace_ray(g_tlas, simd_make_float3(cx, cy, cz),
                          simd_make_float3(dirs[i][0], dirs[i][1], dirs[i][2]),
                          512.0f, &lh, &ld, &li);
            }
            if (lh) { out[6]++; }
            if (i == 3 && lh) { out[7] = 1; }
            // iso
            uint32_t ih = 0; float idist = -1.0f; uint32_t ii = 0xFFFFFFFFu;
            if (iso != nil) {
                trace_ray(iso, simd_make_float3(cx, cy, cz),
                          simd_make_float3(dirs[i][0], dirs[i][1], dirs[i][2]),
                          512.0f, &ih, &idist, &ii);
            }
            if (ih) { out[3]++; }
            // v0.3: LIVE side now echoes dist/hitPoint too — side-by-side
            // geometry agreement is direct field evidence (v0.2 only printed
            // LIVE HIT/MISS with the details on the ISO side). Labels were
            // also fixed to match the direction vectors 1:1.
            if (ih || lh) {
                float ipx = cx + idist * dirs[i][0];
                float ipy = cy + idist * dirs[i][1];
                float ipz = cz + idist * dirs[i][2];
                int gx = ih && fabsf(ipx - roundf(ipx)) < 0.02f;
                int gy = ih && fabsf(ipy - roundf(ipy)) < 0.02f;
                int gz = ih && fabsf(ipz - roundf(ipz)) < 0.02f;
                if (gx || gy || gz) { out[4]++; }
                if (i == 3 && ih) { out[5] = 1; }
                char isoDetail[160];
                if (ih) {
                    snprintf(isoDetail, sizeof(isoDetail),
                             "HIT dist=%.2f hitPoint=(%.2f, %.2f, %.2f)%s%s%s",
                             idist, ipx, ipy, ipz,
                             gx ? " (grid-x)" : "", gy ? " (grid-y)" : "",
                             gz ? " (grid-z)" : "");
                } else {
                    snprintf(isoDetail, sizeof(isoDetail), "MISS");
                }
                char liveDetail[160];
                if (lh) {
                    snprintf(liveDetail, sizeof(liveDetail),
                             "HIT dist=%.2f hitPoint=(%.2f, %.2f, %.2f)",
                             ld, cx + ld * dirs[i][0], cy + ld * dirs[i][1],
                             cz + ld * dirs[i][2]);
                } else {
                    snprintf(liveDetail, sizeof(liveDetail), "MISS");
                }
                rp("[audit] ISO %s: %s  |  LIVE %s: %s",
                   nm2[i], isoDetail, nm2[i], liveDetail);
            } else {
                rp("[audit] ISO %s: MISS  |  LIVE %s: MISS", nm2[i], nm2[i]);
            }
            (void)names;
        }
        if (iso != nil) {
            rp("[audit] ISO summary: hits=%d/6 grid=%d/6 down=%d — "
               "iso-HIT+live-MISS ⇒ live TLAS 组装缺陷；iso-MISS ⇒ 相机 BLAS "
               "内容缺陷（结合 raw AABB 与 readback 判读）",
               out[3], out[4], out[5]);
            // ARC: iso (strong local) releases at scope exit.
        } else {
            rp("[audit] ISO TLAS build failed — BLAS 层判别缺失");
        }
    }

    jintArray result = (*env)->NewIntArray(env, 8);
    if (result != NULL) { (*env)->SetIntArrayRegion(env, result, 0, 8, out); }
    return result;
}

// v0.3: force a full TLAS rebuild (instances + shading pools) on the CALLING
// thread. Empirical instrument-fix — the evidence chain across three field
// runs (all on Apple M3, same save):
//   2.1 v0.5   every ray battery ran right after setPlacementModeNative +
//              frameTickNative (i.e. a TLAS rebuild on the probe thread,
//              between the sentinel and the rays): 9/14 hit.
//   2.2 v0.1/2  S6 cast the same six axis rays against the ingestion-era TLAS
//              with NO intervening AS build on the probe thread: 0/6, twice,
//              deterministically — while the sentinel (own fresh TLAS build
//              right before its rays) hit 3/3 in the same process.
//   2.2 v0.2   the audit battery cast the SAME rays against the SAME g_tlas
//              right after build_iso_tlas (an AS build on the probe thread):
//              3/6 hit with exactly the v0.5 hitPoints (27.00/91.00/6.00);
//              the isolated render likewise measured 57%+ camera-ray hits.
// The discriminating variable is a fresh acceleration-structure build
// enqueued on the calling thread between ingestion and the ray dispatches.
// The mechanism is driver-level (AS traversal residency/binding after
// ingestion-era builds — same thread, same TLAS object, same trace_ray code
// path, byte-identical to 2.1) and is NOT reproducible offline; this call
// replicates the proven working pattern instead of guessing further at the
// driver. PathtraceMod's A/B re-battery turns the fix itself into an
// on-device proof: round 1 miss + round 2 (after this call) hit = mechanism
// confirmed in the report; both miss = S6.5 audit discriminates as before.
JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_refreshTlasNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (g_slots == nil) {
        rp("[refresh] no slot grid — skip");
        return;
    }
    uint32_t ready = 0;
    for (SceneSlot *s in g_slots) {
        if (s.ready && s.blas != nil) { ready++; }
    }
    if (ready == 0 || g_device == nil || g_queue == nil) {
        rp("[refresh] no ready BLAS / device — skip");
        return;
    }
    const uint64_t before = g_stats[3];
    g_tlasDirty = YES;
    rebuild_tlas();
    if (g_stats[3] > before && g_tlas != nil) {
        rp("[refresh] TLAS re-anchored on probe thread (instances=%u, "
           "tlas builds=%llu)",
           g_instOriginCount, (unsigned long long)g_stats[3]);
    } else {
        rp("[refresh] TLAS rebuild did not advance (builds=%llu) — "
           "see [tlas]/FATAL lines above",
           (unsigned long long)g_stats[3]);
    }
}

// S6.4: origin of the slot behind a TLAS instance index (hit attribution).
JNIEXPORT jintArray JNICALL
Java_net_prisma_pathtrace_Native_instanceOriginNative(JNIEnv *env, jclass cls, jint instanceIndex) {
    (void)cls;
    jint out[3] = {0, 0, 0};
    jintArray result = (*env)->NewIntArray(env, 3);
    if (g_instOrigins != NULL && instanceIndex >= 0
            && (uint32_t)instanceIndex < g_instOriginCount) {
        out[0] = g_instOrigins[instanceIndex][0];
        out[1] = g_instOrigins[instanceIndex][1];
        out[2] = g_instOrigins[instanceIndex][2];
    }
    if (result != NULL) { (*env)->SetIntArrayRegion(env, result, 0, 3, out); }
    return result;
}


// -------------------------------------------------------------------
// 2.2: offscreen path-traced frame (S7)
// -------------------------------------------------------------------
// Renders width*height pixels from the camera pose (pos + yaw/pitch, MC
// conventions), spp samples per pixel, up to maxBounces diffuse bounces
// (+ one shadow ray per surface sample). Returns the tonemapped RGBA8 image
// as a jint array (0xAARRGGBB, alpha=255) — the Java side writes the PNG.
// The kernel increments an atomic query counter; the report's Mray/s is a
// MEASURED number, not an estimate.
//
// Camera conventions (mappings-verified class_4184 accessors):
//   yaw   0 = +Z (south), 90 = -X (west)
//   pitch -90 = straight up, +90 = straight down
//   forward = (-sin(yaw)cos(pitch), -sin(pitch), cos(yaw)cos(pitch))
//   screen-right = normalize(forward x worldUp)   [verified: facing south
//   the right hand points west (-X)]; screen-up = right x forward.
//   70 deg vertical FOV (MC default at slider 70).
// Shared camera-basis + uniform builder (2.3: also the live loop's writer).
static void pt_fill_uniforms(void *dst, jfloat camX, jfloat camY, jfloat camZ,
                             jfloat yawDeg, jfloat pitchDeg,
                             jint width, jint height, jint spp, jint bounces,
                             uint32_t instCount, uint32_t resetFlag,
                             uint32_t sppTotal) {
    // ---- camera basis ----
    float pitch = pitchDeg;
    if (pitch > 89.5f) { pitch = 89.5f; }     // degenerate cross product guard
    if (pitch < -89.5f) { pitch = -89.5f; }
    const float yawR = yawDeg * (float)M_PI / 180.0f;
    const float pitchR = pitch * (float)M_PI / 180.0f;
    simd_float3 fwd = simd_normalize(simd_make_float3(
        -sinf(yawR) * cosf(pitchR),
        -sinf(pitchR),
         cosf(yawR) * cosf(pitchR)));
    simd_float3 right = simd_normalize(
        simd_cross(fwd, simd_make_float3(0.0f, 1.0f, 0.0f)));
    simd_float3 up = simd_normalize(simd_cross(right, fwd));
    const float tanHalfY = tanf(35.0f * (float)M_PI / 180.0f);
    const float tanHalfX = tanHalfY * (float)width / (float)height;

    // ---- uniforms (all scalars — byte-identical to MSL PtUniforms) ----
    typedef struct {
        float camPosX, camPosY, camPosZ;
        float fwdX, fwdY, fwdZ;
        float rightX, rightY, rightZ;
        float upX, upY, upZ;
        float tanHalfX, tanHalfY;
        uint32_t width, height, spp, maxBounces;
        uint32_t instCount, resetFlag, sppTotal, pad2;
    } PtUniforms;
    _Static_assert(sizeof(PtUniforms) == 88, "PtUniforms must be 88 B (scalars only)");
    PtUniforms *u = (PtUniforms *)dst;
    memset(u, 0, sizeof(*u));
    u->camPosX = camX; u->camPosY = camY; u->camPosZ = camZ;
    u->fwdX = fwd.x; u->fwdY = fwd.y; u->fwdZ = fwd.z;
    u->rightX = right.x; u->rightY = right.y; u->rightZ = right.z;
    u->upX = up.x; u->upY = up.y; u->upZ = up.z;
    u->tanHalfX = tanHalfX; u->tanHalfY = tanHalfY;
    u->width = (uint32_t)width; u->height = (uint32_t)height;
    u->spp = (uint32_t)spp; u->maxBounces = (uint32_t)bounces;
    u->instCount = instCount;
    u->resetFlag = resetFlag;      // 2.3: 1 = store, 0 = add
    u->sppTotal = sppTotal;        // 2.3: present kernel divisor
}

// Padded dispatch grid + kernel bounds guard (dispatchThreadgroups is
// universally supported; dispatchThreads would need a capability check).
// v0.4.1 fix: dispatchThreadgroups:'s first argument is the GROUP COUNT,
// not the total grid size. v0.4 passed the padded total (480×272) as the
// group count with 16×16 threads → 7680×4352 threads for a 480×270
// target = 256× over-dispatch (correct output thanks to the kernel bounds
// guard, but ~33M mostly-idle thread invocations per frame). Correct:
// groups = ceil(w/16) × ceil(h/16); the padded tail threads are killed by
// the kernel's bounds guard.
static MTLSize pt_grid(jint width, jint height) {
    return MTLSizeMake(((NSUInteger)width + 15) / 16,
                       ((NSUInteger)height + 15) / 16, 1);
}
static const MTLSize kThreadgroup = { 16, 16, 1 };

// 2.3: create an RGBA8 texture from host bytes — private storage + blit copy
// from a staging shared buffer (universally supported, no shared-texture
// storage capability question at all).
static id<MTLTexture> make_texture_from_bytes(const void *bytes,
                                              uint32_t w, uint32_t h) {
    MTLTextureDescriptor *td =
        [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:MTLPixelFormatRGBA8Unorm
                                                           width:(NSUInteger)w
                                                          height:(NSUInteger)h
                                                       mipmapped:NO];
    if (td == nil) { return nil; }
    td.usage = MTLTextureUsageShaderRead;
    td.storageMode = MTLStorageModePrivate;
    id<MTLTexture> tex = [g_device newTextureWithDescriptor:td];
    if (tex == nil) { return nil; }
    id<MTLBuffer> staging =
        [g_device newBufferWithBytes:bytes length:(NSUInteger)w * h * 4
                             options:MTLResourceStorageModeShared];
    if (staging == nil) { return nil; }
    id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
    id<MTLBlitCommandEncoder> blit = [cmd blitCommandEncoder];
    if (blit == nil) { return nil; }
    [blit copyFromBuffer:staging sourceOffset:0 sourceBytesPerRow:(NSUInteger)w * 4
        sourceBytesPerImage:0 sourceSize:MTLSizeMake((NSUInteger)w, (NSUInteger)h, 1)
        toTexture:tex destinationSlice:0 destinationLevel:0
        destinationOrigin:MTLOriginMake(0, 0, 0)];
    [blit endEncoding];
    [cmd commit];
    [cmd waitUntilCompleted];
    if (cmd.error != nil) {
        rp("FATAL: texture blit upload failed: %s",
           cmd.error.localizedDescription.UTF8String);
        return nil;
    }
    return tex;
}

// 2.3: lazily create the shared pathtrace aux resources (present pipeline,
// 1x1 white fallback texture, linear sampler). Idempotent; called from
// pt_render_core (probe thread) and liveBeginNative before any live dispatch.
static BOOL ensure_pt_resources(void) {
    if (g_device == nil || g_library == nil) { return NO; }
    if (g_presentPipeline == nil) {
        NSError *error = nil;
        id<MTLFunction> f = [g_library newFunctionWithName:@"present_kernel"];
        if (f == nil) {
            rp("FATAL: present_kernel not found in metallib — 2.3 cannot present");
            return NO;
        }
        g_presentPipeline = [g_device newComputePipelineStateWithFunction:f error:&error];
        if (g_presentPipeline == nil) {
            rp("FATAL: present pipeline creation failed: %s",
               error.localizedDescription.UTF8String);
            return NO;
        }
    }
    if (g_whiteTexture == nil) {
        const uint8_t white[4] = { 255, 255, 255, 255 };
        g_whiteTexture = make_texture_from_bytes(white, 1, 1);
        if (g_whiteTexture == nil) {
            rp("FATAL: 1x1 white fallback texture creation failed");
            return NO;
        }
    }
    if (g_sampler == nil) {
        MTLSamplerDescriptor *sd = [MTLSamplerDescriptor new];
        sd.minFilter = MTLSamplerMinMagFilterLinear;
        sd.magFilter = MTLSamplerMinMagFilterLinear;
        sd.sAddressMode = MTLSamplerAddressModeClampToEdge;
        sd.tAddressMode = MTLSamplerAddressModeClampToEdge;
        g_sampler = [g_device newSamplerStateWithDescriptor:sd];
        if (g_sampler == nil) {
            rp("FATAL: sampler creation failed");
            return NO;
        }
    }
    return YES;
}

// Shared render core: identical dispatch for the live TLAS (S7) and the
// v0.2 diagnostic isolated single-instance TLAS (renderIsolatedNative).
// 2.3: two dispatches in ONE command buffer — pathtrace_kernel accumulates
// into a transient float4 buffer (resetFlag=1: one-shot), then
// present_kernel tonemaps into the RGBA8 image. Compute dispatches within
// a single command buffer are serialized, so the ordering is guaranteed.
static jintArray pt_render_core(JNIEnv *env,
                                id<MTLAccelerationStructure> tlas,
                                id<MTLBuffer> baseTable,
                                uint32_t instCount,
                                jfloat camX, jfloat camY, jfloat camZ,
                                jfloat yawDeg, jfloat pitchDeg,
                                jint width, jint height, jint spp, jint bounces,
                                const char *tag) {
    if (!ensure_pt_resources() || g_triUVPool == nil) {
        rp("[render] reject: aux resources / triUV pool not ready");
        return NULL;
    }

    uint8_t ub[88];
    pt_fill_uniforms(ub, camX, camY, camZ, yawDeg, pitchDeg,
                     width, height, spp, bounces, instCount, 1u, (uint32_t)spp);

    id<MTLBuffer> uniformBuf =
        [g_device newBufferWithBytes:ub length:sizeof(ub)
                             options:MTLResourceStorageModeShared];
    id<MTLBuffer> accumBuf =
        [g_device newBufferWithLength:(NSUInteger)((uint64_t)width * height * 16)
                              options:MTLResourceStorageModeShared];
    id<MTLBuffer> imageBuf =
        [g_device newBufferWithLength:(NSUInteger)((uint64_t)width * height * 4)
                              options:MTLResourceStorageModeShared];
    // v0.3: two atomics in one buffer — [0] total path-segment queries
    // (the measured Mray/s), [1] camera-ray hits (b==0 segments that hit).
    // camHit% instantly distinguishes "all-sky frame" (camera basis or
    // traversal defect) from "wall/terrain point-blank" (legitimate
    // composition) — the exact ambiguity the v0.2 ISO frame left open.
    id<MTLBuffer> rayBuf =
        [g_device newBufferWithLength:2 * sizeof(uint32_t)
                              options:MTLResourceStorageModeShared];
    if (uniformBuf == nil || accumBuf == nil || imageBuf == nil || rayBuf == nil) {
        rp("FATAL: render buffer allocation failed");
        return NULL;
    }
    memset(rayBuf.contents, 0, 2 * sizeof(uint32_t));

    id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cmd computeCommandEncoder];
    [enc setComputePipelineState:g_ptPipeline];
    [enc setBuffer:uniformBuf offset:0 atIndex:0];
    [enc setBuffer:accumBuf offset:0 atIndex:1];
    [enc setAccelerationStructure:tlas atBufferIndex:2];
    [enc setBuffer:g_triColorPool offset:0 atIndex:3];
    [enc setBuffer:g_triNormalPool offset:0 atIndex:4];
    [enc setBuffer:baseTable offset:0 atIndex:5];
    [enc setBuffer:rayBuf offset:0 atIndex:6];
    [enc setBuffer:g_triUVPool offset:0 atIndex:7];
    [enc setTexture:(g_atlasTexture != nil ? g_atlasTexture : g_whiteTexture)
             atIndex:0];
    [enc setSamplerState:g_sampler atIndex:0];
    [enc dispatchThreadgroups:pt_grid(width, height)
         threadsPerThreadgroup:kThreadgroup];
    [enc setComputePipelineState:g_presentPipeline];
    [enc setBuffer:uniformBuf offset:0 atIndex:0];
    [enc setBuffer:accumBuf offset:0 atIndex:1];
    [enc setBuffer:imageBuf offset:0 atIndex:2];
    [enc dispatchThreadgroups:pt_grid(width, height)
         threadsPerThreadgroup:kThreadgroup];
    [enc endEncoding];

    double t0 = clockms();
    [cmd commit];
    [cmd waitUntilCompleted];
    double ms = clockms() - t0;
    if (cmd.error != nil) {
        rp("FATAL: pathtrace dispatch failed: %s",
           cmd.error.localizedDescription.UTF8String);
        return NULL;
    }
    const uint32_t *stats = (const uint32_t *)rayBuf.contents;
    const uint32_t rays = stats[0];
    const uint32_t camHits = stats[1];
    g_ptRays = rays;
    const double samples = (double)width * (double)height * (double)spp;
    rp("[render] %s: %dx%d spp=%d bounces=%d -> %.1f ms, queries=%llu "
       "(camHit=%.1f%%, %.2f Mray/s measured)",
       tag, width, height, spp, bounces, ms,
       (unsigned long long)rays, camHits * 100.0 / samples,
       ms > 0.0 ? rays / (ms * 1000.0) : 0.0);

    // ---- readback -> jint ARGB ----
    const uint8_t *px = (const uint8_t *)imageBuf.contents;
    const jsize n = (jsize)((int64_t)width * height);
    jintArray result = (*env)->NewIntArray(env, n);
    if (result == NULL) { return NULL; }
    jint *tmp = (jint *)malloc((size_t)n * sizeof(jint));
    if (tmp == NULL) { return NULL; }
    for (jsize i = 0; i < n; i++) {
        tmp[i] = ((jint)255 << 24)
               | ((jint)px[i * 4 + 0] << 16)
               | ((jint)px[i * 4 + 1] << 8)
               |  (jint)px[i * 4 + 2];
    }
    (*env)->SetIntArrayRegion(env, result, 0, n, tmp);
    free(tmp);
    return result;
}

JNIEXPORT jintArray JNICALL
Java_net_prisma_pathtrace_Native_renderPathtraceNative(
        JNIEnv *env, jclass cls,
        jfloat camX, jfloat camY, jfloat camZ, jfloat yawDeg, jfloat pitchDeg,
        jint width, jint height, jint spp, jint bounces) {
    (void)cls;
    if (g_device == nil || g_ptPipeline == nil || g_tlas == nil
            || g_triColorPool == nil || g_triNormalPool == nil
            || g_instTriBase == nil || g_triUVPool == nil) {
        rp("[render] reject: pipeline/TLAS/shading pools not ready");
        return NULL;
    }
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096
            || spp <= 0 || spp > 1024 || bounces < 0 || bounces > 8) {
        rp("[render] reject: bad parameters %dx%d spp=%d bounces=%d",
           width, height, spp, bounces);
        return NULL;
    }
    return pt_render_core(env, g_tlas, g_instTriBase, g_instOriginCount,
                          camX, camY, camZ, yawDeg, pitchDeg,
                          width, height, spp, bounces, "pathtrace");
}

// v0.2 diagnostic channel: render against an ISOLATED single-instance TLAS
// built from the camera section's BLAS (same BLAS bytes, same pipeline, same
// shading pools — only the TLAS assembly differs). Called when the live-TLAS
// placement check fails: an image with correct world content here proves the
// entire render chain and isolates the defect to multi-instance TLAS assembly.
JNIEXPORT jintArray JNICALL
Java_net_prisma_pathtrace_Native_renderIsolatedNative(
        JNIEnv *env, jclass cls,
        jfloat camX, jfloat camY, jfloat camZ, jfloat yawDeg, jfloat pitchDeg,
        jint width, jint height, jint spp, jint bounces) {
    (void)cls;
    if (g_device == nil || g_ptPipeline == nil
            || g_triColorPool == nil || g_triNormalPool == nil
            || g_triUVPool == nil) {
        rp("[render-iso] reject: pipeline/shading pools not ready");
        return NULL;
    }
    if (width <= 0 || height <= 0 || width > 4096 || height > 4096
            || spp <= 0 || spp > 1024 || bounces < 0 || bounces > 8) {
        rp("[render-iso] reject: bad parameters");
        return NULL;
    }
    SceneSlot *cam = camera_section_slot(camX, camY, camZ);
    if (cam == nil || cam.blas == nil) {
        rp("[render-iso] reject: no ready slot near camera");
        return NULL;
    }
    id<MTLAccelerationStructure> iso = build_iso_tlas(cam);
    if (iso == nil) { return NULL; }
    const uint32_t base = slot_pool_base(cam);
    uint32_t one[1] = { base };
    id<MTLBuffer> baseTable =
        [g_device newBufferWithBytes:one length:sizeof(one)
                              options:MTLResourceStorageModeShared];
    if (baseTable == nil) { return NULL; }
    rp("[render-iso] isolated TLAS: slot (%d,%d,%d), tris=%u, poolBase=%u — "
       "同一 BLAS/管线/池，仅 TLAS 组装不同（诊断通道）",
       cam.ox, cam.oy, cam.oz, cam.indexCount / 3, base);
    return pt_render_core(env, iso, baseTable, 1,
                          camX, camY, camZ, yawDeg, pitchDeg,
                          width, height, spp, bounces, "pathtrace-iso");
}

// ===================================================================
// 2.3 live session (progressive accumulation + presentation)
// ===================================================================
// Threading: liveFrameNative is called from the RT worker thread
// (LiveSession); livePresentNative / liveReadbackNative / liveStatsNative
// are called from the game render thread (PathtraceHook → GlComposer).
// All entry points synchronize on g_liveLock.

JNIEXPORT jboolean JNICALL
Java_net_prisma_pathtrace_Native_liveBeginNative(
        JNIEnv *env, jclass cls, jint width, jint height,
        jint sppPerFrame, jint bounces) {
    (void)env; (void)cls;
    @synchronized (g_liveLock) {
        if (g_device == nil || g_queue == nil) {
            rp("[live] begin reject: device/queue not initialized");
            return JNI_FALSE;
        }
        if (g_tlas == nil || g_triColorPool == nil || g_triNormalPool == nil
                || g_instTriBase == nil || g_triUVPool == nil) {
            rp("[live] begin reject: TLAS/shading pools not ready");
            return JNI_FALSE;
        }
        if (!ensure_pt_resources()) {
            rp("[live] begin reject: aux resources failed");
            return JNI_FALSE;
        }
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096
                || sppPerFrame <= 0 || sppPerFrame > 1024
                || bounces < 0 || bounces > 8) {
            rp("[live] begin reject: bad parameters %dx%d spp=%d bounces=%d",
               width, height, sppPerFrame, bounces);
            return JNI_FALSE;
        }
        // Fresh session (idempotent: a second begin reallocates).
        g_liveAccum = nil; g_liveImage = nil; g_liveStaging = nil;
        g_liveStagingData = NULL;
        g_liveUniform = nil; g_liveStats = nil; g_liveCmd = nil;
        const uint64_t pixels = (uint64_t)width * (uint64_t)height;
        g_liveAccum =
            [g_device newBufferWithLength:(NSUInteger)(pixels * 16)
                                 options:MTLResourceStorageModeShared];
        g_liveImage =
            [g_device newBufferWithLength:(NSUInteger)(pixels * 4)
                                 options:MTLResourceStorageModeShared];
        // v0.4.3: 头尾 guard（GL 上传源指针防越界——见声明区注释）。
        g_liveStaging =
            [g_device newBufferWithLength:(NSUInteger)(2 * kGuardPad + pixels * 4)
                                 options:MTLResourceStorageModeShared];
        if (g_liveStaging != nil) {
            g_liveStagingData = (uint8_t *)g_liveStaging.contents + kGuardPad;
        }
        g_liveUniform =
            [g_device newBufferWithLength:88
                                 options:MTLResourceStorageModeShared];
        g_liveStats =
            [g_device newBufferWithLength:2 * sizeof(uint32_t)
                                 options:MTLResourceStorageModeShared];
        if (g_liveAccum == nil || g_liveImage == nil || g_liveStaging == nil
                || g_liveUniform == nil || g_liveStats == nil) {
            g_liveAccum = nil; g_liveImage = nil; g_liveStaging = nil;
            g_liveStagingData = NULL;
            g_liveUniform = nil; g_liveStats = nil;
            rp("[live] FATAL: session buffer allocation failed");
            return JNI_FALSE;
        }
        memset(g_liveStats.contents, 0, 2 * sizeof(uint32_t));
        // v0.4.5: 共享金丝瑞（64KB 确定性图案，sharedAuditNative 周期比对；
        // 分配在 accum/image 之后——若越界写手按分配顺序扫射，金丝瑞与
        // 实例缓冲至少一个会被打中，签名直接可读）。
        g_sharedCanary =
            [g_device newBufferWithLength:kCanaryBytes
                                 options:MTLResourceStorageModeShared];
        if (g_sharedCanary != nil) {
            uint8_t *p = (uint8_t *)g_sharedCanary.contents;
            for (NSUInteger i = 0; i < kCanaryBytes; i++) {
                p[i] = (uint8_t)(i * 31u + 7u);
            }
        }
        g_liveAtlasDispatched = 0;
        g_liveW = (uint32_t)width; g_liveH = (uint32_t)height;
        g_liveSpp = (uint32_t)sppPerFrame; g_liveBounces = (uint32_t)bounces;
        g_liveFrames = 0; g_liveSppTotal = 0; g_liveResets = 0;
        g_liveTotalQueries = 0; g_liveLastQueries = 0; g_liveLastCamHits = 0;
        g_liveLastMs = 0.0;
        g_liveTlasBuilds = g_stats[3];
        g_liveActive = YES;
        rp("[live] session begun: %dx%d spp/frame=%d bounces=%d "
           "(atlas=%s)", width, height, sppPerFrame, bounces,
           g_atlasTexture != nil ? "ATLAS_RGB" : "white-vertex-color");
        // v0.4.5: 会话缓冲地址图（与 [addr] TLAS 行同格式——统一布局视图）。
        rp("[addr] live bufs: accum(mem)=0x%llX image(mem)=0x%llX "
           "staging(mem)=0x%llX canary(mem)=0x%llX uniform(mem)=0x%llX "
           "stats(mem)=0x%llX",
           (unsigned long long)(uintptr_t)g_liveAccum.contents,
           (unsigned long long)(uintptr_t)g_liveImage.contents,
           (unsigned long long)(uintptr_t)g_liveStaging.contents,
           (unsigned long long)(uintptr_t)(g_sharedCanary != nil
               ? g_sharedCanary.contents : NULL),
           (unsigned long long)(uintptr_t)g_liveUniform.contents,
           (unsigned long long)(uintptr_t)g_liveStats.contents);
        return JNI_TRUE;
    }
}

// RT worker thread: accumulate one frame (trace + present dispatches in one
// command buffer, committed without waiting). Returns the stats of the last
// COMPLETED frame as jint[2] {queries, camHits} (read after waiting for the
// previous command buffer — the natural serialization point).
JNIEXPORT jintArray JNICALL
Java_net_prisma_pathtrace_Native_liveFrameNative(
        JNIEnv *env, jclass cls,
        jfloat camX, jfloat camY, jfloat camZ, jfloat yawDeg, jfloat pitchDeg) {
    (void)cls;
    jint out[2] = { 0, 0 };
    jintArray result = (*env)->NewIntArray(env, 2);
    @synchronized (g_liveLock) {
        if (result != NULL) { (*env)->SetIntArrayRegion(env, result, 0, 2, out); }
        if (!g_liveActive || g_device == nil || g_ptPipeline == nil) {
            return result;
        }
        // 1. Wait for the previous frame (uniform/stats/accum buffers idle —
        //    the previous kernel must not read the uniforms we are about to
        //    rewrite or the stats we are about to memset).
        if (g_liveCmd != nil) {
            [g_liveCmd waitUntilCompleted];
            if (g_liveCmd.error != nil) {
                rp("[live] FATAL: frame dispatch failed: %s",
                   g_liveCmd.error.localizedDescription.UTF8String);
                g_liveActive = NO;
                return result;
            }
            const uint32_t *stats = (const uint32_t *)g_liveStats.contents;
            g_liveLastQueries = stats[0];
            g_liveLastCamHits = stats[1];
            g_liveTotalQueries += stats[0];
            out[0] = (jint)g_liveLastQueries;
            out[1] = (jint)g_liveLastCamHits;
            if (result != NULL) { (*env)->SetIntArrayRegion(env, result, 0, 2, out); }
        }
        // 2. Scene-change auto-reset: any TLAS rebuild since the last frame
        //    invalidates the accumulated image (instance/triangle indices
        //    shift under the pools).
        if (g_stats[3] != g_liveTlasBuilds) {
            g_liveFrames = 0;
            g_liveSppTotal = 0;
            g_liveResets++;
            g_liveTlasBuilds = g_stats[3];
            rp("[live] reset: TLAS rebuilt (builds=%llu)",
               (unsigned long long)g_stats[3]);
        }
        // 3. Per-frame stats reset (safe: no kernel in flight).
        memset(g_liveStats.contents, 0, 2 * sizeof(uint32_t));
        // 4. Uniforms (shared buffer contents write — CPU-side).
        // v0.4.2: snapshot scene state under g_sceneLock (nested inside
        // g_liveLock — lock order L→S, deadlock-audited in the g_sceneLock
        // comment). The probe thread's rebuild_tlas (S8.4 watchdog) can
        // otherwise swap + retire the TLAS/pools between our reads and the
        // encoder binds; a torn bind set (new TLAS + old pools, or a
        // released object) is exactly the class of silent all-miss we are
        // hunting. Skip the frame if the pools are torn down.
        id<MTLAccelerationStructure> tlas;
        id<MTLBuffer> colorPool;
        id<MTLBuffer> normalPool;
        id<MTLBuffer> triBase;
        id<MTLBuffer> uvPool;
        uint32_t instCount;
        @synchronized (g_sceneLock) {
            tlas = g_tlas;
            colorPool = g_triColorPool;
            normalPool = g_triNormalPool;
            triBase = g_instTriBase;
            uvPool = g_triUVPool;
            instCount = g_instOriginCount;
        }
        if (tlas == nil || colorPool == nil || normalPool == nil
                || triBase == nil || uvPool == nil) {
            rp("[live] frame skipped: scene state unavailable (TLAS/pools)");
            return result;
        }
        // v0.4.5: 首帧 ATLAS_RGB 派发标记——死亡时刻关联事件（毒化窗口内
        // 的关键事件时间戳，[tlas-health] 行引用）。
        if (g_atlasTexture != nil && g_liveAtlasDispatched == 0) {
            g_liveAtlasDispatched = 1;
            rp("[live] first ATLAS_RGB frame dispatched (tlas=build#%llu)",
               (unsigned long long)g_stats[3]);
        }
        const uint32_t resetFlag = (g_liveFrames == 0) ? 1u : 0u;
        const uint32_t sppTotalNext = g_liveSppTotal + g_liveSpp;
        pt_fill_uniforms(g_liveUniform.contents, camX, camY, camZ,
                         yawDeg, pitchDeg, (jint)g_liveW, (jint)g_liveH,
                         (jint)g_liveSpp, (jint)g_liveBounces,
                         instCount, resetFlag, sppTotalNext);
        // 5. Encode trace + present in one command buffer, commit async.
        id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
        id<MTLComputeCommandEncoder> enc = [cmd computeCommandEncoder];
        [enc setComputePipelineState:g_ptPipeline];
        [enc setBuffer:g_liveUniform offset:0 atIndex:0];
        [enc setBuffer:g_liveAccum offset:0 atIndex:1];
        [enc setAccelerationStructure:tlas atBufferIndex:2];
        [enc setBuffer:colorPool offset:0 atIndex:3];
        [enc setBuffer:normalPool offset:0 atIndex:4];
        [enc setBuffer:triBase offset:0 atIndex:5];
        [enc setBuffer:g_liveStats offset:0 atIndex:6];
        [enc setBuffer:uvPool offset:0 atIndex:7];
        [enc setTexture:(g_atlasTexture != nil ? g_atlasTexture : g_whiteTexture)
                 atIndex:0];
        [enc setSamplerState:g_sampler atIndex:0];
        [enc dispatchThreadgroups:pt_grid((jint)g_liveW, (jint)g_liveH)
             threadsPerThreadgroup:kThreadgroup];
        [enc setComputePipelineState:g_presentPipeline];
        [enc setBuffer:g_liveUniform offset:0 atIndex:0];
        [enc setBuffer:g_liveAccum offset:0 atIndex:1];
        [enc setBuffer:g_liveImage offset:0 atIndex:2];
        [enc dispatchThreadgroups:pt_grid((jint)g_liveW, (jint)g_liveH)
             threadsPerThreadgroup:kThreadgroup];
        [enc endEncoding];
        double t0 = clockms();
        [cmd commit];
        g_liveCmd = cmd;
        g_liveLastMs = clockms() - t0;
        g_liveFrames++;
        g_liveSppTotal = sppTotalNext;
        return result;
    }
}

JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_liveResetNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    @synchronized (g_liveLock) {
        if (!g_liveActive) { return; }
        if (g_liveFrames != 0) {
            g_liveFrames = 0;
            g_liveSppTotal = 0;
            g_liveResets++;
        }
    }
}

// Render thread (GL compositor): wait for the last committed frame, snapshot
// the tonemapped image into the staging buffer and return a direct ByteBuffer
// aliasing it. The snapshot is stable — a subsequently committed frame can
// never tear the bytes the GL upload is reading.
JNIEXPORT jobject JNICALL
Java_net_prisma_pathtrace_Native_livePresentNative(JNIEnv *env, jclass cls) {
    (void)cls;
    @synchronized (g_liveLock) {
        if (!g_liveActive || g_liveCmd == nil || g_liveStaging == nil
                || g_liveStagingData == NULL) {
            return NULL;
        }
        [g_liveCmd waitUntilCompleted];
        if (g_liveCmd.error != nil) {
            rp("[live] present: last frame failed: %s",
               g_liveCmd.error.localizedDescription.UTF8String);
            return NULL;
        }
        memcpy(g_liveStagingData, g_liveImage.contents,
               (size_t)g_liveW * g_liveH * 4);
        return (*env)->NewDirectByteBuffer(env, g_liveStagingData,
                                           (jlong)((size_t)g_liveW * g_liveH * 4));
    }
}

// RT worker thread: read the current presentation snapshot as an ARGB jint
// array for the PNG evidence writer.
JNIEXPORT jintArray JNICALL
Java_net_prisma_pathtrace_Native_liveReadbackNative(JNIEnv *env, jclass cls) {
    (void)cls;
    @synchronized (g_liveLock) {
        if (!g_liveActive || g_liveCmd == nil) { return NULL; }
        [g_liveCmd waitUntilCompleted];
        if (g_liveCmd.error != nil) { return NULL; }
        const uint8_t *px = (const uint8_t *)g_liveImage.contents;
        const jsize n = (jsize)((uint64_t)g_liveW * g_liveH);
        jintArray result = (*env)->NewIntArray(env, n);
        if (result == NULL) { return NULL; }
        jint *tmp = (jint *)malloc((size_t)n * sizeof(jint));
        if (tmp == NULL) { return NULL; }
        for (jsize i = 0; i < n; i++) {
            tmp[i] = ((jint)255 << 24)
                   | ((jint)px[i * 4 + 0] << 16)
                   | ((jint)px[i * 4 + 1] << 8)
                   |  (jint)px[i * 4 + 2];
        }
        (*env)->SetIntArrayRegion(env, result, 0, n, tmp);
        free(tmp);
        return result;
    }
}

// Live counters for the report. Returns jlong[10]: frames, sppTotal, resets,
// refreshes, totalQueries, lastQueries, lastCamHits, atlasMode(0/1),
// tlasBuilds(v0.4.4), tlasAgeMs(v0.4.7).
JNIEXPORT jlongArray JNICALL
Java_net_prisma_pathtrace_Native_liveStatsNative(JNIEnv *env, jclass cls) {
    (void)cls;
    // v0.4.4: [8] = TLAS build 计数（g_stats[3] 的宽恕竞读——与 liveFrameNative
    // 的重置检测同级别的提示读；[live] 行打出 tlas=build#N，camHit 衰减与
    // build 年龄的相关性一眼可见）。
    // v0.4.7: [9] = 当前 TLAS 年龄 ms（-1=从未构建）——死亡行 age= 与
    // “重建后 ~1s 即死”签名一行对照。
    jlong out[10] = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    @synchronized (g_liveLock) {
        out[0] = (jlong)g_liveFrames;
        out[1] = (jlong)g_liveSppTotal;
        out[2] = (jlong)g_liveResets;
        out[3] = (jlong)g_liveRefreshes;
        out[4] = (jlong)g_liveTotalQueries;
        out[5] = (jlong)g_liveLastQueries;
        out[6] = (jlong)g_liveLastCamHits;
        out[7] = (jlong)(g_atlasTexture != nil ? 1 : 0);
        out[8] = (jlong)g_stats[3];
        out[9] = (g_lastTlasBuildMs < 0.0) ? (jlong)-1
                : (jlong)(clockms() - g_lastTlasBuildMs);
    }
    jlongArray result = (*env)->NewLongArray(env, 10);
    if (result != NULL) { (*env)->SetLongArrayRegion(env, result, 0, 10, out); }
    return result;
}

// Live session dims for the GL compositor: jint[2] {w, h}, null if inactive.
JNIEXPORT jintArray JNICALL
Java_net_prisma_pathtrace_Native_liveDimsNative(JNIEnv *env, jclass cls) {
    (void)cls;
    jint out[2] = { 0, 0 };
    BOOL active = NO;
    @synchronized (g_liveLock) {
        active = g_liveActive;
        out[0] = (jint)g_liveW;
        out[1] = (jint)g_liveH;
    }
    if (!active) { return NULL; }
    jintArray result = (*env)->NewIntArray(env, 2);
    if (result != NULL) { (*env)->SetIntArrayRegion(env, result, 0, 2, out); }
    return result;
}

// Allocate the shared staging buffer the Java GL compositor will glReadPixels
// the block atlas into. Returns a direct ByteBuffer aliasing it.
JNIEXPORT jobject JNICALL
Java_net_prisma_pathtrace_Native_liveAtlasBufferNative(
        JNIEnv *env, jclass cls, jint bytes) {
    (void)cls;
    @synchronized (g_liveLock) {
        if (g_device == nil || bytes < 4 || bytes > 64 * 1024 * 1024) {
            rp("[live] atlas buffer reject: bad size %d", bytes);
            return NULL;
        }
        // v0.4.3: 头尾 guard（glReadPixels 写目标防 PACK SKIP_* 平移越界）。
        g_atlasStaging =
            [g_device newBufferWithLength:(NSUInteger)(2 * kGuardPad
                                                         + (uint64_t)bytes)
                                 options:MTLResourceStorageModeShared];
        if (g_atlasStaging == nil) {
            g_atlasStagingData = NULL;
            rp("[live] FATAL: atlas staging allocation failed (%d B)", bytes);
            return NULL;
        }
        memset(g_atlasStaging.contents, 0,
               (size_t)(2 * kGuardPad + (uint64_t)bytes));
        g_atlasStagingBytes = (uint64_t)bytes;
        g_atlasStagingData = (uint8_t *)g_atlasStaging.contents + kGuardPad;
        return (*env)->NewDirectByteBuffer(env, g_atlasStagingData,
                                           (jlong)bytes);
    }
}

// Turn the staged atlas bytes into an MTLTexture and switch the live albedo
// path to ATLAS_RGB. The next dispatched frame samples it.
// v0.4.2: the texture creation (newTexture + buffer blit + waitUntilCompleted)
// now runs OUTSIDE g_liveLock — v0.4.1 held the session mutex across a GPU
// wait on the render thread, stalling every worker liveFrameNative for the
// blit duration (and vice versa). Validation snapshots the staging state
// under the lock; only the pointer swap is locked.
JNIEXPORT jboolean JNICALL
Java_net_prisma_pathtrace_Native_uploadAtlasNative(
        JNIEnv *env, jclass cls, jint width, jint height) {
    (void)env; (void)cls;
    // ---- validation under the session lock ----
    @synchronized (g_liveLock) {
        // v0.4.7: bisect Phase B 之后整条链已灭活——上传直接拒绝（防迟到写手）。
        if (!g_atlasEnabled) {
            rp("[bisect] atlas upload reject: chain torn down (Phase B)");
            return JNI_FALSE;
        }
        if (g_atlasStaging == nil) {
            rp("[live] atlas upload reject: no staging buffer");
            return JNI_FALSE;
        }
        if (width <= 0 || height <= 0
                || (uint64_t)width * height * 4 > g_atlasStagingBytes) {
            rp("[live] atlas upload reject: dims %dx%d exceed staging (%llu B)",
               width, height, (unsigned long long)g_atlasStagingBytes);
            return JNI_FALSE;
        }
    }
    // ---- GPU work outside the lock (render thread; once per session) ----
    const void *staged;
    @synchronized (g_liveLock) {
        staged = (const void *)g_atlasStagingData;
    }
    id<MTLTexture> tex =
        make_texture_from_bytes(staged, (uint32_t)width, (uint32_t)height);
    if (tex == nil) { return JNI_FALSE; }
    // ---- swap under the lock (worker frames bind g_atlasTexture) ----
    @synchronized (g_liveLock) {
        if (g_atlasTexture != nil) { [g_retired addObject:g_atlasTexture]; }
        g_atlasTexture = tex;
        g_liveAtlasMode = 1;
        rp("[live] atlas uploaded: %dx%d RGBA8 -> MTLTexture "
           "(albedo path: ATLAS_RGB x vertex tint)", width, height);
    }
    return JNI_TRUE;
}

// v0.4.7: bisect Phase B——整条 atlas 链灭活。MTLTexture 与 GL 回读暂存
// 缓冲一并入 g_retired（下一次 TLAS 构建完成时 flush——换段重锚紧接着
// 触发，即“灭活→重锚→flush”同一拍完成）；albedo 路径回落顶点色（内核
// 绑定侧 g_atlasTexture==nil 时自动用 g_whiteTexture）；此后所有上传被
// 拒绝。锁序：liveLock→sceneLock（与 liveFrameNative 同序——g_retired
// 的另一路变更都在 sceneLock 下，双锁嵌套排除并发 flush）。
JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_setAtlasEnabledNative(
        JNIEnv *env, jclass cls, jboolean enabled) {
    (void)env; (void)cls;
    @synchronized (g_liveLock) {
        BOOL wasEnabled = g_atlasEnabled;
        g_atlasEnabled = (enabled == JNI_TRUE) ? YES : NO;
        if (g_atlasEnabled) {
            rp("[bisect] native atlas chain re-enabled (无操作——除非重新采集)");
            return;
        }
        if (!wasEnabled) { return; }
        @synchronized (g_sceneLock) {
            if (g_atlasTexture != nil) {
                [g_retired addObject:g_atlasTexture];
                g_atlasTexture = nil;
            }
            if (g_atlasStaging != nil) {
                [g_retired addObject:g_atlasStaging];
                g_atlasStaging = nil;
            }
            g_atlasStagingData = NULL;
            g_atlasStagingBytes = 0;
            g_liveAtlasMode = 0;
            g_liveAtlasDispatched = 0;
        }
        rp("[bisect] native atlas chain torn down（MTLTexture+暂存入 retired"
           "——随下次 TLAS 构建释放；albedo=顶点色；后续上传拒绝）");
    }
}

// Worker-thread re-anchor. v0.4.1: NOT called by the live loop anymore —
// v0.4 field evidence showed worker-thread-rebuilt TLASes (builds>=10) all
// miss live camera rays while probe-thread builds (8/9) hit 100% in the
// same scene/pose (S6.5 battery / S7 render). TLAS build duty moved to the
// probe thread (30s watchdog, [S8.4]); this entry point stays exported as
// a diagnostic channel (A/B on demand).
JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_liveRefreshNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    @synchronized (g_liveLock) {
        if (!g_liveActive) { return; }
    }
    const uint64_t before = g_stats[3];
    g_tlasDirty = YES;
    rebuild_tlas();
    if (g_stats[3] > before) {
        g_liveRefreshes++;
        rp("[live] TLAS re-anchored on worker thread (builds=%llu, "
           "refreshes=%u)", (unsigned long long)g_stats[3], g_liveRefreshes);
    }
}

JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_liveEndNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    @synchronized (g_liveLock) {
        if (!g_liveActive) { return; }
        if (g_liveCmd != nil) { [g_liveCmd waitUntilCompleted]; }
        g_liveCmd = nil;
        g_liveAccum = nil; g_liveImage = nil; g_liveStaging = nil;
        g_liveStagingData = NULL;
        g_liveUniform = nil; g_liveStats = nil;
        g_liveActive = NO;
        rp("[live] session ended (frames=%u spp=%u resets=%u)",
           g_liveFrames, g_liveSppTotal, g_liveResets);
    }
}

JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_shutdownNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    // 2.3: if a live session is running, tear it down first (its buffers
    // must not outlive the device/queue references we are about to drop).
    @synchronized (g_liveLock) {
        if (g_liveActive) {
            if (g_liveCmd != nil) { [g_liveCmd waitUntilCompleted]; }
            g_liveCmd = nil;
            g_liveAccum = nil; g_liveImage = nil; g_liveStaging = nil;
            g_liveStagingData = NULL;
            g_liveUniform = nil; g_liveStats = nil;
            g_liveActive = NO;
        }
    }
    if (g_slots != nil) {
        for (SceneSlot *s in g_slots) { s.ready = NO; }
        g_slots = nil;
    }
    g_tlas = nil;
    g_instanceBuffer = nil;
    g_verifiedTlas = nil;
    g_verifiedTlasBuild = 0;
    g_pipeline = nil;
    g_ptPipeline = nil;
    g_presentPipeline = nil;
    g_library = nil;
    g_triColorPool = nil;
    g_triNormalPool = nil;
    g_triUVPool = nil;
    g_instTriBase = nil;
    g_atlasTexture = nil;
    g_whiteTexture = nil;
    g_sampler = nil;
    g_atlasStaging = nil;
    g_atlasStagingData = NULL;
    g_atlasStagingBytes = 0;
    // v0.4.5: 毒化仪器状态（影子/金丝瑞/首帧标记）。
    g_sharedCanary = nil;
    g_liveAtlasDispatched = 0;
    g_atlasEnabled = YES;   // v0.4.7: bisect 灭活随会话关闭复位（重开世界=新实验）
    g_lastTlasBuildMs = -1.0;
    if (g_instanceShadow != NULL) {
        free(g_instanceShadow);
        g_instanceShadow = NULL;
        g_instanceShadowBytes = 0;
    }
    g_liveAtlasMode = 0;
    g_poolTris = 0;
    g_tlasDirty = NO;
    if (g_instOrigins != NULL) {
        free(g_instOrigins);
        g_instOrigins = NULL;
        g_instOriginCount = 0;
    }
    if (g_retired != nil) {
        [g_retired removeAllObjects];
        g_stats[8] = 0;
    }
    rp("[scene] shutdown — all GPU resources released "
       "(last frame: %llu queries)", (unsigned long long)g_ptRays);
    if (g_report != NULL) { fclose(g_report); g_report = NULL; }
}
