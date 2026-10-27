package net.prisma.rtprobe;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRBufferDeviceAddress;
import org.lwjgl.vulkan.KHRGetMemoryRequirements2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryInstancesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkAccelerationStructureInstanceKHR;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 阶段 T1-T6：真·硬件 RT 管线端到端验证。
 * 构建一个三角形 BLAS -> 一个实例的 TLAS -> 用 ray query 计算着色器打两根射线
 * (一根必中 t=1.0, 一根必脱靶 t=-1.0) -> 回读验证。
 * 全部走 vkCmdBuildAccelerationStructuresKHR（GPU 构建），无任何软件模拟。
 */
final class RtPipeline {

    private static final long FENCE_TIMEOUT = 10_000_000_000L; // 10s
    private static final int USAGE_BDA = KHRBufferDeviceAddress.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT_KHR;

    // 一个顶点缓冲 + 一个 scratch + BLAS + TLAS + 实例缓冲 + 输出缓冲
    private long vBuf, vMem;
    private long scratchBuf, scratchMem;
    private long blasBuf, blasMem;
    private long tlasBuf, tlasMem;
    private long instBuf, instMem;
    private long outBuf, outMem;

    private long blas, tlas;
    private long cmdPool;
    private long descPool, descSetLayout, pipelineLayout, pipeline, shaderModule;

    private RtPipeline() {
    }

    static void run(ProbeReport r, VulkanProbe.Ctx ctx) {
        RtPipeline p = new RtPipeline();
        try {
            p.exec(r, ctx);
        } catch (Throwable t) {
            r.fail("RT 管线执行中断", t);
            r.finish("E", "RT 管线在执行中失败（扩展与特性均已通过，属实现层缺陷）。"
                    + "附整份报告回报，错误位置已在上方 FAIL 行标明。");
        } finally {
            p.cleanup(r, ctx);
        }
    }

    private void exec(ProbeReport r, VulkanProbe.Ctx ctx) throws Exception {
        VkDevice dev = ctx.device;
        try (MemoryStack stack = MemoryStack.stackPush()) {

            // ============ T1 缓冲与内存 ============
            r.stage("T1 缓冲与内存分配");
            int memTypes = findMemoryTypeMask(ctx);
            r.info("可用内存类型掩码 = 0x" + Integer.toHexString(memTypes));

            // 顶点：3 顶点 * 3 float
            allocBuffer(ctx, stack, 36,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | USAGE_BDA, true, "vertex");
            try (MemoryStack s1 = MemoryStack.stackPush()) {
                FloatBuffer fv = MemoryUtil.memFloatBuffer(vMapPtr, 9);
                fv.put(0, 0.0f).put(1, 0.0f).put(2, 0.0f);   // A
                fv.put(3, 1.0f).put(4, 0.0f).put(5, 0.0f);   // B
                fv.put(6, 0.5f).put(7, 1.0f).put(8, 0.0f);   // C (z=0 平面三角形)
            }
            long vertexAddr = bufferAddress(ctx, vBuf);

            // 输出：2 * vec2 (hit t, miss t)
            allocBuffer(ctx, stack, 32, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "output");
            MemoryUtil.memFloatBuffer(outMapPtr, 8).put(new float[8]); // 清零

            // 实例缓冲（TLAS 输入，64 字节对齐）
            allocBuffer(ctx, stack, 256,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR
                            | USAGE_BDA, true, "instances");
            long instAddr = bufferAddress(ctx, instBuf);

            r.ok("顶点/输出/实例缓冲就绪 (vertexAddr=0x"
                    + Long.toHexString(vertexAddr) + ", instAddr=0x" + Long.toHexString(instAddr) + ")");

            // ============ T2 BLAS ============
            r.stage("T2 构建 BLAS (vkCmdBuildAccelerationStructuresKHR, GPU侧)");
            VkAccelerationStructureGeometryKHR triGeomS =
                    VkAccelerationStructureGeometryKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                            .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
            VkAccelerationStructureGeometryTrianglesDataKHR tri =
                    VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_TRIANGLES_DATA_KHR)
                            .vertexFormat(VK_FORMAT_R32G32B32_SFLOAT)
                            .vertexStride(12)
                            .maxVertex(2)
                            .indexType(VK_INDEX_TYPE_NONE_KHR);
            tri.vertexData().deviceAddress(vertexAddr);
            triGeomS.geometry().triangles(tri); // union 写入(拷贝)
            VkAccelerationStructureGeometryKHR.Buffer triGeom =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack).put(0, triGeomS);

            VkAccelerationStructureBuildGeometryInfoKHR blasBuild =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                            .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .geometryCount(1)
                            .pGeometries(triGeom)
                            .srcAccelerationStructure(VK_NULL_HANDLE);

            VkAccelerationStructureBuildSizesInfoKHR blasSizes =
                    sizesFor(ctx, stack, blasBuild, 1);
            r.info(String.format("BLAS 尺寸: AS=%d B, scratch=%d B",
                    blasSizes.accelerationStructureSize(), blasSizes.buildScratchSize()));

            allocBuffer(ctx, stack, blasSizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | USAGE_BDA, false, "blas");
            allocBuffer(ctx, stack, blasSizes.buildScratchSize() + 256,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, "scratch");

            VkAccelerationStructureCreateInfoKHR blasInfo =
                    VkAccelerationStructureCreateInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                            .buffer(blasBuf)
                            .offset(0)
                            .size(blasSizes.accelerationStructureSize());
            LongBuffer pBlas = stack.mallocLong(1);
            check(vkCreateAccelerationStructureKHR(dev, blasInfo, null, pBlas), "vkCreateAccelerationStructureKHR(BLAS)");
            blas = pBlas.get(0);
            blasBuild.dstAccelerationStructure(blas);

            buildOne(ctx, stack, r, "BLAS", blasBuild, 1);

            VkAccelerationStructureDeviceAddressInfoKHR blasAddrInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_DEVICE_ADDRESS_INFO_KHR)
                            .accelerationStructure(blas);
            long blasAddr = KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(dev, blasAddrInfo);
            r.ok("BLAS 构建完成 (deviceAddr=0x" + Long.toHexString(blasAddr) + ")");

            // ============ T3 TLAS ============
            r.stage("T3 构建 TLAS (单实例引用 BLAS)");
            // 写实例结构体到已映射内存
            VkAccelerationStructureInstanceKHR.Buffer instView =
                    new VkAccelerationStructureInstanceKHR.Buffer(
                            MemoryUtil.memByteBuffer(instMapPtr, 256));
            instView.get(0)
                    .instanceCustomIndex(0)
                    .mask(0xFF)
                    .instanceShaderBindingTableRecordOffset(0)
                    .flags(VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR)
                    .accelerationStructureReference(blasAddr);
            FloatBuffer tm = instView.get(0).transform().matrix();
            for (int i = 0; i < 12; i++) {
                tm.put(i, (i == 0 || i == 5 || i == 10) ? 1.0f : 0.0f);
            }

            VkAccelerationStructureGeometryKHR instGeomS =
                    VkAccelerationStructureGeometryKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_KHR)
                            .geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            VkAccelerationStructureGeometryInstancesDataKHR instData =
                    VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_GEOMETRY_INSTANCES_DATA_KHR)
                            .arrayOfPointers(false);
            instData.data().deviceAddress(instAddr);
            instGeomS.geometry().instances(instData);
            VkAccelerationStructureGeometryKHR.Buffer instGeom =
                    VkAccelerationStructureGeometryKHR.calloc(1, stack).put(0, instGeomS);

            VkAccelerationStructureBuildGeometryInfoKHR tlasBuild =
                    VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_GEOMETRY_INFO_KHR)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                            .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                            .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                            .geometryCount(1)
                            .pGeometries(instGeom)
                            .srcAccelerationStructure(VK_NULL_HANDLE);

            VkAccelerationStructureBuildSizesInfoKHR tlasSizes =
                    sizesFor(ctx, stack, tlasBuild, 1);
            r.info(String.format("TLAS 尺寸: AS=%d B, scratch=%d B",
                    tlasSizes.accelerationStructureSize(), tlasSizes.buildScratchSize()));

            if (tlasSizes.buildScratchSize() > blasSizes.buildScratchSize() + 256) {
                r.info("TLAS 需要更大 scratch，重新分配...");
                VK10.vkDestroyBuffer(dev, scratchBuf, null);
                VK10.vkFreeMemory(dev, scratchMem, null);
                allocBuffer(ctx, stack, tlasSizes.buildScratchSize() + 256,
                        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                                | VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, "scratch2");
            }

            allocBuffer(ctx, stack, tlasSizes.accelerationStructureSize(),
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR
                            | USAGE_BDA, false, "tlas");

            VkAccelerationStructureCreateInfoKHR tlasInfo =
                    VkAccelerationStructureCreateInfoKHR.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_CREATE_INFO_KHR)
                            .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                            .buffer(tlasBuf)
                            .offset(0)
                            .size(tlasSizes.accelerationStructureSize());
            LongBuffer pTlas = stack.mallocLong(1);
            check(vkCreateAccelerationStructureKHR(dev, tlasInfo, null, pTlas), "vkCreateAccelerationStructureKHR(TLAS)");
            tlas = pTlas.get(0);
            tlasBuild.dstAccelerationStructure(tlas);

            buildOne(ctx, stack, r, "TLAS", tlasBuild, 1);
            r.ok("TLAS 构建完成");

            // ============ T4 描述符 + ray query 计算管线 ============
            r.stage("T4 创建 ray query 计算管线");
            String spvVariant = ctx.apiVersion >= VK_MAKE_VERSION(1, 2, 0) ? "12" : "14";
            ByteBuffer spirv = loadSpv("/rtprobe/probe" + spvVariant + ".spv");
            r.info("加载 SPIR-V 变体 probe" + spvVariant + ".spv (" + spirv.remaining() + " 字节)");

            VkShaderModuleCreateInfo smci = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(spirv);
            LongBuffer pSm = stack.mallocLong(1);
            check(VK10.vkCreateShaderModule(dev, smci, null, pSm), "vkCreateShaderModule");
            shaderModule = pSm.get(0);

            VkDescriptorSetLayoutBinding.Buffer bindings =
                    VkDescriptorSetLayoutBinding.callocStack(2, stack);
            bindings.get(0).binding(0)
                    .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            bindings.get(1).binding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            VkDescriptorSetLayoutCreateInfo slci = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                    .pBindings(bindings);
            LongBuffer pDsl = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorSetLayout(dev, slci, null, pDsl), "vkCreateDescriptorSetLayout");
            descSetLayout = pDsl.get(0);

            VkPipelineLayoutCreateInfo plci = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(stack.longs(descSetLayout));
            LongBuffer pPl = stack.mallocLong(1);
            check(VK10.vkCreatePipelineLayout(dev, plci, null, pPl), "vkCreatePipelineLayout");
            pipelineLayout = pPl.get(0);

            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.callocStack(2, stack);
            poolSizes.get(0).type(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR).descriptorCount(1);
            poolSizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1);
            VkDescriptorPoolCreateInfo dpci = VkDescriptorPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                    .maxSets(1)
                    .pPoolSizes(poolSizes);
            LongBuffer pDp = stack.mallocLong(1);
            check(VK10.vkCreateDescriptorPool(dev, dpci, null, pDp), "vkCreateDescriptorPool");
            descPool = pDp.get(0);

            VkDescriptorSetAllocateInfo dsai = VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                    .descriptorPool(descPool)
                    .pSetLayouts(stack.longs(descSetLayout));
            LongBuffer pDs = stack.mallocLong(1);
            check(VK10.vkAllocateDescriptorSets(dev, dsai, pDs), "vkAllocateDescriptorSets");
            descSet = pDs.get(0);

            VkWriteDescriptorSetAccelerationStructureKHR asWrite =
                    VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack)
                            .sType(KHRAccelerationStructure.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET_ACCELERATION_STRUCTURE_KHR)
                            .pAccelerationStructures(stack.longs(tlas));
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.callocStack(2, stack);
            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descSet)
                    .dstBinding(0)
                    .descriptorType(KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR)
                    .pNext(asWrite.address());
            VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack);
            bufInfo.get(0).buffer(outBuf).offset(0).range(32);
            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(descSet)
                    .dstBinding(1)
                    .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
                    .pBufferInfo(bufInfo);
            VK10.vkUpdateDescriptorSets(dev, writes, null);

            VkPipelineShaderStageCreateInfo stage = VkPipelineShaderStageCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_COMPUTE_BIT)
                    .module(shaderModule)
                    .pName(stack.UTF8("main"));
            VkComputePipelineCreateInfo.Buffer cpci =
                    VkComputePipelineCreateInfo.calloc(1, stack)
                            .sType(VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO)
                            .stage(stage)
                            .layout(pipelineLayout);
            LongBuffer pPipe = stack.mallocLong(1);
            check(VK10.vkCreateComputePipelines(dev, VK_NULL_HANDLE, cpci, null, pPipe), "vkCreateComputePipelines(ray query)");
            pipeline = pPipe.get(0);
            r.ok("计算管线创建成功（SPIR-V 内含 rayQuery 指令，MoltenVK 需将其转译为 MSL）");

            // ============ T5 dispatch + 回读 ============
            r.stage("T5 提交 dispatch 并回读结果");
            LongBuffer pFence = stack.mallocLong(1);
            VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            check(VK10.vkCreateFence(dev, fci, null, pFence), "vkCreateFence");
            long fence = pFence.get(0);

            VkCommandBuffer cmd = beginOneShot(ctx, stack);
            VK10.vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            VK10.vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                    0, stack.longs(descSet), null);
            VK10.vkCmdDispatch(cmd, 1, 1, 1);
            // compute 写 -> 宿主读 屏障
            VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
            mb.get(0).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            VK10.vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0,
                    mb, null, null);
            endOneShotSubmit(ctx, stack, cmd, fence);

            check(VK10.vkWaitForFences(dev, pFence, true, FENCE_TIMEOUT), "vkWaitForFences(dispatch)");

            FloatBuffer out = MemoryUtil.memFloatBuffer(outMapPtr, 8);
            float tHit = out.get(0), hitPad = out.get(1);
            float tMiss = out.get(2), missPad = out.get(3);
            r.info(String.format("回读: ray[hit ] t = %f (期望 1.0)", tHit));
            r.info(String.format("回读: ray[miss] t = %f (期望 -1.0)", tMiss));
            VK10.vkDestroyFence(dev, fence, null);

            boolean hitOk = Math.abs(tHit - 1.0f) < 0.05f && Math.abs(hitPad) < 1e-4f;
            boolean missOk = tMiss < -0.5f && Math.abs(missPad) < 1e-4f;

            if (hitOk && missOk) {
                r.ok("命中射线 t=1.0 ✓  脱靶射线 t=-1.0 ✓ —— 硬件加速结构 + ray query 求交全部正确");
                r.finish("A", "探针全通过：JAR 环境（启动器内 bundled LWJGL + libMoltenVK.dylib）即可完成"
                        + " BLAS/TLAS 构建、加速结构描述符绑定与 ray query 求交，无 AMFI/dlopen 问题，"
                        + "无需改包。可直接进入下一章：把 Radiance 的 path tracing 管线适配到本环境。");
            } else if (hitOk != missOk) {
                r.finish("D", "结果异常（一根对一根错）-> 求交语义不一致，属 MoltenVK/Metal 转译层缺陷，"
                        + "附报告回报（这是极有价值的数据点）。");
            } else {
                r.finish("D", "回读结果不符（两根射线均异常）-> 加速结构内容或求交未按预期执行，"
                        + "附报告回报（附回读数值）。");
            }
        }
    }

    // ================= 内部工具 =================

    private long descSet; // 描述符集句柄

    /** 查询构建尺寸 */
    private VkAccelerationStructureBuildSizesInfoKHR sizesFor(
            VulkanProbe.Ctx ctx, MemoryStack stack,
            VkAccelerationStructureBuildGeometryInfoKHR info, int maxPrims) {
        IntBuffer primCounts = stack.mallocInt(1);
        primCounts.put(0, maxPrims);
        VkAccelerationStructureBuildSizesInfoKHR sizes =
                VkAccelerationStructureBuildSizesInfoKHR.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_ACCELERATION_STRUCTURE_BUILD_SIZES_INFO_KHR);
        KHRAccelerationStructure.vkGetAccelerationStructureBuildSizesKHR(
                ctx.device, KHRAccelerationStructure.VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                info, primCounts, sizes);
        return sizes;
    }

    /** 单次 AS 构建：命令缓冲 + 前后屏障 + 提交等待 */
    private void buildOne(VulkanProbe.Ctx ctx, MemoryStack stack, ProbeReport r, String name,
                          VkAccelerationStructureBuildGeometryInfoKHR buildInfo, int primCount) {
        LongBuffer pFence = stack.mallocLong(1);
        VkFenceCreateInfo fci = VkFenceCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
        check(VK10.vkCreateFence(ctx.device, fci, null, pFence), "vkCreateFence(build)");

        VkCommandBuffer cmd = beginOneShot(ctx, stack);
        // 构建前屏障：scratch 可写 + AS 可写
        VkMemoryBarrier.Buffer mb = VkMemoryBarrier.calloc(1, stack);
        mb.get(0).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                        | KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR);
        VK10.vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR, 0,
                mb, null, null);

        VkAccelerationStructureBuildRangeInfoKHR range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(stack)
                        .primitiveCount(primCount);
        PointerBuffer pRanges = stack.mallocPointer(1);
        pRanges.put(0, range.address());
        VkAccelerationStructureBuildGeometryInfoKHR.Buffer infosBuf =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack)
                        .put(0, buildInfo);
        KHRAccelerationStructure.vkCmdBuildAccelerationStructuresKHR(cmd, infosBuf, pRanges);

        // 构建后屏障：AS 写 -> 后续读
        VkMemoryBarrier.Buffer mb2 = VkMemoryBarrier.calloc(1, stack);
        mb2.get(0).sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                .srcAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_WRITE_BIT_KHR)
                .dstAccessMask(KHRAccelerationStructure.VK_ACCESS_ACCELERATION_STRUCTURE_READ_BIT_KHR
                        | VK_ACCESS_SHADER_READ_BIT);
        VK10.vkCmdPipelineBarrier(cmd,
                KHRAccelerationStructure.VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0,
                mb2, null, null);

        endOneShotSubmit(ctx, stack, cmd, pFence.get(0));
        check(VK10.vkWaitForFences(ctx.device, pFence, true, FENCE_TIMEOUT),
                "vkWaitForFences(build " + name + ")");
        VK10.vkDestroyFence(ctx.device, pFence.get(0), null);
    }

    private VkCommandBuffer beginOneShot(VulkanProbe.Ctx ctx, MemoryStack stack) {
        if (cmdPool == 0) {
            VkCommandPoolCreateInfo cpci = VkCommandPoolCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(ctx.queueFamily);
            LongBuffer p = stack.mallocLong(1);
            check(VK10.vkCreateCommandPool(ctx.device, cpci, null, p), "vkCreateCommandPool");
            cmdPool = p.get(0);
        }
        VkCommandBufferAllocateInfo cai = VkCommandBufferAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                .commandPool(cmdPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                .commandBufferCount(1);
        PointerBuffer pCmd = stack.mallocPointer(1);
        check(VK10.vkAllocateCommandBuffers(ctx.device, cai, pCmd), "vkAllocateCommandBuffers");
        VkCommandBuffer cmd = new VkCommandBuffer(pCmd.get(0), ctx.device);
        check(VK10.vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfoOneShot(stack)), "vkBeginCommandBuffer");
        return cmd;
    }

    private VkCommandBufferBeginInfo VkCommandBufferBeginInfoOneShot(MemoryStack stack) {
        return org.lwjgl.vulkan.VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
    }

    private void endOneShotSubmit(VulkanProbe.Ctx ctx, MemoryStack stack,
                                  VkCommandBuffer cmd, long fence) {
        check(VK10.vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
        PointerBuffer pCmd = stack.pointers(cmd.address());
        VkSubmitInfo.Buffer si = VkSubmitInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                .pCommandBuffers(pCmd);
        check(VK10.vkQueueSubmit(ctx.queue, si, fence), "vkQueueSubmit");
    }

    private int findMemoryTypeMask(VulkanProbe.Ctx ctx) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties mp =
                    org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.calloc(stack);
            VK10.vkGetPhysicalDeviceMemoryProperties(ctx.pd, mp);
            int mask = 0;
            for (int i = 0; i < mp.memoryTypeCount(); i++) {
                int flags = mp.memoryTypes(i).propertyFlags();
                if ((flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0
                        && (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) {
                    mask |= (1 << i);
                }
            }
            return mask == 0 ? 0x1 : mask;
        }
    }

    private long vMapPtr, instMapPtr, outMapPtr; // 已映射指针

    /** 分配 buffer + memory（统一 host visible coherent，Apple 统一内存下即最优路径） */
    private void allocBuffer(VulkanProbe.Ctx ctx, MemoryStack stack, long size, int usage,
                             boolean needMap, String tag) {
        VkBufferCreateInfo bci = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage)
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
        LongBuffer pBuf = stack.mallocLong(1);
        check(VK10.vkCreateBuffer(ctx.device, bci, null, pBuf), "vkCreateBuffer(" + tag + ")");
        long buf = pBuf.get(0);

        VkMemoryRequirements req = VkMemoryRequirements.calloc(stack);
        VK10.vkGetBufferMemoryRequirements(ctx.device, buf, req);

        int memType = -1;
        try (MemoryStack s2 = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties mp =
                    org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties.calloc(s2);
            VK10.vkGetPhysicalDeviceMemoryProperties(ctx.pd, mp);
            // 优先 DEVICE_LOCAL|HOST_VISIBLE（Apple 统一内存），回退 HOST_VISIBLE|COHERENT
            int bestType = -1;
            for (int i = 0; i < mp.memoryTypeCount(); i++) {
                int flags = mp.memoryTypes(i).propertyFlags();
                boolean hv = (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0;
                boolean hc = (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0;
                boolean dl = (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0;
                if (hv && hc && dl && (req.memoryTypeBits() & (1 << i)) != 0) {
                    bestType = i;
                    break;
                }
                if (hv && hc && bestType == -1 && (req.memoryTypeBits() & (1 << i)) != 0) {
                    bestType = i;
                }
            }
            if (bestType < 0) {
                throw new IllegalStateException("找不到 host-visible 内存类型 (reqBits=0x"
                        + Integer.toHexString(req.memoryTypeBits()) + ")");
            }
            memType = bestType;
        }

        VkMemoryAllocateInfo mai = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(req.size())
                .memoryTypeIndex(memType);
        if ((usage & USAGE_BDA) != 0) {
            org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo flagsInfo =
                    org.lwjgl.vulkan.VkMemoryAllocateFlagsInfo.calloc(stack)
                            .sType(VK12.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO)
                            .flags(VK12.VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT);
            mai.pNext(flagsInfo.address());
        }
        LongBuffer pMem = stack.mallocLong(1);
        check(VK10.vkAllocateMemory(ctx.device, mai, null, pMem), "vkAllocateMemory(" + tag + ")");
        long mem = pMem.get(0);
        check(VK10.vkBindBufferMemory(ctx.device, buf, mem, 0), "vkBindBufferMemory(" + tag + ")");

        switch (tag) {
            case "vertex": vBuf = buf; vMem = mem; break;
            case "output": outBuf = buf; outMem = mem; break;
            case "instances": case "instances2": instBuf = buf; instMem = mem; break;
            case "scratch": case "scratch2": scratchBuf = buf; scratchMem = mem; break;
            case "blas": blasBuf = buf; blasMem = mem; break;
            case "tlas": tlasBuf = buf; tlasMem = mem; break;
            default: throw new IllegalArgumentException(tag);
        }
        if (needMap) {
            PointerBuffer pMap = stack.mallocPointer(1);
            check(VK10.vkMapMemory(ctx.device, mem, 0, req.size(), 0, pMap), "vkMapMemory(" + tag + ")");
            long ptr = pMap.get(0);
            switch (tag) {
                case "vertex": vMapPtr = ptr; break;
                case "output": outMapPtr = ptr; break;
                case "instances": case "instances2": instMapPtr = ptr; break;
                default: break;
            }
        }
    }

    private long bufferAddress(VulkanProbe.Ctx ctx, long buffer) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferDeviceAddressInfo ai = VkBufferDeviceAddressInfo.calloc(stack)
                    .sType(VK12.VK_STRUCTURE_TYPE_BUFFER_DEVICE_ADDRESS_INFO)
                    .buffer(buffer);
            return ctx.apiVersion >= VK_MAKE_VERSION(1, 2, 0)
                    ? VK12.vkGetBufferDeviceAddress(ctx.device, ai)
                    : org.lwjgl.vulkan.KHRBufferDeviceAddress.vkGetBufferDeviceAddressKHR(ctx.device, ai);
        }
    }

    private void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(what + " 失败: " + VulkanProbe.translate(result)
                    + " (" + result + ")");
        }
    }

    /** 从 JAR 资源加载 SPIR-V */
    static ByteBuffer loadSpv(String path) throws Exception {
        try (InputStream in = RtPipeline.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("找不到 JAR 内 SPIR-V 资源 " + path);
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) {
                bos.write(tmp, 0, n);
            }
            byte[] bytes = bos.toByteArray();
            ByteBuffer bb = MemoryUtil.memAlloc(bytes.length);
            bb.put(bytes);
            bb.flip();
            return bb;
        }
    }

    private void cleanup(ProbeReport r, VulkanProbe.Ctx ctx) {
        if (ctx == null || ctx.device == null) {
            return;
        }
        VkDevice dev = ctx.device;
        try { if (pipeline != 0) VK10.vkDestroyPipeline(dev, pipeline, null); } catch (Throwable ignored) {}
        try { if (pipelineLayout != 0) VK10.vkDestroyPipelineLayout(dev, pipelineLayout, null); } catch (Throwable ignored) {}
        try { if (shaderModule != 0) VK10.vkDestroyShaderModule(dev, shaderModule, null); } catch (Throwable ignored) {}
        try { if (descPool != 0) VK10.vkDestroyDescriptorPool(dev, descPool, null); } catch (Throwable ignored) {}
        try { if (descSetLayout != 0) VK10.vkDestroyDescriptorSetLayout(dev, descSetLayout, null); } catch (Throwable ignored) {}
        try { if (blas != 0) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(dev, blas, null); } catch (Throwable ignored) {}
        try { if (tlas != 0) KHRAccelerationStructure.vkDestroyAccelerationStructureKHR(dev, tlas, null); } catch (Throwable ignored) {}
        try { if (cmdPool != 0) VK10.vkDestroyCommandPool(dev, cmdPool, null); } catch (Throwable ignored) {}
        try { if (vBuf != 0) VK10.vkDestroyBuffer(dev, vBuf, null); } catch (Throwable ignored) {}
        try { if (vMem != 0) VK10.vkFreeMemory(dev, vMem, null); } catch (Throwable ignored) {}
        try { if (scratchBuf != 0) VK10.vkDestroyBuffer(dev, scratchBuf, null); } catch (Throwable ignored) {}
        try { if (scratchMem != 0) VK10.vkFreeMemory(dev, scratchMem, null); } catch (Throwable ignored) {}
        try { if (blasBuf != 0) VK10.vkDestroyBuffer(dev, blasBuf, null); } catch (Throwable ignored) {}
        try { if (blasMem != 0) VK10.vkFreeMemory(dev, blasMem, null); } catch (Throwable ignored) {}
        try { if (tlasBuf != 0) VK10.vkDestroyBuffer(dev, tlasBuf, null); } catch (Throwable ignored) {}
        try { if (tlasMem != 0) VK10.vkFreeMemory(dev, tlasMem, null); } catch (Throwable ignored) {}
        try { if (instBuf != 0) VK10.vkDestroyBuffer(dev, instBuf, null); } catch (Throwable ignored) {}
        try { if (instMem != 0) VK10.vkFreeMemory(dev, instMem, null); } catch (Throwable ignored) {}
        try { if (outBuf != 0) VK10.vkDestroyBuffer(dev, outBuf, null); } catch (Throwable ignored) {}
        try { if (outMem != 0) VK10.vkFreeMemory(dev, outMem, null); } catch (Throwable ignored) {}
        try { if (ctx.instance != null) org.lwjgl.vulkan.VK10.vkDestroyInstance(ctx.instance, null); } catch (Throwable ignored) {}
    }
}
