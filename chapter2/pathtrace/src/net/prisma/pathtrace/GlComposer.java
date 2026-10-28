package net.prisma.pathtrace;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;

/**
 * GL 合成器（2.3 v0.4）——路径追踪画面第一次真正叠进游戏帧缓冲。
 *
 * 职责（全部经反射调用运行时 classpath 上的 LWJGL GL11/GL13/GL30——与
 * Pojav 环境的 OSMesa 软件光栅化共用同一上下文；本类只在此游戏的渲染
 * 线程被 PathtraceHook 调用）：
 *
 *   init          创建 RT 纹理（w×h RGBA8）+ 只读 FBO；记录 GL
 *                 vendor/version 证据行；保存/恢复 GL 状态纪律（active
 *                 texture、纹理绑定、FB 绑定、scissor、完整像素传输五元组）
 *   captureAtlas  反射链取块图集 GL 纹理 id（mc.getTextureManager() →
 *                 Identifier.of → getTexture → glId）→ 挂 FBO →
 *                 glReadPixels 回读到 native 暂存缓冲（PACK 五元组重置 +
 *                 native guard pad）→ uploadAtlasNative 变 MTLTexture；
 *                 任一环失败 → 降级纯顶点色（2.2 等价路径）
 *   present       每帧：分段 glTexSubImage2D 上传 RT 快照（每段 ≤64 行；
 *                 完整 UNPACK 状态归零——SKIP_* 遗留平移是 v0.4 花屏条
 *                 与 v0.4.2 SIGSEGV 的统一根因；Java 侧容量闸门）→
 *                 glBlitFramebuffer 整帧替换默认帧缓冲（RT → 窗口
 *                 分辨率，dst y 翻转补偿 GL 左下原点）
 *
 * GL 常量硬编码（GL 规范固定值，注释标注）——避免对 LWJGL 常量类的
 * 反射面扩大到字段级。
 */
final class GlComposer {

    // v0.4.5: 合成帧计数（静态 volatile——[tlas-health] 死亡时刻关联事件：
    // "死亡时已合成 N 帧"——TLAS 死亡与合成器帧序的相关性一步可见）。
    private static volatile long composeFrameCounter = 0;

    /** v0.4.5: 已合成帧数（健康监视器死亡行引用；无需锁——volatile 读）。 */
    static long composeFrames() {
        return composeFrameCounter;
    }

    // ---- GL 常量（规范固定值）----
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    private static final int GL_LINEAR = 0x2601;
    /** v0.4.2: NEAREST（0x2600，规范固定值）——LINEAR blit 出错时的自愈降级。 */
    private static final int GL_NEAREST = 0x2600;
    private static final int GL_TEXTURE_WRAP_S = 0x2802;
    private static final int GL_TEXTURE_WRAP_T = 0x2803;
    private static final int GL_CLAMP_TO_EDGE = 0x812F;
    private static final int GL_VIEWPORT = 0x0BA2;
    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int GL_SCISSOR_BOX = 0x0C10;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_FRAMEBUFFER = 0x8D40;
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;
    private static final int GL_COLOR_ATTACHMENT0 = 0x8CE0;
    private static final int GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
    private static final int GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
    private static final int GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA9;
    private static final int GL_COLOR_BUFFER_BIT = 0x4000;
    private static final int GL_TEXTURE_WIDTH = 0x1000;
    private static final int GL_TEXTURE_HEIGHT = 0x1001;
    private static final int GL_UNPACK_ROW_LENGTH = 0x0CF2;
    private static final int GL_UNPACK_ALIGNMENT = 0x0CF5;
    /** v0.4.3: SKIP_* 把 glTexSubImage2D 的源指针正向平移 skip 像素/行（规范
     * 固定值 0x0CF3/0x0CF4）——v0.4.2 只重置 ROW_LENGTH/ALIGNMENT 漏了它们。
     * MC/字体图集路径遗留的 SKIP 会把上传源平移出缓冲尾部：错位行（v0.4
     * 底部花屏条）与读源越界（v0.4.2 SIGSEGV，hs_err _platform_memmove
     * SEGV_ACCERR，317216+201184=518400 拼合铁证）同源。 */
    private static final int GL_UNPACK_SKIP_ROWS = 0x0CF3;
    private static final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;
    /** v0.4.3: 字节交换遗留同样污染传输（RGBA8+UBYTE 下无意义但状态仍在）。 */
    private static final int GL_UNPACK_SWAP_BYTES = 0x0CF0;
    /** v0.4.3: PACK 侧四元组（glReadPixels 的写目标同样会被 SKIP_* 平移
     * ——atlas 采集写越界的同源防御）。 */
    private static final int GL_PACK_SWAP_BYTES = 0x0D00;
    private static final int GL_PACK_ROW_LENGTH = 0x0D02;
    private static final int GL_PACK_SKIP_ROWS = 0x0D03;
    private static final int GL_PACK_SKIP_PIXELS = 0x0D04;
    private static final int GL_PACK_ALIGNMENT = 0x0D05;
    private static final int GL_VENDOR = 0x1F00;
    private static final int GL_RENDERER = 0x1F01;
    private static final int GL_VERSION = 0x1F02;

    private static final int MAX_ATLAS_DIM = 8192;

    // ---- 反射解析的 LWJGL 方法（init 时一次性解析缓存）----
    private Method mGenTextures;
    private Method mBindTexture;
    private Method mTexImage2D;
    private Method mTexSubImage2D;
    private Method mTexParameteri;
    private Method mPixelStorei;
    private Method mGetError;
    private Method mGetInteger;
    /** v0.4.2: glGetIntegerv(int, int[]) —— viewport/scissor box 四元组查询（软符号：缺失则几何行降级）。 */
    private Method mGetIntegerv;
    private Method mIsEnabled;
    private Method mEnable;
    private Method mDisable;
    private Method mScissor;
    private Method mGetString;
    private Method mReadPixels;
    private Method mGetTexLevelParameteriv;
    private Method mActiveTexture;
    private Method mGenFramebuffers;
    private Method mBindFramebuffer;
    private Method mFramebufferTexture2D;
    private Method mCheckFramebufferStatus;
    private Method mBlitFramebuffer;
    private Method mDeleteFramebuffers;

    private int rtTex = -1;
    private int rtFbo = -1;
    private int rtW = -1;
    private int rtH = -1;
    private int winW = -1;
    private int winH = -1;

    // ---- v0.4.2 GL 错误分类学（底部花屏/错误计数同源取证）----
    // v0.4.1 现场：glErrors=10 实为计数器封顶值（glErrors<10 才果加）——
    // 真实错误可能每帧都在发生（blit=3565 帧，屏幕底部 1/10 花屏条）。
    // 本版：逐操作探错（上传后/blit 后各一次）+ 错误码命名 + 每操作首例
    // 留行（含几何参数），计数不再封顶。
    private int firstPresentDone = 0;
    private boolean blitNearest = false;   // LINEAR blit 出错后的自愈降级
    private boolean blitFallbackLogged = false;
    private int texSubErrCount = 0;
    private int blitErrCount = 0;
    private int texSubErrLogged = 0;
    private int blitErrLogged = 0;
    /** v0.4.3: 分段上传行数（64 行 × 1920B = 122,880B/段——低于 zink
     * glthread marshal / transfer 路径常见的 128KB 分块粒度；v0.4.2 现场
     * 崩在单次 518,400B 大包上传。 */
    private static final int UPLOAD_ROWS_PER_CHUNK = 64;
    /** v0.4.3: 快照缓冲不足跳帧计数（LWJGL 3 不做 buffer 边界检查——
     * Java 侧闸门是防 SIGSEGV 的第一道）。 */
    private int shortBufCount = 0;
    private int shortBufLogged = 0;
    /** v0.4.3: present 计数（心跳行分子——SIGSEGV 前的最后可见状态）。 */
    private int presentCount = 0;
    // ---- v0.4.4 错误归因与黑屏根治 ----
    // v0.4.3 现场：texSubErr=2815/2816 帧全灭 ⇒ RT 纹理永黑（用户"黑块"）。
    // 直接成因：present 缺预排空 + 段级 break——进队早于本帧的错误（游戏侧
    // 渲染/上一帧恢复序列/本类 v0.4.3 新增的五元组读写）被本帧段 0 后的
    // 第一个 glGetError 误记到 texSubImage2D 头上，随即 break 弃帧。
    // v0.4.2 同类采样 89s 仅 10 错、v0.4.3 每帧必错——时间线直指每帧新增
    // 调用。本版四件套：预排空 / pname 掩码探测 / 首帧调用归因梯 / 段级
    // 重试（误记自愈，真失败才计——不再整帧弃帧）。
    private int drainCount = 0;
    private int drainLogged = 0;
    private int retryOkCount = 0;
    private int retryLogged = 0;
    private int chunkFailCount = 0;
    private boolean pnameProbeDone = false;
    /** v0.4.4: 驱动拒绝的 UNPACK pname 位掩码（b0=SKIP_PIXELS b1=SKIP_ROWS b2=SWAP_BYTES）。
     * 被拒的 pname 不可能持有污染值（驱动不受理写入）——读/重置/恢复三连全跳过。 */
    private int maskedPnames = 0;
    private static final int PN_SKIP_PX = 1;
    private static final int PN_SKIP_ROWS = 2;
    private static final int PN_SWAP = 4;
    /** 报告引用（init 时保存——present 的错误/几何留行用）。 */
    private ProbeReport rpt;

    private ProbeReport report() {
        return rpt != null ? rpt : PathtraceHook.silentReport();
    }

    // ------------------------------------------------------------------
    // 反射调用助手（primitive 装箱直通）
    // ------------------------------------------------------------------

    private static Object invoke(Method m, Object... args) throws Exception {
        return m.invoke(null, args);
    }

    private void call(Method m, Object... args) throws Exception {
        m.invoke(null, args);
    }

    private int callI(Method m, Object... args) throws Exception {
        return (Integer) m.invoke(null, args);
    }

    private boolean callZ(Method m, Object... args) throws Exception {
        return (Boolean) m.invoke(null, args);
    }

    /** v0.4.4: 排空错误队列（最多 max 条；返回排空条数——这些错误不归因后续操作）。 */
    private int drainErrors(int max) throws Exception {
        int n = 0;
        while (n < max) {
            if (callI(mGetError) == 0) {
                break;
            }
            n++;
        }
        return n;
    }

    private boolean pnMasked(int bit) {
        return (maskedPnames & bit) != 0;
    }

    private static String hex(int e) {
        return String.format("0x%04X", e);
    }

    private static Method method(Class<?> c, String name, Class<?>... params)
            throws NoSuchMethodException {
        Method m = c.getMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    // ------------------------------------------------------------------
    // 初始化（渲染线程，hook 首帧）
    // ------------------------------------------------------------------

    void init(ProbeReport r) throws Exception {
        rpt = r;
        Class<?> gl11 = Class.forName("org.lwjgl.opengl.GL11");
        Class<?> gl13 = Class.forName("org.lwjgl.opengl.GL13");
        Class<?> gl30 = Class.forName("org.lwjgl.opengl.GL30");

        mGenTextures = method(gl11, "glGenTextures");
        mBindTexture = method(gl11, "glBindTexture", int.class, int.class);
        mTexImage2D = method(gl11, "glTexImage2D",
                int.class, int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, ByteBuffer.class);
        mTexSubImage2D = method(gl11, "glTexSubImage2D",
                int.class, int.class, int.class, int.class, int.class, int.class,
                int.class, int.class, ByteBuffer.class);
        mTexParameteri = method(gl11, "glTexParameteri", int.class, int.class, int.class);
        mPixelStorei = method(gl11, "glPixelStorei", int.class, int.class);
        mGetError = method(gl11, "glGetError");
        mGetInteger = method(gl11, "glGetInteger", int.class);
        // v0.4.2: 四元组查询重载（软符号——LWJGL 3.3.x 为 glGetIntegerv(int, int[])）。
        try {
            mGetIntegerv = method(gl11, "glGetIntegerv", int.class, int[].class);
        } catch (Throwable t) {
            mGetIntegerv = null;
        }
        mIsEnabled = method(gl11, "glIsEnabled", int.class);
        mEnable = method(gl11, "glEnable", int.class);
        mDisable = method(gl11, "glDisable", int.class);
        mScissor = method(gl11, "glScissor", int.class, int.class, int.class, int.class);
        mGetString = method(gl11, "glGetString", int.class);
        mReadPixels = method(gl11, "glReadPixels",
                int.class, int.class, int.class, int.class, int.class, int.class,
                ByteBuffer.class);
        // v0.4.1 fix: LWJGL 3.3.3 has NO 3-arg glGetTexLevelParameteriv —
        // the level parameter is WRITTEN to a 4th output argument (int[] or
        // IntBuffer). v0.4 looked up (int,int,int) → NoSuchMethodException at
        // init → COMPOSE_DEAD on every v0.4 field run (verified against
        // lwjgl-opengl-3.3.3.jar via reflection audit: the only bad signature
        // in this table — all 21 other lookups are exact matches).
        mGetTexLevelParameteriv = method(gl11, "glGetTexLevelParameteriv",
                int.class, int.class, int.class, int[].class);
        mActiveTexture = method(gl13, "glActiveTexture", int.class);
        mGenFramebuffers = method(gl30, "glGenFramebuffers");
        mBindFramebuffer = method(gl30, "glBindFramebuffer", int.class, int.class);
        mFramebufferTexture2D = method(gl30, "glFramebufferTexture2D",
                int.class, int.class, int.class, int.class, int.class);
        mCheckFramebufferStatus = method(gl30, "glCheckFramebufferStatus", int.class);
        mBlitFramebuffer = method(gl30, "glBlitFramebuffer",
                int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class,
                int.class, int.class);
        mDeleteFramebuffers = method(gl30, "glDeleteFramebuffers", int.class);

        // GL 环境证据行
        // v0.4.2: 先排空错误队列——否则游戏侧遗留错误会被后续 err!=0 检查
        // 记到合成器头上（甚至直接判死 init）。排空条数留行，不计入 glErrors。
        int drained = 0;
        while (callI(mGetError) != 0 && drained < 8) {
            drained++;
        }
        if (drained > 0) {
            r.info(String.format("[S8.1] 排空 GL 错误队列: %d 条（游戏侧遗留，"
                    + "不归因本合成器——后续 [compose] 错误行均为本类操作）", drained));
        }
        String vendor = str(GL_VENDOR);
        String renderer = str(GL_RENDERER);
        String version = str(GL_VERSION);
        r.pass(String.format("[S8.1] GL 合成器初始化: vendor=%s renderer=%s version=%s",
                vendor, renderer, version));

        // ---- 会话尺寸（native 侧询问；mixin hook 先于首帧渲染是正常序）----
        int[] dims = null;
        try {
            dims = Native.liveDimsNative();
        } catch (Throwable ignored) {
            // liveBegin 之前的 hook 调用（不可能路径——arm 在 begin 之后，防御）
        }
        if (dims == null) {
            throw new IllegalStateException("live 会话未开启（liveDims=null）");
        }
        rtW = dims[0];
        rtH = dims[1];

        // ---- RT 纹理 + 只读 FBO（保存/恢复纪律：active unit + 绑定 + FB）----
        int savedActive = callI(mGetInteger, GL_ACTIVE_TEXTURE);
        int savedBinding = callI(mGetInteger, GL_TEXTURE_BINDING_2D);
        int savedUnpackRow = callI(mGetInteger, GL_UNPACK_ROW_LENGTH);
        int savedUnpackAlign = callI(mGetInteger, GL_UNPACK_ALIGNMENT);
        int savedReadFb = callI(mGetInteger, GL_READ_FRAMEBUFFER_BINDING);
        try {
            rtTex = callI(mGenTextures);
            call(mBindTexture, GL_TEXTURE_2D, rtTex);
            call(mTexImage2D, GL_TEXTURE_2D, 0, GL_RGBA8, rtW, rtH, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
            call(mTexParameteri, GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            call(mTexParameteri, GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            call(mTexParameteri, GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            call(mTexParameteri, GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

            rtFbo = callI(mGenFramebuffers);
            call(mBindFramebuffer, GL_READ_FRAMEBUFFER, rtFbo);
            call(mFramebufferTexture2D, GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, rtTex, 0);
            int status = callI(mCheckFramebufferStatus, GL_READ_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException(String.format(
                        "RT FBO 不完整 (status=0x%04X)", status));
            }
        } finally {
            call(mBindFramebuffer, GL_READ_FRAMEBUFFER, savedReadFb);
            call(mBindTexture, GL_TEXTURE_2D, savedBinding);
            call(mActiveTexture, savedActive);
            call(mPixelStorei, GL_UNPACK_ROW_LENGTH, savedUnpackRow);
            call(mPixelStorei, GL_UNPACK_ALIGNMENT, savedUnpackAlign);
        }
        int err = callI(mGetError);
        if (err != 0) {
            throw new IllegalStateException(String.format(
                    "GL 合成器初始化残留错误 0x%04X", err));
        }
        r.pass(String.format("[S8.1] RT 纹理+FBO 就绪: %dx%d RGBA8 (tex=%d fbo=%d)",
                rtW, rtH, rtTex, rtFbo));
    }

    // ------------------------------------------------------------------
    // atlas 采集（渲染线程，hook 首帧；best-effort——失败即顶点色回退）
    // ------------------------------------------------------------------

    void captureAtlas(ProbeReport r) {
        try {
            // ---- 反射链：mc.getTextureManager() → Identifier.of → getTexture → glId
            Object mc = Mappings.call(null, Mappings.C_MC, Mappings.M_MC_GET_INSTANCE);
            Object textureManager = Mappings.call(mc, Mappings.C_MC,
                    Mappings.M_MC_GET_TEXTURE_MANAGER);
            Object blockAtlasId = Mappings.call(null, Mappings.C_IDENTIFIER,
                    Mappings.M_IDENTIFIER_OF, "minecraft", "textures/atlas/blocks.png");
            Object atlasTexture = Mappings.call(textureManager, Mappings.C_TEXTURE_MANAGER,
                    Mappings.M_TM_GET_TEXTURE, blockAtlasId);
            if (atlasTexture == null) {
                r.info("[S8.2] atlas 采集降级：TextureManager.getTexture 返回 null"
                        + "（图集尚未注册？）—— albedo 回退纯顶点色");
                return;
            }
            java.lang.reflect.Field glIdF = Mappings.resOpt(
                    Mappings.C_ABSTRACT_TEXTURE + "#" + Mappings.F_TEX_GL_ID);
            if (glIdF == null) {
                r.info("[S8.2] atlas 采集降级：glId 符号缺失（[S8soft] 行）"
                        + "—— albedo 回退纯顶点色");
                return;
            }
            int glId = (Integer) glIdF.get(atlasTexture);
            if (glId <= 0) {
                r.info("[S8.2] atlas 采集降级：glId=" + glId + "（图集未上传 GL）"
                        + "—— albedo 回退纯顶点色");
                return;
            }

            // ---- 尺寸查询（glGetTexLevelParameteriv 需纹理绑定在当前单元；
            // 出参写进 int[1]——LWJGL 无返回值重载）----
            int savedActive = callI(mGetInteger, GL_ACTIVE_TEXTURE);
            int savedBinding = callI(mGetInteger, GL_TEXTURE_BINDING_2D);
            int[] box = new int[1];
            int w;
            int h;
            try {
                call(mBindTexture, GL_TEXTURE_2D, glId);
                call(mGetTexLevelParameteriv, GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH, box);
                w = box[0];
                call(mGetTexLevelParameteriv, GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT, box);
                h = box[0];
            } finally {
                call(mBindTexture, GL_TEXTURE_2D, savedBinding);
                call(mActiveTexture, savedActive);
            }
            if (w <= 0 || h <= 0 || w > MAX_ATLAS_DIM || h > MAX_ATLAS_DIM) {
                r.info(String.format("[S8.2] atlas 采集降级：异常尺寸 %dx%d"
                        + "—— albedo 回退纯顶点色", w, h));
                return;
            }

            // ---- native 暂存缓冲 → FBO attach → glReadPixels ----
            ByteBuffer staging = Native.liveAtlasBufferNative(w * h * 4);
            if (staging == null) {
                r.info("[S8.2] atlas 采集降级：native 暂存分配失败"
                        + "—— albedo 回退纯顶点色");
                return;
            }
            int savedReadFb = callI(mGetInteger, GL_READ_FRAMEBUFFER_BINDING);
            // v0.4.3: PACK 五元组重置（SKIP_* 平移 glReadPixels 写目标——
            // atlas 采集写越界与 texSubImage 读越界同源同防）。
            int savedPackRow = callI(mGetInteger, GL_PACK_ROW_LENGTH);
            int savedPackAlign = callI(mGetInteger, GL_PACK_ALIGNMENT);
            int savedPackSkipPx = callI(mGetInteger, GL_PACK_SKIP_PIXELS);
            int savedPackSkipRows = callI(mGetInteger, GL_PACK_SKIP_ROWS);
            int savedPackSwap = callI(mGetInteger, GL_PACK_SWAP_BYTES);
            int savedScissor = callZ(mIsEnabled, GL_SCISSOR_TEST) ? 1 : 0;
            try {
                call(mPixelStorei, GL_PACK_ALIGNMENT, 4);
                call(mPixelStorei, GL_PACK_ROW_LENGTH, 0);
                call(mPixelStorei, GL_PACK_SKIP_PIXELS, 0);
                call(mPixelStorei, GL_PACK_SKIP_ROWS, 0);
                call(mPixelStorei, GL_PACK_SWAP_BYTES, 0);
                if (savedScissor != 0) {
                    call(mDisable, GL_SCISSOR_TEST);
                }
                call(mBindFramebuffer, GL_READ_FRAMEBUFFER, rtFbo);
                // ---- v0.4.4: 排空 + PACK 回读验证——状态不可控就不读 ----
                // glReadPixels 的写目标若被 SKIP_* 平移，越界量级（行×宽×4）
                // 可远超 native 头尾 64KB guard，会打坏同设备 GPU 堆邻居
                // （TLAS 毒化悬案的头号嫌疑）——宁缺毋滥，直接顶点色回退。
                // 注：此处位于 rtFbo 绑定之后（finally 的重挂目标正确）。
                drainErrors(8);
                int vRow = callI(mGetInteger, GL_PACK_ROW_LENGTH);
                int vSkipPx = callI(mGetInteger, GL_PACK_SKIP_PIXELS);
                int vSkipRows = callI(mGetInteger, GL_PACK_SKIP_ROWS);
                int vErr = callI(mGetError);
                if (vErr != 0 || vRow != 0 || vSkipPx != 0 || vSkipRows != 0) {
                    r.fail(String.format(
                            "[S8.2] atlas 采集中止：PACK 状态不可控（row=%d "
                                    + "skipPx=%d skipRows=%d err=%s）——"
                                    + "glReadPixels 写目标被平移会打坏 GPU 堆"
                                    + "邻居（TLAS 毒化头号嫌疑），宁缺毋滥"
                                    + " → 顶点色回退",
                            vRow, vSkipPx, vSkipRows, hex(vErr)));
                    return;
                }
                call(mFramebufferTexture2D, GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_2D, glId, 0);
                int status = callI(mCheckFramebufferStatus, GL_READ_FRAMEBUFFER);
                if (status != GL_FRAMEBUFFER_COMPLETE) {
                    r.info(String.format("[S8.2] atlas 采集降级：attach 后 FBO 不完整"
                            + " (0x%04X)—— albedo 回退纯顶点色", status));
                    return;
                }
                call(mReadPixels, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, staging);
            } finally {
                // 恢复：atlas 纹理 detach（挂回 RT 纹理）+ FB/像素/裁剪状态
                call(mFramebufferTexture2D, GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                        GL_TEXTURE_2D, rtTex, 0);
                call(mBindFramebuffer, GL_READ_FRAMEBUFFER, savedReadFb);
                call(mPixelStorei, GL_PACK_ALIGNMENT, savedPackAlign);
                call(mPixelStorei, GL_PACK_ROW_LENGTH, savedPackRow);
                call(mPixelStorei, GL_PACK_SKIP_PIXELS, savedPackSkipPx);
                call(mPixelStorei, GL_PACK_SKIP_ROWS, savedPackSkipRows);
                call(mPixelStorei, GL_PACK_SWAP_BYTES, savedPackSwap);
                if (savedScissor != 0) {
                    call(mEnable, GL_SCISSOR_TEST);
                }
            }
            int err = callI(mGetError);
            if (err != 0) {
                r.info(String.format("[S8.2] atlas 采集降级：glReadPixels 残留错误 0x%04X"
                        + "—— albedo 回退纯顶点色", err));
                return;
            }

            // ---- 上传 MTLTexture（native；成功后 kernel 切 ATLAS_RGB）----
            boolean uploaded = Native.uploadAtlasNative(w, h);
            if (uploaded) {
                r.pass(String.format("[S8.2] atlas 采集 PASS: glId=%d %dx%d RGBA8 "
                        + "已上传 MTLTexture（albedo = texel × 顶点色）", glId, w, h));
            } else {
                r.info("[S8.2] atlas 采集降级：native 上传失败—— albedo 回退纯顶点色");
            }
        } catch (Throwable t) {
            r.info("[S8.2] atlas 采集降级（" + t + "）—— albedo 回退纯顶点色"
                    + "（2.2 等价路径，零回归风险）");
        }
    }

    // ------------------------------------------------------------------
    // 每帧合成（渲染线程）
    // ------------------------------------------------------------------

    void present(ByteBuffer rgba) throws Exception {
        // 窗口尺寸（反射链缓存；软符号缺失 → 以 RT 尺寸 1:1 blit 回退）
        if (winW <= 0) {
            resolveWindowDims();
        }
        final int dstW = winW > 0 ? winW : rtW;
        final int dstH = winH > 0 ? winH : rtH;
        presentCount++;
        composeFrameCounter++;

        // ---- v0.4.4: 预排空（进队早于本帧的错误一律不归因本类）----
        // 上见字段区注释——v0.4.3 的每帧 INVALID_ENUM → break 弃帧 = 黑屏。
        final int drained = drainErrors(8);
        if (drained > 0) {
            drainCount += drained;
            if (drainLogged < 2) {
                drainLogged++;
                report().info(String.format(
                        "[compose] 预排空 %d 条（进队早于本帧——游戏侧/恢复序列"
                                + "遗留，不归因上传；v0.4.3 把这类错误记到"
                                + " texSubImage2D 头上并弃帧 = 黑屏直接成因）",
                        drained));
            }
        }

        // ---- 污染取证（v0.4.4: 逐 pname 探错——读被拒即掩码，值不可信置 0）----
        // SKIP_* 非 0 = 现行犯：遗留 SKIP 把上传源指针平移 skip 像素/行——
        // v0.4 底部花屏条（错位行）与 v0.4.2 SIGSEGV（平移出缓冲尾部撞
        // 保护页）同源。首帧钉在报告里，读报告即知污染是否存在。
        final int preRow = callI(mGetInteger, GL_UNPACK_ROW_LENGTH);
        final int preAlign = callI(mGetInteger, GL_UNPACK_ALIGNMENT);
        int preSkipPx = 0;
        int preSkipRows = 0;
        int preSwap = 0;
        if (!pnMasked(PN_SKIP_PX)) {
            preSkipPx = callI(mGetInteger, GL_UNPACK_SKIP_PIXELS);
            if (callI(mGetError) != 0) {
                maskedPnames |= PN_SKIP_PX;
                preSkipPx = 0;
            }
        }
        if (!pnMasked(PN_SKIP_ROWS)) {
            preSkipRows = callI(mGetInteger, GL_UNPACK_SKIP_ROWS);
            if (callI(mGetError) != 0) {
                maskedPnames |= PN_SKIP_ROWS;
                preSkipRows = 0;
            }
        }
        if (!pnMasked(PN_SWAP)) {
            preSwap = callI(mGetInteger, GL_UNPACK_SWAP_BYTES);
            if (callI(mGetError) != 0) {
                maskedPnames |= PN_SWAP;
                preSwap = 0;
            }
        }
        if (firstPresentDone == 0) {
            firstPresentDone = 1;
            String skipPxStr = pnMasked(PN_SKIP_PX)
                    ? "N/A(拒读掩码)" : String.valueOf(preSkipPx);
            String skipRowsStr = pnMasked(PN_SKIP_ROWS)
                    ? "N/A(拒读掩码)" : String.valueOf(preSkipRows);
            report().pass(String.format(
                    "[compose] 捕获时像素传输状态: row=%d align=%d skipPx=%s "
                            + "skipRows=%s%s",
                    preRow, preAlign, skipPxStr, skipRowsStr,
                    ((!pnMasked(PN_SKIP_PX) && preSkipPx != 0)
                            || (!pnMasked(PN_SKIP_ROWS) && preSkipRows != 0))
                            ? "  ⚠ SKIP 污染坐实（花屏条/SIGSEGV 同源——本帧起归零重置）"
                            : "（skip=0——本轮上传源无平移）"));
        }

        // ---- v0.4.4 首帧：exotic pname 写侧探测（值已捕获，恢复有据）----
        // 写被拒 = 该 pname 驱动不受理 → 不可能被污染 → 读/重置/恢复三连
        // 全掩码。若 v0.4.3 的每帧 INVALID_ENUM 源于此，掩码即根治；若不是，
        // 下方首帧归因梯会点名真正的出生地。
        if (!pnameProbeDone) {
            pnameProbeDone = true;
            int[] pnConsts = {GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS,
                    GL_UNPACK_SWAP_BYTES};
            int[] pnBits = {PN_SKIP_PX, PN_SKIP_ROWS, PN_SWAP};
            String[] pnNames = {"SKIP_PIXELS", "SKIP_ROWS", "SWAP_BYTES"};
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pnConsts.length; i++) {
                if (pnMasked(pnBits[i])) {
                    sb.append(pnNames[i]).append("=拒读→掩码 ");
                    continue;
                }
                call(mPixelStorei, pnConsts[i], 0);
                int e = callI(mGetError);
                if (e != 0) {
                    maskedPnames |= pnBits[i];
                    sb.append(String.format("%s=拒写(%s)→掩码 ",
                            pnNames[i], hex(e)));
                } else {
                    sb.append(pnNames[i]).append("=ok ");
                }
            }
            report().info(String.format(
                    "[compose] pname 支持探测: %s——掩码=0x%X"
                            + "（被拒 pname 跳过读/重置/恢复三连；被拒即"
                            + "不可能被污染，无残留风险）",
                    sb.toString().trim(), maskedPnames));
        }

        // v0.4.2: 首帧几何取证一行——底部花屏条（v0.4.1 现场 ~1/10 屏高
        // 粉/黑/彩条纹）的两个头号嫌疑：dst 矩形 ≠ 实际帧缓冲尺寸（点/像素
        // 或缩放系数错位 → blit 只盖住部分）；或 blit 本身 GL_INVALID_*
        // 失败（残留旧画面/未初始化区）。此行把 src/dst/viewport/scissor
        // 全部钉在报告里，读图即知覆盖区与缺口。
        if (presentCount == 1) {
            report().pass(String.format(
                    "[compose] 首帧几何: RT=%dx%d win=%d(%s) dst=%dx%d "
                            + "blit_src=(0,0,%d,%d) blit_dst=(0,%d,%d,0)%s%s",
                    rtW, rtH, winW, winW > 0 ? "反射链" : "缺失→RT回退",
                    dstW, dstH, rtW, rtH, dstH, dstW,
                    viewportSuffix(), scissorSuffix()));
        }

        // ---- v0.4.3 心跳：每 256 帧一行——SIGSEGV 若复发，崩溃前最后可见状态。
        // v0.4.4: 新增 drained/retryOk/chunkFail/mask 四计数——黑屏/自愈/真失败
        // 的区分度直接上桌。----
        if (presentCount % 256 == 0) {
            report().info(String.format(
                    "[compose] 心跳 #%d: texSubErr=%d blitErr=%d shortBuf=%d "
                            + "drained=%d retryOk=%d chunkFail=%d mask=0x%X "
                            + "(RT=%dx%d chunks=%d行/段)",
                    presentCount, texSubErrCount, blitErrCount, shortBufCount,
                    drainCount, retryOkCount, chunkFailCount, maskedPnames,
                    rtW, rtH, UPLOAD_ROWS_PER_CHUNK));
        }

        // ---- v0.4.3: 容量闸门（LWJGL 3 不做 buffer 边界检查——不足即跳帧）----
        final int need = rtW * rtH * 4;
        if (rgba == null || rgba.remaining() < need) {
            shortBufCount++;
            if (shortBufLogged < 3) {
                shortBufLogged++;
                report().fail(String.format(
                        "[compose] 跳帧：快照缓冲不足 remaining=%d < %d "
                                + "(RT=%dx%dx4)——不调 GL（LWJGL 不查边界，"
                                + "native 读越界即 SIGSEGV）",
                        rgba != null ? rgba.remaining() : -1, need, rtW, rtH));
            }
            return;
        }

        // ---- 状态保存（v0.4.4: 掩码感知——被拒 pname 不读不恢复；
        // SKIP/SWAP 值已在顶部预排空后捕获，可信）----
        int savedActive = callI(mGetInteger, GL_ACTIVE_TEXTURE);
        int savedBinding = callI(mGetInteger, GL_TEXTURE_BINDING_2D);
        int savedUnpackRow = preRow;
        int savedUnpackAlign = preAlign;
        int savedUnpackSkipPx = preSkipPx;
        int savedUnpackSkipRows = preSkipRows;
        int savedUnpackSwap = preSwap;
        int savedReadFb = callI(mGetInteger, GL_READ_FRAMEBUFFER_BINDING);
        int savedDrawFb = callI(mGetInteger, GL_DRAW_FRAMEBUFFER_BINDING);
        boolean savedScissor = callZ(mIsEnabled, GL_SCISSOR_TEST);

        try {
            call(mPixelStorei, GL_UNPACK_ROW_LENGTH, 0);
            call(mPixelStorei, GL_UNPACK_ALIGNMENT, 4);
            if (!pnMasked(PN_SKIP_PX)) {
                call(mPixelStorei, GL_UNPACK_SKIP_PIXELS, 0);
            }
            if (!pnMasked(PN_SKIP_ROWS)) {
                call(mPixelStorei, GL_UNPACK_SKIP_ROWS, 0);
            }
            if (!pnMasked(PN_SWAP)) {
                call(mPixelStorei, GL_UNPACK_SWAP_BYTES, 0);
            }
            if (savedScissor) {
                call(mDisable, GL_SCISSOR_TEST);
            }

            // ---- 上传 RT 快照（v0.4.3: 分段；v0.4.4: 段级重试——
            // 首错先排空重打一次，误记错误自愈；重试仍错才计真失败并
            // 继续后续段（部分帧 >> 零帧——v0.4.3 的 break 是黑屏直接成因）----
            // v0.4.4 首帧归因梯：预排空后每步独立采样（0=净）——下一轮
            // 真机报告直接点名 INVALID_ENUM 的出生地（重置段/bind/段上传）。
            final boolean ladder = (presentCount == 1);
            int ladReset = 0, ladBind = 0, ladC0 = 0, ladC0r = 0, ladC1 = 0;
            if (ladder) {
                ladReset = callI(mGetError);
            }
            call(mBindTexture, GL_TEXTURE_2D, rtTex);
            if (ladder) {
                ladBind = callI(mGetError);
            }
            final int rowBytes = rtW * 4;
            for (int y = 0; y < rtH; y += UPLOAD_ROWS_PER_CHUNK) {
                final int rows = Math.min(UPLOAD_ROWS_PER_CHUNK, rtH - y);
                ByteBuffer chunk = rgba.duplicate();
                chunk.position(y * rowBytes);
                chunk.limit(y * rowBytes + rows * rowBytes);
                call(mTexSubImage2D, GL_TEXTURE_2D, 0, 0, y, rtW, rows,
                        GL_RGBA, GL_UNSIGNED_BYTE, chunk);
                int errChunk = callI(mGetError);
                if (ladder) {
                    if (y == 0) {
                        ladC0 = errChunk;
                    } else if (y == UPLOAD_ROWS_PER_CHUNK) {
                        ladC1 = errChunk;
                    }
                }
                if (errChunk != 0) {
                    // 排空后重试一次——排空前入队的错误不判刑
                    drainErrors(4);
                    call(mTexSubImage2D, GL_TEXTURE_2D, 0, 0, y, rtW, rows,
                            GL_RGBA, GL_UNSIGNED_BYTE, chunk);
                    int errRetry = callI(mGetError);
                    if (ladder && y == 0) {
                        ladC0r = errRetry;
                    }
                    if (errRetry == 0) {
                        retryOkCount++;
                        if (retryLogged < 3) {
                            retryLogged++;
                            report().pass(String.format(
                                    "[compose] 段上传误记自愈: y=%d rows=%d "
                                            + "重试后成功（首错 %s 为排空前"
                                            + "遗留误记，非上传失败）",
                                    y, rows, hex(errChunk)));
                        }
                    } else {
                        noteGlError(String.format("texSubImage2D[y=%d rows=%d]",
                                y, rows), errRetry, chunk);
                        chunkFailCount++;
                        // 不 break：该段跳过，其余段继续（部分帧 >> 零帧）
                    }
                }
            }
            if (ladder) {
                report().pass(String.format(
                        "[compose] 首帧调用归因梯: 重置=%s bind=%s 段0=%s "
                                + "段0重试=%s 段1=%s（0=净；非 0 即该步被"
                                + "驱动拒——INVALID_ENUM 出生地一步定谳）",
                        hex(ladReset), hex(ladBind), hex(ladC0),
                        hex(ladC0r), hex(ladC1)));
            }

            // ---- blit 整帧替换（dst y 翻转：GL 左下原点 vs 图像上下行主序）----
            // v0.4.2 自愈：LINEAR blit 首次报错 → 换 NEAREST 重试一次并记住
            // （缩放 blit 在个别驱动/格式组合下只允许 NEAREST——错误码行自证）。
            call(mBindFramebuffer, GL_READ_FRAMEBUFFER, rtFbo);
            call(mBindFramebuffer, GL_DRAW_FRAMEBUFFER, 0);
            int filter = blitNearest ? GL_NEAREST : GL_LINEAR;
            call(mBlitFramebuffer,
                    0, 0, rtW, rtH,          // src: full RT
                    0, dstH, dstW, 0,        // dst: full window, y-flipped
                    GL_COLOR_BUFFER_BIT, filter);
            int errBlit = callI(mGetError);
            if (errBlit != 0) {
                PathtraceHook.glErrors++;
                blitErrCount++;
                if (!blitNearest) {
                    blitNearest = true;
                    if (!blitFallbackLogged) {
                        blitFallbackLogged = true;
                        report().fail(String.format(
                                "[compose] blit GL 错误 %s(0x%04X) @ LINEAR"
                                        + " → 改用 NEAREST 重试（后续帧生效；"
                                        + "几何见 [compose] 首帧几何行）",
                                errName(errBlit), errBlit));
                    }
                    // 本次立即用 NEAREST 重打一遍（自愈当下帧）
                    call(mBlitFramebuffer,
                            0, 0, rtW, rtH,
                            0, dstH, dstW, 0,
                            GL_COLOR_BUFFER_BIT, GL_NEAREST);
                    callI(mGetError); // 重试后的错误不再重复计数
                } else if (blitErrLogged < 10) {
                    blitErrLogged++;
                    report().fail(String.format(
                            "[compose] blit GL 错误 %s(0x%04X) 第 %d 次"
                                    + "（NEAREST 模式下仍错——见首帧几何行）",
                            errName(errBlit), errBlit, blitErrCount));
                }
            }
        } finally {
            call(mBindFramebuffer, GL_DRAW_FRAMEBUFFER, savedDrawFb);
            call(mBindFramebuffer, GL_READ_FRAMEBUFFER, savedReadFb);
            call(mBindTexture, GL_TEXTURE_2D, savedBinding);
            call(mActiveTexture, savedActive);
            call(mPixelStorei, GL_UNPACK_ROW_LENGTH, savedUnpackRow);
            call(mPixelStorei, GL_UNPACK_ALIGNMENT, savedUnpackAlign);
            if (!pnMasked(PN_SKIP_PX)) {
                call(mPixelStorei, GL_UNPACK_SKIP_PIXELS, savedUnpackSkipPx);
            }
            if (!pnMasked(PN_SKIP_ROWS)) {
                call(mPixelStorei, GL_UNPACK_SKIP_ROWS, savedUnpackSkipRows);
            }
            if (!pnMasked(PN_SWAP)) {
                call(mPixelStorei, GL_UNPACK_SWAP_BYTES, savedUnpackSwap);
            }
            if (savedScissor) {
                call(mEnable, GL_SCISSOR_TEST);
            }
        }
    }

    /**
     * texSubImage2D 错误留行（v0.4.4: 重试后仍错 = 真失败——非误记；该段跳过
     * 其余段继续）。首例带段几何与缓冲规格。
     */
    private void noteGlError(String op, int err, ByteBuffer rgba) {
        PathtraceHook.glErrors++;
        texSubErrCount++;
        if (texSubErrLogged < 10) {
            texSubErrLogged++;
            int cap = rgba != null ? rgba.capacity() : -1;
            int rem = rgba != null ? rgba.remaining() : -1;
            report().fail(String.format(
                    "[compose] %s GL 错误 %s(0x%04X) 第 %d 次（重试后仍错 = "
                            + "真失败；tex=%d 规格=%dx%d 段 buf=%dB/%dB "
                            + "mask=0x%X——该段跳过，其余段继续）",
                    op, errName(err), err, texSubErrCount, rtTex, rtW, rtH,
                    rem, cap, maskedPnames));
        }
    }

    /** v0.4.2: GL 错误码命名（规范固定值——底部花屏与错误行直接对上号）。 */
    private static String errName(int err) {
        switch (err) {
            case 0x0500: return "GL_INVALID_ENUM";
            case 0x0501: return "GL_INVALID_VALUE";
            case 0x0502: return "GL_INVALID_OPERATION";
            case 0x0503: return "GL_STACK_OVERFLOW";
            case 0x0504: return "GL_STACK_UNDERFLOW";
            case 0x0505: return "GL_OUT_OF_MEMORY";
            case 0x0506: return "GL_INVALID_FRAMEBUFFER_OPERATION";
            case 0x0507: return "GL_CONTEXT_LOST";
            default: return "GL_ERROR_" + Integer.toHexString(err);
        }
    }

    /** 当前帧 viewport 四元组（软符号缺失 → 空串）。 */
    private String viewportSuffix() {
        int[] vp = vec4(GL_VIEWPORT);
        return vp != null ? String.format(" viewport=[%d,%d %dx%d]", vp[0], vp[1], vp[2], vp[3]) : "";
    }

    /** 当前帧 scissor 状态与盒子（首帧花屏定位：裁剪区是否吃掉底部）。 */
    private String scissorSuffix() {
        try {
            boolean on = callZ(mIsEnabled, GL_SCISSOR_TEST);
            int[] box = vec4(GL_SCISSOR_BOX);
            return on || box != null
                    ? String.format(" scissor=%s[%s]", on ? "ON" : "off",
                            box != null
                                    ? String.format("%d,%d %dx%d", box[0], box[1], box[2], box[3])
                                    : "?")
                    : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private int[] vec4(int pname) {
        if (mGetIntegerv == null) {
            return null;
        }
        try {
            int[] out = new int[4];
            mGetIntegerv.invoke(null, pname, out);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private void resolveWindowDims() {
        try {
            Object mc = Mappings.call(null, Mappings.C_MC, Mappings.M_MC_GET_INSTANCE);
            Object window = Mappings.call(mc, Mappings.C_MC, Mappings.M_MC_GET_WINDOW);
            int w = (Integer) Mappings.call(window, Mappings.C_WINDOW,
                    Mappings.M_WINDOW_GET_FB_WIDTH);
            int h = (Integer) Mappings.call(window, Mappings.C_WINDOW,
                    Mappings.M_WINDOW_GET_FB_HEIGHT);
            if (w > 0 && h > 0) {
                winW = w;
                winH = h;
            }
        } catch (Throwable ignored) {
            // 软符号缺失 → 1:1 blit 回退（初始化段 [S8soft] 行已注明降级）
        }
    }

    private String str(int name) {
        try {
            Object v = invoke(mGetString, name);
            return v != null ? v.toString() : "?";
        } catch (Throwable t) {
            return "?";
        }
    }
}
