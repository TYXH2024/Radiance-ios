//
//  trace_kernel.metal
//  Radiance iOS Port — Chapter 1 Foundation Probe
//
//  Metal 3 ray_query (inline ray tracing) inside a plain compute kernel,
//  traced against a triangle BLAS + instance TLAS.
//
//  This is the exact primitive the iOS port will use to replace
//  Vulkan ray tracing (VK_KHR_ray_query semantics) on the Metal side.
//
#include <metal_stdlib>
#include <metal_raytracing>

using namespace metal;
using namespace metal::raytracing;

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
    uint32_t geometry_id;
    uint32_t hit;
    uint32_t pad;
};

kernel void probe_trace_kernel(
    device const ProbeRay *rays [[buffer(0)]],
    device ProbeResult *results [[buffer(1)]],
    constant uint32_t &rayCount [[buffer(2)]],
    acceleration_structure<instancing> scene [[acceleration_structure(0)]],
    uint32_t gid [[thread_position_in_grid]])
{
    if (gid >= rayCount) { return; }

    device ProbeResult &out = results[gid];
    out.distance = -1.0;
    out.geometry_id = 0xFFFFFFFFu;
    out.hit = 0;

    ray tracedRay = ray(rays[gid].origin, rays[gid].direction);
    ray_query<intersection_type::triangle, instancing> rq;
    rq.initialize(tracedRay, rays[gid].mask);
    rq.trace(scene);
    while (rq.next()) {
        rq.commit_triangle_intersection();
    }
    if (rq.committed_intersection_type() != intersection_type::none) {
        out.hit = 1;
        out.distance = rq.committed_distance();
        out.geometry_id = rq.committed_geometry_id();
    }
}
