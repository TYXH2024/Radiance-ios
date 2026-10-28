package net.prisma.pathtrace.mixin;

import net.prisma.pathtrace.PathtraceHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 帧呈现钩子（2.3 v0.4）—— net.minecraft.class_1041（Window）.
 * method_15998（swapBuffers，descriptor (Lnet/minecraft/class_10219;)V，
 * 参数为 TracyFrameCapturer——本注入不捕获参数，CallbackInfo-only 签名）。
 *
 * 目标类/方法均为 intermediary 字面名 + remap=false：运行环境（Pojav/
 * Fabric 生产端）类名即 intermediary，零 refmap 直命中。yarn
 * 1.21.4+build.8 离线核验（工具与记录见 build 仓 deps/yarn-mappings）。
 *
 * 双保险（崩游戏零风险纪律）：
 *   - pathtrace.mixins.json "required": false —— 配置级应用失败仅告警
 *   - @Inject(require = 0) —— 注入点不匹配仅告警
 *   - 回调全 try/catch（PathtraceHook.onPreSwap 自带）→ 任何异常判死
 *     合成器而非游戏
 *   - method_15998 每帧恰一次（帧尾 swap 路径），HEAD 注入 = MC 完成自身
 *     最终 blit 之后、缓冲交换之前——合成不被覆盖
 */
@Mixin(targets = "net.minecraft.class_1041")
public final class PathtraceWindowMixin {

    private PathtraceWindowMixin() {}

    @Inject(method = "method_15998", at = @At("HEAD"), remap = false, require = 0)
    private void pathtrace$onSwapBuffers(CallbackInfo ci) {
        // 静态极简转发：OFF 态一次 volatile 读即返回（游戏帧循环零负担）。
        PathtraceHook.onPreSwap();
    }
}
