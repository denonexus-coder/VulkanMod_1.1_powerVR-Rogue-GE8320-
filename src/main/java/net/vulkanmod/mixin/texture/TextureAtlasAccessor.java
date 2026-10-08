package net.vulkanmod.mixin.texture;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * Acesso à lista de sprites costurados no atlas, usada pela depuração (SpriteVerifier) para
 * comparar o que o vanilla tem na CPU com o que a GPU guarda.
 */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {

    @Accessor("sprites")
    List<TextureAtlasSprite> getSprites();
}
