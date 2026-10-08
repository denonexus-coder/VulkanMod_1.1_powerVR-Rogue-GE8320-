package net.vulkanmod.render.texture;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;

/**
 * Depuração: lê o nível 0 do atlas de blocos direto da memória da GPU e grava em
 * {@code config/atlas_dump.png}.
 * <p>
 * O objetivo é separar as duas hipóteses do bug de textura:
 * <ul>
 *     <li>se o PNG mostra os sprites corretos → os pixels chegam bem à GPU e o problema está na
 *     amostragem (sampler/view/layout);</li>
 *     <li>se o PNG está branco/preto/rajado → o problema está no upload ou nas barreiras de
 *     layout antes da leitura.</li>
 * </ul>
 * Ativado com {@code "atlasDump": true} em config/vulkanmod_settings.json. A leitura é síncrona
 * (vkWaitForFences) e acontece fora do render pass, no início do frame.
 */
public abstract class AtlasDumper {

    private static boolean dumped = false;

    public static void tryDump() {
        if (dumped) {
            return;
        }

        Config config = Initializer.CONFIG;
        if (config == null || !config.atlasDump) {
            return;
        }

        Path directory = Config.getDirectory();
        if (directory == null) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return;
        }

        AbstractTexture atlasTexture = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        if (atlasTexture == null) {
            return;
        }

        // No vanilla getTextureView() lança IllegalStateException antes da textura ser inicializada.
        com.mojang.blaze3d.textures.GpuTextureView atlasView;
        try {
            atlasView = atlasTexture.getTextureView();
        } catch (IllegalStateException e) {
            return;
        }

        if (atlasView == null) {
            return;
        }

        if (!(atlasView.texture() instanceof VkGpuTexture gpuTexture)) {
            return;
        }

        VulkanImage image = gpuTexture.getVulkanImage();
        if (image == null || image.width <= 0 || image.height <= 0) {
            return;
        }

        // O atlas precisa ter sido enviado pelo menos uma vez antes de ser lido.
        if (!image.isLevelUploaded(0)) {
            return;
        }

        dumped = true;

        long imageSize = (long) image.width * image.height * image.formatSize;
        long ptr = MemoryUtil.nmemAlloc(imageSize);

        try {
            ImageUtil.downloadTexture(image, ptr);
            writePng(image, ptr, directory.resolve("atlas_dump.png"));

            Initializer.LOGGER.info("Atlas dump: {}x{}, mips={}, formatSize={}, mipsEnviados={}, layout={} -> {}",
                                    image.width, image.height, image.mipLevels, image.formatSize,
                                    image.contiguousUploadedLevels(), image.getCurrentLayout(),
                                    directory.resolve("atlas_dump.png"));
        } catch (Throwable throwable) {
            Initializer.LOGGER.error("Falha ao gerar o dump do atlas:", throwable);
        } finally {
            MemoryUtil.nmemFree(ptr);
        }
    }

    private static void writePng(VulkanImage image, long ptr, Path path) throws Exception {
        // 4 bytes por pixel no formato R8G8B8A8/B8G8R8A8, sem canal alfa utilitário no atlas.
        boolean bgra = image.format == org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM
                || image.format == org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB;

        BufferedImage bufferedImage = new BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB);

        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                long offset = ptr + (long) (x + y * image.width) * image.formatSize;

                int r = MemoryUtil.memGetByte(offset) & 0xFF;
                int g = MemoryUtil.memGetByte(offset + 1) & 0xFF;
                int b = MemoryUtil.memGetByte(offset + 2) & 0xFF;

                if (bgra) {
                    int swap = r;
                    r = b;
                    b = swap;
                }

                bufferedImage.setRGB(x, y, 0xFF000000 | (r << 16) | (g << 8) | b);
            }
        }

        ImageIO.write(bufferedImage, "png", path.toFile());
    }
}
