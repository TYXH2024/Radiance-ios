//
//  metal_scene.m
//  Radiance iOS Port — Chapter 2 — Stage 2.1 — Scene Ingestion (Metal-direct)
//
//  Scene manager behind net.prisma.sceneingest.Native (JNI):
//    initNative          → own MTLDevice/queue (zero shared state with the
//                          zink/MoltenVK render chain — 2.0 verified pattern),
//                          slot grid, metallib + ray_query pipeline
//    rebuildSingleNative → per-chunk: concat all RenderLayer vertex buffers
//                          (float3 @0, arbitrary stride) → recentre by -origin
//                          (chunk-local coordinates — float32 world-absolute
//                          precision discipline, design §4) → expand QUADS to
//                          uint32 indices → triangle BLAS → slot ready
//    frameTickNative     → throttled TLAS rebuild over all ready slots
//                          (instance transform = translate(t), see below)
//    castRayNative       → single world-space ray via scene_trace_kernel
//    invalidate/relocate/isChunkReady/sceneStats/shutdown
//
//  v0.4 additions (diagnostic matrix):
//    sceneInfoNative         → raw-AABB audit over all rebuilt slots: measures
//                              whether extracted vertices are world-absolute
//                              or chunk-local(+constant R), rp()s the evidence,
//                              stores the measured offset for MEASURED mode
//    sentinelProbeNative     → isolated 2-instance TLAS (identity + translate)
//                              + 3 rays: validates instance machinery + the
//                              matrix encoding independent of MC data
//    setPlacementModeNative  → mode 0 (ABS: t=origin) / 1 (MEASURED:
//                              t=2*origin-R, R from the AABB audit)
//    instanceOriginNative    → origin of the slot behind a TLAS instance index
//
//  Instance matrix layout — v0.4 doc-confirmed fix (v0.3 regression):
//    transformationMatrix is MTLPackedFloat4x3. Apple documentation defines it
//    as "a structure that contains the top three rows of a 4x4 matrix ... in
//    column-major order" with columns (MTLPackedFloat3 x4) and the implied
//    bottom row [0,0,0,1]. Memory = four packed float3 columns; the rotation
//    basis is columns 0-2 and **column 3 (flat[9..11]) is the translation**.
//    Identity+translate(t) = [1,0,0, 0,1,0, 0,0,1, tx,ty,tz] — the encoding the
//    2.0 probe used for its identity transform (and v0.2 used for translate).
//    v0.3 moved the translation to flat[3]/[7]/[11] ("simd_float3x4, 3 columns
//    of simd_float4") which under the real layout decodes as shear garbage
//    (col1=(tx,0,1), col2=(0,ty,0)) — every ray misses. Confirmed against the
//    v0.3 field report (six-way all-miss with healthy ingestion stats) and the
//    2.0 field report (identity transform verified on device).
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
@property (nonatomic, strong) id<MTLBuffer> vertexBuffer; // recentred float3
@property (nonatomic, strong) id<MTLBuffer> indexBuffer;  // uint32
@property (nonatomic) int32_t ox, oy, oz;
@property (nonatomic) int64_t generation;
@property (nonatomic) uint32_t vertexCount;
@property (nonatomic) uint32_t indexCount;
@property (nonatomic) BOOL ready;
// v0.4: raw (pre-recentre) AABB of the extracted vertices — the evidence
// source for the vertex-space audit (world-absolute vs chunk-local).
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
static id<MTLComputePipelineState> g_pipeline = nil;

// v0.4: placement mode + measured vertex-space offset.
//   mode 0 (ABS):     t = origin            (raw vertices are world-absolute)
//   mode 1 (MEASURED): t = 2*origin - R      (raw = world - origin + R, i.e.
//                     chunk-local with constant extra shift R; R=0 = pure
//                     section-local). R measured by sceneInfoNative.
static int g_placementMode = 0;
static float g_offsetR[3] = {0.0f, 0.0f, 0.0f};

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

// Rebuild TLAS over all ready slots. Flushes the retired list afterwards.
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

    // Instance descriptors: transform = translate(t).
    //   mode 0 (ABS):      t = origin
    //   mode 1 (MEASURED): t = 2*origin - R  (R = g_offsetR, measured from the
    //                       raw-AABB audit; correct whenever the extracted
    //                       vertices are chunk-local with constant shift R:
    //                       raw = world - origin + R, BLAS = raw - origin,
    //                       world = BLAS + t = raw - origin + 2*origin - R)
    const uint32_t n = (uint32_t)ready.count;
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
        float tx = (float)s.ox, ty = (float)s.oy, tz = (float)s.oz;
        if (g_placementMode == 1) {
            tx = 2.0f * (float)s.ox - g_offsetR[0];
            ty = 2.0f * (float)s.oy - g_offsetR[1];
            tz = 2.0f * (float)s.oz - g_offsetR[2];
        }
        encode_instance_matrix(&instances[i].transformationMatrix, tx, ty, tz);
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

    // Ready only after the new TLAS completed — safe to drop retired objects.
    flush_retired();
    rp("[tlas] rebuilt in %.2f ms (instances=%u, as=%llu B, total=%llu builds, mode=%s)",
       ms, n, (unsigned long long)sizes.accelerationStructureSize,
       (unsigned long long)g_stats[3],
       g_placementMode == 0 ? "ABS" : "MEASURED");
}

// ------------------------- JNI -------------------------

static const char *kMetallibPath = NULL;

static void open_report(const char *path) {
    if (g_report != NULL) { fclose(g_report); }
    g_report = path != NULL ? fopen(path, "a") : NULL;
}

JNIEXPORT void JNICALL
Java_net_prisma_sceneingest_Native_initNative(
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
    }
}

JNIEXPORT void JNICALL
Java_net_prisma_sceneingest_Native_rebuildSingleNative(
        JNIEnv *env, jclass cls, jint ox, jint oy, jint oz,
        jlong index, jlong generation, jint layerCount,
        jobjectArray vertices, jintArray counts, jintArray strides) {
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
    if (cCounts == NULL || cStrides == NULL || layerCount <= 0) {
        rp("[rebuild] reject: bad arrays @ index %lld", (long long)index);
        if (cCounts != NULL) { (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT); }
        if (cStrides != NULL) { (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT); }
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
        return;
    }

    // ---- Pass 2: compact recentred float3 vertices + quad indices ----
    simd_float3 *verts =
        (simd_float3 *)malloc(totalVerts * sizeof(simd_float3));
    uint32_t *idx = (uint32_t *)malloc(totalIndices * sizeof(uint32_t));
    if (verts == NULL || idx == NULL) {
        rp("FATAL: OOM in rebuild (verts=%llu)", (unsigned long long)totalVerts);
        free(verts); free(idx);
        (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
        (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
        return;
    }

    // v0.4: raw (pre-recentre) AABB accumulators — the vertex-space evidence.
    float rmin[3] = {FLT_MAX, FLT_MAX, FLT_MAX};
    float rmax[3] = {-FLT_MAX, -FLT_MAX, -FLT_MAX};
    BOOL rawBad = NO;

    uint64_t vBase = 0;
    for (jint i = 0; i < layerCount; i++) {
        jobject bb = (*env)->GetObjectArrayElement(env, vertices, (jsize)i);
        if (bb == NULL) {
            rp("[rebuild] reject: layer %d null ByteBuffer @ index %lld",
               i, (long long)index);
            free(verts); free(idx);
            (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
            return;
        }
        const uint8_t *src = (const uint8_t *)(*env)->GetDirectBufferAddress(env, bb);
        jlong srcCap = (*env)->GetDirectBufferCapacity(env, bb);
        uint32_t vc = (uint32_t)cCounts[i];
        uint32_t stride = (uint32_t)cStrides[i];
        if (src == NULL
                || (uint64_t)srcCap < (uint64_t)vc * stride) {
            rp("[rebuild] reject: layer %d non-direct/short buffer "
               "(cap=%lld need=%u) @ index %lld",
               i, (long long)srcCap, vc * stride, (long long)index);
            (*env)->DeleteLocalRef(env, bb);
            free(verts); free(idx);
            (*env)->ReleaseIntArrayElements(env, counts, cCounts, JNI_ABORT);
            (*env)->ReleaseIntArrayElements(env, strides, cStrides, JNI_ABORT);
            return;
        }
        // Copy + recentre (world-absolute → chunk-local; float3 @ offset 0).
        // v0.4: accumulate the RAW coordinates first (vertex-space audit).
        for (uint32_t v = 0; v < vc; v++) {
            const float *p = (const float *)(src + (size_t)v * stride);
            if (isfinite(p[0]) && isfinite(p[1]) && isfinite(p[2])) {
                if (p[0] < rmin[0]) { rmin[0] = p[0]; }
                if (p[1] < rmin[1]) { rmin[1] = p[1]; }
                if (p[2] < rmin[2]) { rmin[2] = p[2]; }
                if (p[0] > rmax[0]) { rmax[0] = p[0]; }
                if (p[1] > rmax[1]) { rmax[1] = p[1]; }
                if (p[2] > rmax[2]) { rmax[2] = p[2]; }
            } else {
                rawBad = YES;
            }
            verts[vBase + v] = simd_make_float3(
                p[0] - (float)ox, p[1] - (float)oy, p[2] - (float)oz);
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

    // ---- Upload + BLAS ----
    id<MTLBuffer> vertexBuffer =
        [g_device newBufferWithBytes:verts length:(NSUInteger)(totalVerts * sizeof(simd_float3))
                             options:MTLResourceStorageModeShared];
    id<MTLBuffer> indexBuffer =
        [g_device newBufferWithBytes:idx length:(NSUInteger)(totalIndices * sizeof(uint32_t))
                             options:MTLResourceStorageModeShared];
    free(verts); free(idx);

    double t0 = clockms();
    slot.ox = ox; slot.oy = oy; slot.oz = oz;
    slot.generation = generation;
    slot.vertexBuffer = vertexBuffer;
    slot.indexBuffer = indexBuffer;
    slot.vertexCount = (uint32_t)totalVerts;
    slot.indexCount = (uint32_t)totalIndices;
    // v0.4: stash the raw AABB (survives until the next rebuild of this slot).
    slot.rawValid = !rawBad;
    slot.rawMinX = rmin[0]; slot.rawMinY = rmin[1]; slot.rawMinZ = rmin[2];
    slot.rawMaxX = rmax[0]; slot.rawMaxY = rmax[1]; slot.rawMaxZ = rmax[2];

    if (vertexBuffer == nil || indexBuffer == nil || !build_blas(slot)) {
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
       "(blas %.2f ms, total builds=%llu)",
       (long long)index, ox, oy, oz, layerCount,
       (unsigned long long)totalVerts, (unsigned long long)(totalIndices / 3),
       clockms() - t0, (unsigned long long)g_stats[4]);
}

JNIEXPORT void JNICALL
Java_net_prisma_sceneingest_Native_invalidateSingle(
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
Java_net_prisma_sceneingest_Native_relocateSingle(
        JNIEnv *env, jclass cls, jlong index, jint ox, jint oy, jint oz, jlong generation) {
    // Polling design: relocation invalidates; the next poll rebuilds with the
    // new origin (rebuild is the authoritative writer of geometry).
    Java_net_prisma_sceneingest_Native_invalidateSingle(env, cls, index, ox, oy, oz, generation);
}

JNIEXPORT void JNICALL
Java_net_prisma_sceneingest_Native_frameTickNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (g_device == nil) { return; }
    rebuild_tlas();
}

JNIEXPORT jboolean JNICALL
Java_net_prisma_sceneingest_Native_isChunkReady(JNIEnv *env, jclass cls, jlong index) {
    (void)env; (void)cls;
    if (g_slots == nil || index < 0 || (NSUInteger)index >= g_slots.count) { return JNI_FALSE; }
    return g_slots[(NSUInteger)index].ready ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlongArray JNICALL
Java_net_prisma_sceneingest_Native_sceneStatsNative(JNIEnv *env, jclass cls) {
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

// v0.4: qsort comparator for float arrays (raw-AABB audit).
static int cmpf(const void *a, const void *b) {
    float fa = *(const float *)a, fb = *(const float *)b;
    return (fa > fb) - (fa < fb);
}

// v0.4: normalize a direction vector in place; returns NO for degenerate input.
static BOOL normalize_dir(float *dx, float *dy, float *dz) {
    float len = sqrtf(*dx * *dx + *dy * *dy + *dz * *dz);
    if (len < 1e-8f) { return NO; }
    *dx /= len; *dy /= len; *dz /= len;
    return YES;
}

JNIEXPORT jfloatArray JNICALL
Java_net_prisma_sceneingest_Native_castRayNative(
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
Java_net_prisma_sceneingest_Native_sentinelProbeNative(JNIEnv *env, jclass cls) {
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

// S6.0/S6.2 raw-AABB audit (v0.5): measures the vertex space of the extracted
// geometry and derives the placement offset R for MEASURED mode.
//
// v0.5 fix (root cause of the v0.4 R bias): the v0.4 estimator took the
// MEDIAN of e = rawCenter - 8 over far slots. For section-local vertices e is
// NOT constant — partial-height sections (bedrock floors, terrain shells)
// drag rawCenter below 8, biasing the median to (0, -2.07, 0) in the v0.4
// field run. That R was applied to the winning placement and displaced every
// Y-normal face by exactly +2.07 blocks (integer-face proof: after removing
// the bias all v0.4 hit faces land on integer block boundaries — +Y 93.07 ->
// 91.00, -Y 19.07 -> 17.00, diagonal 67.07 -> 65.00 — while X/Z faces were
// already exact at 26.00/8.00/-27.00/-18.00 because R_x = R_z = 0; the eye
// cross-check 92.62 = 91.00 + 1.62 matches MC's exact eye height).
//
// The correct discriminator is a BOUNDS test: for section-local vertices the
// global raw AABB over far slots is a subset of [-1..17]^3 (v0.4 field data:
// [-0.2..16.2, -0.2..16.0, -0.2..16.1]), while world-absolute vertices near
// far slots range over the thousands. Bounds hit => R = (0,0,0) exactly.
// The legacy median remains only as a fallback when bounds are inconclusive
// (reported as low-confidence).
//
// rp()s the evidence lines, stores the measured R for MEASURED mode, and
// returns {Rx, Ry, Rz, farCount, absVotes, fam2Votes, garbage, readyCount}
// (floats; counts as truncated floats).
JNIEXPORT jfloatArray JNICALL
Java_net_prisma_sceneingest_Native_sceneInfoNative(
        JNIEnv *env, jclass cls, jfloat camX, jfloat camY, jfloat camZ) {
    (void)cls;
    jfloat out[8] = {0, 0, 0, 0, 0, 0, 0, 0};
    jfloatArray result = (*env)->NewFloatArray(env, 8);

    // ---- Pass 1: counts, garbage screen, global raw bounds, far slots,
    // camera slot (single sweep). ----
    uint32_t readyCount = 0, rawValid = 0, garbage = 0;
    NSUInteger farCount = 0;
    NSMutableArray<SceneSlot *> *far = [NSMutableArray array];
    SceneSlot *camSlot = nil;
    float wmin[3] = {FLT_MAX, FLT_MAX, FLT_MAX};
    float wmax[3] = {-FLT_MAX, -FLT_MAX, -FLT_MAX};
    if (g_slots != nil) {
        for (SceneSlot *s in g_slots) {
            if (!s.ready) { continue; }
            readyCount++;
            if (!s.rawValid) { garbage++; continue; }
            float span[3] = {s.rawMaxX - s.rawMinX, s.rawMaxY - s.rawMinY,
                             s.rawMaxZ - s.rawMinZ};
            if (span[0] > 512 || span[1] > 512 || span[2] > 512
                    || fabsf(s.rawMinX) > 1e7f || fabsf(s.rawMinY) > 1e7f
                    || fabsf(s.rawMinZ) > 1e7f) {
                garbage++;
                continue;
            }
            rawValid++;
            float mn[3] = {s.rawMinX, s.rawMinY, s.rawMinZ};
            float mx[3] = {s.rawMaxX, s.rawMaxY, s.rawMaxZ};
            for (int a = 0; a < 3; a++) {
                if (mn[a] < wmin[a]) { wmin[a] = mn[a]; }
                if (mx[a] > wmax[a]) { wmax[a] = mx[a]; }
            }
            if (camSlot == nil
                    && camX >= (float)s.ox && camX < (float)s.ox + 16.0f
                    && camY >= (float)s.oy && camY < (float)s.oy + 16.0f
                    && camZ >= (float)s.oz && camZ < (float)s.oz + 16.0f) {
                camSlot = s;
            }
            if (abs(s.ox) >= 64 || abs(s.oy) >= 64 || abs(s.oz) >= 64) {
                [far addObject:s];
            }
        }
        farCount = far.count;
    }

    // ---- Bounds test: section-local => R = (0,0,0) exactly. ----
    float Rx = 0, Ry = 0, Rz = 0;
    uint32_t absVotes = 0, fam2Votes = 0;
    BOOL sectionLocal = NO;
    if (rawValid >= 4 && farCount >= 4
            && wmax[0] <= 17.0f && wmax[1] <= 17.0f && wmax[2] <= 17.0f
            && wmin[0] >= -1.0f && wmin[1] >= -1.0f && wmin[2] >= -1.0f) {
        sectionLocal = YES;
        Rx = 0; Ry = 0; Rz = 0;
        fam2Votes = (uint32_t)farCount; // every far slot's raw AABB is in-bounds
        rp("[aabb] bounds test: global raw AABB [%.1f..%.1f, %.1f..%.1f, "
           "%.1f..%.1f] within [-1..17] over %u far slots "
           "-> SECTION-LOCAL, R=(0,0,0) exactly",
           wmin[0], wmax[0], wmin[1], wmax[1], wmin[2], wmax[2],
           (unsigned)farCount);
    } else if (farCount >= 4) {
        // ---- Legacy e-median estimator (fallback, low confidence). ----
        NSUInteger n = farCount;
        float *ex = (float *)malloc(n * sizeof(float));
        float *ey = (float *)malloc(n * sizeof(float));
        float *ez = (float *)malloc(n * sizeof(float));
        if (ex != NULL && ey != NULL && ez != NULL) {
            for (NSUInteger i = 0; i < n; i++) {
                SceneSlot *s = far[i];
                ex[i] = (s.rawMinX + s.rawMaxX) * 0.5f - 8.0f;
                ey[i] = (s.rawMinY + s.rawMaxY) * 0.5f - 8.0f;
                ez[i] = (s.rawMinZ + s.rawMaxZ) * 0.5f - 8.0f;
            }
            qsort(ex, n, sizeof(float), cmpf);
            qsort(ey, n, sizeof(float), cmpf);
            qsort(ez, n, sizeof(float), cmpf);
            Rx = ex[n / 2]; Ry = ey[n / 2]; Rz = ez[n / 2];
            float spread[3] = {
                ex[n - 1] - ex[0], ey[n - 1] - ey[0], ez[n - 1] - ez[0],
            };
            rp("[aabb] legacy median (fallback, biased by partial sections): "
               "far=%u median=(%.1f, %.1f, %.1f) spread=(%.1f, %.1f, %.1f)",
               (unsigned)farCount, Rx, Ry, Rz, spread[0], spread[1], spread[2]);
            for (NSUInteger i = 0; i < n; i++) {
                SceneSlot *s = far[i];
                float dx = ex[i] - (float)s.ox;
                float dy = ey[i] - (float)s.oy;
                float dz = ez[i] - (float)s.oz;
                float ddx = ex[i] - Rx, ddy = ey[i] - Ry, ddz = ez[i] - Rz;
                BOOL constish = spread[0] <= 24 && spread[1] <= 24 && spread[2] <= 24;
                if (fabsf(dx) <= 16 && fabsf(dy) <= 16 && fabsf(dz) <= 16) {
                    absVotes++;
                } else if (constish && fabsf(ddx) <= 16 && fabsf(ddy) <= 16
                           && fabsf(ddz) <= 16) {
                    fam2Votes++;
                }
            }
        }
        free(ex); free(ey); free(ez);
    } else if (g_slots != nil) {
        // ---- Far evidence thin: all-ready-slots median (lowest confidence). ----
        NSMutableArray<SceneSlot *> *all = [NSMutableArray array];
        for (SceneSlot *s in g_slots) {
            if (s.ready && s.rawValid) { [all addObject:s]; }
        }
        if (all.count >= 4) {
            NSUInteger n = all.count;
            float *ex = (float *)malloc(n * sizeof(float));
            float *ey = (float *)malloc(n * sizeof(float));
            float *ez = (float *)malloc(n * sizeof(float));
            if (ex != NULL && ey != NULL && ez != NULL) {
                for (NSUInteger i = 0; i < n; i++) {
                    SceneSlot *s = all[i];
                    ex[i] = (s.rawMinX + s.rawMaxX) * 0.5f - 8.0f;
                    ey[i] = (s.rawMinY + s.rawMaxY) * 0.5f - 8.0f;
                    ez[i] = (s.rawMinZ + s.rawMaxZ) * 0.5f - 8.0f;
                }
                qsort(ex, n, sizeof(float), cmpf);
                qsort(ey, n, sizeof(float), cmpf);
                qsort(ez, n, sizeof(float), cmpf);
                Rx = ex[n / 2]; Ry = ey[n / 2]; Rz = ez[n / 2];
                rp("[aabb] far-slot evidence thin — R estimated from all %u "
                   "ready slots (low confidence)", (unsigned)n);
            }
            free(ex); free(ey); free(ez);
        }
    }
    g_offsetR[0] = Rx; g_offsetR[1] = Ry; g_offsetR[2] = Rz;

    // ---- Grid span + representative per-slot lines. ----
    if (g_slots != nil) {
        int32_t gmin[3] = {INT32_MAX, INT32_MAX, INT32_MAX};
        int32_t gmax[3] = {INT32_MIN, INT32_MIN, INT32_MIN};
        NSUInteger printed = 0;
        for (SceneSlot *s in g_slots) {
            if (!s.ready) { continue; }
            if (s.ox < gmin[0]) { gmin[0] = s.ox; }
            if (s.oy < gmin[1]) { gmin[1] = s.oy; }
            if (s.oz < gmin[2]) { gmin[2] = s.oz; }
            if (s.ox + 16 > gmax[0]) { gmax[0] = s.ox + 16; }
            if (s.oy + 16 > gmax[1]) { gmax[1] = s.oy + 16; }
            if (s.oz + 16 > gmax[2]) { gmax[2] = s.oz + 16; }
            if (printed < 5 || s == camSlot) {
                rp("[aabb] slot origin=(%d,%d,%d) raw=[%.1f..%.1f, %.1f..%.1f, "
                   "%.1f..%.1f]%s",
                   s.ox, s.oy, s.oz, s.rawMinX, s.rawMaxX, s.rawMinY, s.rawMaxY,
                   s.rawMinZ, s.rawMaxZ,
                   s == camSlot ? "  <- camera section" : "");
                printed++;
            }
        }
        if (readyCount > 0) {
            rp("[aabb] grid span (origin+16): x[%d..%d] y[%d..%d] z[%d..%d]",
               gmin[0], gmax[0], gmin[1], gmax[1], gmin[2], gmax[2]);
            rp("[aabb] global raw AABB: [%.1f..%.1f, %.1f..%.1f, %.1f..%.1f] "
               "(ready=%u rawValid=%u garbage=%u)",
               wmin[0], wmax[0], wmin[1], wmax[1], wmin[2], wmax[2],
               (unsigned)readyCount, (unsigned)rawValid, (unsigned)garbage);
        }
    }

    // ---- Verdict. ----
    if (sectionLocal) {
        rp("[aabb] verdict: SECTION-LOCAL (R=0 exactly; v0.4 median bias "
           "eliminated) — MEASURED placement = translate(2*origin)");
    } else if (farCount >= 4) {
        if (absVotes > fam2Votes * 3) {
            rp("[aabb] verdict: extracted vertices look WORLD-ABSOLUTE "
               "(e follows origin; ABS votes=%u, local-family=%u)",
               (unsigned)absVotes, (unsigned)fam2Votes);
        } else if (fam2Votes > absVotes * 3) {
            rp("[aabb] verdict: extracted vertices look CHUNK-LOCAL "
               "(constant e; local-family votes=%u, ABS=%u; R=(%.1f, %.1f, %.1f))",
               (unsigned)fam2Votes, (unsigned)absVotes, Rx, Ry, Rz);
        } else {
            rp("[aabb] verdict: INCONCLUSIVE (ABS votes=%u, local-family=%u) "
               "— tournament will decide empirically",
               (unsigned)absVotes, (unsigned)fam2Votes);
        }
    } else {
        rp("[aabb] verdict: insufficient far-slot evidence (far=%u) "
           "— tournament will decide empirically", (unsigned)farCount);
    }

    out[0] = Rx; out[1] = Ry; out[2] = Rz;
    out[3] = (jfloat)farCount;
    out[4] = (jfloat)absVotes;
    out[5] = (jfloat)fam2Votes;
    out[6] = (jfloat)garbage;
    out[7] = (jfloat)readyCount;
    if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 8, out); }
    return result;
}

// S6.3: placement mode switch (0 = ABS t=origin, 1 = MEASURED t=2*origin-R).
// Marks the TLAS dirty so the next frameTickNative rebuilds with the new
// encoding.
JNIEXPORT void JNICALL
Java_net_prisma_sceneingest_Native_setPlacementModeNative(JNIEnv *env, jclass cls, jint mode) {
    (void)env; (void)cls;
    if (mode != 0 && mode != 1) { return; }
    if (g_placementMode == mode) { return; }
    g_placementMode = (int)mode;
    g_tlasDirty = YES;
    rp("[scene] placement mode -> %s",
       mode == 0 ? "ABS (t=origin)" : "MEASURED (t=2*origin-R)");
}

// S6.4: origin of the slot behind a TLAS instance index (hit attribution).
JNIEXPORT jintArray JNICALL
Java_net_prisma_sceneingest_Native_instanceOriginNative(JNIEnv *env, jclass cls, jint instanceIndex) {
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

JNIEXPORT void JNICALL
Java_net_prisma_sceneingest_Native_shutdownNative(JNIEnv *env, jclass cls) {
    (void)env; (void)cls;
    if (g_slots != nil) {
        for (SceneSlot *s in g_slots) { s.ready = NO; }
        g_slots = nil;
    }
    g_tlas = nil;
    g_instanceBuffer = nil;
    g_pipeline = nil;
    g_library = nil;
    g_tlasDirty = NO;
    if (g_instOrigins != NULL) {
        free(g_instOrigins);
        g_instOrigins = NULL;
        g_instOriginCount = 0;
    }
    g_placementMode = 0;
    g_offsetR[0] = 0; g_offsetR[1] = 0; g_offsetR[2] = 0;
    if (g_retired != nil) {
        [g_retired removeAllObjects];
        g_stats[8] = 0;
    }
    rp("[scene] shutdown — all GPU resources released");
    if (g_report != NULL) { fclose(g_report); g_report = NULL; }
}
