package net.vulkanmod.vulkan.texture;

import net.vulkanmod.Initializer;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public abstract class ImageUtil {

    // Cache of vkGetPhysicalDeviceFormatProperties() results for the blit based mip generation.
    private static final Map<Integer, Boolean> LINEAR_BLIT_FORMATS = new HashMap<>();

    public static void copyBufferToImageCmd(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer,
                                            long image, int arrayLayer,
                                            int mipLevel, int width, int height, int xOffset, int yOffset,
                                            int bufferOffset, int bufferRowLenght, int bufferImageHeight) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(bufferRowLenght);
        region.bufferImageHeight(bufferImageHeight);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        region.imageSubresource().mipLevel(mipLevel);
        region.imageSubresource().baseArrayLayer(arrayLayer);
        region.imageSubresource().layerCount(1);
        region.imageOffset().set(xOffset, yOffset, 0);
        region.imageExtent(VkExtent3D.calloc(stack).set(width, height, 1));

        vkCmdCopyBufferToImage(commandBuffer, buffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
    }

    public static void downloadTexture(VulkanImage image, long ptr) {
        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getCurrentLayout();
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionImageLayout(stack, commandBuffer.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            long imageSize = (long) image.width * image.height * image.formatSize;

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            PointerBuffer pStagingAllocation = stack.pointers(0L);
            MemoryManager.getInstance().createBuffer(imageSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                                     pStagingBuffer, pStagingAllocation);

            copyImageToBuffer(stack, commandBuffer.getHandle(), pStagingBuffer.get(0), image.getId(), 0, image.width,
                              image.height, 0, 0, 0, 0, 0);
            image.transitionImageLayout(stack, commandBuffer.getHandle(), prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);

            MemoryManager.MapAndCopy(pStagingAllocation.get(0),
                                     (data) -> VUtil.memcpy(data.getByteBuffer(0, (int) imageSize), ptr));

            MemoryManager.freeBuffer(pStagingBuffer.get(0), pStagingAllocation.get(0));
        }
    }

    /**
     * Lê um retângulo de um nível de mip direto da memória da GPU para {@code ptr}, de forma
     * síncrona, e devolve a imagem ao layout que ela estava. Usado pela depuração para comparar os
     * pixels que o vanilla tem na CPU com os que a GPU realmente guarda.
     */
    public static void downloadTextureRegion(VulkanImage image, int mipLevel, int x, int y,
                                             int width, int height, long ptr) {
        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getCurrentLayout();
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionImageLayout(stack, commandBuffer.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            long imageSize = (long) width * height * image.formatSize;

            LongBuffer pStagingBuffer = stack.mallocLong(1);
            PointerBuffer pStagingAllocation = stack.pointers(0L);
            MemoryManager.getInstance().createBuffer(imageSize, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                                     VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                                     pStagingBuffer, pStagingAllocation);

            copyImageToBuffer(stack, commandBuffer.getHandle(), pStagingBuffer.get(0), image.getId(), mipLevel,
                              width, height, x, y, 0, 0, 0);
            image.transitionImageLayout(stack, commandBuffer.getHandle(), prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);

            MemoryManager.MapAndCopy(pStagingAllocation.get(0),
                                     (data) -> VUtil.memcpy(data.getByteBuffer(0, (int) imageSize), ptr));

            MemoryManager.freeBuffer(pStagingBuffer.get(0), pStagingAllocation.get(0));
        }
    }

    public static void copyImageToBuffer(Buffer buffer, VulkanImage image, int mipLevel,
                                         int width, int height, int xOffset, int yOffset,
                                         long bufferOffset, int bufferRowLength, int bufferImageHeight
    ) {
        try (MemoryStack stack = stackPush()) {
            int prevLayout = image.getCurrentLayout();
            CommandPool.CommandBuffer commandBuffer = DeviceManager.getGraphicsQueue().beginCommands();
            image.transitionImageLayout(stack, commandBuffer.getHandle(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            copyImageToBuffer(stack, commandBuffer.getHandle(), buffer.getId(), image.getId(), mipLevel, width,
                              height, xOffset, yOffset, bufferOffset, bufferRowLength, bufferImageHeight);
            image.transitionImageLayout(stack, commandBuffer.getHandle(), prevLayout);

            long fence = DeviceManager.getGraphicsQueue().submitCommands(commandBuffer);
            vkWaitForFences(DeviceManager.vkDevice, fence, true, VUtil.UINT64_MAX);
        }
    }

    public static void copyImageToBuffer(MemoryStack stack, VkCommandBuffer commandBuffer, long buffer, long image,
                                         int mipLevel, int width, int height, int xOffset, int yOffset,
                                         long bufferOffset, int bufferRowLength, int bufferImageHeight
    ) {
        VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
        region.bufferOffset(bufferOffset);
        region.bufferRowLength(bufferRowLength);
        region.bufferImageHeight(bufferImageHeight);
        region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
        region.imageSubresource().mipLevel(mipLevel);
        region.imageSubresource().baseArrayLayer(0);
        region.imageSubresource().layerCount(1);
        region.imageOffset().set(xOffset, yOffset, 0);
        region.imageExtent().set(width, height, 1);

        vkCmdCopyImageToBuffer(commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, region);
    }

    public static void blitFramebuffer(VulkanImage srcImage, VulkanImage dstImage) {
        blitFramebuffer(srcImage, dstImage, VK_FILTER_NEAREST);
    }

    public static void blitFramebuffer(VulkanImage srcImage, VulkanImage dstImage, int filtering) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

            Renderer.getInstance().endRenderPass(commandBuffer);

            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            srcImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.srcOffsets(1, VkOffset3D.calloc(stack).set(srcImage.width, srcImage.height, 1));
            blit.srcSubresource()
                .aspectMask(srcImage.aspect)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
            blit.dstOffsets(1, VkOffset3D.calloc(stack).set(dstImage.width, dstImage.height, 1));
            blit.dstSubresource()
                .aspectMask(dstImage.aspect)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            vkCmdBlitImage(commandBuffer, srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, filtering);

            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    public static void blitFramebuffer(VulkanImage dstImage, int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1) {
        // TODO: implement blit
//        blitFramebuffer(Renderer.getInstance().getSwapChain().getColorAttachment(), dstImage);
    }

    public static void blitFramebuffer(VulkanImage srcImage, VulkanImage dstImage,
                                       int srcX0, int srcY0, int srcX1, int srcY1,
                                       int dstX0, int dstY0, int dstX1, int dstY1,
                                       int filtering
    ) {
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();

            Renderer.getInstance().endRenderPass(commandBuffer);

            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            srcImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.srcOffsets(0, VkOffset3D.calloc(stack).set(srcX0, srcY0, 0));
            blit.srcOffsets(1, VkOffset3D.calloc(stack).set(srcX1, srcY1, 1));
            blit.srcSubresource()
                .aspectMask(srcImage.aspect)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            blit.dstOffsets(0, VkOffset3D.calloc(stack).set(dstX0, dstY0, 0));
            blit.dstOffsets(1, VkOffset3D.calloc(stack).set(dstX1, dstY1, 1));
            blit.dstSubresource()
                .aspectMask(dstImage.aspect)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

            vkCmdBlitImage(commandBuffer, srcImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           dstImage.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, filtering);

            dstImage.transitionImageLayout(stack, commandBuffer, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    }

    /**
     * Generates every mip level of {@code image} that does not hold valid data yet.
     * <p>
     * The commands are recorded into the command buffer used for texture uploads, so they always
     * run after the upload of the base level, and the image is registered so that it ends up in
     * VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL before the frame is submitted.
     * <p>
     * This replaces the old implementation, which was never called on the block atlas path
     * (only from the legacy GL glue) and left the destination levels in TRANSFER_DST layout.
     */
    public static void generateMipmaps(VulkanImage image) {
        if (image == null) {
            return;
        }

        VkCommandBuffer commandBuffer = ImageUploadHelper.INSTANCE.getOrStartCommandBuffer().getHandle();
        ensureMipChain(image, commandBuffer);

        SpriteUpdateUtil.addTransitionedLayout(image);
    }

    /**
     * Whether the mip chain of {@code image} still has holes that have to be filled before the
     * image is sampled with a mip level above 0.
     * <p>
     * Cheap enough to run on every transition to VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL: it
     * only returns true for sampled color images whose base level was uploaded while some of the
     * levels above it are still missing (the game only uploads level 0 and expects the mipmaps
     * to be generated).
     */
    public static boolean needsMipGeneration(VulkanImage image) {
        if (image == null || image.getId() == 0L || image.mipLevels <= 1) {
            return false;
        }

        // Samplers clamped to mip level 0 never fetch the higher levels (Plan B fallback).
        if (image.getSamplerMaxLod() <= 0) {
            return false;
        }

        // vkCmdBlitImage() needs both transfer usages and a color aspect.
        int transferUsage = VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if ((image.usage & transferUsage) != transferUsage || image.aspect != VK_IMAGE_ASPECT_COLOR_BIT) {
            return false;
        }

        if (!supportsLinearBlit(image.format)) {
            return false;
        }

        // Plano B (atlasMipmaps = false): nada acima do nível 0 é amostrado, então não gera nada.
        // Também evita comandos de blit desnecessários (e possivelmente mal suportados) no driver.
        if (Initializer.CONFIG != null && !Initializer.CONFIG.atlasMipmaps) {
            return false;
        }

        // Every level above 0 is derived from the base level: without it there is nothing to generate.
        if (!image.isLevelUploaded(0)) {
            return false;
        }

        return image.contiguousUploadedLevels() < image.mipLevels;
    }

    /**
     * Builds the missing mip levels of {@code image} with a linear blit chain and leaves the
     * whole image in VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL.
     * <p>
     * Levels {@code [0, valid - 1)} were left untouched (still in TRANSFER_DST), levels
     * {@code [valid - 1, mipLevels - 1)} are turned into TRANSFER_SRC while they are read and the
     * last level stays in TRANSFER_DST, so the three closing barriers cover every level exactly
     * once with the layout it is actually in.
     */
    public static void ensureMipChain(VulkanImage image, VkCommandBuffer commandBuffer) {
        if (commandBuffer == null || !needsMipGeneration(image)) {
            return;
        }

        try (MemoryStack stack = stackPush()) {
            // The whole chain has to be in TRANSFER_DST before the missing levels can be written.
            VulkanImage.transitionImageLayout(stack, commandBuffer, image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);

            final int validLevels = image.contiguousUploadedLevels();
            int srcLevel = validLevels - 1;

            transitionMipLevels(stack, commandBuffer, image, srcLevel, 1,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);

            for (int dstLevel = validLevels; dstLevel < image.mipLevels; dstLevel++) {
                blitMipLevel(stack, commandBuffer, image, srcLevel, dstLevel);

                if (dstLevel + 1 < image.mipLevels) {
                    transitionMipLevels(stack, commandBuffer, image, dstLevel, 1,
                                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                                        VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
                }

                srcLevel = dstLevel;
            }

            final int shaderStages = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

            // Levels [0, validLevels - 1) are still in TRANSFER_DST.
            if (validLevels > 1) {
                transitionMipLevels(stack, commandBuffer, image, 0, validLevels - 1,
                                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                                    shaderStages, VK_ACCESS_SHADER_READ_BIT);
            }

            // Levels [validLevels - 1, mipLevels - 1) were used as blit sources.
            transitionMipLevels(stack, commandBuffer, image, validLevels - 1, image.mipLevels - validLevels,
                                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                                shaderStages, VK_ACCESS_SHADER_READ_BIT);

            // The last level was only written to.
            transitionMipLevels(stack, commandBuffer, image, image.mipLevels - 1, 1,
                                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                                shaderStages, VK_ACCESS_SHADER_READ_BIT);

            image.setCurrentLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            image.markMipsGenerated();

            Initializer.LOGGER.info("Mips gerados na GPU para \"{}\": níveis {}..{} (mips={}, enviados={})",
                                    image.name, validLevels, image.mipLevels - 1, image.mipLevels, validLevels);
        }
    }

    private static void transitionMipLevels(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image,
                                            int baseLevel, int levelCount, int oldLayout, int newLayout,
                                            int srcStage, int srcAccessMask, int dstStage, int dstAccessMask) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        barrier.oldLayout(oldLayout);
        barrier.newLayout(newLayout);
        barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.image(image.getId());

        barrier.subresourceRange().baseMipLevel(baseLevel);
        barrier.subresourceRange().levelCount(levelCount);
        barrier.subresourceRange().baseArrayLayer(0);
        barrier.subresourceRange().layerCount(VK_REMAINING_ARRAY_LAYERS);
        barrier.subresourceRange().aspectMask(image.aspect);

        barrier.srcAccessMask(srcAccessMask);
        barrier.dstAccessMask(dstAccessMask);

        vkCmdPipelineBarrier(commandBuffer, srcStage, dstStage, 0, null, null, barrier);
    }

    private static void blitMipLevel(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image,
                                     int srcLevel, int dstLevel) {
        int srcWidth = Math.max(1, image.width >> srcLevel);
        int srcHeight = Math.max(1, image.height >> srcLevel);
        int dstWidth = Math.max(1, image.width >> dstLevel);
        int dstHeight = Math.max(1, image.height >> dstLevel);

        VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
        blit.srcOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
        blit.srcOffsets(1, VkOffset3D.calloc(stack).set(srcWidth, srcHeight, 1));
        blit.srcSubresource()
            .aspectMask(image.aspect)
            .mipLevel(srcLevel)
            .baseArrayLayer(0)
            .layerCount(image.arrayLayers);

        blit.dstOffsets(0, VkOffset3D.calloc(stack).set(0, 0, 0));
        blit.dstOffsets(1, VkOffset3D.calloc(stack).set(dstWidth, dstHeight, 1));
        blit.dstSubresource()
            .aspectMask(image.aspect)
            .mipLevel(dstLevel)
            .baseArrayLayer(0)
            .layerCount(image.arrayLayers);

        vkCmdBlitImage(commandBuffer, image.getId(), VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                       image.getId(), VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, blit, VK_FILTER_LINEAR);
    }

    /**
     * Whether {@code format} can be used both as blit source and destination with linear
     * filtering in optimal tiling. R8G8B8A8_UNORM/SRGB report all three features on the
     * PowerVR Rogue GE8320 (see the gpuinfo dump), so the atlas qualifies.
     */
    public static boolean supportsLinearBlit(int format) {
        Boolean cached = LINEAR_BLIT_FORMATS.get(format);
        if (cached != null) {
            return cached;
        }

        boolean supported = false;

        if (DeviceManager.physicalDevice != null) {
            try (MemoryStack stack = stackPush()) {
                VkFormatProperties properties = VkFormatProperties.malloc(stack);
                vkGetPhysicalDeviceFormatProperties(DeviceManager.physicalDevice, format, properties);

                int required = VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT
                        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;

                supported = (properties.optimalTilingFeatures() & required) == required;
            }
        }

        LINEAR_BLIT_FORMATS.put(format, supported);

        if (!supported) {
            Initializer.LOGGER.warn("Format 0x{} cannot be blitted with linear filtering: its mip levels will not be generated",
                                    Integer.toHexString(format));
        }

        return supported;
    }

    public static void imageTransferMemoryBarrier(MemoryStack stack, VkCommandBuffer commandBuffer, VulkanImage image, int baseLevel) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        barrier.oldLayout(image.getCurrentLayout());
        barrier.newLayout(image.getCurrentLayout());
        barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.image(image.getId());

        barrier.subresourceRange().baseMipLevel(baseLevel);
        barrier.subresourceRange().levelCount(1);
        barrier.subresourceRange().baseArrayLayer(0);
        barrier.subresourceRange().layerCount(VK_REMAINING_ARRAY_LAYERS);

        barrier.subresourceRange().aspectMask(image.aspect);

        barrier.srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT);
        barrier.dstAccessMask(VK_ACCESS_MEMORY_WRITE_BIT);

        vkCmdPipelineBarrier(commandBuffer,
                             VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                             0,
                             null,
                             null,
                             barrier);
    }
}
