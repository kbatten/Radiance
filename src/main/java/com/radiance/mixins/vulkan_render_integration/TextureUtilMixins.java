package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.client.proxy.vulkan.TextureProxy;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2 Vulkan-substitution core.
 *
 * <p>In 1.21.4 this mixin made MC's GL textures Vulkan-backed by redirecting
 * {@code TextureUtil.generateTextureId()} to a Vulkan id and replacing
 * {@code TextureUtil.prepareImage(...)} with {@code TextureProxy.prepareImage(...)},
 * cancelling the GL allocation. Both {@code TextureUtil} hooks are gone in 26.2.
 *
 * <p>New model -- <b>import, not substitute</b>: let {@code GpuDevice}/{@code GlDevice}
 * create the real GL texture, then import its storage into the Vulkan backend by GL
 * id at {@code createTexture} RETURN. The shared int handle is now the actual
 * {@link GlTexture#glId()} (so {@code generateTextureId()} substitution is no longer
 * needed; {@code TextureProxy.generateTextureId()} remains only for the mod's own
 * auxiliary textures).
 *
 * <p>Pixel uploads are mirrored separately in {@link CommandEncoderMixins} (the new
 * {@code CommandEncoder.writeToTexture} path).
 */
@Mixin(GpuDevice.class)
public abstract class TextureUtilMixins {

    @Inject(
        method = "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("RETURN"))
    private void radiance$importSupplier(Supplier<String> label, int usage, GpuFormat format,
        int width, int height, int depthOrLayers, int mipLevels,
        CallbackInfoReturnable<GpuTexture> cir) {
        if (radiance$isCubeTexture(usage, depthOrLayers)) {
            return;
        }
        radiance$importToVulkan(cir.getReturnValue(), format, width, height, mipLevels);
    }

    @Inject(
        method = "createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("RETURN"))
    private void radiance$importString(String label, int usage, GpuFormat format,
        int width, int height, int depthOrLayers, int mipLevels,
        CallbackInfoReturnable<GpuTexture> cir) {
        if (radiance$isCubeTexture(usage, depthOrLayers)) {
            return;
        }
        radiance$importToVulkan(cir.getReturnValue(), format, width, height, mipLevels);
    }

    // A cube texture (6 cube-compatible layers) must not be imported as a 2D image here -- that would
    // register a wrong 2D image under its GL id and the later cube face upload would fault. The
    // panorama's CubeMapTexture is imported and uploaded as a samplerCube by CubeMapTextureMixins.
    private static boolean radiance$isCubeTexture(int usage, int depthOrLayers) {
        return (usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0 && depthOrLayers == 6;
    }

    private static void radiance$importToVulkan(GpuTexture gpuTexture, GpuFormat format,
        int width, int height, int mipLevels) {
        if (gpuTexture instanceof GlTexture glTexture) {
            // Import the GL texture's storage into the Vulkan backend under its GL id.
            // prepareImage ignores formats the backend does not track.
            TextureProxy.prepareImage(format, glTexture.glId(), mipLevels, width, height);
        }
    }
}
