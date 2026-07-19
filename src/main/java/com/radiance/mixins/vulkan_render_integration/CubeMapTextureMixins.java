package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.client.proxy.vulkan.TextureProxy;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.CubeMapTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Imports the title-screen panorama cube map into the Vulkan overlay backend as a samplerCube.
 *
 * <p>{@code CubeMapTexture.doLoad} stitches six faces stacked vertically in one {@link NativeImage},
 * creates a 6-layer {@code GpuTexture} and copies each face in via {@code copyBufferToTexture} -- a
 * route the overlay backend cannot mirror here: the source is a transient staging buffer (never
 * captured) and the destination is a cube, not the 2D bindless array. So {@code TextureUtilMixins}
 * deliberately skips importing the cube {@code GpuTexture} as a 2D image, and instead, once
 * {@code doLoad} has finished, this creates the cube in the backend and uploads its six faces straight
 * from the still-live {@code NativeImage} (the format is always {@code RGBA8_UNORM}, single mip).
 */
@Mixin(CubeMapTexture.class)
public abstract class CubeMapTextureMixins {

    @Inject(method = "doLoad", at = @At("RETURN"))
    private void radiance$uploadCube(NativeImage image, CallbackInfo ci) {
        GpuTexture gpuTexture = ((AbstractTexture) (Object) this).getTexture();
        if (!(gpuTexture instanceof GlTexture glTexture)) {
            return;
        }
        int faceWidth = image.getWidth();
        int faceHeight = image.getHeight() / 6;
        TextureProxy.prepareCubeImage(GpuFormat.RGBA8_UNORM, glTexture.glId(), 1, faceWidth, faceHeight);
        TextureProxy.uploadCube(glTexture.glId(), image.getPointer());
    }
}
