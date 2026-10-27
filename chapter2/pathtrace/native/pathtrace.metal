//
//  pathtrace.metal
//  Radiance iOS Port — Chapter 2 — Stage 2.2 — Path Tracing Engine (v0.1)
//
//  Two kernels in one metallib:
//
//    scene_trace_kernel — single-ray query against the chunk TLAS.
//                         Inherited VERBATIM from 2.1 (proven on device in the
//                         metal-rt-probe 2.0 and scene-ingestion 2.1 field
//                         runs). Powers castRayNative (S6 spot check +
//                         center-ray cross-instrument check) and
//                         sentinelProbeNative (instance machinery proof).
//
//    pathtrace_kernel   — full offscreen path-traced frame:
//                         camera rays -> TLAS traversal -> MC vertex-color
//                         albedo (barycentric) -> sun direct + shadow ray ->
//                         cosine-weighted diffuse bounces -> accumulation ->
//                         Reinhard tonemap + gamma -> RGBA8.
//
//  Shading data pools (built CPU-side during every TLAS rebuild; pool order
//  == instance order, see metal_pathtrace.m rebuild_tlas):
//    buffer(3): triColors   — 3 x uchar4 per triangle. MC bakes block tinting
//                              (grass green, water blue, ...) into vertex
//                              colors — they ARE the albedo, no atlas needed.
//    buffer(4): triNormals  — 1 x packed_float3 per triangle (geometric face
//                              normal, computed CPU-side from the same vertex
//                              positions the BLAS was built from — zero MSL
//                              API risk, exact agreement by construction).
//    buffer(5): instTriBase — uint per instance: index of that instance's
//                              first triangle in the pools.
//    buffer(6): rayCount    — single atomic_uint; every issued intersection
//                              query increments it — the report's Mray/s is a
//                              MEASURED number, not an estimate.
//
//  MSL raytracing API surface (triangle_data queries):
//    proven in 2.0/2.1: get_committed_intersection_type / _distance /
//                       _instance_id
//    new in 2.2:        get_committed_primitive_id,
//                       get_committed_triangle_barycentric_coords
//
//  Placement contract (2.1 field-verified, v0.5): SectionBuilder vertices are
//  SECTION-LOCAL (v in [0..16]^3); BLAS stores them verbatim (no recentring);
//  TLAS instance transform = translate(origin). Rays are world-space.
//
#include <metal_stdlib>
#include <metal_raytracing>

using namespace metal;

// =====================================================================
// scene_trace_kernel — 2.1 verbatim
// =====================================================================

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

// =====================================================================
// pathtrace_kernel — 2.2 new
// =====================================================================

// All-scalar layout — byte-identical to the C side in metal_pathtrace.m
// (no vector members: no C-vs-MSL padding surprises).
struct PtUniforms {
    float camPosX, camPosY, camPosZ;
    float fwdX, fwdY, fwdZ;         // normalized camera forward
    float rightX, rightY, rightZ;   // normalized screen-right
    float upX, upY, upZ;            // normalized screen-up
    float tanHalfX, tanHalfY;       // tan(vfov/2)*aspect, tan(vfov/2)
    uint32_t width, height, spp, maxBounces;
    uint32_t instCount, pad0, pad1, pad2;
};

static inline uint32_t pcg_hash(uint32_t s) {
    s = s * 747796405u + 2891336453u;
    uint32_t w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}

static inline float rnd(thread uint32_t &state) {
    state = pcg_hash(state);
    return (float)state * (1.0f / 4294967296.0f);
}

static inline float3 sunDirection() {
    return normalize(float3(0.45f, 0.75f, 0.25f));
}

// Sky radiance seen by escaping rays (and by ambient shading).
static inline float3 skyRadiance(float3 d) {
    float t = clamp(d.y * 0.5f + 0.5f, 0.0f, 1.0f);
    float3 horizon = float3(0.72f, 0.80f, 0.92f);
    float3 zenith  = float3(0.25f, 0.45f, 0.90f);
    float3 s = mix(horizon, zenith, t);
    if (dot(d, sunDirection()) > 0.9995f) {
        s += float3(8.0f, 7.2f, 6.0f);   // sun disk
    }
    return s;
}

// Hemispheric sky irradiance approximation for surface shading.
static inline float3 skyAmbient(float3 n) {
    return float3(0.55f, 0.65f, 0.90f) * (0.55f + 0.45f * clamp(n.y, 0.0f, 1.0f));
}

// Any-hit occlusion query — the shadow ray. One candidate is enough:
// the scene is fully opaque in v0.1 (no alpha-tested transparency yet).
static inline bool occluded(
    raytracing::acceleration_structure<raytracing::instancing> scene,
    float3 origin, float3 dir, float maxDist)
{
    raytracing::intersection_params params;
    raytracing::intersection_query<raytracing::instancing, raytracing::triangle_data> q;
    q.reset(raytracing::ray(origin, dir, 0.001f, maxDist),
            scene, 0xFFFFFFFFu, params);
    if (q.next()) { return true; }   // candidate present = triangle hit
    return false;
}

// Cosine-weighted hemisphere sample around n.
static inline float3 cosineDir(float3 n, thread uint32_t &seed) {
    float r1 = rnd(seed);
    float r2 = rnd(seed);
    float a = 6.2831853f * r1;
    float r = sqrt(r2);
    float3 w = n;
    float3 helper = fabs(w.x) > 0.5f ? float3(0.0f, 1.0f, 0.0f)
                                     : float3(1.0f, 0.0f, 0.0f);
    float3 u = normalize(cross(helper, w));
    float3 v = cross(w, u);
    return normalize(u * (r * cos(a)) + v * (r * sin(a)) + w * sqrt(1.0f - r2));
}

kernel void pathtrace_kernel(
    constant PtUniforms &u [[buffer(0)]],
    device uchar4 *image [[buffer(1)]],
    raytracing::acceleration_structure<raytracing::instancing> scene [[buffer(2)]],
    device const uchar4 *triColors [[buffer(3)]],
    device const packed_float3 *triNormals [[buffer(4)]],
    device const uint *instTriBase [[buffer(5)]],
    device atomic_uint *rayCount [[buffer(6)]],
    uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= u.width || gid.y >= u.height) { return; }

    uint32_t seed = (uint32_t)(gid.y * u.width + gid.x) * 9781u + 6271u;
    seed = pcg_hash(seed);

    const float3 camPos = float3(u.camPosX, u.camPosY, u.camPosZ);
    const float3 fwd    = float3(u.fwdX, u.fwdY, u.fwdZ);
    const float3 right  = float3(u.rightX, u.rightY, u.rightZ);
    const float3 up     = float3(u.upX, u.upY, u.upZ);
    const float3 sunDir = sunDirection();

    float3 acc = float3(0.0f);
    for (uint32_t s = 0; s < u.spp; s++) {
        float px = ((float)gid.x + rnd(seed)) / (float)u.width  * 2.0f - 1.0f;
        float py = 1.0f - ((float)gid.y + rnd(seed)) / (float)u.height * 2.0f;
        float3 dir = normalize(fwd + right * (px * u.tanHalfX)
                                    + up    * (py * u.tanHalfY));
        float3 origin = camPos;
        float3 throughput = float3(1.0f);
        float3 radiance = float3(0.0f);

        for (uint32_t b = 0; b <= u.maxBounces; b++) {
            raytracing::intersection_params params;
            raytracing::intersection_query<raytracing::instancing, raytracing::triangle_data> q;
            q.reset(raytracing::ray(origin, dir, 0.001f, 1024.0f),
                    scene, 0xFFFFFFFFu, params);
            atomic_fetch_add_explicit(rayCount, 1u, memory_order_relaxed);
            while (q.next()) {
                q.commit_triangle_intersection();
            }
            if (q.get_committed_intersection_type()
                    != raytracing::intersection_type::triangle) {
                radiance += throughput * skyRadiance(dir);
                break;
            }

            const float dist = q.get_committed_distance();
            const uint32_t inst = q.get_committed_instance_id();
            const uint32_t prim = q.get_committed_primitive_id();
            const float2 bary = q.get_committed_triangle_barycentric_coords();
            const uint32_t tri = instTriBase[inst] + prim;

            float3 n = float3(triNormals[tri]);
            if (dot(n, dir) > 0.0f) { n = -n; }   // two-sided

            // Albedo — barycentric interpolation of MC vertex colors.
            float w0 = 1.0f - bary.x - bary.y;
            float3 albedo =
                  float3(triColors[tri * 3 + 0].rgb) * (w0      * (1.0f / 255.0f))
                + float3(triColors[tri * 3 + 1].rgb) * (bary.x  * (1.0f / 255.0f))
                + float3(triColors[tri * 3 + 2].rgb) * (bary.y  * (1.0f / 255.0f));
            albedo = clamp(albedo, 0.0f, 1.0f);

            const float3 hitP = origin + dir * dist;

            // Sun direct light + shadow ray (NEE toward the sun disk).
            float ndl = dot(n, sunDir);
            if (ndl > 0.0f
                    && !occluded(scene, hitP + n * 0.002f, sunDir, 1024.0f)) {
                radiance += throughput * albedo
                          * float3(1.0f, 0.95f, 0.85f) * (ndl * 2.2f);
            }

            // Hemispheric sky ambient (structure/AO comes from bounces).
            radiance += throughput * albedo * skyAmbient(n);

            // Diffuse bounce.
            if (b == u.maxBounces) { break; }
            throughput *= albedo;
            dir = cosineDir(n, seed);
            origin = hitP + n * 0.002f;
        }
        acc += radiance;
    }

    float3 c = acc / (float)u.spp;
    c = c / (1.0f + c);                       // Reinhard
    c = pow(c, float3(1.0f / 2.2f));          // gamma
    c = clamp(c, 0.0f, 1.0f);
    image[(uint32_t)gid.y * u.width + (uint32_t)gid.x] =
        uchar4((uchar)(c.r * 255.0f + 0.5f),
               (uchar)(c.g * 255.0f + 0.5f),
               (uchar)(c.b * 255.0f + 0.5f),
               255u);
}
