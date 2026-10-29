package net.prisma.pathtrace.mixin;

import net.prisma.pathtrace.PathtraceHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 网格发布事件钩子（2.4 v0.5.1 事件驱动摄取）—— net.minecraft
 * class_846$class_851（ChunkBuilder$BuiltChunk）.method_60908
 * （setData，descriptor (Lnet/minecraft/class_846$class_849;)V，
 * 参数为重建完成的 ChunkData——本注入不捕获参数，CallbackInfo-only
 * 签名）。
 *
 * 语义：setData 是 vanilla 把新 mesh 发布进 BuiltChunk.data
 * （field_4459）的唯一写点 —— 也就是 v0.4.x 轮询 dirty 判定
 * （AtomicReference 引用变化）所观察的同一事件源。钩 HEAD = MC
 * 完成 section 重建并发布的精确时刻，(Object) this 即 BuiltChunk
 * 实例（消费侧反射读 index/origin/data，全走 S3 已审计符号）。
 * 摄取内容仍由 SceneProxy 自建 SectionBuilder.build 重建（2.1
 * 契约不变）—— 本钩子只提供【何时】的信号，不碰【取什么】。
 *
 * 目标类/方法均为 intermediary 字面名 + remap=false：运行环境
 * （Pojav/Fabric 生产端）类名即 intermediary，零 refmap 直命中。
 * yarn 1.21.4+build.8 核验（deps/yarn-mappings）：
 *   class_846$class_851#method_60908 setData(ChunkData) V。
 *
 * 双保险（崩游戏零风险纪律，同 PathtraceWindowMixin）：
 *   - pathtrace.mixins.json "required": false —— 配置级应用失败仅告警
 *   - @Inject(require = 0) —— 注入点不匹配仅告警
 *   - 回调全 try/catch（PathtraceHook.onChunkMeshPublished 自带）
 *     → 任何异常判死事件通道而非游戏；mixin 未命中时 SceneProxy
 *     哨兵自动升回 500ms 全量轮询（v0.5.0 等价行为，永不退化）
 *   - 回调 O(1) 无锁（ConcurrentLinkedQueue.add + map putIfAbsent），
 *     渲染线程零负担；OFF 态一次 volatile 读即返回
 */
@Mixin(targets = "net.minecraft.class_846$class_851")
public final class PathtraceBuiltChunkMixin {

    private PathtraceBuiltChunkMixin() {}

    @Inject(method = "method_60908", at = @At("HEAD"), remap = false, require = 0)
    private void pathtrace$onMeshPublished(CallbackInfo ci) {
        // 静态极简转发：(Object) this 恒合法（一切实例皆 Object）——
        // mixin 编译期视角 this 为本类，运行期为 BuiltChunk 实例。
        PathtraceHook.onChunkMeshPublished((Object) this);
    }
}
