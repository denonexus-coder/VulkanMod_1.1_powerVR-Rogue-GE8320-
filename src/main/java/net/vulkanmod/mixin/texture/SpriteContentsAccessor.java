package net.vulkanmod.mixin.texture;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Acesso às imagens por nível de mip de um sprite (geradas na CPU pelo vanilla), usada pela
 * depuração (SpriteVerifier) como referência da comparação com os pixels lidos da GPU.
 */
@Mixin(SpriteContents.class)
public interface SpriteContentsAccessor {

    @Accessor("byMipLevel")
    NativeImage[] getByMipLevel();
}
