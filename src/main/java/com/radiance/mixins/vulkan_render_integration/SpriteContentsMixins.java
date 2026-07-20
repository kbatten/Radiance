package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ISpriteContentsExt;
import net.minecraft.client.renderer.texture.SpriteContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Exposes a sprite's source images so {@link TextureAtlasMixins} can copy them into the Vulkan atlas.
 * See {@link ISpriteContentsExt} for why the atlas has to be filled CPU-side.
 */
@Mixin(SpriteContents.class)
public class SpriteContentsMixins implements ISpriteContentsExt {

    @Shadow
    private NativeImage[] byMipLevel;

    @Override
    public NativeImage[] radiance$getMipLevels() {
        return this.byMipLevel;
    }
}
