package com.radiance.mixins.vanilla_resource_tracker;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.client.texture.TextureTracker;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2: GL texture allocation moved off {@code TextureUtil.prepareImage(
 * NativeImage$InternalFormat, ...)} (removed) onto
 * {@code GpuDevice.createTexture(..., GpuFormat, ...)}. We capture the allocated
 * {@link GpuTexture}'s GL id + metadata here. Only the colour formats the Vulkan
 * backend tracks (R8/RG8/RGB8/RGBA8 UNORM) are recorded; everything else MC
 * allocates (depth, float, etc.) is ignored.
 *
 * <p>Targets {@code GpuDevice} (was {@code TextureUtil}); the mixin class name is
 * kept for the radiance.mixins.json reference.
 */
@Mixin(GpuDevice.class)
public abstract class TextureUtilMixins {

    @Inject(
        method = "createTexture(Ljava/util/function/Supplier;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("RETURN"))
    private void radiance$trackSupplier(Supplier<String> label, int usage, GpuFormat format,
        int width, int height, int depthOrLayers, int mipLevels,
        CallbackInfoReturnable<GpuTexture> cir) {
        radiance$track(cir.getReturnValue(), format, width, height, mipLevels);
    }

    @Inject(
        method = "createTexture(Ljava/lang/String;ILcom/mojang/blaze3d/GpuFormat;IIII)Lcom/mojang/blaze3d/textures/GpuTexture;",
        at = @At("RETURN"))
    private void radiance$trackString(String label, int usage, GpuFormat format,
        int width, int height, int depthOrLayers, int mipLevels,
        CallbackInfoReturnable<GpuTexture> cir) {
        radiance$track(cir.getReturnValue(), format, width, height, mipLevels);
    }

    private static void radiance$track(GpuTexture gpuTexture, GpuFormat format, int width,
        int height, int mipLevels) {
        if (gpuTexture instanceof GlTexture glTexture && radiance$isTracked(format)) {
            TextureTracker.GLID2Texture.put(glTexture.glId(),
                new TextureTracker.Texture(width, height, format, mipLevels));
        }
    }

    private static boolean radiance$isTracked(GpuFormat format) {
        return switch (format) {
            case R8_UNORM, RG8_UNORM, RGB8_UNORM, RGBA8_UNORM -> true;
            default -> false;
        };
    }
}
