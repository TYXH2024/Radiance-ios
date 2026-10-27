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
//                          (instance transform = translate(origin))
//    castRayNative       → single world-space ray via scene_trace_kernel
//    invalidate/relocate/isChunkReady/sceneStats/shutdown
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

    // Instance descriptors: transform = translate(origin) — row-major 4x3.
    const uint32_t n = (uint32_t)ready.count;
    size_t instBytes = n * sizeof(MTLAccelerationStructureInstanceDescriptor);
    MTLAccelerationStructureInstanceDescriptor *instances =
        (MTLAccelerationStructureInstanceDescriptor *)calloc(1, instBytes);
    if (instances == NULL) { return; }
    NSMutableArray<id<MTLAccelerationStructure>> *blasRefs =
        [NSMutableArray arrayWithCapacity:n];
    for (uint32_t i = 0; i < n; i++) {
        SceneSlot *s = ready[i];
        float *m = (float *)&instances[i].transformationMatrix;
        m[0] = 1; m[1] = 0; m[2] = 0;      // row 0
        m[3] = 0; m[4] = 1; m[5] = 0;      // row 1
        m[6] = 0; m[7] = 0; m[8] = 1;      // row 2
        m[9] = (float)s.ox;                // row 3 = translation
        m[10] = (float)s.oy;
        m[11] = (float)s.oz;
        instances[i].options = MTLAccelerationStructureInstanceOptionNone;
        instances[i].mask = 0xFFFFFFFFu;
        instances[i].intersectionFunctionTableOffset = 0;
        instances[i].accelerationStructureIndex = i;
        [blasRefs addObject:s.blas];
    }

    id<MTLBuffer> instanceBuffer =
        [g_device newBufferWithBytes:instances length:instBytes
                              options:MTLResourceStorageModeShared];
    free(instances);
    if (instanceBuffer == nil) { return; }

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
        return;
    }

    if (g_tlas != nil) { [g_retired addObject:g_tlas]; g_stats[8]++; }
    if (g_instanceBuffer != nil) { [g_retired addObject:g_instanceBuffer]; g_stats[8]++; }
    g_tlas = tlas;
    g_instanceBuffer = instanceBuffer;
    g_stats[3]++;
    g_stats[7] = sizes.accelerationStructureSize;
    g_tlasDirty = NO;

    // Ready only after the new TLAS completed — safe to drop retired objects.
    flush_retired();
    rp("[tlas] rebuilt in %.2f ms (instances=%u, as=%llu B, total=%llu builds)",
       ms, n, (unsigned long long)sizes.accelerationStructureSize,
       (unsigned long long)g_stats[3]);
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
        for (uint32_t v = 0; v < vc; v++) {
            const float *p = (const float *)(src + (size_t)v * stride);
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

    // Normalize direction.
    float len = sqrtf(dx * dx + dy * dy + dz * dz);
    if (len < 1e-8f) {
        if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 3, out); }
        return result;
    }
    dx /= len; dy /= len; dz /= len;

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
    ray.origin = simd_make_float3(ox, oy, oz);
    ray.direction = simd_make_float3(dx, dy, dz);
    ray.maxDistance = maxDist;
    ray.pad = 0;
    SceneHit hit = { -1.0f, 0xFFFFFFFFu, 0, 0 };

    id<MTLBuffer> rayBuffer =
        [g_device newBufferWithBytes:&ray length:sizeof(SceneRay)
                             options:MTLResourceStorageModeShared];
    id<MTLBuffer> hitBuffer =
        [g_device newBufferWithLength:sizeof(SceneHit)
                              options:MTLResourceStorageModeShared];
    if (rayBuffer == nil || hitBuffer == nil) {
        if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 3, out); }
        return result;
    }

    id<MTLCommandBuffer> cmd = [g_queue commandBuffer];
    id<MTLComputeCommandEncoder> enc = [cmd computeCommandEncoder];
    [enc setComputePipelineState:g_pipeline];
    [enc setBuffer:rayBuffer offset:0 atIndex:0];
    [enc setBuffer:hitBuffer offset:0 atIndex:1];
    [enc setAccelerationStructure:g_tlas atBufferIndex:2];
    [enc dispatchThreadgroups:MTLSizeMake(1, 1, 1)
         threadsPerThreadgroup:MTLSizeMake(1, 1, 1)];
    [enc endEncoding];
    [cmd commit];
    [cmd waitUntilCompleted];
    if (cmd.error != nil) {
        rp("FATAL: castRay dispatch failed: %s", cmd.error.localizedDescription.UTF8String);
    } else {
        const SceneHit *h = (const SceneHit *)hitBuffer.contents;
        out[0] = h->hit ? 1.0f : 0.0f;
        out[1] = h->distance;
        out[2] = (jfloat)h->instanceIndex;
    }
    if (result != NULL) { (*env)->SetFloatArrayRegion(env, result, 0, 3, out); }
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
    if (g_retired != nil) {
        [g_retired removeAllObjects];
        g_stats[8] = 0;
    }
    rp("[scene] shutdown — all GPU resources released");
    if (g_report != NULL) { fclose(g_report); g_report = NULL; }
}
