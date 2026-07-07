package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IAbstractTextureExt;
import net.minecraft.client.renderer.texture.AbstractTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 26.2 re-architecture of the GL texture-id bridge.
 *
 * <p>In 1.21.4 {@code AbstractTexture} exposed an {@code int glId} plus
 * {@code bindTexture()}/{@code setFilter()}/{@code setClamp()}/{@code clearGlId()}.
 * In 26.2 those are all gone: a texture is a backend-agnostic {@link GpuTexture}
 * (created lazily by the {@code GpuDevice}), and sampling state lives in a
 * {@code GpuSampler}. On the OpenGL backend the underlying GL name is reachable
 * via {@link GlTexture#glId()}.
 *
 * <p>This mixin now only resolves the GL id from the {@link GpuTexture}; that is
 * the single primitive the rest of the mod needs (it called {@code getGlId()} in
 * ~20 places). The former filter/clamp/bind redirects into Vulkan must be
 * re-expressed against the {@code GpuSampler} API -- see TODOs.
 */
@Mixin(AbstractTexture.class)
public class AbstractTextureMixins implements IAbstractTextureExt {

    @Shadow
    protected GpuTexture texture;

    @Shadow
    protected GpuSampler sampler;

    @Override
    public int radiance$getGlIDUnsafe() {
        if (this.texture instanceof GlTexture glTexture) {
            return glTexture.glId();
        }
        // GpuTexture not created yet, or a non-OpenGL backend is active.
        throw new IllegalStateException(
            "Texture GL id is not available (texture=" + this.texture + ")");
    }

    @Override
    public GpuTexture radiance$getTexture() {
        return this.texture;
    }

    @Override
    public GpuSampler radiance$getSampler() {
        return this.sampler;
    }

    // The former setFilter(ZZ)/setClamp(Z) redirects are re-expressed against the new
    // decoupled GpuSampler in ReloadableTextureMixins (sampler is read at texture load
    // and mirrored to TextureProxy.setFilter/setClamp).
    // TODO(26.2): bindTexture()/clearGlId() and lazy id generation via
    //   TextureUtil.generateTextureId() no longer exist -- GpuTextures are owned by
    //   the GpuDevice. The Vulkan importer should consume the GpuTexture directly
    //   (GlTexture#glId() on GL, or the native handle on a Vulkan GpuDevice).
}
