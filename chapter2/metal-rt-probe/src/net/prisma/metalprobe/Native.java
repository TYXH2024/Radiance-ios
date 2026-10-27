package net.prisma.metalprobe;

/**
 * 原生绑定 —— libradiance_metal_probe.dylib 的唯一 JNI 入口。
 *
 *装载由 MetalProbeMod 控制（解包到 game 目录后 System.load），
 * 本类只声明 native 方法；不做任何 ClassLoader 级副作用。
 */
final class Native {

    private Native() {}

    /**
     * 执行完整 Metal RT 探针链路：
     *   MTLDevice 审计 → 三角形 BLAS → 实例 TLAS → metallib 装载 →
     *   ray_query compute pipeline → 262144 光线追踪（半数带掩码必失）→
     *   回读校验 → （可选）运行时 MSL 编译数据点。
     * 全过程细节增量写入 reportPath（追加模式，与 Java 侧报告同文件）。
     *
     * @return 判定码：
     *         0 = METAL_RT_OK（全链路通过）
     *         1 = METAL_RT_MISMATCH（回读数值不符）
     *         2 = NATIVE_FATAL（设备/构建/管线等致命失败，细节在报告）
     *         3 = METAL_RT_API_UNAVAILABLE（device.supportsRaytracing == NO）
     */
    static native int nativeRunProbe(String metallibPath, String mslSourcePath, String reportPath);
}
