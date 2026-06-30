package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.client.constant.VulkanConstants;
import com.radiance.client.proxy.vulkan.TextureProxy;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.client.renderer.texture.TextureContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 sampler (filter/clamp) path.
 *
 * <p>Was: {@code vri/AbstractTextureMixins} redirected {@code AbstractTexture.setFilter(ZZ)}
 * / {@code setClamp(Z)} to {@code TextureProxy.setFilter}/{@code setClamp}. Those methods
 * are gone -- sampling state is now a decoupled {@link GpuSampler} chosen when a texture
 * loads. {@link ReloadableTexture#apply} sets the sampler (from clamp/blur) and then
 * creates the {@code GpuTexture}, so at its RETURN both the GL id and the sampler are
 * known; mirror the sampler to the Vulkan backend there.
 *
 * <p>Other texture types ({@code DynamicTexture}, {@code TextureAtlas}) pick their sampler
 * the same way and would need an equivalent hook if their Vulkan sampling must match.
 */
@Mixin(ReloadableTexture.class)
public abstract class ReloadableTextureMixins {

    @Shadow
    protected GpuTexture texture;

    @Shadow
    protected GpuSampler sampler;

    @Inject(
        method = "apply(Lnet/minecraft/client/renderer/texture/TextureContents;)V",
        at = @At("RETURN"))
    private void radiance$pushSampler(TextureContents contents, CallbackInfo ci) {
        if (!(this.texture instanceof GlTexture glTexture) || this.sampler == null) {
            return;
        }
        int id = glTexture.glId();

        boolean linear = this.sampler.getMagFilter() == FilterMode.LINEAR;
        int filter = (linear
            ? VulkanConstants.VkFilter.VK_FILTER_LINEAR
            : VulkanConstants.VkFilter.VK_FILTER_NEAREST).getValue();
        int mipmap = (this.sampler.getMaxLod().isPresent()
            ? (linear
                ? VulkanConstants.VkSamplerMipmapMode.VK_SAMPLER_MIPMAP_MODE_LINEAR
                : VulkanConstants.VkSamplerMipmapMode.VK_SAMPLER_MIPMAP_MODE_NEAREST)
            : VulkanConstants.VkSamplerMipmapMode.VK_SAMPLER_MIPMAP_MODE_NEAREST).getValue();
        TextureProxy.setFilter(id, filter, mipmap);

        int clamp = (this.sampler.getAddressModeU() == AddressMode.CLAMP_TO_EDGE
            ? VulkanConstants.VkSamplerAddressMode.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
            : VulkanConstants.VkSamplerAddressMode.VK_SAMPLER_ADDRESS_MODE_REPEAT).getValue();
        TextureProxy.setClamp(id, clamp);
    }
}
