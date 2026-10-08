package net.vulkanmod.render.texture;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.texture.VulkanImage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    private static final Map<String, Stats> COPIES = new HashMap<>();
    private static final List<String> COPY_SAMPLES = new ArrayList<>();
    private static final int MAX_COPY_SAMPLES = 6;

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

    /**
     * Conta as cópias GPU→GPU (CommandEncoder.copyTextureToTexture) recebidas por uma imagem de
     * atlas. No 1.21.11 é assim que o vanilla monta o atlas: cada sprite é copiado da textura
     * própria dele para a imagem do atlas.
     */
    public static void recordCopy(VulkanImage src, VulkanImage dst, int level, int dstX, int dstY, int width, int height) {
        if (dst == null || dst.name == null || !dst.name.contains("atlas")) {
            return;
        }

        Stats stats = COPIES.computeIfAbsent(dst.name, k -> new Stats());
        stats.uploads++;
        stats.bytes += (long) width * height * dst.formatSize;
        stats.minWidth = Math.min(stats.minWidth, width);
        stats.minHeight = Math.min(stats.minHeight, height);
        stats.maxWidth = Math.max(stats.maxWidth, width);
        stats.maxHeight = Math.max(stats.maxHeight, height);
        stats.minLevel = Math.min(stats.minLevel, level);
        stats.maxLevel = Math.max(stats.maxLevel, level);

        if (COPY_SAMPLES.size() < MAX_COPY_SAMPLES) {
            COPY_SAMPLES.add(String.format("%s <- %s %dx%d em (%d,%d) nível %d",
                                           dst.name, src == null ? "?" : src.name, width, height, dstX, dstY, level));
        }
    }

    public static void flush() {
        if (!STATS.isEmpty()) {
            STATS.forEach((name, stats) ->
                    Initializer.LOGGER.info("Uploads de \"{}\": {} uploads, {} KiB, tamanhos {}x{}..{}x{}, níveis {}..{}",
                                            name, stats.uploads, stats.bytes / 1024L,
                                            stats.minWidth, stats.minHeight, stats.maxWidth, stats.maxHeight,
                                            stats.minLevel, stats.maxLevel));
            STATS.clear();
        }

        if (!COPIES.isEmpty()) {
            COPIES.forEach((name, stats) ->
                    Initializer.LOGGER.info("Cópias para \"{}\": {} cópias, {} KiB, tamanhos {}x{}..{}x{}, níveis {}..{}",
                                            name, stats.uploads, stats.bytes / 1024L,
                                            stats.minWidth, stats.minHeight, stats.maxWidth, stats.maxHeight,
                                            stats.minLevel, stats.maxLevel));
            COPIES.clear();

            COPY_SAMPLES.forEach(sample -> Initializer.LOGGER.info("  cópia de exemplo: {}", sample));
            COPY_SAMPLES.clear();
        }
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
