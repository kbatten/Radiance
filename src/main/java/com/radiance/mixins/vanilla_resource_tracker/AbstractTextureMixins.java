package com.radiance.mixins.vanilla_resource_tracker;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Base mixin for the texture-tracking mixins.
 *
 * <p>26.2: {@code AbstractTexture#getGlId()}/{@code bindTexture()} were removed.
 * A texture is now a {@link GpuTexture} (a {@link GlTexture} on the OpenGL
 * backend, exposing {@link GlTexture#glId()}). Subclasses resolve the GL name via
 * {@link #radiance$glId()} instead of the old {@code getGlId()} shadow.
 *
 * <p>NOTE: the {@link GpuTexture} is created lazily by the {@code GpuDevice}, so
 * {@link #radiance$glId()} returns 0 until the texture has actually been
 * uploaded/used. Tracking code must resolve the id lazily (when the Vulkan
 * importer needs it), not eagerly at registration/upload time as before.
 */
@Mixin(AbstractTexture.class)
public abstract class AbstractTextureMixins {

    @Shadow
    protected GpuTexture texture;

    /** GL texture name on the OpenGL backend, or 0 if not yet created. */
    protected int radiance$glId() {
        return this.texture instanceof GlTexture glTexture ? glTexture.glId() : 0;
    }
}
