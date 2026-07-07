package com.radiance.mixin_related.extensions.vulkan_render_integration;

import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;

/**
 * Exposes {@code AbstractTexture}'s {@code texture} / {@code sampler} fields (both declared on
 * {@code AbstractTexture}) to code holding a subclass instance -- Mixin can't {@code @Shadow}
 * inherited fields on a subclass target, so subclass mixins (e.g. {@code ReloadableTextureMixins})
 * read them through this accessor.
 */
public interface IAbstractTextureExt {

    int radiance$getGlIDUnsafe();

    GpuTexture radiance$getTexture();

    GpuSampler radiance$getSampler();
}
