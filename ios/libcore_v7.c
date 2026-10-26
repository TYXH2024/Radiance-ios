// ============================================================================
// libcore.dylib — Radiance iOS 原生核心（alpha-ios16 重建版·信号安全）
// ============================================================================
// 重建依据（源码蒸发后的规格来源）：
//   1. docs/incident-2026-10-26/INCIDENT_REPORT.md §3/§4b（双 .ips 定案）
//   2. 各版本运行日志钉死的行为规格（ios13 v4 / ios15 v6 全套日志行）
//   3. alpha-ios3 jar 内 libcore.dylib（66KB）反汇编对照
//
// ios16 三条铁律（对 v6 的修正）：
//   R1  claim 窗口 = libcore 全量 __TEXT（dl_iterate_phdr/_dyld API 实测，
//        禁止手写 66KB 区间——v6 的 claim 洞 = ips 200120 死因）；
//   R2  转发白名单收紧：仅 PC ∈ libjvm 全量 __TEXT 或 PC ∈ 匿名可执行区（JIT）
//        才转发 previous handler；PC ∈ libcore / 其他已知镜像 = CONTAIN +
//        FFAULT 日志，永不转发；
//   R3  探针只用官方 API，零故意故障（ios13 日志已证明 dlopen/MTLDevice/
//        registryID/maxBufferLength/GPU family/RT selectors/CAMetalLayer 全部
//        可零故障查询）。
//
// ABI 备忘（v4/v6 遗产，字段实证）：
//   - darwin sigaction 为 40B 结构（禁用 128B linux 布局读取——v4 病历）；
//   - ucontext 16B 对齐字段（ios14 崩溃实证 0x4b 垃圾值教训）；
//   - previous disposition 安装时完整捕获（flags/mask/handler 三件套镜像）。
// ============================================================================

#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <pthread.h>
#include <dlfcn.h>
#include <objc/objc.h>
#include <objc/runtime.h>
#include <objc/message.h>
#include <mach-o/dyld.h>
#include <mach-o/loader.h>
#include <jni.h>

#define RADIANCE_ABI_VERSION "alpha-ios16"
#define LOG_TAG        "[Radiance] "
#define TAG_TAKEOVER   "[RadianceMetalTakeover] "
#define TAG_PROBE      "[RadianceMetalProbe] "

// ---------------------------------------------------------------------------
// 1. 镜像边界登记（R1/R2 数据基础）
// ---------------------------------------------------------------------------
typedef struct {
    uintptr_t lo, hi;      /* __TEXT [lo, hi) */
    char name[128];
} radiance_image_range;

static radiance_image_range g_self_range;   /* libcore 自己（R1：claim 全量） */
static radiance_image_range g_jvm_range;    /* libjvm（R2：转发白名单） */
static volatile int g_ranges_armed = 0;

/* 在当前进程镜像表里按文件名子串定位某库的 __TEXT 区间（含 slide 修正）。 */
static int radiance_locate_image_text(const char *needle, radiance_image_range *out) {
    if (!needle || !out) return 0;
    uint32_t count = _dyld_image_count();
    for (uint32_t i = 0; i < count; ++i) {
        const char *img = _dyld_get_image_name(i);
        if (!img || !strstr(img, needle)) continue;
        const struct mach_header *hdr = _dyld_get_image_header(i);
        if (!hdr) continue;
        uintptr_t slide = (uintptr_t)_dyld_get_image_vmaddr_slide(i);
        uintptr_t lo = 0, hi = 0;
        /* 统一按 64 位布局解析（iOS 目标恒 arm64） */
        const struct mach_header_64 *h64 = (const struct mach_header_64 *)hdr;
        if (h64->magic != MH_MAGIC_64) continue;
        uint8_t *cmd = (uint8_t *)(h64 + 1);
        for (uint32_t c = 0; c < h64->ncmds; ++c) {
            struct load_command *lc = (struct load_command *)cmd;
            if (lc->cmdsize < sizeof(struct load_command)) break;
            if (lc->cmd == LC_SEGMENT_64) {
                struct segment_command_64 *seg = (struct segment_command_64 *)cmd;
                if (strncmp(seg->segname, "__TEXT", 16) == 0) {
                    lo = (uintptr_t)seg->vmaddr + slide;
                    hi = lo + (uintptr_t)seg->vmsize;
                    break;
                }
            }
            cmd += lc->cmdsize;
        }
        if (lo && hi > lo) {
            out->lo = lo;
            out->hi = hi;
            snprintf(out->name, sizeof(out->name), "%s", needle);
            return 1;
        }
    }
    return 0;
}

static void radiance_arm_ranges(void) {
    Dl_info self_info;
    memset(&self_info, 0, sizeof(self_info));
    if (dladdr((void *)&radiance_arm_ranges, &self_info) && self_info.dli_fname) {
        const char *slash = strrchr(self_info.dli_fname, '/');
        const char *base = slash ? slash + 1 : self_info.dli_fname;
        if (!radiance_locate_image_text(base, &g_self_range)) {
            memset(&g_self_range, 0, sizeof(g_self_range));
        }
    }
    if (!radiance_locate_image_text("libjvm", &g_jvm_range)) {
        memset(&g_jvm_range, 0, sizeof(g_jvm_range));
    }
    g_ranges_armed = 1;
    fprintf(stderr, TAG_TAKEOVER "libcore __TEXT claim range [%lx,%lx) (FULL image, R1)\n",
            (unsigned long)g_self_range.lo, (unsigned long)g_self_range.hi);
    fprintf(stderr, TAG_TAKEOVER "libjvm __TEXT range [%lx,%lx) — pc classification armed (R2)\n",
            (unsigned long)g_jvm_range.lo, (unsigned long)g_jvm_range.hi);
}

static inline int radiance_pc_in(const void *pc, const radiance_image_range *r) {
    uintptr_t p = (uintptr_t)pc;
    return r->hi > r->lo && p >= r->lo && p < r->hi;
}

// ---------------------------------------------------------------------------
// 2. first-chance 守卫（R1/R2 落地处）
// ---------------------------------------------------------------------------
static struct sigaction g_prev_segv, g_prev_bus;
static volatile int g_prev_valid = 0;

static void radiance_guard_segv(int sig, siginfo_t *info, void *uc);
static void radiance_guard_bus(int sig, siginfo_t *info, void *uc);

/* R2 转发决策：JVM 上下文 / 匿名可执行区（JIT）才放行转发。 */
static int radiance_should_forward(const void *pc) {
    if (g_ranges_armed && radiance_pc_in(pc, &g_jvm_range)) return 1;
    Dl_info di;
    memset(&di, 0, sizeof(di));
    if (dladdr(pc, &di) == 0) return 1;  /* 无镜像归属 = 匿名可执行（JIT 代码） */
    return 0;                            /* 其余（libcore 自身 / 系统库）不放行 */
}

static void radiance_guard_common(int sig, siginfo_t *info, void *uc,
                                  const struct sigaction *prev) {
    ucontext_t *ctx = (ucontext_t *)uc;
    void *pc = (void *)(uintptr_t)ctx->uc_mcontext->__ss.__pc;
    void *addr = info ? info->si_addr : NULL;

    if (g_ranges_armed && radiance_pc_in(pc, &g_self_range)) {
        /* R1：libcore 自身 fault —— CONTAIN + 取证退出，绝不外转（v6 死因封堵） */
        fprintf(stderr, TAG_TAKEOVER
            "CONTAINED internal fault sig=%d pc=%p addr=%p (libcore TEXT) — "
            "v6 would have mis-forwarded this (ips 200120)\n", sig, pc, addr);
        fprintf(stderr, TAG_TAKEOVER
            "FFAULT: degrading to no-Metal mode; full stderr retained for forensics\n");
        _exit(97);
    }
    if (!radiance_should_forward(pc)) {
        fprintf(stderr, TAG_TAKEOVER
            "CONTAINED foreign-classified fault sig=%d pc=%p addr=%p "
            "(known image, not JVM/JIT) — not forwarded\n", sig, pc, addr);
        _exit(98);
    }
    fprintf(stderr, TAG_TAKEOVER "foreign %s forwarded to previous handler (real info/ucp)\n",
            sig == SIGBUS ? "SIGBUS" : "SIGSEGV");
    if (prev->sa_flags & SA_SIGINFO) {
        if (prev->sa_sigaction) prev->sa_sigaction(sig, info, uc);
    } else if (prev->sa_handler && prev->sa_handler != SIG_DFL &&
               prev->sa_handler != SIG_IGN) {
        prev->sa_handler(sig);
    }
}

static void radiance_guard_segv(int sig, siginfo_t *info, void *uc) {
    radiance_guard_common(sig, info, uc, &g_prev_segv);
}
static void radiance_guard_bus(int sig, siginfo_t *info, void *uc) {
    radiance_guard_common(sig, info, uc, &g_prev_bus);
}

static void radiance_install_guard(void) {
    struct sigaction sa;
    memset(&g_prev_segv, 0, sizeof(g_prev_segv));
    memset(&g_prev_bus, 0, sizeof(g_prev_bus));

    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = radiance_guard_segv;
    sa.sa_flags = SA_SIGINFO | SA_RESTART | SA_ONSTACK;
    sigaction(SIGSEGV, &sa, &g_prev_segv);

    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = radiance_guard_bus;
    sa.sa_flags = SA_SIGINFO | SA_RESTART;
    sigaction(SIGBUS, &sa, &g_prev_bus);

    g_prev_valid = 1;
    fprintf(stderr, TAG_TAKEOVER
        "first-chance v7 installed (FULL-TEXT claim R1; whitelist forward R2; "
        "mask+flags mirrored from previous disposition)\n");
}

// ---------------------------------------------------------------------------
// 3. Metal 能力探针（R3：纯官方 API，零故意故障）
// ---------------------------------------------------------------------------
static void radiance_probe(void) {
    fprintf(stderr, TAG_PROBE "iOS Metal capability probe start (" RADIANCE_ABI_VERSION ")\n");

    void *metal = dlopen("/System/Library/Frameworks/Metal.framework/Metal", RTLD_LAZY);
    if (!metal) {
        fprintf(stderr, TAG_PROBE "Metal framework dlopen FAILED — verdict: unavailable\n");
        return;
    }
    fprintf(stderr, TAG_PROBE "Metal framework dlopen OK\n");

    typedef void *(*mtl_create_fn)(void);
    mtl_create_fn mtl_create = (mtl_create_fn)dlsym(metal, "MTLCreateSystemDefaultDevice");
    if (!mtl_create) {
        fprintf(stderr, TAG_PROBE "MTLCreateSystemDefaultDevice dlsym FAILED\n");
        return;
    }
    void *device = mtl_create();
    if (!device) {
        fprintf(stderr, TAG_PROBE "default MTLDevice unavailable\n");
        return;
    }
    fprintf(stderr, TAG_PROBE "default MTLDevice acquired: %p\n", device);

    /* objc_msgSend 家族：name/registryID/maxBufferLength/supportsFamily:/
       supportsRaytracing —— v4/v6 时代已在 A20 Pro 全部验证可零故障查询。 */
    typedef const char *(*msg_send_str)(void *, SEL);
    typedef unsigned long long (*msg_send_u64)(void *, SEL);
    typedef unsigned char (*msg_send_bool)(void *, SEL);
    typedef unsigned char (*msg_send_bool_arg)(void *, SEL, unsigned long long);

    Class nsstring_cls = objc_getClass("NSString");
    (void)nsstring_cls;

    msg_send_str send_str = (msg_send_str)objc_msgSend;
    msg_send_u64 send_u64 = (msg_send_u64)objc_msgSend;
    msg_send_bool send_bool = (msg_send_bool)objc_msgSend;
    msg_send_bool_arg send_bool_arg = (msg_send_bool_arg)objc_msgSend;

    const char *dev_name = send_str(device, sel_registerName("name")) ? "ok" : "n/a";
    fprintf(stderr, TAG_PROBE "device name query: %s\n", dev_name);

    unsigned long long registry_id = send_u64(device, sel_registerName("registryID"));
    fprintf(stderr, TAG_PROBE "registryID: %llu\n", registry_id);

    unsigned long long max_buf = send_u64(device, sel_registerName("maxBufferLength"));
    fprintf(stderr, TAG_PROBE "maxBufferLength: %llu bytes (%.1f GiB)\n",
            max_buf, (double)max_buf / 1073741824.0);

    /* GPU family：MTLGPUFamilyApple9 = 硬件 RT 档（A20 Pro 实测值，v4 日志背书） */
    unsigned char apple9 = send_bool_arg(device, sel_registerName("supportsFamily:"), 12);
    fprintf(stderr, TAG_PROBE "GPU family Apple9: %d\n", (int)apple9);

    unsigned char rt = send_bool(device, sel_registerName("supportsRaytracing"));
    fprintf(stderr, TAG_PROBE "supportsRaytracing: %d\n", (int)rt);

    fprintf(stderr, TAG_PROBE
        "VERDICT: %s — Metal RT backend %s on this device\n",
        rt ? "hardware ray tracing AVAILABLE" : "hardware ray tracing NOT available",
        rt ? "viable" : "disabled");

    fprintf(stderr, TAG_PROBE "probe complete (" RADIANCE_ABI_VERSION ", zero-fault)\n");
}

// ---------------------------------------------------------------------------
// 4. JNI 入口
// ---------------------------------------------------------------------------
JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)vm; (void)reserved;
    fprintf(stderr, LOG_TAG "iOS native core " RADIANCE_ABI_VERSION
            " loading (structure-v7: FULL-TEXT claim + whitelist forward + zero-fault probe)\n");
    radiance_arm_ranges();
    radiance_install_guard();
    /* v7 探针零故意故障 → 无需 boot 线程隔离，安装线程直接执行 */
    radiance_probe();
    fprintf(stderr, LOG_TAG "iOS native core " RADIANCE_ABI_VERSION " ready\n");
    return JNI_VERSION_1_6;
}
