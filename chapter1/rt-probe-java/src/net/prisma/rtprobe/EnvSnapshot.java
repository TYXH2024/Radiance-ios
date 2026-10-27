package net.prisma.rtprobe;

import java.lang.reflect.Method;
import java.util.Properties;

/**
 * 环境快照：Java / OS / 启动器 / LWJGL 绑定类齐全性。
 * 这一节的目标是把“JAR 环境里到底有什么”一次摸清，避免二次提问。
 */
final class EnvSnapshot {

    private EnvSnapshot() {
    }

    static void run(ProbeReport r) {
        r.stage("S0 环境快照");

        // Java / OS
        r.info("java.version  = " + prop("java.version"));
        r.info("java.vendor   = " + prop("java.vendor"));
        r.info("java.vm.name  = " + prop("java.vm.name"));
        r.info("os.name       = " + prop("os.name") + " " + prop("os.version")
                + " / " + prop("os.arch"));
        r.info("user.dir      = " + prop("user.dir"));
        r.info("user.home     = " + prop("user.home"));

        // 启动器相关属性（Pojav/Amethyst 系会注入大量 pojav.* / ame.* 键）
        Properties p = System.getProperties();
        for (String key : p.stringPropertyNames()) {
            String k = key.toLowerCase();
            if (k.startsWith("pojav") || k.startsWith("ame") || k.startsWith("zalith")
                    || k.contains("minecraft.home") || k.equals("org.lwjgl.vulkan.libname")
                    || k.startsWith("org.lwjgl.opengl") || k.equals("java.library.path")
                    || k.startsWith("org.lwjgl.opengl.libname")) {
                String v = p.getProperty(key);
                if (v != null && v.length() > 200) {
                    v = v.substring(0, 200) + "...(截断)";
                }
                r.info("sys[" + key + "] = " + v);
            }
        }

        // LWJGL 版本
        try {
            Class<?> ver = Class.forName("org.lwjgl.Version");
            Method m = ver.getMethod("getVersion");
            r.info("LWJGL 版本: " + m.invoke(null));
        } catch (Throwable t) {
            r.fail("读取 org.lwjgl.Version 失败（非致命）", t);
        }

        // Vulkan 绑定类齐全性 —— 这是判定 B（改 LWJGL）的直接证据
        r.info("LWJGL Vulkan 绑定类检查:");
        String[][] cls = {
                {"org.lwjgl.vulkan.VK10", "Vulkan 1.0 核心"},
                {"org.lwjgl.vulkan.VK11", "Vulkan 1.1 核心"},
                {"org.lwjgl.vulkan.VK12", "Vulkan 1.2 核心"},
                {"org.lwjgl.vulkan.VK13", "Vulkan 1.3 核心"},
                {"org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2", "props2 查询"},
                {"org.lwjgl.vulkan.KHRPortabilityEnumeration", "iOS/macOS 枚举 MoltenVK 必需"},
                {"org.lwjgl.vulkan.KHRBufferDeviceAddress", "BLAS/TLAS 设备地址"},
                {"org.lwjgl.vulkan.KHRDeferredHostOperations", "AS 依赖"},
                {"org.lwjgl.vulkan.KHRGetMemoryRequirements2", "AS 依赖"},
                {"org.lwjgl.vulkan.KHRAccelerationStructure", "AS 宿主侧构建命令"},
                {"org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR", "TLAS 实例结构体"},
                {"org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR", "ray query 特性查询"},
                {"org.lwjgl.vulkan.KHRRayQuery", "ray query 常量(壳类即可)"},
                {"org.lwjgl.vulkan.KHRRayTracingPipeline", "RT 管线(本探针不需要)"},
                {"org.lwjgl.vulkan.EXTMetalSurface", "Metal surface(本探针不需要)"},
        };
        int missing = 0;
        for (String[] c : cls) {
            boolean ok = exists(c[0]);
            r.info(String.format("   %-4s %-58s %s", ok ? "有" : "无", c[1], c[0]));
            if (!ok) {
                missing++;
            }
        }
        if (missing == 0) {
            r.ok("LWJGL Vulkan 绑定类齐全，判定 B（需换 LWJGL）可排除");
        } else {
            r.info("缺失 " + missing + " 个绑定类：若缺失的是加速结构相关类，判定 B 成立（需替换/升级启动器内 LWJGL jar）");
            r.candidateVerdict("B", "LWJGL 缺少 Vulkan RT 绑定类 -> 需改包替换 LWJGL jar");
        }
    }

    private static boolean exists(String name) {
        try {
            Class.forName(name, false, EnvSnapshot.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String prop(String key) {
        String v = System.getProperty(key);
        return v == null ? "(未设置)" : v;
    }
}
