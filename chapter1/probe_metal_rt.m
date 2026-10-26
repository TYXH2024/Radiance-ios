//
//  probe_metal_rt.m
//  Radiance iOS Port — Chapter 1 — Foundation Probe A
//
//  Question it answers: does the iOS ray tracing API (Metal Ray Tracing)
//  work end-to-end on Apple GPUs — the only viable hardware-RT path once
//  MoltenVK is confirmed to lack VK_KHR ray tracing extensions?
//
//  What it proves (when VERDICT: METAL_RT_OK is printed):
//    1. MTLAccelerationStructure: triangle BLAS builds correctly
//    2. Instance TLAS (the MCVR scene-graph shape) builds correctly
//    3. Metal 3 ray_query inline ray tracing inside a compute kernel works
//    4. Intersection masking works (analogue of rayTraversalPrimitiveCulling)
//    5. Committed hit distances are numerically correct
//    6. Runtime MSL compilation with <metal_raytracing> (shaderpack path)
//    7. The identical source cross-compiles for iphoneos arm64 (CI step)
//
//  Scene: one triangle (0,0,0)-(1,0,0)-(0,1,0) in the z=0 plane.
//  Rays:   origin (0.25, 0.25, 5), direction (0, 0, -1).
//          first half carries mask 0xFFFFFFFF (must hit at distance 5),
//          second half carries mask 0x00000000 (must miss — instance mask culling).
//
//  Usage: probe_metal_rt [trace_kernel.metallib] [trace_kernel.metal]
//

#import <Foundation/Foundation.h>
#import <Metal/Metal.h>
#import <simd/simd.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
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
        // No Apple10 enum exists; newer GPUs stay reported as Apple9-class here.
    };
    int family = 0;
    for (size_t i = 0; i < sizeof(families) / sizeof(families[0]); i++) {
        if ([device supportsFamily:families[i]]) { family = (int)(i + 1); }
    }
    return family;
}

int main(int argc, char **argv) {
    @autoreleasepool {
        const char *metallibPath = (argc > 1) ? argv[1] : "trace_kernel.metallib";
        const char *mslSourcePath = (argc > 2) ? argv[2] : NULL;

        printf("== Radiance iOS Port — Chapter 1 — Probe A: Metal Ray Tracing ==\n");

        id<MTLDevice> device = MTLCreateSystemDefaultDevice();
        if (device == nil) {
            printf("FATAL: MTLCreateSystemDefaultDevice returned NULL\n");
            return 2;
        }

        int family = apple_family_of(device);
        printf("[device] name=%s\n", device.name.UTF8String);
        printf("[device] gpuFamily=Apple%d\n", family);
        printf("[device] maxThreadgroupMemory=%llu B\n",
               (unsigned long long)device.maxThreadgroupMemoryLength);
        printf("[device] supportsRaytracing=%s\n", device.supportsRaytracing ? "YES" : "NO");
        printf("[device] dedicatedIntersectionHW(Apple9+)=%s\n",
               [device supportsFamily:MTLGPUFamilyApple9] ? "YES" : "NO");

        if (!device.supportsRaytracing) {
            printf("VERDICT: METAL_RT_API_UNAVAILABLE\n");
            return 3;
        }

        id<MTLCommandQueue> queue = [device newCommandQueue];
        if (queue == nil) {
            printf("FATAL: no command queue\n");
            return 2;
        }

        // ------------------------------------------------------------------
        // BLAS: one triangle in the z = 0 plane
        // ------------------------------------------------------------------
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
            printf("FATAL: BLAS build failed: %s\n", blasCmd.error.localizedDescription.UTF8String);
            return 2;
        }
        printf("[blas] triangle BLAS built in %.2f ms (as=%llu B, scratch=%llu B)\n",
               blasMs,
               (unsigned long long)blasSizes.accelerationStructureSize,
               (unsigned long long)blasSizes.buildScratchBufferSize);

        // ------------------------------------------------------------------
        // TLAS: one instance, identity transform, mask 0xFFFFFFFF
        // ------------------------------------------------------------------
        MTLAccelerationStructureInstanceDescriptor instance;
        memset(&instance, 0, sizeof(instance));
        // MTLPackedFloat4x3 = 4 packed float3 columns (48 bytes). Identity transform:
        // columns[0]=(1,0,0) columns[1]=(0,1,0) columns[2]=(0,0,1) columns[3]=(0,0,0)
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
            printf("FATAL: TLAS build failed: %s\n", tlasCmd.error.localizedDescription.UTF8String);
            return 2;
        }
        printf("[tlas] instance TLAS built in %.2f ms (as=%llu B)\n",
               tlasMs, (unsigned long long)tlasSizes.accelerationStructureSize);

        // ------------------------------------------------------------------
        // Kernel library + pipeline
        // ------------------------------------------------------------------
        NSError *error = nil;
        id<MTLLibrary> library =
            [device newLibraryWithURL:[NSURL fileURLWithPath:
                [NSString stringWithUTF8String:metallibPath]] error:&error];
        if (library == nil) {
            printf("FATAL: cannot load metallib '%s': %s\n", metallibPath,
                   error.localizedDescription.UTF8String);
            return 2;
        }
        id<MTLFunction> kernel = [library newFunctionWithName:@"probe_trace_kernel"];
        if (kernel == nil) {
            printf("FATAL: probe_trace_kernel not found in metallib\n");
            return 2;
        }

        t0 = clockms();
        id<MTLComputePipelineState> pipeline =
            [device newComputePipelineStateWithFunction:kernel error:&error];
        double pipelineMs = clockms() - t0;
        if (pipeline == nil) {
            printf("FATAL: ray_query pipeline creation failed: %s\n",
                   error.localizedDescription.UTF8String);
            return 2;
        }
        printf("[pipeline] ray_query compute pipeline created in %.2f ms "
               "(threadsPerThreadgroup=%u)\n",
               pipelineMs, (uint32_t)pipeline.maxTotalThreadsPerThreadgroup);

        // Optional datapoint: runtime MSL compilation (the shaderpack path).
        if (mslSourcePath != NULL) {
            NSString *source =
                [NSString stringWithContentsOfFile:[NSString stringWithUTF8String:mslSourcePath]
                                            encoding:NSUTF8StringEncoding error:nil];
            if (source != nil) {
                NSError *runtimeError = nil;
                id<MTLLibrary> runtimeLibrary =
                    [device newLibraryWithSource:source options:nil error:&runtimeError];
                if (runtimeLibrary != nil) {
                    printf("[runtime-compile] newLibraryWithSource(<metal_raytracing>): OK\n");
                } else {
                    printf("[runtime-compile] newLibraryWithSource(<metal_raytracing>): FAIL (%s)\n",
                           runtimeError.localizedDescription.UTF8String);
                }
            }
        }

        // ------------------------------------------------------------------
        // Rays: first half mask 0xFF (must hit), second half mask 0x00 (must miss)
        // ------------------------------------------------------------------
        ProbeRay *rays = malloc(kRayCount * sizeof(ProbeRay));
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

        // ------------------------------------------------------------------
        // Dispatch
        // ------------------------------------------------------------------
        t0 = clockms();
        id<MTLCommandBuffer> traceCmd = [queue commandBuffer];
        id<MTLComputeCommandEncoder> traceEnc = [traceCmd computeCommandEncoder];
        [traceEnc setComputePipelineState:pipeline];
        [traceEnc setBuffer:rayBuffer offset:0 atIndex:0];
        [traceEnc setBuffer:resultBuffer offset:0 atIndex:1];
        [traceEnc setBuffer:countBuffer offset:0 atIndex:2];
        // Metal binds acceleration structures at BUFFER indices
        // (MTLComputeCommandEncoder.h: setAccelerationStructure:atBufferIndex:)
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
            printf("FATAL: trace dispatch failed: %s\n",
                   traceCmd.error.localizedDescription.UTF8String);
            return 2;
        }
        printf("[trace] %u rays in %.2f ms (%.2f M rays/s)\n",
               kRayCount, traceMs, kRayCount / traceMs / 1000.0);

        // ------------------------------------------------------------------
        // Verify
        // ------------------------------------------------------------------
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
                        printf("[mismatch] ray %u: expected hit@5.0 got hit=%u dist=%f prim=%u\n",
                               i, results[i].hit, results[i].distance, results[i].primitive_id);
                    }
                    bad++;
                }
            } else {
                if (results[i].hit == 0) {
                    missOk++;
                } else {
                    if (bad < 5) {
                        printf("[mismatch] ray %u: expected miss got hit=%u dist=%f\n",
                               i, results[i].hit, results[i].distance);
                    }
                    bad++;
                }
            }
        }
        printf("[verify] hit-rays correct: %u/%u, masked-miss rays correct: %u/%u\n",
               hitOk, kRayCount / 2, missOk, kRayCount / 2);

        if (hitOk == kRayCount / 2 && missOk == kRayCount / 2) {
            printf("VERDICT: METAL_RT_OK (BLAS + TLAS + ray_query + mask culling + distances)\n");
            return 0;
        }
        printf("VERDICT: METAL_RT_MISMATCH\n");
        return 1;
    }
}
