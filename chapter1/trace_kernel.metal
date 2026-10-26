//
//  trace_kernel.metal
//  Radiance iOS Port — Chapter 1 Foundation Probe
//
//  Inline ray tracing inside a plain compute kernel, traced against a
//  triangle BLAS + instance TLAS — the exact primitive the iOS port will
//  use to replace Vulkan ray queries (VK_KHR_ray_query semantics) on the
//  Metal side.
//
//  API surface verified against:
//    - SPIRV-Cross MSL reference output (shaders-msl/comp/ray-query*)
//      which is exactly the GLSL -> SPIR-V -> MSL chain the port needs
//    - MTLComputeCommandEncoder.h: setAccelerationStructure:atBufferIndex:
//      (AS binds to a BUFFER index; API_AVAILABLE(macos 11.0, ios 14.0))
//
#include <metal_stdlib>
#include <metal_raytracing>

using namespace metal;

// Layout mirrors the host-side structs in probe_metal_rt.m
// (float3 = 16 bytes in MSL, simd_float3 = 16 bytes on the host side).
struct ProbeRay {
    float3 origin;
    float3 direction;
    uint32_t mask;
    uint32_t pad;
};

struct ProbeResult {
    float distance;
    uint32_t primitive_id;
    uint32_t hit;
    uint32_t pad;
};

kernel void probe_trace_kernel(
    device const ProbeRay *rays [[buffer(0)]],
    device ProbeResult *results [[buffer(1)]],
    constant uint32_t &rayCount [[buffer(2)]],
    raytracing::acceleration_structure<raytracing::instancing> scene [[buffer(3)]],
    uint32_t gid [[thread_position_in_grid]])
{
    if (gid >= rayCount) { return; }

    device ProbeResult &out = results[gid];
    out.distance = -1.0;
    out.primitive_id = 0xFFFFFFFFu;
    out.hit = 0;

    raytracing::intersection_params params;
    raytracing::intersection_query<raytracing::instancing, raytracing::triangle_data> q;
    q.reset(ray(rays[gid].origin, rays[gid].direction, 0.0f, 1.0e9f),
            scene, rays[gid].mask, params);
    while (q.next()) {
        q.commit_triangle_intersection();
    }
    if (q.get_committed_intersection_type() == raytracing::intersection_type::triangle) {
        out.hit = 1;
        out.distance = q.get_committed_distance();
        out.primitive_id = uint32_t(q.get_committed_primitive_id());
    }
}
