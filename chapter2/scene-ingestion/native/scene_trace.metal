//
//  scene_trace.metal
//  Radiance iOS Port — Chapter 2 — Stage 2.1 — Scene Ingestion Verification
//
//  Single-ray scene query against the dynamically rebuilt chunk TLAS.
//  API surface mirrors chapter2/metal-rt-probe trace_kernel.metal (verified
//  on device — intersection_query against acceleration_structure<instancing>)
//  with the instance id added for slot attribution.
//
#include <metal_stdlib>
#include <metal_raytracing>

using namespace metal;

struct SceneRay {
    float3 origin;
    float3 direction;
    float maxDistance;
    uint32_t pad;
};

struct SceneHit {
    float distance;
    uint32_t instanceIndex;
    uint32_t hit;
    uint32_t pad;
};

kernel void scene_trace_kernel(
    device const SceneRay *ray [[buffer(0)]],
    device SceneHit *out [[buffer(1)]],
    raytracing::acceleration_structure<raytracing::instancing> scene [[buffer(2)]],
    uint2 gid [[thread_position_in_grid]])
{
    if (gid.x != 0) { return; }

    out->distance = -1.0f;
    out->instanceIndex = 0xFFFFFFFFu;
    out->hit = 0;

    // Instances live in world space (transform = translate(origin)); rays are
    // issued in world space directly.
    raytracing::intersection_params params;
    raytracing::intersection_query<raytracing::instancing, raytracing::triangle_data> q;
    q.reset(raytracing::ray(ray->origin, ray->direction, 0.0f, ray->maxDistance),
            scene, 0xFFFFFFFFu, params);
    while (q.next()) {
        q.commit_triangle_intersection();
    }
    if (q.get_committed_intersection_type() == raytracing::intersection_type::triangle) {
        out->hit = 1;
        out->distance = q.get_committed_distance();
        out->instanceIndex = q.get_committed_instance_id();
    }
}
