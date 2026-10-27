package net.prisma.rtprobe;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRBufferDeviceAddress;
import org.lwjgl.vulkan.KHRGetPhysicalDeviceProperties2;
import org.lwjgl.vulkan.KHRPortabilityEnumeration;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkDeviceCreateInfo;
import org.lwjgl.vulkan.VkDeviceQueueCreateInfo;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerProperties;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceBufferDeviceAddressFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VK12;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.KHRRayQuery.VK_KHR_RAY_QUERY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 阶段 S1-S8：实例创建 -> 物理设备枚举 -> 扩展/特性审计 -> 逻辑设备创建。
 * 只做无头计算所需的最小对象，不触碰 swapchain / surface，与活动渲染器零冲突。
 */
final class VulkanProbe {

    static final String EXT_ACCEL = VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME;
    static final String EXT_RAY_QUERY = VK_KHR_RAY_QUERY_EXTENSION_NAME;
    static final String EXT_DEFERRED = "VK_KHR_deferred_host_operations";
    static final String EXT_GET_MEM_REQ2 = "VK_KHR_get_memory_requirements2";
    static final String EXT_BDA = "VK_KHR_buffer_device_address";
    static final String EXT_SPIRV14 = "VK_KHR_spirv_1_4";
    static final String EXT_PORTABILITY_SUBSET = "VK_KHR_portability_subset";
    static final String EXT_DRIVER_PROPS = "VK_KHR_driver_properties";

    /** 逻辑设备上下文，供 RtPipeline 使用 */
    static final class Ctx {
        VkInstance instance;
        VkPhysicalDevice pd;
        VkDevice device;
        VkQueue queue;
        int queueFamily;
        int apiVersion;
        String deviceName;
        int vkResultAtFailure;
    }

    private VulkanProbe() {
    }

    /** 返回 null 表示无法进入 RT 管线阶段（判定已写入报告） */
    static Ctx run(ProbeReport r) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            Ctx ctx = new Ctx();

            // ---- S1 加载 Vulkan 库（启动器已通过 org.lwjgl.vulkan.libname=MoltenVK 指向 libMoltenVK.dylib）----
            // v1.1 修复：LWJGL 3.3.3 的 VK 类有静态初始化块，进程内任何代码首次触碰
            // org.lwjgl.vulkan.* 都会自动 VK.create()；启动器/渲染栈往往已抢先完成加载。
            // 重复 create 会抛 "Vulkan has already been created." —— 这恰恰证明 MoltenVK
            // 已在进程内且全局命令可用，捕获后直接复用即可继续。
            // 另：LWJGL 3.3.3 所有实例级/设备级命令都经出参句柄对象自带的函数表解析，
            // 探针自建实例与宿主零共享零干扰。
            r.stage("S1 加载 Vulkan 实现 (VK.create，已加载则复用)");
            try {
                try {
                    VK.create();
                    r.ok("LWJGL Vulkan 库由探针加载成功（本进程首个创建者，应为捆绑的 libMoltenVK.dylib）");
                } catch (IllegalStateException already) {
                    if (String.valueOf(already.getMessage()).contains("already been created")) {
                        r.ok("Vulkan 库已由宿主(启动器/渲染栈/LWJGL 静态初始化)提前加载 —— 复用现有库句柄"
                                + "（v1.1 修复点：v1.0 在此误判 F）");
                    } else {
                        throw already;
                    }
                }
            } catch (Throwable t) {
                r.fail("LWJGL 无法加载 Vulkan 库", t);
                r.finish("F", "Vulkan 实现真实加载失败（v1.1 已排除\"重复创建\"误报，"
                        + "此判定仅在库本体缺失/架构不符时出现）。附报告回报。");
                return null;
            }

            // ---- S2 实例版本 ----
            r.stage("S2 实例支持版本");
            IntBuffer ver = stack.mallocInt(1);
            int supported = 0;
            if (VK11.vkEnumerateInstanceVersion(ver) == VK_SUCCESS) {
                supported = ver.get(0);
                r.ok("实例最高支持 Vulkan " + verStr(supported));
            } else {
                supported = VK_MAKE_VERSION(1, 0, 0);
                r.info("vkEnumerateInstanceVersion 不可用（Vulkan 1.0 实现）");
            }

            // ---- S3 实例层与实例扩展清单 ----
            r.stage("S3 实例层 / 实例扩展清单");
            List<String> layers = enumInstanceLayers(r);
            List<String> instExts = enumInstanceExtensions(r);
            boolean moltenLayer = layers.contains("VK_LAYER_MoltenVK");
            boolean portabilityEnum = instExts.contains("VK_KHR_portability_enumeration");
            r.info("检测到 MoltenVK 层: " + (moltenLayer ? "是" : "否") +
                    "；portability_enumeration 扩展: " + (portabilityEnum ? "是" : "否"));

            // ---- S4 创建实例 ----
            r.stage("S4 创建 VkInstance");
            VkInstance instance;
            try (MemoryStack s2 = MemoryStack.stackPush()) {
                VkApplicationInfo app = VkApplicationInfo.calloc(s2)
                        .sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
                        .pApplicationName(s2.UTF8("RTProbe"))
                        .applicationVersion(VK_MAKE_VERSION(1, 0, 0))
                        .pEngineName(s2.UTF8("RTProbe"))
                        .engineVersion(VK_MAKE_VERSION(1, 0, 0))
                        .apiVersion(supported >= VK_MAKE_VERSION(1, 2, 0)
                                ? VK_MAKE_VERSION(1, 2, 0)
                                : (supported >= VK_MAKE_VERSION(1, 1, 0)
                                ? VK_MAKE_VERSION(1, 1, 0)
                                : VK_MAKE_VERSION(1, 0, 0))); // v1.1: 尽量以 1.2 建实例，使 Vulkan12Features 链合法

                List<String> wanted = new ArrayList<>();
                if (instExts.contains("VK_KHR_get_physical_device_properties2")) {
                    wanted.add("VK_KHR_get_physical_device_properties2");
                }
                if (portabilityEnum) {
                    wanted.add("VK_KHR_portability_enumeration");
                }
                PointerBuffer extNames = bufferStrings(s2, wanted);

                VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(s2)
                        .sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                        .pApplicationInfo(app)
                        .ppEnabledExtensionNames(extNames);
                if (portabilityEnum) {
                    ci.flags(KHRPortabilityEnumeration.VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR);
                }
                PointerBuffer pInstance = s2.mallocPointer(1);
                int err = VK10.vkCreateInstance(ci, null, pInstance);
                if (err != VK_SUCCESS) {
                    r.fail("vkCreateInstance 失败 vkResult=" + translate(err), null);
                    r.finish("E", "实例创建失败 (" + translate(err) + ") -> 无法继续，附报告回报。");
                    return null;
                }
                instance = new VkInstance(pInstance.get(0), ci);
                r.ok("VkInstance 创建成功 (启用扩展: " + wanted + ")");
            }
            ctx.instance = instance;

            // ---- S5 物理设备枚举 ----
            r.stage("S5 物理设备枚举");
            IntBuffer pdCount = stack.mallocInt(1);
            int err = VK10.vkEnumeratePhysicalDevices(instance, pdCount, null);
            if (err != VK_SUCCESS || pdCount.get(0) == 0) {
                r.fail("未枚举到任何物理设备 (err=" + translate(err) + ", count=" + pdCount.get(0) + ")", null);
                r.finish("E", "无物理设备 -> MoltenVK 未正确初始化，附报告回报。");
                destroyInstance(r, instance); // v1.2: 失败路径同样不泄漏
                return null;
            }
            PointerBuffer pds = stack.mallocPointer(pdCount.get(0));
            VK10.vkEnumeratePhysicalDevices(instance, pdCount, pds);
            r.info("物理设备数量: " + pdCount.get(0));

            Ctx chosen = null;
            for (int i = 0; i < pds.capacity(); i++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(pds.get(i), instance);
                Ctx c = inspectDevice(r, i, pd, instance);
                if (c != null && chosen == null) {
                    chosen = c;
                }
            }
            if (chosen == null) {
                // v1.2 修复：判 C/E 路径同样销毁实例。
                // v1.1 在此泄漏 VkInstance（进程存活），现场取证显示 MobileGlues
                // (GL->Vulkan 翻译层) 随后初始化未完成，MC 调 glGetError 撞 NULL 函数表
                // SIGSEGV —— 残留实例 + 与 GL 初始化并发竞争是首要嫌疑。
                destroyInstance(r, instance);
                return null; // inspectDevice 内已写判定
            }
            r.ok("选定设备: " + chosen.deviceName + " (Vulkan " + verStr(chosen.apiVersion) + ")");
            return chosen;
        }
    }

    /** 单个物理设备的完整体检。返回可用 Ctx 则代表该设备可进入 RT 管线。 */
    private static Ctx inspectDevice(ProbeReport r, int index, VkPhysicalDevice pd, VkInstance instance) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceProperties(pd, props);
            int api = props.apiVersion();
            String name = props.deviceNameString();
            r.stage("S5." + index + " 设备: " + name);
            r.info(String.format("apiVersion=%s  vendorID=0x%04X  deviceID=0x%04X  driverVersion=0x%08X",
                    verStr(api), props.vendorID(), props.deviceID(), props.driverVersion()));
            r.info("driverVersion 按 MoltenVK 打包方式解读: " + mvkDriverVersion(props.driverVersion()));

            // 设备扩展全量清单
            Set<String> devExts = enumDeviceExtensions(r, pd);

            // props2 链：驱动属性（MoltenVK 版本信息）
            if (devExts.contains(EXT_DRIVER_PROPS) || api >= VK_MAKE_VERSION(1, 2, 0)) {
                try (MemoryStack s3 = MemoryStack.stackPush()) {
                    VkPhysicalDeviceDriverProperties drv = VkPhysicalDeviceDriverProperties.calloc(s3)
                            .sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES);
                    VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(s3)
                            .sType(KHRGetPhysicalDeviceProperties2.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2_KHR)
                            .pNext(drv.address());
                    KHRGetPhysicalDeviceProperties2.vkGetPhysicalDeviceProperties2KHR(pd, props2);
                    r.info("driverName=" + drv.driverNameString() + "  driverInfo=" + drv.driverInfoString());
                } catch (Throwable t) {
                    r.info("driver properties 查询失败（不致命）: " + t);
                }
            }

            // ---- S6 RT 相关扩展审计 ----
            r.stage("S6." + index + " RT 扩展审计");
            String[][] need = {
                    {EXT_ACCEL, "加速结构（BLAS/TLAS 构建命令）", "必需"},
                    {EXT_RAY_QUERY, "ray query（着色器内求交）", "必需"},
                    {EXT_DEFERRED, "延迟宿主操作（AS 依赖）", "必需"},
                    {EXT_GET_MEM_REQ2, "内存需求查询（AS 依赖）", api >= VK_MAKE_VERSION(1, 1, 0) ? "1.1核心/依赖" : "必需"},
                    {EXT_BDA, "缓冲设备地址（几何/实例寻址）", api >= VK_MAKE_VERSION(1, 2, 0) ? "1.2核心" : "必需"},
                    {EXT_SPIRV14, "SPIR-V 1.4（RT 着色器需要）", api >= VK_MAKE_VERSION(1, 2, 0) ? "1.2核心" : "必需"},
                    {EXT_PORTABILITY_SUBSET, "MoltenVK 可移植子集", "应存在"},
                    {EXT_DRIVER_PROPS, "驱动属性（读 MoltenVK 版本）", "可选"},
            };
            int missingHard = 0;
            for (String[] n : need) {
                boolean has = devExts.contains(n[0]);
                r.info(String.format("   %-4s %-32s %-22s (%s)", has ? "有" : "无", n[0], n[1], n[2]));
                boolean hard = n[2].startsWith("必需") || n[2].endsWith("依赖");
                if (!has && hard && !(n[0].equals(EXT_BDA) && api >= VK_MAKE_VERSION(1, 2, 0))
                        && !(n[0].equals(EXT_SPIRV14) && api >= VK_MAKE_VERSION(1, 2, 0))
                        && !(n[0].equals(EXT_GET_MEM_REQ2) && api >= VK_MAKE_VERSION(1, 1, 0))) {
                    missingHard++;
                }
            }

            // ---- S7 特性审计（features2 pNext 链）----
            r.stage("S7." + index + " RT 特性审计");
            boolean hasAccel = devExts.contains(EXT_ACCEL);
            boolean hasRq = devExts.contains(EXT_RAY_QUERY);
            boolean hasBda = devExts.contains(EXT_BDA) || api >= VK_MAKE_VERSION(1, 2, 0);

            VkPhysicalDeviceAccelerationStructureFeaturesKHR accelF =
                    VkPhysicalDeviceAccelerationStructureFeaturesKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR);
            VkPhysicalDeviceRayQueryFeaturesKHR rqF =
                    VkPhysicalDeviceRayQueryFeaturesKHR.calloc(stack)
                            .sType(KHRRayQuerySType());
            VkPhysicalDeviceBufferDeviceAddressFeaturesKHR bdaF = null;
            VkPhysicalDeviceVulkan12Features v12F = null;

            // 组 pNext 链(features2 -> [v12] -> accel -> rayQuery -> [bda])，用 long 地址链接最稳
            if (api >= VK_MAKE_VERSION(1, 2, 0)) {
                v12F = VkPhysicalDeviceVulkan12Features.calloc(stack)
                        .sType(VK12.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES)
                        .pNext(accelF.address());
            } else if (hasBda) {
                bdaF = VkPhysicalDeviceBufferDeviceAddressFeaturesKHR.calloc(stack)
                        .sType(KHRBufferDeviceAddress.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_BUFFER_DEVICE_ADDRESS_FEATURES_KHR)
                        .pNext(accelF.address());
            }
            accelF.pNext(rqF.address());
            VkPhysicalDeviceFeatures2 f2 = VkPhysicalDeviceFeatures2.calloc(stack)
                    .sType(KHRGetPhysicalDeviceProperties2.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2_KHR)
                    .pNext(v12F != null ? v12F.address() : (bdaF != null ? bdaF.address() : accelF.address()));

            KHRGetPhysicalDeviceProperties2.vkGetPhysicalDeviceFeatures2KHR(pd, f2);
            r.info("accelerationStructure 特性 = " + accelF.accelerationStructure());
            r.info("rayQuery 特性           = " + rqF.rayQuery());
            if (v12F != null) {
                r.info("bufferDeviceAddress 特性 = " + v12F.bufferDeviceAddress());
            } else if (bdaF != null) {
                r.info("bufferDeviceAddress 特性 = " + bdaF.bufferDeviceAddress());
            }

            // ---- S8 判定与设备创建 ----
            r.stage("S8." + index + " 判定与逻辑设备创建");
            if (missingHard > 0 || !hasAccel || !hasRq) {
                r.info("该设备缺少必需的 RT 扩展（缺失硬性项 " + missingHard + " 个），无法进入构建阶段。");
                r.candidateVerdict("C", "设备缺少 VK_KHR_acceleration_structure / VK_KHR_ray_query"
                        + " -> 当前 MoltenVK 未暴露 Metal RT（截至 1.4.2 上游未实现），"
                        + "需改包替换 ICD（KosmicKrisp/honeykrisp 或自研 shim）");
                if (index == 0) {
                    r.finish("C", "探针在扩展审计阶段停止：JAR 环境本身工作正常（Vulkan 已加载、设备已枚举、"
                            + "扩展清单已完整取证），但本 MoltenVK 不暴露 Vulkan RT 扩展。"
                            + "下一步：改包替换 Vulkan ICD 为支持 Metal RT 的实现（KosmicKrisp/honeykrisp 路线），"
                            + "或给本启动器捆绑的 MoltenVK 加 RT 支持后用同一个探针 JAR 复测。");
                }
                return null;
            }

            boolean accelOk = accelF.accelerationStructure();
            boolean rqOk = rqF.rayQuery();
            boolean bdaOk = v12F != null ? v12F.bufferDeviceAddress()
                    : (bdaF != null ? bdaF.bufferDeviceAddress() : false);
            if (!accelOk || !rqOk || !bdaOk) {
                r.info(String.format("扩展在列但特性关闭: accel=%b rayQuery=%b bda=%b", accelOk, rqOk, bdaOk));
                r.finish("C2", "RT 扩展存在但物理设备特性位关闭 -> MoltenVK 声明了扩展却未真正支持，"
                        + "属于 MoltenVK 侧能力伪装/半实现，附报告回报后走 ICD 替换路线。");
                return null;
            }

            // 找 compute 队列族
            IntBuffer qCount = stack.mallocInt(1);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd, qCount, null);
            VkQueueFamilyProperties.Buffer qProps =
                    VkQueueFamilyProperties.callocStack(qCount.get(0), stack);
            VK10.vkGetPhysicalDeviceQueueFamilyProperties(pd, qCount, qProps);
            int family = -1;
            for (int q = 0; q < qProps.capacity(); q++) {
                if ((qProps.get(q).queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0) {
                    family = q;
                    break;
                }
            }
            if (family < 0) {
                r.fail("未找到 compute 队列族", null);
                r.finish("E", "该设备无 compute 队列 -> 异常实现，附报告回报。");
                return null;
            }
            r.info("compute 队列族 = " + family);

            // 创建逻辑设备
            float prio = 1.0f;
            VkDeviceQueueCreateInfo.Buffer qc = VkDeviceQueueCreateInfo.calloc(1, stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO)
                    .queueFamilyIndex(family)
                    .pQueuePriorities(stack.floats(prio));

            List<String> enable = new ArrayList<>();
            if (api < VK_MAKE_VERSION(1, 2, 0)) {
                addIf(devExts, enable, EXT_BDA);
                addIf(devExts, enable, EXT_SPIRV14);
                addIf(devExts, enable, EXT_GET_MEM_REQ2);
            }
            addIf(devExts, enable, EXT_ACCEL);
            addIf(devExts, enable, EXT_RAY_QUERY);
            addIf(devExts, enable, EXT_DEFERRED);
            addIf(devExts, enable, EXT_PORTABILITY_SUBSET);

            PointerBuffer extNames = bufferStrings(stack, enable);
            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO)
                    .pQueueCreateInfos(qc)
                    .ppEnabledExtensionNames(extNames)
                    .pNext(f2.address()); // 启用 accel/rayQuery/bda 特性

            PointerBuffer pDevice = stack.mallocPointer(1);
            int derr = VK10.vkCreateDevice(pd, dci, null, pDevice);
            if (derr != VK_SUCCESS) {
                r.fail("vkCreateDevice 失败 vkResult=" + translate(derr), null);
                r.candidateVerdict("C3", "设备创建失败 " + translate(derr)
                        + " -> 特性链/扩展组合被 MoltenVK 拒绝，附报告回报");
                if (index == 0) {
                    r.finish("C3", "设备创建失败 (" + translate(derr) + ") -> 扩展审计已通过但设备创建被拒，"
                            + "说明 MoltenVK 对 RT 特性链支持不完整，附报告回报。");
                }
                return null;
            }
            VkDevice device = new VkDevice(pDevice.get(0), pd, dci);
            PointerBuffer pQueue = stack.mallocPointer(1);
            VK10.vkGetDeviceQueue(device, family, 0, pQueue);

            r.ok("逻辑设备创建成功，启用扩展: " + enable);

            Ctx ctx = new Ctx();
            ctx.instance = instance; // v1.2: 保留给 RtPipeline.cleanup 统一销毁（v1.1 误置 null）
            ctx.pd = pd;
            ctx.device = device;
            ctx.queue = new VkQueue(pQueue.get(0), device);
            ctx.queueFamily = family;
            ctx.apiVersion = api;
            ctx.deviceName = name;
            return ctx;
        }
    }

    // ================= 工具方法 =================

    /** v1.2: 审计结束/提前退出时销毁探针自建实例，避免残留实例干扰宿主翻译层 */
    static void destroyInstance(ProbeReport r, VkInstance instance) {
        if (instance != null) {
            try {
                VK10.vkDestroyInstance(instance, null);
                if (r != null) {
                    r.info("VkInstance 已销毁（v1.2 修复：v1.1 泄漏实例，判 C 后残留于整个进程周期）");
                }
            } catch (Throwable t) {
                if (r != null) {
                    r.fail("vkDestroyInstance 失败（不致命）", t);
                }
            }
        }
    }

    static List<String> enumInstanceLayers(ProbeReport r) {
        List<String> out = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            if (VK10.vkEnumerateInstanceLayerProperties(count, null) == VK_SUCCESS && count.get(0) > 0) {
                VkLayerProperties.Buffer props = VkLayerProperties.callocStack(count.get(0), stack);
                VK10.vkEnumerateInstanceLayerProperties(count, props);
                for (int i = 0; i < props.capacity(); i++) {
                    out.add(props.get(i).layerNameString());
                }
            }
            r.info("实例层(" + out.size() + "): " + out);
        } catch (Throwable t) {
            r.fail("枚举实例层失败（不致命）", t);
        }
        return out;
    }

    static List<String> enumInstanceExtensions(ProbeReport r) {
        List<String> out = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            if (VK10.vkEnumerateInstanceExtensionProperties((String) null, count, null) == VK_SUCCESS
                    && count.get(0) > 0) {
                VkExtensionProperties.Buffer props =
                        VkExtensionProperties.callocStack(count.get(0), stack);
                VK10.vkEnumerateInstanceExtensionProperties((String) null, count, props);
                for (int i = 0; i < props.capacity(); i++) {
                    out.add(props.get(i).extensionNameString());
                }
            }
            r.info("实例扩展(" + out.size() + "): " + out);
        } catch (Throwable t) {
            r.fail("枚举实例扩展失败（不致命）", t);
        }
        return out;
    }

    static Set<String> enumDeviceExtensions(ProbeReport r, VkPhysicalDevice pd) {
        Set<String> out = new HashSet<>();
        List<String> all = new ArrayList<>();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer count = stack.mallocInt(1);
            if (VK10.vkEnumerateDeviceExtensionProperties(pd, (String) null, count, null) == VK_SUCCESS
                    && count.get(0) > 0) {
                VkExtensionProperties.Buffer props =
                        VkExtensionProperties.callocStack(count.get(0), stack);
                VK10.vkEnumerateDeviceExtensionProperties(pd, (String) null, count, props);
                for (int i = 0; i < props.capacity(); i++) {
                    String s = props.get(i).extensionNameString();
                    out.add(s);
                    all.add(s);
                }
            }
            r.info("设备扩展(" + all.size() + "): " + all);
        } catch (Throwable t) {
            r.fail("枚举设备扩展失败（不致命）", t);
        }
        return out;
    }

    static PointerBuffer bufferStrings(MemoryStack stack, List<String> strings) {
        PointerBuffer pb = stack.mallocPointer(strings.size());
        for (String s : strings) {
            pb.put(stack.UTF8(s));
        }
        pb.flip();
        return pb;
    }

    static void addIf(Set<String> have, List<String> target, String name) {
        if (have.contains(name) && !target.contains(name)) {
            target.add(name);
        }
    }

    static int KHRRayQuerySType() {
        // KHRRayQuery 是常量壳类，直接取其 SType 常量
        return org.lwjgl.vulkan.KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;
    }

    static String verStr(int v) {
        return VK_VERSION_MAJOR(v) + "." + VK_VERSION_MINOR(v) + "." + VK_VERSION_PATCH(v);
    }

    /** MoltenVK 把自己的版本打包进 driverVersion：MAJOR<<22 | MINOR<<14 | PATCH<<6 */
    static String mvkDriverVersion(int dv) {
        int a = dv >>> 22, b = (dv >>> 14) & 0xFF, c = (dv >>> 6) & 0xFF;
        if (a > 0 && a < 32 && (b > 0 || c > 0) && b < 64 && c < 64) {
            return "0x" + Integer.toHexString(dv) + " (MoltenVK " + a + "." + b + "." + c + "?，两种解读中较合理者为准)";
        }
        return "0x" + Integer.toHexString(dv);
    }

    static String translate(int vkResult) {
        switch (vkResult) {
            case VK_SUCCESS: return "VK_SUCCESS";
            case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
            case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
            case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
            case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
            case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
            case -6: return "VK_ERROR_INVALID_SHADER(-6, 着色器被拒)";
            default: return "VK_RESULT(" + vkResult + ")";
        }
    }
}
