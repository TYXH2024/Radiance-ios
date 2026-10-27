//
//  metal_probe.m
//  Radiance iOS Port — Chapter 2 — Stage 2.0 — In-Process Metal RT Probe
//
//  JNI adaptation of chapter1/probe_metal_rt.m (same verified sequence):
//  BLAS -> TLAS -> metallib -> ray_query compute pipeline -> 262144 rays
//  (half masked-miss) -> readback verification -> optional runtime MSL
//  compile datapoint. All detail lines are appended to the Java-side report
//  file (incremental flush — survives a native crash mid-probe).
//
//  Verdict codes (mirror net.prisma.metalprobe.MetalProbeMod):
//    0 METAL_RT_OK   — full chain verified inside the game process
//    1 METAL_RT_MISMATCH
//    2 NATIVE_FATAL
//    3 METAL_RT_API_UNAVAILABLE
//
//  Loading: extracted by the mod from the JAR to <game>/metalprobe/ and
//  System.load()-ed — the same trusted dlopen path LWJGL natives use under
//  the launcher's hooked_dlopen (see docs/chapter2-route-decision.md §3.2).
//
//  Notes:
//    - Own MTLDevice/queue: zero shared state with the zink/MoltenVK render
//      chain (lesson from rt-probe v1.1: never leak/contend host resources).
//      ARC releases every ObjC object on scope exit.
//    - Called on a Java background thread; Metal allows device/queue/command
//      buffer use from any thread.
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

static const uint32_t kRayCount = 1u << 18; // 262144

typedef struct {
    simd_float3 origin;
    simd_float3 direction;
    uint32_t mask;
    uint32_t pad;
} ProbeRay;

typedef struct {
    float distance;
    uint32_t primitive_id;
    uint32_t hit;
    uint32_t pad;
} ProbeResult;

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

static int apple_family_of(id<MTLDevice> device) {
    static const MTLGPUFamily families[] = {
        MTLGPUFamilyApple1, MTLGPUFamilyApple2, MTLGPUFamilyApple3, MTLGPUFamilyApple4,
        MTLGPUFamilyApple5, MTLGPUFamilyApple6, MTLGPUFamilyApple7, MTLGPUFamilyApple8,
        MTLGPUFamilyApple9, // Apple9+ = dedicated intersection hardware (A17 Pro / M3+).
    };
    int family = 0;
    for (size_t i = 0; i < sizeof(families) / sizeof(families[0]); i++) {
        if ([device supportsFamily:families[i]]) { family = (int)(i + 1); }
    }
    return family;
}

// ------------------------- probe body -------------------------

static int run_probe(const char *metallibPath, const char *mslSourcePath) {
    int fatal_rc = 2;

    id<MTLDevice> device = MTLCreateSystemDefaultDevice();
    if (device == nil) {
        rp("FATAL: MTLCreateSystemDefaultDevice returned NULL");
        return fatal_rc;
    }

    int family = apple_family_of(device);
    rp("[device] name=%s", device.name.UTF8String);
    rp("[device] gpuFamily=Apple%d", family);
    rp("[device] maxThreadgroupMemory=%llu B",
       (unsigned long long)device.maxThreadgroupMemoryLength);
    rp("[device] supportsRaytracing=%s", device.supportsRaytracing ? "YES" : "NO");
    rp("[device] dedicatedIntersectionHW(Apple9+)=%s",
       [device supportsFamily:MTLGPUFamilyApple9] ? "YES" : "NO");
    NSLog(@"[MetalProbe] device=%@ family=Apple%d raytracing=%@",
          device.name, family, device.supportsRaytracing ? @"YES" : @"NO");

    if (!device.supportsRaytracing) {
        rp("VERDICT: METAL_RT_API_UNAVAILABLE (device.supportsRaytracing == NO)");
        return 3;
    }

    id<MTLCommandQueue> queue = [device newCommandQueue];
    if (queue == nil) {
        rp("FATAL: no command queue");
        return fatal_rc;
    }

    // ---- BLAS: one triangle in the z = 0 plane ------------------
    static simd_float3 vertices[3] = {
        {0.0f, 0.0f, 0.0f},
        {1.0f, 0.0f, 0.0f},
        {0.0f, 1.0f, 0.0f},
    };
    id<MTLBuffer> vertexBuffer =
        [device newBufferWithBytes:vertices length:sizeof(vertices)
                            options:MTLResourceStorageModeShared];

    MTLAccelerationStructureTriangleGeometryDescriptor *geometry =
        [MTLAccelerationStructureTriangleGeometryDescriptor descriptor];
    geometry.vertexBuffer = vertexBuffer;
    geometry.vertexBufferOffset = 0;
    geometry.vertexStride = sizeof(simd_float3);
    geometry.vertexFormat = MTLAttributeFormatFloat3;
    geometry.triangleCount = 1;

    MTLPrimitiveAccelerationStructureDescriptor *blasDescriptor =
        [MTLPrimitiveAccelerationStructureDescriptor descriptor];
    blasDescriptor.geometryDescriptors = @[ geometry ];

    double t0 = clockms();
    MTLAccelerationStructureSizes blasSizes =
        [device accelerationStructureSizesWithDescriptor:blasDescriptor];
    id<MTLAccelerationStructure> blas =
        [device newAccelerationStructureWithSize:blasSizes.accelerationStructureSize];
    id<MTLBuffer> blasScratch =
        [device newBufferWithLength:blasSizes.buildScratchBufferSize
                             options:MTLResourceStorageModeShared];

    id<MTLCommandBuffer> blasCmd = [queue commandBuffer];
    id<MTLAccelerationStructureCommandEncoder> blasEnc =
        [blasCmd accelerationStructureCommandEncoder];
    [blasEnc buildAccelerationStructure:blas
                              descriptor:blasDescriptor
                           scratchBuffer:blasScratch
                       scratchBufferOffset:0];
    [blasEnc endEncoding];
    [blasCmd commit];
    [blasCmd waitUntilCompleted];
    double blasMs = clockms() - t0;
    if (blasCmd.error != nil) {
        rp("FATAL: BLAS build failed: %s", blasCmd.error.localizedDescription.UTF8String);
        return fatal_rc;
    }
    rp("[blas] triangle BLAS built in %.2f ms (as=%llu B, scratch=%llu B)",
       blasMs,
       (unsigned long long)blasSizes.accelerationStructureSize,
       (unsigned long long)blasSizes.buildScratchBufferSize);

    // ---- TLAS: one instance, identity transform, mask 0xFFFFFFFF --
    MTLAccelerationStructureInstanceDescriptor instance;
    memset(&instance, 0, sizeof(instance));
    static const float kIdentityColumns[12] = {
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f,
        0.0f, 0.0f, 0.0f,
    };
    memcpy(&instance.transformationMatrix, kIdentityColumns, sizeof(kIdentityColumns));
    instance.options = MTLAccelerationStructureInstanceOptionNone;
    instance.mask = 0xFFFFFFFFu;
    instance.intersectionFunctionTableOffset = 0;
    instance.accelerationStructureIndex = 0;

    id<MTLBuffer> instanceBuffer =
        [device newBufferWithBytes:&instance length:sizeof(instance)
                            options:MTLResourceStorageModeShared];

    MTLInstanceAccelerationStructureDescriptor *tlasDescriptor =
        [MTLInstanceAccelerationStructureDescriptor descriptor];
    tlasDescriptor.instanceCount = 1;
    tlasDescriptor.instanceDescriptorBuffer = instanceBuffer;
    tlasDescriptor.instanceDescriptorBufferOffset = 0;
    tlasDescriptor.instanceDescriptorStride = sizeof(MTLAccelerationStructureInstanceDescriptor);
    tlasDescriptor.instancedAccelerationStructures = @[ blas ];

    t0 = clockms();
    MTLAccelerationStructureSizes tlasSizes =
        [device accelerationStructureSizesWithDescriptor:tlasDescriptor];
    id<MTLAccelerationStructure> tlas =
        [device newAccelerationStructureWithSize:tlasSizes.accelerationStructureSize];
    id<MTLBuffer> tlasScratch =
        [device newBufferWithLength:tlasSizes.buildScratchBufferSize
                             options:MTLResourceStorageModeShared];

    id<MTLCommandBuffer> tlasCmd = [queue commandBuffer];
    id<MTLAccelerationStructureCommandEncoder> tlasEnc =
        [tlasCmd accelerationStructureCommandEncoder];
    [tlasEnc buildAccelerationStructure:tlas
                              descriptor:tlasDescriptor
                           scratchBuffer:tlasScratch
                       scratchBufferOffset:0];
    [tlasEnc endEncoding];
    [tlasCmd commit];
    [tlasCmd waitUntilCompleted];
    double tlasMs = clockms() - t0;
    if (tlasCmd.error != nil) {
        rp("FATAL: TLAS build failed: %s", tlasCmd.error.localizedDescription.UTF8String);
        return fatal_rc;
    }
    rp("[tlas] instance TLAS built in %.2f ms (as=%llu B)",
       tlasMs, (unsigned long long)tlasSizes.accelerationStructureSize);

    // ---- kernel library + pipeline -------------------------------
    NSError *error = nil;
    id<MTLLibrary> library =
        [device newLibraryWithURL:[NSURL fileURLWithPath:
            [NSString stringWithUTF8String:metallibPath]] error:&error];
    if (library == nil) {
        rp("FATAL: cannot load metallib '%s': %s", metallibPath,
           error.localizedDescription.UTF8String);
        return fatal_rc;
    }
    id<MTLFunction> kernel = [library newFunctionWithName:@"probe_trace_kernel"];
    if (kernel == nil) {
        rp("FATAL: probe_trace_kernel not found in metallib");
        return fatal_rc;
    }

    t0 = clockms();
    id<MTLComputePipelineState> pipeline =
        [device newComputePipelineStateWithFunction:kernel error:&error];
    double pipelineMs = clockms() - t0;
    if (pipeline == nil) {
        rp("FATAL: ray_query pipeline creation failed: %s",
           error.localizedDescription.UTF8String);
        return fatal_rc;
    }
    rp("[pipeline] ray_query compute pipeline created in %.2f ms "
       "(threadsPerThreadgroup=%u)",
       pipelineMs, (uint32_t)pipeline.maxTotalThreadsPerThreadgroup);

    // Optional datapoint: runtime MSL compilation (shaderpack path).
    if (mslSourcePath != NULL) {
        NSString *source =
            [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:mslSourcePath]
                                        encoding:NSUTF8StringEncoding error:nil];
        if (source != nil) {
            NSError *runtimeError = nil;
            id<MTLLibrary> runtimeLibrary =
                [device newLibraryWithSource:source options:nil error:&runtimeError];
            if (runtimeLibrary != nil) {
                rp("[runtime-compile] newLibraryWithSource(<metal_raytracing>): OK "
                   "(shaderpack 路线在真机可用)");
            } else {
                rp("[runtime-compile] newLibraryWithSource(<metal_raytracing>): FAIL (%s)",
                   runtimeError.localizedDescription.UTF8String);
            }
        }
    }

    // ---- rays: half mask 0xFF (hit), half 0x00 (masked miss) -----
    ProbeRay *rays = malloc(kRayCount * sizeof(ProbeRay));
    if (rays == NULL) {
        rp("FATAL: ray host allocation failed");
        return fatal_rc;
    }
    for (uint32_t i = 0; i < kRayCount; i++) {
        rays[i].origin = simd_make_float3(0.25f, 0.25f, 5.0f);
        rays[i].direction = simd_make_float3(0.0f, 0.0f, -1.0f);
        rays[i].mask = (i < kRayCount / 2) ? 0xFFFFFFFFu : 0x00000000u;
        rays[i].pad = 0;
    }
    id<MTLBuffer> rayBuffer =
        [device newBufferWithBytes:rays length:kRayCount * sizeof(ProbeRay)
                            options:MTLResourceStorageModeShared];
    free(rays);

    uint32_t rayCount = kRayCount;
    id<MTLBuffer> countBuffer =
        [device newBufferWithBytes:&rayCount length:sizeof(rayCount)
                            options:MTLResourceStorageModeShared];
    id<MTLBuffer> resultBuffer =
        [device newBufferWithLength:kRayCount * sizeof(ProbeResult)
                             options:MTLResourceStorageModeShared];
    memset(resultBuffer.contents, 0, kRayCount * sizeof(ProbeResult));

    // ---- dispatch -------------------------------------------------
    t0 = clockms();
    id<MTLCommandBuffer> traceCmd = [queue commandBuffer];
    id<MTLComputeCommandEncoder> traceEnc = [traceCmd computeCommandEncoder];
    [traceEnc setComputePipelineState:pipeline];
    [traceEnc setBuffer:rayBuffer offset:0 atIndex:0];
    [traceEnc setBuffer:resultBuffer offset:0 atIndex:1];
    [traceEnc setBuffer:countBuffer offset:0 atIndex:2];
    [traceEnc setAccelerationStructure:tlas atBufferIndex:3];
    NSUInteger threadsPerGroup = 64;
    MTLSize groupSize = MTLSizeMake(threadsPerGroup, 1, 1);
    MTLSize groupCount =
        MTLSizeMake((kRayCount + threadsPerGroup - 1) / threadsPerGroup, 1, 1);
    [traceEnc dispatchThreadgroups:groupCount threadsPerThreadgroup:groupSize];
    [traceEnc endEncoding];
    [traceCmd commit];
    [traceCmd waitUntilCompleted];
    double traceMs = clockms() - t0;
    if (traceCmd.error != nil) {
        rp("FATAL: trace dispatch failed: %s", traceCmd.error.localizedDescription.UTF8String);
        return fatal_rc;
    }
    rp("[trace] %u rays in %.2f ms (%.2f M rays/s) —— 真机硬件 RT 吞吐数据",
       kRayCount, traceMs, kRayCount / traceMs / 1000.0);

    // ---- verify ---------------------------------------------------
    const ProbeResult *results = resultBuffer.contents;
    uint32_t hitOk = 0;
    uint32_t missOk = 0;
    uint32_t bad = 0;
    for (uint32_t i = 0; i < kRayCount; i++) {
        if (i < kRayCount / 2) {
            if (results[i].hit == 1 && fabsf(results[i].distance - 5.0f) < 1e-3f) {
                hitOk++;
            } else {
                if (bad < 5) {
                    rp("[mismatch] ray %u: expected hit@5.0 got hit=%u dist=%f prim=%u",
                       i, results[i].hit, results[i].distance, results[i].primitive_id);
                }
                bad++;
            }
        } else {
            if (results[i].hit == 0) {
                missOk++;
            } else {
                if (bad < 5) {
                    rp("[mismatch] ray %u: expected miss got hit=%u dist=%f",
                       i, results[i].hit, results[i].distance);
                }
                bad++;
            }
        }
    }
    rp("[verify] hit-rays correct: %u/%u, masked-miss rays correct: %u/%u",
       hitOk, kRayCount / 2, missOk, kRayCount / 2);
    NSLog(@"[MetalProbe] verify hit=%u/%u miss=%u/%u",
          hitOk, kRayCount / 2, missOk, kRayCount / 2);

    if (hitOk == kRayCount / 2 && missOk == kRayCount / 2) {
        rp("VERDICT: METAL_RT_OK (BLAS + TLAS + ray_query + mask culling + distances, "
           "in-process, 与渲染主链零干扰)");
        return 0;
    }
    rp("VERDICT: METAL_RT_MISMATCH");
    return 1;
}

// ------------------------- JNI entry -------------------------

JNIEXPORT jint JNICALL
Java_net_prisma_metalprobe_Native_nativeRunProbe(JNIEnv *env, jclass clazz,
                                                 jstring metallibPathJ,
                                                 jstring mslSourcePathJ,
                                                 jstring reportPathJ)
{
    (void)clazz;
    const char *metallibPath = (*env)->GetStringUTFChars(env, metallibPathJ, NULL);
    const char *mslSourcePath = (mslSourcePathJ != NULL)
        ? (*env)->GetStringUTFChars(env, mslSourcePathJ, NULL) : NULL;
    const char *reportPath = (*env)->GetStringUTFChars(env, reportPathJ, NULL);

    g_report = fopen(reportPath, "a");
    if (g_report == NULL) {
        // 报告文件打不开 —— 无法取证，直接判 FATAL
        jint rc = 2;
        if (metallibPath != NULL) { (*env)->ReleaseStringUTFChars(env, metallibPathJ, metallibPath); }
        if (mslSourcePath != NULL) { (*env)->ReleaseStringUTFChars(env, mslSourcePathJ, mslSourcePath); }
        if (reportPath != NULL) { (*env)->ReleaseStringUTFChars(env, reportPathJ, reportPath); }
        return rc;
    }

    jint rc = 2;
    @autoreleasepool {
        rc = (jint)run_probe(metallibPath, mslSourcePath);
    }

    fflush(g_report);
    fclose(g_report);
    g_report = NULL;

    if (metallibPath != NULL) { (*env)->ReleaseStringUTFChars(env, metallibPathJ, metallibPath); }
    if (mslSourcePath != NULL) { (*env)->ReleaseStringUTFChars(env, mslSourcePathJ, mslSourcePath); }
    if (reportPath != NULL) { (*env)->ReleaseStringUTFChars(env, reportPathJ, reportPath); }
    return rc;
}
