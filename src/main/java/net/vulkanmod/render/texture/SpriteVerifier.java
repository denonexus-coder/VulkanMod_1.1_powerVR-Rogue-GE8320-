package net.vulkanmod.render.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.vulkanmod.Initializer;
import net.vulkanmod.config.Config;
import net.vulkanmod.mixin.texture.SpriteContentsAccessor;
import net.vulkanmod.mixin.texture.TextureAtlasAccessor;
import net.vulkanmod.mixin.texture.image.NativeImageAccessor;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;

import java.util.List;

/**
 * Depuração: compara, sprite a sprite, os pixels que o vanilla tem na CPU
 * (SpriteContents.byMipLevel) com os pixels que a GPU guarda no atlas.
 * <p>
 * É a versão viável da ideia de "subir cada textura individualmente": no 1.21.11 o vanilla já
 * sobe o atlas sprite a sprite, então o que falta é saber em qual lado da fronteira CPU/GPU os
 * pixels se perdem.
 * <ul>
 *     <li>nível 0 idêntico → o upload está certo e o problema é amostragem (sampler/LOD/layout);</li>
 *     <li>nível 0 divergente → o upload ou o driver perde os dados;</li>
 *     <li>nível > 0 vazio/uniforme → os mips nunca chegaram à GPU.</li>
 * </ul>
 * Roda uma única vez, quando {@code atlasDump} está ativo, antes do frame começar.
 */
public abstract class SpriteVerifier {

    private static final int SPRITES_TO_CHECK = 8;
    private static final int MAX_DIVERGENT_LOGS = 5;

    private static boolean verified = false;

    public static void verify() {
        if (verified) {
            return;
        }

        Config config = Initializer.CONFIG;
        if (config == null || !config.atlasDump) {
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

        GpuTextureView view;
        try {
            view = atlasTexture.getTextureView();
        } catch (IllegalStateException e) {
            return;
        }

        if (view == null || !(view.texture() instanceof VkGpuTexture gpuTexture)) {
            return;
        }

        VulkanImage image = gpuTexture.getVulkanImage();
        if (image == null || image.width <= 0 || image.height <= 0 || !image.isLevelUploaded(0)) {
            return;
        }

        List<TextureAtlasSprite> sprites;
        try {
            sprites = ((TextureAtlasAccessor) (Object) atlasTexture).getSprites();
        } catch (Throwable throwable) {
            Initializer.LOGGER.warn("SpriteVerifier: sem acesso à lista de sprites: {}", throwable.toString());
            verified = true;
            return;
        }

        if (sprites == null || sprites.isEmpty()) {
            return;
        }

        verified = true;

        int ok = 0, divergent = 0, skipped = 0, logged = 0;
        int step = Math.max(1, sprites.size() / SPRITES_TO_CHECK);

        for (int i = 0; i < sprites.size(); i += step) {
            String problem = verifySprite(sprites.get(i), image);

            if (problem == null) {
                skipped++;
            } else if (problem.isEmpty()) {
                ok++;
            } else {
                divergent++;
                if (logged < MAX_DIVERGENT_LOGS) {
                    logged++;
                    Initializer.LOGGER.warn("Sprite com problema: {}", problem);
                }
            }
        }

        Initializer.LOGGER.info("SpriteVerifier ({} de {} sprites do atlas): {} conferem, {} com problema, {} pulados",
                                ok + divergent + skipped, sprites.size(), ok, divergent, skipped);
    }

    /**
     * @return {@code null} quando o sprite não pôde ser verificado, uma string vazia quando ele
     *         confere e a descrição dos problemas quando não confere
     */
    private static String verifySprite(TextureAtlasSprite sprite, VulkanImage image) {
        SpriteContents contents = sprite.contents();

        NativeImage[] byMipLevel;
        try {
            byMipLevel = ((SpriteContentsAccessor) (Object) contents).getByMipLevel();
        } catch (Throwable throwable) {
            return null;
        }

        if (byMipLevel == null || byMipLevel.length == 0 || byMipLevel[0] == null) {
            return null;
        }

        long cpuPixels = ((NativeImageAccessor) (Object) byMipLevel[0]).getPixels();
        if (cpuPixels == 0L) {
            return null;
        }

        // A imagem da GPU pode vir em R8G8B8A8 ou B8G8R8A8; o vanilla é sempre RGBA.
        boolean bgra = image.format == VK10.VK_FORMAT_B8G8R8A8_UNORM
                || image.format == VK10.VK_FORMAT_B8G8R8A8_SRGB;

        StringBuilder problems = new StringBuilder();
        int levels = Math.min(byMipLevel.length, image.mipLevels);

        for (int level = 0; level < levels; level++) {
            NativeImage cpuImage = byMipLevel[level];
            if (cpuImage == null) {
                continue;
            }

            int width = cpuImage.getWidth(), height = cpuImage.getHeight();
            if (width <= 0 || height <= 0) {
                continue;
            }

            // O vanilla coloca o sprite no nível L em (x >> L, y >> L), o mesmo deslocamento usado
            // nas UVs (TextureAtlasSprite.uploadSpriteUbo).
            int x = sprite.getX() >> level, y = sprite.getY() >> level;
            if (x < 0 || y < 0 || x + width > Math.max(1, image.width >> level)
                    || y + height > Math.max(1, image.height >> level)) {
                problems.append("[nível ").append(level).append(": retângulo fora da imagem] ");
                continue;
            }

            long gpu = MemoryUtil.nmemAlloc((long) width * height * image.formatSize);
            try {
                ImageUtil.downloadTextureRegion(image, level, x, y, width, height, gpu);

                if (level == 0) {
                    long reference = ((NativeImageAccessor) (Object) cpuImage).getPixels();
                    if (reference == 0L) {
                        return null;
                    }

                    int different = countDifferences(reference, gpu, width * height, image.formatSize, bgra);
                    if (different > 0) {
                        problems.append("[nível 0: ").append(different).append('/').append(width * height)
                                 .append(" pixels diferentes] ");
                    }
                } else if (isUniform(gpu, width * height, image.formatSize, bgra)) {
                    // Um nível de mip inteiro de uma cor só significa que o mipmap não chegou.
                    problems.append("[nível ").append(level).append(": vazio/uniforme] ");
                }
            } finally {
                MemoryUtil.nmemFree(gpu);
            }
        }

        if (problems.length() == 0) {
            return "";
        }

        return contents.name() + " (" + sprite.getX() + "," + sprite.getY() + ", " + width0(byMipLevel) + "x"
                + height0(byMipLevel) + "): " + problems;
    }

    private static int width0(NativeImage[] byMipLevel) {
        return byMipLevel[0] == null ? 0 : byMipLevel[0].getWidth();
    }

    private static int height0(NativeImage[] byMipLevel) {
        return byMipLevel[0] == null ? 0 : byMipLevel[0].getHeight();
    }

    private static int countDifferences(long cpu, long gpu, int pixels, int gpuFormatSize, boolean gpuIsBgra) {
        int different = 0;

        for (int i = 0; i < pixels; i++) {
            long cpuOffset = (long) i * 4;
            long gpuOffset = (long) i * gpuFormatSize;

            int r = MemoryUtil.memGetByte(gpu + gpuOffset) & 0xFF;
            int g = MemoryUtil.memGetByte(gpu + gpuOffset + 1) & 0xFF;
            int b = MemoryUtil.memGetByte(gpu + gpuOffset + 2) & 0xFF;

            if (gpuIsBgra) {
                int swap = r;
                r = b;
                b = swap;
            }

            if (r != (MemoryUtil.memGetByte(cpu + cpuOffset) & 0xFF)
                    || g != (MemoryUtil.memGetByte(cpu + cpuOffset + 1) & 0xFF)
                    || b != (MemoryUtil.memGetByte(cpu + cpuOffset + 2) & 0xFF)
                    || (MemoryUtil.memGetByte(gpu + gpuOffset + 3) & 0xFF) != (MemoryUtil.memGetByte(cpu + cpuOffset + 3) & 0xFF)) {
                different++;
            }
        }

        return different;
    }

    private static boolean isUniform(long gpu, int pixels, int formatSize, boolean bgra) {
        if (pixels == 0) {
            return true;
        }

        int first = pixel(gpu, 0, formatSize, bgra);

        for (int i = 1; i < pixels; i++) {
            if (pixel(gpu, i, formatSize, bgra) != first) {
                return false;
            }
        }

        return true;
    }

    private static int pixel(long ptr, int index, int formatSize, boolean bgra) {
        long offset = (long) index * formatSize;

        int r = MemoryUtil.memGetByte(ptr + offset) & 0xFF;
        int g = MemoryUtil.memGetByte(ptr + offset + 1) & 0xFF;
        int b = MemoryUtil.memGetByte(ptr + offset + 2) & 0xFF;

        if (bgra) {
            int swap = r;
            r = b;
            b = swap;
        }

        int a = MemoryUtil.memGetByte(ptr + offset + 3) & 0xFF;

        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
