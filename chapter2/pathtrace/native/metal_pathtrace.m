//
//  metal_pathtrace.m
//  Radiance iOS Port — Chapter 2 — Stage 2.2 — Path Tracing Engine (v0.1)
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
@property (nonatomic, strong) id<MTLBuffer> colorBuffer;  // uchar4 per vertex (albedo)
@property (nonatomic) int32_t ox, oy, oz;
@property (nonatomic) int64_t generation;
@property (nonatomic) uint32_t vertexCount;
@property (nonatomic) uint32_t indexCount;
@property (nonatomic) BOOL ready;
@property (nonatomic) BOOL hasColor;         // any layer carried COLOR
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
static id<MTLLibrary> g_library = nil;
static id<MTLComputePipelineState> g_pipeline = nil;      // scene_trace_kernel
static id<MTLComputePipelineState> g_ptPipeline = nil;    // pathtrace_kernel

// ---- 2.2 shading pools (rebuilt with every TLAS; order == instance order) ----
static id<MTLBuffer> g_triColorPool = nil;   // 3 x uchar4 per triangle
static id<MTLBuffer> g_triNormalPool = nil;  // packed_float3 per triangle
static id<MTLBuffer> g_instTriBase = nil;    // uint per instance
static uint32_t g_poolTris = 0;              // triangles covered by the pools
static uint64_t g_poolColorBytes = 0;
static uint64_t g_poolNormalBytes = 0;
static uint64_t g_ptRays = 0;                // last frame's measured ray count

// v0.4: per-instance slot origins of the live TLAS (hit attribution).
static int32_t (*g_instOrigins)[3] = NULL;
static uint32_t g_instOriginCount = 0;

// stats: [0]=readyBLAS [1]=totalTris [2]=totalVerts [3]=tlasBuilds
//        [4]=blasBuilds [5]=vertexBytes [6]=indexBytes [7]=tlasBytes
//        [8]=retiredPending
static uint64_t g_stats[9];

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
static void rebuild_tlas(void) {
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
        uint32_t *bases = (uint32_t *)malloc((size_t)n * sizeof(uint32_t));
        if (colors == NULL || normals == NULL || bases == NULL) {
            free(colors); free(normals); free(bases);
            // Nil the pools: a stale pool would no longer match the new TLAS
            // instance order — rendering must refuse rather than shade wrong.
            g_triColorPool = nil; g_triNormalPool = nil; g_instTriBase = nil;
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
                for (uint32_t t = 0; t < tris; t++) {
                    const uint32_t i0 = idx[t * 3 + 0];
                    const uint32_t i1 = idx[t * 3 + 1];
                    const uint32_t i2 = idx[t * 3 + 2];
                    uint8_t *dstC = colors + ((size_t)(triBase + t) * 3) * 4;
                    for (int k = 0; k < 3; k++) {
                        const uint32_t vi = (k == 0) ? i0 : (k == 1) ? i1 : i2;
                        dstC[k * 4 + 0] = col[(size_t)vi * 4 + 0];
                        dstC[k * 4 + 1] = col[(size_t)vi * 4 + 1];
                        dstC[k * 4 + 2] = col[(size_t)vi * 4 + 2];
                        dstC[k * 4 + 3] = 255;
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
            free(colors); free(normals); free(bases);
            if (colorPool == nil || normalPool == nil || basePool == nil) {
                g_triColorPool = nil; g_triNormalPool = nil; g_instTriBase = nil;
                g_poolTris = 0;
                rp("FATAL: shading pool MTLBuffer allocation failed");
            } else {
                if (g_triColorPool != nil) { [g_retired addObject:g_triColorPool]; }
                if (g_triNormalPool != nil) { [g_retired addObject:g_triNormalPool]; }
                if (g_instTriBase != nil) { [g_retired addObject:g_instTriBase]; }
                g_triColorPool = colorPool;
                g_triNormalPool = normalPool;
                g_instTriBase = basePool;
                g_poolTris = (uint32_t)totalTris;
                g_poolColorBytes = totalTris * 3 * 4;
                g_poolNormalBytes = totalTris * 3 * sizeof(float);
            }
        }
    }

    // Ready only after the new TLAS completed — safe to drop retired objects.
    flush_retired();
    rp("[tlas] rebuilt in %.2f ms (instances=%u, as=%llu B, tris=%u, "
       "colorSlots=%u, pools=%llu+%llu B, total=%llu builds)",
       ms, n, (unsigned long long)sizes.accelerationStructureSize,
       g_poolTris, colorSlots,
       (unsigned long long)g_poolColorBytes, (unsigned long long)g_poolNormalBytes,
       (unsigned long long)g_stats[3]);
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
        jintArray colorOffsets) {
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
    if (cCounts == NULL || cStrides == NULL || cColorOff == NULL || layerCount <= 0) {
        rp("[rebuild] reject: bad arrays @ index %lld", (long long)index);
        if (cCounts != NULL) { (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT); }
        if (cStrides != NULL) { (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT); }
        if (cColorOff != NULL) { (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT); }
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
    // + quad indices ----
    simd_float3 *verts =
        (simd_float3 *)malloc(totalVerts * sizeof(simd_float3));
    uint8_t *colors = (uint8_t *)malloc(totalVerts * 4);
    uint32_t *idx = (uint32_t *)malloc(totalIndices * sizeof(uint32_t));
    if (verts == NULL || colors == NULL || idx == NULL) {
        rp("FATAL: OOM in rebuild (verts=%llu)", (unsigned long long)totalVerts);
        free(verts); free(colors); free(idx);
        (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT);
        return;
    }

    uint64_t vBase = 0;
    BOOL anyColor = NO;
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
            free(verts); free(colors); free(idx);
            (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, colorOffsets, cColorOff, JNI_ABORT);
            return;
        }
        // Copy verbatim (section-local per the 2.1 v0.5 sealed contract:
        // float3 @ offset 0, NO recentring) + per-vertex uchar4 COLOR.
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
    free(verts); free(colors); free(idx);

    double t0 = clockms();
    slot.ox = ox; slot.oy = oy; slot.oz = oz;
    slot.generation = generation;
    slot.vertexBuffer = vertexBuffer;
    slot.indexBuffer = indexBuffer;
    slot.colorBuffer = colorBuffer;
    slot.vertexCount = (uint32_t)totalVerts;
    slot.indexCount = (uint32_t)totalIndices;
    slot.hasColor = anyColor;
    slot.rawMinX = rmin[0]; slot.rawMinY = rmin[1]; slot.rawMinZ = rmin[2];
    slot.rawMaxX = rmax[0]; slot.rawMaxY = rmax[1]; slot.rawMaxZ = rmax[2];
    slot.rawValid = (rmin[0] <= rmax[0]);

    if (vertexBuffer == nil || indexBuffer == nil || colorBuffer == nil
            || !build_blas(slot)) {
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
       "color=%s (blas %.2f ms, total builds=%llu)",
       (long long)index, ox, oy, oz, layerCount,
       (unsigned long long)totalVerts, (unsigned long long)(totalIndices / 3),
       anyColor ? "yes" : "WHITE",
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

JNIEXPORT jfloatArray JNICALL
Java_net_prisma_pathtrace_Native_castRayNative(
        JNIEnv *env, jclass cls, jfloat ox, jfloat oy, jfloat oz,
        jfloat dx, jfloat dy, jfloat dz, jfloat maxDist) {
    (void)cls;
    jfloat out[3] = {0.0f, -1.0f, -1.0f}; // hitFlag, distance, instanceIndex
    jfloatArray result = (*env)->NewFloatArray(env, 3);

    if (g_device == nil || g_pipeline == nil || g_tlas == nil) {
        if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 3, out); }
        return result;
    }

    if (!normalize_dir(&dx, &dy, &dz)) {
        if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 3, out); }
        return result;
    }

    uint32_t hitFlag = 0; float dist = -1.0f; uint32_t instIdx = 0xFFFFFFFFu;
    if (trace_ray(g_tlas, simd_make_float3(ox, oy, oz),
                  simd_make_float3(dx, dy, dz), maxDist,
                  &hitFlag, &dist, &instIdx)) {
        out[0] = hitFlag ? 1.0f : 0.0f;
        out[1] = dist;
        out[2] = (jfloat)instIdx;
    }
    if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 3, out); }
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
// Shared render core: identical dispatch for the live TLAS (S7) and the
// v0.2 diagnostic isolated single-instance TLAS (renderIsolatedNative).
static jintArray pt_render_core(JNIEnv *env,
                                id<MTLAccelerationStructure> tlas,
                                id<MTLBuffer> baseTable,
                                uint32_t instCount,
                                jfloat camX, jfloat camY, jfloat camZ,
                                jfloat yawDeg, jfloat pitchDeg,
                                jint width, jint height, jint spp, jint bounces,
                                const char *tag) {
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
        uint32_t instCount, pad0, pad1, pad2;
    } PtUniforms;
    _Static_assert(sizeof(PtUniforms) == 88, "PtUniforms must be 88 B (scalars only)");
    PtUniforms u;
    memset(&u, 0, sizeof(u));
    u.camPosX = camX; u.camPosY = camY; u.camPosZ = camZ;
    u.fwdX = fwd.x; u.fwdY = fwd.y; u.fwdZ = fwd.z;
    u.rightX = right.x; u.rightY = right.y; u.rightZ = right.z;
    u.upX = up.x; u.upY = up.y; u.upZ = up.z;
    u.tanHalfX = tanHalfX; u.tanHalfY = tanHalfY;
    u.width = (uint32_t)width; u.height = (uint32_t)height;
    u.spp = (uint32_t)spp; u.maxBounces = (uint32_t)bounces;
    u.instCount = instCount;

    id<MTLBuffer> ub =
        [g_device newBufferWithBytes:&u length:sizeof(u)
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
    if (ub == nil || imageBuf == nil || rayBuf == nil) {
        rp("FATAL: render buffer allocation failed");
        return NULL;
    }
    memset(rayBuf.contents, 0, 2 * sizeof(uint32_t));

    id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cmd computeCommandEncoder];
    [enc setComputePipelineState:g_ptPipeline];
    [enc setBuffer:ub offset:0 atIndex:0];
    [enc setBuffer:imageBuf offset:0 atIndex:1];
    [enc setAccelerationStructure:tlas atBufferIndex:2];
    [enc setBuffer:g_triColorPool offset:0 atIndex:3];
    [enc setBuffer:g_triNormalPool offset:0 atIndex:4];
    [enc setBuffer:baseTable offset:0 atIndex:5];
    [enc setBuffer:rayBuf offset:0 atIndex:6];
    // Padded grid + kernel bounds guard (dispatchThreadgroups is universally
    // supported; dispatchThreads would need a capability check).
    MTLSize grid = MTLSizeMake(((NSUInteger)width + 15) / 16 * 16,
                               ((NSUInteger)height + 15) / 16 * 16, 1);
    MTLSize tg = MTLSizeMake(16, 16, 1);
    [enc dispatchThreadgroups:grid threadsPerThreadgroup:tg];
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
            || g_instTriBase == nil) {
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
            || g_triColorPool == nil || g_triNormalPool == nil) {
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

JNIEXPORT void JNICALL
Java_net_prisma_pathtrace_Native_shutdownNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (g_slots != nil) {
        for (SceneSlot *s in g_slots) { s.ready = NO; }
        g_slots = nil;
    }
    g_tlas = nil;
    g_instanceBuffer = nil;
    g_pipeline = nil;
    g_ptPipeline = nil;
    g_library = nil;
    g_triColorPool = nil;
    g_triNormalPool = nil;
    g_instTriBase = nil;
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
