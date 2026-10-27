package net.prisma.pathtrace;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * intermediary 符号表（yarn 1.21.4+build.8 校验源：tools/yarn-mappings）。
 *
 * 轮询摄取路线（设计 §2-差异 1）不使用 mixin/refmap，全部经反射访问
 * MC 内部结构；每个符号都是运行时 intermediary 名。符号表集中于此，
 * S3 段逐项审计（解析失败 → REFLECT_FAIL，报告逐符号定位，不猜）。
 *
 * 命名对照（named ↔ intermediary）关键项：
 *   MinecraftClient           class_310    .world field_1687  .gameRenderer field_1773
 *                             .getInstance() method_1551
 *   ClientWorld               class_638    .worldRenderer field_17780
 *   WorldRenderer             class_761    .chunks field_4112 (BuiltChunkStorage)
 *                             .chunkBuilder field_45614 (ChunkBuilder)
 *   BuiltChunkStorage         class_769    .chunks field_4150 (BuiltChunk[])
 *                             .sizeX/Y/Z field_4148/4149/4147
 *   ChunkBuilder              class_846    .sectionBuilder field_52171 (SectionBuilder)
 *                             .world field_20831
 *   ChunkBuilder$BuiltChunk   class_846$class_851  .index field_29641
 *                             .origin field_4467  .data field_4459 (AtomicReference)
 *                             .getOrigin() method_3670  .getSectionPos() method_62975
 *   Vec3i (BlockPos 父类)     class_2382   .getX/getY/getZ method_10263/10264/10260
 *   BlockPos                  class_2338
 *   ChunkSectionPos           class_4076   .from(BlockPos) method_18682
 *   ChunkRendererRegionBuilder class_6850  .build(World, ChunkSectionPos) method_39969
 *   World                    class_1937   (build 首参类型，v0.2 纳入审计)
 *   SectionBuilder            class_9810   .build(sectionPos, region, sorter, storage)
 *                                          method_60904 → RenderData
 *   SectionBuilder$RenderData class_9810$class_9811 .buffers field_52168 (Map)
 *                                          .close() method_60905
 *   BuiltBuffer               class_9801   .getBuffer() method_60818 (ByteBuffer)
 *                             .getDrawParameters() method_60822
 *   DrawParameters            class_9801$class_4574 .format() comp_749
 *                             .vertexCount() comp_750  .mode() comp_752
 *   VertexFormat              class_293    .getVertexSizeByte() method_1362
 *   VertexSorter              class_8251   .BY_DISTANCE field_43360
 *   BlockBufferAllocatorStorage class_750  (无参构造)
 *   Camera                    class_4184   .getPos() method_19326 (Vec3d)
 *   Vec3d                     class_243    .x/.y/.z field_1352/1351/1350
 *                                          （v0.3 入审计：cameraPos 运行时
 *                                          使用但 v0.2 漏审计 → res() 静默
 *                                          null → NPE，S6 被跳过）
 *   GameRenderer              class_757    .camera field_18765
 */
final class Mappings {

    // ---- intermediary 类名 ----
    static final String C_MC              = "net.minecraft.class_310";
    static final String C_WORLD_RENDERER  = "net.minecraft.class_761";
    static final String C_STORAGE         = "net.minecraft.class_769";
    static final String C_CHUNK_BUILDER   = "net.minecraft.class_846";
    static final String C_BUILT_CHUNK     = "net.minecraft.class_846$class_851";
    static final String C_VEC3I           = "net.minecraft.class_2382";
    static final String C_BLOCK_POS       = "net.minecraft.class_2338";
    static final String C_SECTION_POS     = "net.minecraft.class_4076";
    static final String C_REGION_BUILDER  = "net.minecraft.class_6850";
    static final String C_REGION          = "net.minecraft.class_853";
    static final String C_SECTION_BUILDER = "net.minecraft.class_9810";
    static final String C_RENDER_DATA     = "net.minecraft.class_9810$class_9811";
    static final String C_BUILT_BUFFER    = "net.minecraft.class_9801";
    static final String C_DRAW_PARAMS     = "net.minecraft.class_9801$class_4574";
    static final String C_VERTEX_FORMAT   = "net.minecraft.class_293";
    static final String C_VERTEX_ELEM     = "net.minecraft.class_296"; // VertexFormatElement
    static final String C_VERTEX_SORTER   = "net.minecraft.class_8251";
    static final String C_ALLOC_STORAGE   = "net.minecraft.class_750";
    static final String C_CAMERA          = "net.minecraft.class_4184";
    static final String C_VEC3D           = "net.minecraft.class_243";
    static final String C_GAME_RENDERER   = "net.minecraft.class_757";
    static final String C_CLIENT_WORLD    = "net.minecraft.class_638";
    static final String C_WORLD           = "net.minecraft.class_1937";

    // ---- 字段 ----
    static final String F_MC_WORLD        = "field_1687";
    static final String F_MC_GAME_RENDERER = "field_1773";
    static final String F_CW_WORLD_RENDERER = "field_17780";
    static final String F_WR_CHUNKS       = "field_4112";
    static final String F_WR_CHUNK_BUILDER = "field_45614";
    static final String F_ST_CHUNKS       = "field_4150";
    static final String F_ST_SIZE_X       = "field_4148";
    static final String F_ST_SIZE_Y       = "field_4149";
    static final String F_ST_SIZE_Z       = "field_4147";
    static final String F_CB_SECTION_BUILDER = "field_52171";
    static final String F_BC_INDEX        = "field_29641";
    static final String F_BC_ORIGIN       = "field_4467";
    static final String F_BC_DATA         = "field_4459";
    static final String F_RD_BUFFERS      = "field_52168";
    static final String F_VS_BY_DISTANCE  = "field_43360"; // VertexSorter.BY_DISTANCE
    static final String F_CAMERA_POS      = "field_18765"; // GameRenderer.camera
    static final String F_VEC3D_X         = "field_1352";
    static final String F_VEC3D_Y         = "field_1351";
    static final String F_VEC3D_Z         = "field_1350";

    // ---- 方法 ----
    static final String M_MC_GET_INSTANCE = "method_1551";
    static final String M_BC_GET_ORIGIN   = "method_3670";
    static final String M_BC_GET_SECTION_POS = "method_62975";
    static final String M_VEC3I_GET_X     = "method_10263";
    static final String M_VEC3I_GET_Y     = "method_10264";
    static final String M_VEC3I_GET_Z     = "method_10260";
    static final String M_SP_FROM_BLOCKPOS = "method_18682";
    static final String M_RB_BUILD        = "method_39969";
    static final String M_SB_BUILD        = "method_60904";
    static final String M_RD_CLOSE        = "method_60905";
    static final String M_BB_GET_BUFFER   = "method_60818";
    static final String M_BB_GET_PARAMS   = "method_60822";
    static final String M_DP_FORMAT       = "comp_749";
    static final String M_DP_VERTEX_COUNT = "comp_750";
    static final String M_DP_MODE         = "comp_752";
    static final String M_VF_VERTEX_SIZE  = "method_1362";
    static final String M_VF_GET_OFFSET   = "method_60835"; // getOffset(VertexFormatElement)I
    static final String M_VF_HAS           = "method_60836"; // has(VertexFormatElement)Z
    static final String F_VE_COLOR         = "field_52108"; // VertexFormatElement.COLOR (static)
    static final String M_CAMERA_GET_POS  = "method_19326";
    static final String M_CAMERA_GET_YAW  = "method_19330"; // Camera.getYaw()F
    static final String M_CAMERA_GET_PITCH = "method_19329"; // Camera.getPitch()F

    // ---- 解析产物 ----
    static final Map<String, Object> RESOLVED = new LinkedHashMap<>();
    static final List<String> FAILURES = new ArrayList<>();
    static boolean resolved = false;

    private Mappings() {}

    /** S3 段审计：解析全部符号，逐项 PASS/FAIL。 */
    static synchronized boolean resolveAll(ProbeReport r) {
        if (resolved) {
            return FAILURES.isEmpty();
        }
        resolved = true;

        // 类（22 个目标；v0.2 增补 World/class_1937 —— RegionBuilder.build 首参）
        String[] classes = {
            C_MC, C_WORLD_RENDERER, C_STORAGE, C_CHUNK_BUILDER, C_BUILT_CHUNK, C_VEC3I,
            C_BLOCK_POS, C_SECTION_POS, C_REGION_BUILDER, C_REGION, C_SECTION_BUILDER,
            C_RENDER_DATA, C_BUILT_BUFFER, C_DRAW_PARAMS, C_VERTEX_FORMAT, C_VERTEX_ELEM,
            C_VERTEX_SORTER,
            C_ALLOC_STORAGE, C_CAMERA, C_VEC3D, C_GAME_RENDERER, C_CLIENT_WORLD, C_WORLD,
        };
        for (String cn : classes) {
            try {
                RESOLVED.put(cn, Class.forName(cn));
                r.pass("class " + cn);
            } catch (Throwable t) {
                FAILURES.add("class " + cn);
                r.fail("class " + cn + " 未找到");
            }
        }

        Class<?> mc = cls(C_MC);
        Class<?> cw = cls(C_CLIENT_WORLD);
        Class<?> wr = cls(C_WORLD_RENDERER);
        Class<?> st = cls(C_STORAGE);
        Class<?> cb = cls(C_CHUNK_BUILDER);
        Class<?> bc = cls(C_BUILT_CHUNK);
        Class<?> v3i = cls(C_VEC3I);
        Class<?> sp = cls(C_SECTION_POS);
        Class<?> rb = cls(C_REGION_BUILDER);
        Class<?> sb = cls(C_SECTION_BUILDER);
        Class<?> rd = cls(C_RENDER_DATA);
        Class<?> bb = cls(C_BUILT_BUFFER);
        Class<?> dp = cls(C_DRAW_PARAMS);
        Class<?> vf = cls(C_VERTEX_FORMAT);
        Class<?> ve = cls(C_VERTEX_ELEM);
        Class<?> vs = cls(C_VERTEX_SORTER);
        Class<?> cam = cls(C_CAMERA);
        Class<?> gr = cls(C_GAME_RENDERER);
        Class<?> v3d = cls(C_VEC3D); // Vec3d（cameraPos 读 x/y/z）
        Class<?> world = cls(C_WORLD); // World (RegionBuilder.build 首参)

        // 字段
        field(r, mc, F_MC_WORLD);
        field(r, mc, F_MC_GAME_RENDERER);
        field(r, cw, F_CW_WORLD_RENDERER);
        field(r, wr, F_WR_CHUNKS);
        field(r, wr, F_WR_CHUNK_BUILDER);
        field(r, st, F_ST_CHUNKS);
        field(r, st, F_ST_SIZE_X);
        field(r, st, F_ST_SIZE_Y);
        field(r, st, F_ST_SIZE_Z);
        field(r, cb, F_CB_SECTION_BUILDER);
        field(r, bc, F_BC_INDEX);
        field(r, bc, F_BC_ORIGIN);
        field(r, bc, F_BC_DATA);
        field(r, rd, F_RD_BUFFERS);
        field(r, vs, "field_43360");
        field(r, gr, F_CAMERA_POS);
        // v0.3：Vec3d x/y/z 入审计（cameraPos 运行时依赖；v0.2 缺席导致 S6 NPE）
        field(r, v3d, F_VEC3D_X);
        field(r, v3d, F_VEC3D_Y);
        field(r, v3d, F_VEC3D_Z);

        // 方法
        method(r, mc, M_MC_GET_INSTANCE);
        method(r, bc, M_BC_GET_ORIGIN);
        method(r, bc, M_BC_GET_SECTION_POS);
        method(r, v3i, M_VEC3I_GET_X);
        method(r, v3i, M_VEC3I_GET_Y);
        method(r, v3i, M_VEC3I_GET_Z);
        method(r, sp, M_SP_FROM_BLOCKPOS, bc2(C_BLOCK_POS));
        method(r, rb, M_RB_BUILD, world, sp);
        method(r, sb, M_SB_BUILD, sp, cls(C_REGION), vs, cls(C_ALLOC_STORAGE));
        method(r, rd, M_RD_CLOSE);
        method(r, bb, M_BB_GET_BUFFER);
        method(r, bb, M_BB_GET_PARAMS);
        method(r, dp, M_DP_FORMAT);
        method(r, dp, M_DP_VERTEX_COUNT);
        method(r, dp, M_DP_MODE);
        method(r, vf, M_VF_VERTEX_SIZE);
        method(r, vf, M_VF_GET_OFFSET, ve);
        method(r, vf, M_VF_HAS, ve);
        // VertexFormatElement.COLOR 静态字段（albedo 提取的运行时依赖）
        field(r, ve, F_VE_COLOR);
        method(r, cam, M_CAMERA_GET_POS);
        method(r, cam, M_CAMERA_GET_YAW);
        method(r, cam, M_CAMERA_GET_PITCH);

        // 构造（BlockBufferAllocatorStorage / ChunkRendererRegionBuilder 无参）
        ctor(r, C_ALLOC_STORAGE);
        ctor(r, C_REGION_BUILDER);

        return FAILURES.isEmpty();
    }

    private static void ctor(ProbeReport r, String cn) {
        try {
            Constructor<?> ctor = cls(cn).getConstructor();
            RESOLVED.put("ctor:" + cn, ctor);
            r.pass("ctor " + cn + "()");
        } catch (Throwable t) {
            FAILURES.add("ctor " + cn);
            r.fail("ctor " + cn + "() 未找到");
        }
    }

    private static Class<?> bc2(String name) {
        return cls(name);
    }

    static Class<?> cls(String name) {
        Class<?> c = (Class<?>) RESOLVED.get(name);
        if (c != null) {
            return c;
        }
        // v0.2 修复：v0.1 此处静默 fallback 返回 Object.class，导致 method_39969
        // 以错误参数类型 (Object, ChunkSectionPos) 查找而必然 NoSuchMethod——
        // 真机 REFLECT_FAIL 的根因（符号本身从未漂移）。未注册类现在显式
        // Class.forName 并缓存，解析失败显式报错，不再静默降级。
        try {
            c = Class.forName(name);
            RESOLVED.put(name, c);
        } catch (Throwable t) {
            throw new IllegalStateException("class 未注册且不可解析: " + name, t);
        }
        return c;
    }

    @SuppressWarnings("unchecked")
    static <T> T res(String key) {
        Object v = RESOLVED.get(key);
        if (v == null) {
            // v0.3 修复：v0.2 此处静默返回 null，下游 f.get()/m.invoke() 抛裸
            // NPE，真实原因（符号未审计）被掩盖（同家族：v0.1 的 cls() 静默降级
            // Object.class）。现在显式报错——运行时使用的每个符号必须在
            // resolveAll 审计列表内。
            throw new IllegalStateException("符号未解析（不在 S3 审计列表）: " + key);
        }
        return (T) v;
    }

    private static void field(ProbeReport r, Class<?> owner, String name) {
        String key = owner.getName() + "#" + name;
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            RESOLVED.put(key, f);
            r.pass("field " + key);
        } catch (Throwable t) {
            FAILURES.add(key);
            r.fail("field " + key + " 未找到");
        }
    }

    private static void method(ProbeReport r, Class<?> owner, String name, Class<?>... params) {
        String key = owner.getName() + "#" + name;
        try {
            Method m = owner.getDeclaredMethod(name, params);
            m.setAccessible(true);
            RESOLVED.put(key, m);
            r.pass("method " + key);
        } catch (Throwable t) {
            FAILURES.add(key);
            r.fail("method " + key + " 未找到（签名漂移？）");
        }
    }

    /** 反射取字段值（字段已审计；owner 以 intermediary 类名索引）。 */
    static Object get(Object owner, String ownerClassName, String fieldName) {
        try {
            Field f = res(ownerClassName + "#" + fieldName);
            return f.get(owner);
        } catch (Throwable t) {
            throw new IllegalStateException("field read " + fieldName, t);
        }
    }

    /** 反射调方法（方法已审计；owner 以 intermediary 类名索引）。 */
    static Object call(Object target, String ownerClassName, String methodName, Object... args) {
        try {
            Method m = res(ownerClassName + "#" + methodName);
            return m.invoke(target, args);
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            throw new IllegalStateException("method call " + methodName + ": " + c, c);
        }
    }
}
