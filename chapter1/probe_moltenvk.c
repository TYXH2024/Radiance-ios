//
//  probe_moltenvk.c
//  Radiance iOS Port — Chapter 1 — Foundation Probe B
//
//  Question it answers: what does the Vulkan implementation beneath the two
//  locked renderers (MobileGlues = GL->Vulkan translation, and the direct
//  "MoltenVK" renderer where MC/LWJGL self-manages Vulkan) actually provide?
//
//  Method: dlopen a MoltenVK dylib (path from $MVK_DYLIB), create a Vulkan
//  1.3 instance exactly like MCVR does (src/core/vulkan/instance.cpp:49),
//  enumerate everything, and score it against MCVR's device gate
//  (src/core/vulkan/physical_device.cpp:112+ / device.cpp:28-39).
//
//  Expected outcome (from source inspection of MoltenVK upstream):
//    - Vulkan 1.4 instance/device + all rasterization-side requirements: PASS
//    - VK_KHR_acceleration_structure / ray_tracing_pipeline / ray_query: ABSENT
//      -> confirms the hardware RT path must be Metal RT, not Vulkan RT
//
//  This probe never fails CI on absent RT: absence is *data*, not an error.
//
#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

/* The exact device-extension gate MCVR applies (physical_device.cpp:112-117,
 * mirrored from device.cpp:28-39) — any missing entry = device rejected. */
static const char *kRequiredDeviceExtensions[] = {
    "VK_KHR_swapchain",
    "VK_KHR_ray_tracing_pipeline",
    "VK_KHR_acceleration_structure",
    "VK_KHR_deferred_host_operations",
    "VK_KHR_push_descriptor",
    "VK_KHR_spirv_1_4",
    "VK_KHR_synchronization_2",
    "VK_KHR_image_format_list",
    "VK_EXT_descriptor_indexing",
    "VK_EXT_extended_dynamic_state2",
    "VK_EXT_extended_dynamic_state3",
    "VK_EXT_vertex_input_dynamic_state",
};
static const size_t kRequiredDeviceExtensionCount =
    sizeof(kRequiredDeviceExtensions) / sizeof(kRequiredDeviceExtensions[0]);

static void print_flags(const char *label, uint32_t major, uint32_t minor, uint32_t patch) {
    printf("%s: %u.%u.%u\n", label, major, minor, patch);
}

static void decodeApiVersion(uint32_t v, uint32_t *major, uint32_t *minor, uint32_t *patch) {
    *major = VK_VERSION_MAJOR(v);
    *minor = VK_VERSION_MINOR(v);
    *patch = VK_VERSION_PATCH(v);
}

int main(int argc, char **argv) {
    const char *dylib = getenv("MVK_DYLIB");
    if (argc > 1) { dylib = argv[1]; }
    if (dylib == NULL || *dylib == '\0') {
        fprintf(stderr, "usage: probe_moltenvk [path/to/libMoltenVK.dylib] (or $MVK_DYLIB)\n");
        return 2;
    }

    printf("== Radiance iOS Port — Chapter 1 — Probe B: MoltenVK capability matrix ==\n");
    printf("[load] dylib = %s\n", dylib);

    void *handle = dlopen(dylib, RTLD_NOW | RTLD_LOCAL);
    if (handle == NULL) {
        printf("FATAL: dlopen failed: %s\n", dlerror());
        return 2;
    }

    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr =
        (PFN_vkGetInstanceProcAddr)dlsym(handle, "vkGetInstanceProcAddr");
    if (vkGetInstanceProcAddr == NULL) {
        printf("FATAL: vkGetInstanceProcAddr not exported\n");
        return 2;
    }

    PFN_vkEnumerateInstanceExtensionProperties vkEnumerateInstanceExtensionProperties =
        (PFN_vkEnumerateInstanceExtensionProperties)
            vkGetInstanceProcAddr(NULL, "vkEnumerateInstanceExtensionProperties");
    PFN_vkCreateInstance vkCreateInstance =
        (PFN_vkCreateInstance)vkGetInstanceProcAddr(NULL, "vkCreateInstance");
    PFN_vkEnumerateInstanceVersion vkEnumerateInstanceVersion =
        (PFN_vkEnumerateInstanceVersion)vkGetInstanceProcAddr(NULL, "vkEnumerateInstanceVersion");
    if (vkEnumerateInstanceExtensionProperties == NULL || vkCreateInstance == NULL) {
        printf("FATAL: instance-level entry points missing\n");
        return 2;
    }

    /* ---- Instance layer ---- */
    uint32_t instanceVersion = 0;
    if (vkEnumerateInstanceVersion != NULL) {
        vkEnumerateInstanceVersion(&instanceVersion);
    } else {
        instanceVersion = VK_API_VERSION_1_0;
    }
    uint32_t vmaj = 0, vmin = 0, vpat = 0;
    decodeApiVersion(instanceVersion, &vmaj, &vmin, &vpat);
    printFlags("[instance] supported Vulkan version", vmaj, vmin, vpat);
    printf("[instance] Vulkan 1.3+ (MCVR instance floor): %s\n",
           (instanceVersion >= VK_API_VERSION_1_3) ? "PASS" : "FAIL");

    uint32_t instExtCount = 0;
    vkEnumerateInstanceExtensionProperties(NULL, &instExtCount, NULL);
    VkExtensionProperties *instExts = malloc(sizeof(VkExtensionProperties) * (instExtCount ? instExtCount : 1));
    vkEnumerateInstanceExtensionProperties(NULL, &instExtCount, instExts);
    printf("[instance] extensions: %u\n", instExtCount);
    for (uint32_t i = 0; i < instExtCount; i++) {
        printf("[instance.ext] %s (spec %u)\n", instExts[i].extensionName, instExts[i].specVersion);
    }

    /* ---- Create a Vulkan 1.3 instance, exactly like MCVR ---- */
    VkApplicationInfo appInfo;
    memset(&appInfo, 0, sizeof(appInfo));
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "radiance-chapter1-probe";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "radiance-ios-port";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = VK_API_VERSION_1_3;

    VkInstanceCreateInfo createInfo;
    memset(&createInfo, 0, sizeof(createInfo));
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    VkResult result = vkCreateInstance(&createInfo, NULL, &instance);
    if (result != VK_SUCCESS) {
        printf("FATAL: vkCreateInstance (apiVersion 1.3) failed with %d\n", result);
        return 2;
    }
    printf("[instance] created with apiVersion=VK_API_VERSION_1_3: PASS\n");

    PFN_vkEnumeratePhysicalDevices vkEnumeratePhysicalDevices =
        (PFN_vkEnumeratePhysicalDevices)vkGetInstanceProcAddr(instance, "vkEnumeratePhysicalDevices");
    PFN_vkEnumerateDeviceExtensionProperties vkEnumerateDeviceExtensionProperties =
        (PFN_vkEnumerateDeviceExtensionProperties)
            vkGetInstanceProcAddr(instance, "vkEnumerateDeviceExtensionProperties");
    PFN_vkGetPhysicalDeviceProperties2 vkGetPhysicalDeviceProperties2 =
        (PFN_vkGetPhysicalDeviceProperties2)
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceProperties2");
    PFN_vkGetPhysicalDeviceFeatures2 vkGetPhysicalDeviceFeatures2 =
        (PFN_vkGetPhysicalDeviceFeatures2)
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2");

    uint32_t deviceCount = 0;
    vkEnumeratePhysicalDevices(instance, &deviceCount, NULL);
    if (deviceCount == 0) {
        printf("FATAL: no physical devices\n");
        return 2;
    }
    VkPhysicalDevice *devices = malloc(sizeof(VkPhysicalDevice) * deviceCount);
    vkEnumeratePhysicalDevices(instance, &deviceCount, devices);
    printf("[device] physical devices: %u\n", deviceCount);

    VkPhysicalDevice physicalDevice = devices[0];

    /* ---- Properties ---- */
    VkPhysicalDeviceProperties2 props2;
    memset(&props2, 0, sizeof(props2));
    props2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2;
    if (vkGetPhysicalDeviceProperties2 != NULL) {
        vkGetPhysicalDeviceProperties2(physicalDevice, &props2);
    }
    uint32_t dmaj = 0, dmin = 0, dpat = 0;
    decodeApiVersion(props2.properties.apiVersion, &dmaj, &dmin, &dpat);
    printf("[device] name=%s\n", props2.properties.deviceName);
    printf("[device] apiVersion=%u.%u.%u driverVersion=0x%x\n", dmaj, dmin, dpat,
           props2.properties.driverVersion);
    printf("[device] deviceType=%u vendorID=0x%x deviceID=0x%x\n",
           (uint32_t)props2.properties.deviceType, props2.properties.vendorID,
           props2.properties.deviceID);
    printf("[device] Vulkan 1.3+ (MCVR device floor, physical_device.cpp:267): %s\n",
           (props2.properties.apiVersion >= VK_API_VERSION_1_3) ? "PASS" : "FAIL");

    /* ---- Device extensions ---- */
    uint32_t devExtCount = 0;
    vkEnumerateDeviceExtensionProperties(physicalDevice, NULL, &devExtCount, NULL);
    VkExtensionProperties *devExts =
        malloc(sizeof(VkExtensionProperties) * (devExtCount ? devExtCount : 1));
    vkEnumerateDeviceExtensionProperties(physicalDevice, NULL, &devExtCount, devExts);
    printf("[device] extensions: %u\n", devExtCount);
    for (uint32_t i = 0; i < devExtCount; i++) {
        printf("[device.ext] %s (spec %u)\n", devExts[i].extensionName, devExts[i].specVersion);
    }

    /* ---- MCVR gate matrix ---- */
    printf("\n[matrix] MCVR device gate (missing extension = device REJECTED):\n");
    uint32_t missing = 0;
    for (size_t i = 0; i < kRequiredDeviceExtensionCount; i++) {
        int found = 0;
        for (uint32_t j = 0; j < devExtCount; j++) {
            if (strcmp(devExts[j].extensionName, kRequiredDeviceExtensions[i]) == 0) {
                found = 1;
                break;
            }
        }
        printf("[matrix] %-45s %s\n", kRequiredDeviceExtensions[i], found ? "PRESENT" : "ABSENT");
        if (!found) { missing++; }
    }

    /* Ray-query / pipeline_library extras (not in MCVR's gate but part of the
     * Vulkan RT surface the port would otherwise want). */
    static const char *kExtraRayExts[] = {
        "VK_KHR_ray_query",
        "VK_KHR_pipeline_library",
        "VK_KHR_ray_tracing_position_fetch",
        "VK_KHR_ray_tracing_maintenance1",
        "VK_KHR_maintenance5",
        "VK_EXT_metal_objects",
    };
    printf("\n[matrix] Vulkan-RT extras (informational):\n");
    for (size_t i = 0; i < sizeof(kExtraRayExts) / sizeof(kExtraRayExts[0]); i++) {
        int found = 0;
        for (uint32_t j = 0; j < devExtCount; j++) {
            if (strcmp(devExts[j].extensionName, kExtraRayExts[i]) == 0) {
                found = 1;
                break;
            }
        }
        printf("[matrix] %-45s %s\n", kExtraRayExts[i], found ? "PRESENT" : "ABSENT");
    }

    /* ---- Features (chained, exactly the ones MCVR consumes) ---- */
    if (vkGetPhysicalDeviceFeatures2 != NULL) {
        VkPhysicalDeviceRayQueryFeaturesKHR rayQueryFeatures;
        memset(&rayQueryFeatures, 0, sizeof(rayQueryFeatures));
        rayQueryFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR;

        VkPhysicalDeviceRayTracingPipelineFeaturesKHR rayPipelineFeatures;
        memset(&rayPipelineFeatures, 0, sizeof(rayPipelineFeatures));
        rayPipelineFeatures.sType =
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_TRACING_PIPELINE_FEATURES_KHR;
        rayPipelineFeatures.pNext = &rayQueryFeatures;

        VkPhysicalDeviceAccelerationStructureFeaturesKHR asFeatures;
        memset(&asFeatures, 0, sizeof(asFeatures));
        asFeatures.sType =
            VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR;
        asFeatures.pNext = &rayPipelineFeatures;

        VkPhysicalDeviceVulkan13Features f13;
        memset(&f13, 0, sizeof(f13));
        f13.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES;
        f13.pNext = &asFeatures;

        VkPhysicalDeviceVulkan12Features f12;
        memset(&f12, 0, sizeof(f12));
        f12.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
        f12.pNext = &f13;

        VkPhysicalDeviceVulkan11Features f11;
        memset(&f11, 0, sizeof(f11));
        f11.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES;
        f11.pNext = &f12;

        VkPhysicalDeviceFeatures2 features2;
        memset(&features2, 0, sizeof(features2));
        features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
        features2.pNext = &f11;

        vkGetPhysicalDeviceFeatures2(physicalDevice, &features2);

        printf("\n[features] MCVR-relevant feature chain:\n");
        printf("[features] shaderDrawParameters (11):        %s\n",
               f11.shaderDrawParameters ? "true" : "false");
        printf("[features] bufferDeviceAddress (12):         %s\n",
               f12.bufferDeviceAddress ? "true" : "false");
        printf("[features] runtimeDescriptorArray (12):      %s\n",
               f12.runtimeDescriptorArray ? "true" : "false");
        printf("[features] descriptorBindingPartiallyBound:  %s\n",
               f12.descriptorBindingPartiallyBound ? "true" : "false");
        printf("[features] hostQueryReset (12):              %s\n",
               f12.hostQueryReset ? "true" : "false");
        printf("[features] timelineSemaphore (12):           %s\n",
               f12.timelineSemaphore ? "true" : "false");
        printf("[features] synchronization2 (13):            %s\n",
               f13.synchronization2 ? "true" : "false");
        printf("[features] maintenance4 (13):                %s\n",
               f13.maintenance4 ? "true" : "false");
        printf("[features] accelerationStructure:            %s\n",
               asFeatures.accelerationStructure ? "true" : "false");
        printf("[features] rayTracingPipeline:               %s\n",
               rayPipelineFeatures.rayTracingPipeline ? "true" : "false");
        printf("[features] rayTracingPipelineTraceRaysIndirect: %s\n",
               rayPipelineFeatures.rayTracingPipelineTraceRaysIndirect ? "true" : "false");
        printf("[features] rayQuery:                         %s\n",
               rayQueryFeatures.rayQuery ? "true" : "false");
    } else {
        printf("[features] vkGetPhysicalDeviceFeatures2 unavailable\n");
    }

    /* ---- Verdict ---- */
    printf("\n");
    if (missing == 0) {
        printf("VERDICT: MOLTENVK_FULL_MCVR_GATE (unlikely on current MoltenVK)\n");
    } else {
        printf("VERDICT: MOLTENVK_MCVR_GATE_MISSING_%u_EXTENSIONS\n", missing);
        printf("NOTE: Vulkan RT is absent (expected). Hardware RT on iOS must be "
               "delivered by a Metal RT backend; the Vulkan/MoltenVK layer only "
               "carries rasterization + compute in the port architecture.\n");
    }
    return 0;
}
