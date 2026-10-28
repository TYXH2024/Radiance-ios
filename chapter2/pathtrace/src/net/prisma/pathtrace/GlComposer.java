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
 *                 texture、纹理绑定、FB 绑定、scissor、pixel store）
 *   captureAtlas  反射链取块图集 GL 纹理 id（mc.getTextureManager() →
 *                 Identifier.of → getTexture → glId）→ 挂 FBO →
 *                 glReadPixels 回读到 native 暂存缓冲 → uploadAtlasNative
 *                 变 MTLTexture；任一环失败 → 降级纯顶点色（2.2 等价路径）
 *   present       每帧：glTexSubImage2D 上传 RT 快照 → glBlitFramebuffer
 *                 整帧替换默认帧缓冲（RT → 窗口分辨率，dst y 翻转补偿
 *                 GL 左下原点）
 *
 * GL 常量硬编码（GL 规范固定值，注释标注）——避免对 LWJGL 常量类的
 * 反射面扩大到字段级。
 */
final class GlComposer {

    // ---- GL 常量（规范固定值）----
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_RGBA = 0x1908;
    private static final int GL_RGBA8 = 0x8058;
    private static final int GL_UNSIGNED_BYTE = 0x1401;
    private static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    private static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    private static final int GL_LINEAR = 0x2601;
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
        mIsEnabled = method(gl11, "glIsEnabled", int.class);
        mEnable = method(gl11, "glEnable", int.class);
        mDisable = method(gl11, "glDisable", int.class);
        mScissor = method(gl11, "glScissor", int.class, int.class, int.class, int.class);
        mGetString = method(gl11, "glGetString", int.class);
        mReadPixels = method(gl11, "glReadPixels",
                int.class, int.class, int.class, int.class, int.class, int.class,
                ByteBuffer.class);
        mGetTexLevelParameteriv = method(gl11, "glGetTexLevelParameteriv",
                int.class, int.class, int.class);
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

            // ---- 尺寸查询（glGetTexLevelParameteriv 需纹理绑定在当前单元）----
            int savedActive = callI(mGetInteger, GL_ACTIVE_TEXTURE);
            int savedBinding = callI(mGetInteger, GL_TEXTURE_BINDING_2D);
            int w;
            int h;
            try {
                call(mBindTexture, GL_TEXTURE_2D, glId);
                w = callI(mGetTexLevelParameteriv, GL_TEXTURE_2D, 0, GL_TEXTURE_WIDTH);
                h = callI(mGetTexLevelParameteriv, GL_TEXTURE_2D, 0, GL_TEXTURE_HEIGHT);
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
            int savedPackAlign = callI(mGetInteger, GL_PACK_ALIGNMENT);
            int savedScissor = callZ(mIsEnabled, GL_SCISSOR_TEST) ? 1 : 0;
            try {
                call(mPixelStorei, GL_PACK_ALIGNMENT, 4);
                if (savedScissor != 0) {
                    call(mDisable, GL_SCISSOR_TEST);
                }
                call(mBindFramebuffer, GL_READ_FRAMEBUFFER, rtFbo);
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

        // ---- 状态保存 ----
        int savedActive = callI(mGetInteger, GL_ACTIVE_TEXTURE);
        int savedBinding = callI(mGetInteger, GL_TEXTURE_BINDING_2D);
        int savedUnpackRow = callI(mGetInteger, GL_UNPACK_ROW_LENGTH);
        int savedUnpackAlign = callI(mGetInteger, GL_UNPACK_ALIGNMENT);
        int savedReadFb = callI(mGetInteger, GL_READ_FRAMEBUFFER_BINDING);
        int savedDrawFb = callI(mGetInteger, GL_DRAW_FRAMEBUFFER_BINDING);
        boolean savedScissor = callZ(mIsEnabled, GL_SCISSOR_TEST);

        try {
            call(mPixelStorei, GL_UNPACK_ROW_LENGTH, 0);
            call(mPixelStorei, GL_UNPACK_ALIGNMENT, 4);
            if (savedScissor) {
                call(mDisable, GL_SCISSOR_TEST);
            }

            // ---- 上传 RT 快照 ----
            call(mBindTexture, GL_TEXTURE_2D, rtTex);
            call(mTexSubImage2D, GL_TEXTURE_2D, 0, 0, 0, rtW, rtH,
                    GL_RGBA, GL_UNSIGNED_BYTE, rgba);

            // ---- blit 整帧替换（dst y 翻转：GL 左下原点 vs 图像上下行主序）----
            call(mBindFramebuffer, GL_READ_FRAMEBUFFER, rtFbo);
            call(mBindFramebuffer, GL_DRAW_FRAMEBUFFER, 0);
            call(mBlitFramebuffer,
                    0, 0, rtW, rtH,          // src: full RT
                    0, dstH, dstW, 0,        // dst: full window, y-flipped
                    GL_COLOR_BUFFER_BIT, GL_LINEAR);
        } finally {
            call(mBindFramebuffer, GL_DRAW_FRAMEBUFFER, savedDrawFb);
            call(mBindFramebuffer, GL_READ_FRAMEBUFFER, savedReadFb);
            call(mBindTexture, GL_TEXTURE_2D, savedBinding);
            call(mActiveTexture, savedActive);
            call(mPixelStorei, GL_UNPACK_ROW_LENGTH, savedUnpackRow);
            call(mPixelStorei, GL_UNPACK_ALIGNMENT, savedUnpackAlign);
            if (savedScissor) {
                call(mEnable, GL_SCISSOR_TEST);
            }
        }
        int err = callI(mGetError);
        if (err != 0 && PathtraceHook.glErrors < 10) {
            PathtraceHook.glErrors++;
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
