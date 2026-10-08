package net.vulkanmod.render.texture;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.HashMap;
import java.util.Map;

/**
 * Depuração: contabiliza os uploads de textura por imagem para ver como o jogo entrega os pixels
 * do atlas ao driver.
 * <p>
 * No 1.21.11 o vanilla sobe o atlas sprite a sprite (TextureAtlas.uploadInitialContents() chama
 * SpriteContents.uploadFirstFrame()), então o esperado são milhares de uploads pequenos (16x16) por
 * nível de mip. Contando as chamadas reais dá para ver de imediato se algum upload grande, algum
 * nível fora de faixa ou algum nível ignorado aparece no caminho.
 * <p>
 * Uma linha por imagem é registrada no início do frame seguinte ao upload ({@link #flush()}).
 */
public abstract class UploadStats {

    private static final Map<String, Stats> STATS = new HashMap<>();

    public static void record(VulkanImage image, int width, int height, int level) {
        if (image == null || image.name == null || !image.name.contains("atlas")) {
            return;
        }

        Stats stats = STATS.computeIfAbsent(image.name, k -> new Stats());
        stats.uploads++;
        stats.bytes += (long) width * height * image.formatSize;
        stats.minWidth = Math.min(stats.minWidth, width);
        stats.minHeight = Math.min(stats.minHeight, height);
        stats.maxWidth = Math.max(stats.maxWidth, width);
        stats.maxHeight = Math.max(stats.maxHeight, height);
        stats.minLevel = Math.min(stats.minLevel, level);
        stats.maxLevel = Math.max(stats.maxLevel, level);
    }

    public static void flush() {
        if (STATS.isEmpty()) {
            return;
        }

        STATS.forEach((name, stats) ->
                Initializer.LOGGER.info("Uploads de \"{}\": {} uploads, {} KiB, tamanhos {}x{}..{}x{}, níveis {}..{}",
                                        name, stats.uploads, stats.bytes / 1024L,
                                        stats.minWidth, stats.minHeight, stats.maxWidth, stats.maxHeight,
                                        stats.minLevel, stats.maxLevel));

        STATS.clear();
    }

    private static final class Stats {
        long uploads;
        long bytes;
        int minWidth = Integer.MAX_VALUE;
        int minHeight = Integer.MAX_VALUE;
        int maxWidth;
        int maxHeight;
        int minLevel = Integer.MAX_VALUE;
        int maxLevel;
    }
}
