package com.radiance.mixins.vulkan_render_integration;

import com.radiance.mixin_related.extensions.vulkan_render_integration.ITextureAtlasSpriteExt;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a stitched sprite's {@code padding} so {@link TextureAtlasMixins} can place its pixels at the
 * padded origin the UVs expect. See {@link ITextureAtlasSpriteExt}.
 */
@Mixin(TextureAtlasSprite.class)
public class TextureAtlasSpriteMixins implements ITextureAtlasSpriteExt {

    @Shadow
    private int padding;

    @Override
    public int radiance$getPadding() {
        return this.padding;
    }
}
