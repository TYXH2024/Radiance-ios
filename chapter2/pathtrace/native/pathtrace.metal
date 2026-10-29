//
//  pathtrace.metal
//  Radiance iOS Port — Chapter 2 — Stage 2.3 — Live Path Tracing (v0.4)
//  v0.4.9 "CLARITY": ① indirect escapes no longer sample the sun disk
//                        (firefly path removed — see skyRadianceIndirect);
//                     ② present_kernel: Reinhard extended with white point
//                        W=2 (v0.4.8 plain Reinhard peaked at ~0.67 gray —
//                        the "washed-out haze" in the field screenshots).
//
//  Three kernels in one metallib:
//
//    scene_trace_kernel — single-ray query against the chunk TLAS.
//                         Inherited VERBATIM from 2.1 (proven on device in the
//                         metal-rt-probe 2.0 and scene-ingestion 2.1 field
//                         runs). Powers castRayNative (S6 spot check +
//                         center-ray cross-instrument check) and
//                         sentinelProbeNative (instance machinery proof).
//
//    pathtrace_kernel   — 2.3 ACCUMULATE mode: radiance is summed into a
//                         persistent float4 buffer across frames (progressive
//                         accumulation). u.resetFlag=1 stores (first frame
//                         after a camera move / scene change), 0 adds.
//                         Albedo is now atlas-textured: barycentric UV ->
//                         texture2d sample, multiplied by the MC vertex color
//                         (biome tint + baked face shading). A UV sentinel of
//                         (-1,-1) (layer without UV0) falls back to pure
//                         vertex color. When no atlas texture is bound the
//                         host binds a 1x1 white texture — identical to the
//                         2.2 vertex-color path by construction.
//
//    present_kernel     — 2.3 NEW: tonemap pass. Reads the accumulated float4
//                         buffer, divides by the accumulated sample count,
//                         applies Reinhard + gamma 2.2 and writes the RGBA8
//                         presentation image. Split from the trace kernel so
//                         tracing and presentation can be dispatched at
//                         different cadences (and so the S7 one-shot render
//                         reuses the exact same math: reset=1, sppTotal=spp).
//
//  Shading data pools (built CPU-side during every TLAS rebuild; pool order
//  == instance order, see metal_pathtrace.m rebuild_tlas):
//    buffer(3): triColors   — 3 x uchar4 per triangle. MC bakes block tinting
//                              (grass green, water blue, ...) into vertex
//                              colors — multiplied with the atlas texel.
//    buffer(4): triNormals  — 1 x packed_float3 per triangle (geometric face
//                              normal, computed CPU-side from the same vertex
//                              positions the BLAS was built from — zero MSL
//                              API risk, exact agreement by construction).
//    buffer(5): instTriBase — uint per instance: index of that instance's
//                              first triangle in the pools.
//    buffer(6): rayStats    — TWO atomic_uints: [0] every issued intersection
//                              query (the report's Mray/s is a MEASURED
//                              number); [1] camera-segment hits (camHit%).
//                              Reset by the host between frames (only while
//                              no kernel is in flight — see metal_pathtrace.m).
//    buffer(7): triUVs      — 2.3 NEW: 3 x packed_float2 per triangle
//                              (barycentric-interpolated atlas UVs, gathered
//                              from the vertex UV0 element through the
//                              quad-expanded index buffer; (-1,-1) per vertex
//                              for layers whose format lacks UV0).
//    texture(0): atlas      — 2.3 NEW: the MC block atlas (RGBA8), or the
//                              1x1 white fallback. Sampled with
//                              sampler(0) (linear, clamp-to-edge).
//
//  MSL raytracing API surface (triangle_data queries):
//    proven in 2.0/2.1: get_committed_intersection_type / _distance /
//                       _instance_id
//    proven in 2.2:      get_committed_primitive_id,
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
// pathtrace_kernel — 2.2 trace core + 2.3 accumulation & atlas albedo
// =====================================================================

// All-scalar layout — byte-identical to the C side in metal_pathtrace.m
// (no vector members: no C-vs-MSL padding surprises).
// 2.3 repurposes the padding scalars:
//   pad0 -> resetFlag   (1 = store, 0 = add; first frame after reset)
//   pad1 -> sppTotal    (accumulated samples per pixel — present_kernel)
struct PtUniforms {
    float camPosX, camPosY, camPosZ;
    float fwdX, fwdY, fwdZ;         // normalized camera forward
    float rightX, rightY, rightZ;   // normalized screen-right
    float upX, upY, upZ;            // normalized screen-up
    float tanHalfX, tanHalfY;       // tan(vfov/2)*aspect, tan(vfov/2)
    uint32_t width, height, spp, maxBounces;
    uint32_t instCount, resetFlag, sppTotal, pad2;
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

// Sky radiance seen by CAMERA rays (b==0 escapes). Keeps the sun disk.
// v0.4.9: disk access is now camera-only — see skyRadianceIndirect.
static inline float3 skyRadiance(float3 d) {
    float t = clamp(d.y * 0.5f + 0.5f, 0.0f, 1.0f);
    float3 horizon = float3(0.72f, 0.80f, 0.92f);
    float3 zenith  = float3(0.25f, 0.45f, 0.90f);
    float3 s = mix(horizon, zenith, t);
    if (dot(d, sunDirection()) > 0.9995f) {
        s += float3(8.0f, 7.2f, 6.0f);   // sun disk (camera rays only)
    }
    return s;
}

// v0.4.9: sky radiance for INDIRECT (b>0) escapes — sun disk removed.
// The sun's direct contribution is already carried by the NEE term
// (ndl * 2.2 toward the sun); letting cosine-sampled diffuse bounces hit
// the ~0.05%-solid-angle disk adds a huge-variance, firefly path: one hit
// deposits throughput * 8.0 into a single pixel — bright speckle that
// survives tens of thousands of accumulated spp (classic integral split:
// big contribution goes to NEE; tiny solid angle is never left to BSDF
// sampling). This was the worst converger in the v0.4.x field images.
static inline float3 skyRadianceIndirect(float3 d) {
    float t = clamp(d.y * 0.5f + 0.5f, 0.0f, 1.0f);
    return mix(float3(0.72f, 0.80f, 0.92f), float3(0.25f, 0.45f, 0.90f), t);
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
    device float4 *accum [[buffer(1)]],                       // 2.3: radiance accumulator
    raytracing::acceleration_structure<raytracing::instancing> scene [[buffer(2)]],
    device const uchar4 *triColors [[buffer(3)]],
    device const packed_float3 *triNormals [[buffer(4)]],
    device const uint *instTriBase [[buffer(5)]],
    device atomic_uint *rayStats [[buffer(6)]],
    device const packed_float2 *triUVs [[buffer(7)]],         // 2.3: atlas UVs
    texture2d<float> atlasTex [[texture(0)]],                  // 2.3: block atlas / white
    sampler atlasSampler [[sampler(0)]],
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
            atomic_fetch_add_explicit(rayStats, 1u, memory_order_relaxed);
            while (q.next()) {
                q.commit_triangle_intersection();
            }
            if (q.get_committed_intersection_type()
                    != raytracing::intersection_type::triangle) {
                // v0.4.9: camera rays keep the sun disk; indirect escapes
                // use the disk-less sky (firefly path removed).
                radiance += throughput
                          * (b == 0 ? skyRadiance(dir)
                                    : skyRadianceIndirect(dir));
                break;
            }
            if (b == 0) {
                // v0.3: camera-segment hit — the render's placement heartbeat.
                atomic_fetch_add_explicit(rayStats + 1, 1u,
                                          memory_order_relaxed);
            }

            const float dist = q.get_committed_distance();
            const uint32_t inst = q.get_committed_instance_id();
            const uint32_t prim = q.get_committed_primitive_id();
            const float2 bary = q.get_committed_triangle_barycentric_coord();
            const uint32_t tri = instTriBase[inst] + prim;

            float3 n = float3(triNormals[tri]);
            if (dot(n, dir) > 0.0f) { n = -n; }   // two-sided

            // Vertex color — barycentric interpolation (MC tint + face shade).
            float w0 = 1.0f - bary.x - bary.y;
            float3 vcol =
                  float3(triColors[tri * 3 + 0].rgb) * (w0      * (1.0f / 255.0f))
                + float3(triColors[tri * 3 + 1].rgb) * (bary.x  * (1.0f / 255.0f))
                + float3(triColors[tri * 3 + 2].rgb) * (bary.y  * (1.0f / 255.0f));
            vcol = clamp(vcol, 0.0f, 1.0f);

            // 2.3: atlas albedo — interpolated UV -> texture sample x vertex
            // color. UV sentinel (-1,-1) on any vertex of the triangle (the
            // sentinel is constant across the layer's vertices, so the
            // interpolation stays exactly (-1,-1)) selects the pure
            // vertex-color path. With no atlas bound the host binds a 1x1
            // white texture: texel == 1 and the product degenerates to the
            // 2.2 path by construction.
            float2 uv0 = float2(triUVs[tri * 3 + 0]);
            float2 uv1 = float2(triUVs[tri * 3 + 1]);
            float2 uv2 = float2(triUVs[tri * 3 + 2]);
            float2 uv = uv0 * w0 + uv1 * bary.x + uv2 * bary.y;
            float3 albedo;
            if (uv.x < 0.0f) {
                albedo = vcol;
            } else {
                float3 texel = atlasTex.sample(atlasSampler, uv).rgb;
                albedo = clamp(texel * vcol, 0.0f, 1.0f);
            }

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

    // 2.3: accumulate into the persistent buffer (one thread per pixel —
    // exclusive access, no atomics needed). resetFlag=1 stores.
    const uint32_t idx = (uint32_t)gid.y * u.width + (uint32_t)gid.x;
    if (u.resetFlag != 0u) {
        accum[idx] = float4(acc, 1.0f);
    } else {
        accum[idx] += float4(acc, 1.0f);
    }
}

// =====================================================================
// present_kernel — 2.3 NEW: accumulation -> tonemapped RGBA8
// =====================================================================
// Divides by the accumulated sample count, applies Reinhard + gamma 2.2.
// The S7 one-shot render dispatches this with resetFlag=1/sppTotal=spp —
// numerically identical to the 2.2 inline tonemap.
kernel void present_kernel(
    constant PtUniforms &u [[buffer(0)]],
    device const float4 *accum [[buffer(1)]],
    device uchar4 *image [[buffer(2)]],
    uint2 gid [[thread_position_in_grid]])
{
    if (gid.x >= u.width || gid.y >= u.height) { return; }
    const uint32_t idx = (uint32_t)gid.y * u.width + (uint32_t)gid.x;
    const float n = (float)(u.sppTotal < 1u ? 1u : u.sppTotal);
    float3 c = accum[idx].rgb / n;
    // v0.4.9: Reinhard extended, white point W=2: c*(1 + c/4)/(1 + c).
    // Plain Reinhard never reaches 1.0 — sky/sunlit faces peaked at ~0.67
    // gray in v0.4.8 field images (the "washed-out haze" the on-device
    // screenshots show). W=2 maps c=2 to pure white; midtones lift,
    // blacks are untouched (c<<1 term is identical), highlights gain real
    // contrast. Overshoot (c>2) clips at the clamp below — intended.
    c = c * (1.0f + c * 0.25f) / (1.0f + c);
    c = pow(c, float3(1.0f / 2.2f));          // gamma
    c = clamp(c, 0.0f, 1.0f);
    image[idx] = uchar4((uchar)(c.r * 255.0f + 0.5f),
                        (uchar)(c.g * 255.0f + 0.5f),
                        (uchar)(c.b * 255.0f + 0.5f),
                        255u);
}
