package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.mixin_related.extensions.vulkan_render_integration.INativeImageExt;
import java.util.function.Consumer;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: {@code ScreenshotRecorder.takeScreenshot(Framebuffer) -> NativeImage} (synchronous)
 * became {@code Screenshot.takeScreenshot(RenderTarget, int downscaleFactor,
 * Consumer<NativeImage>)} (asynchronous, GpuDevice/CommandEncoder readback). Substitute the
 * mod's Vulkan readback: build a render-target-sized image, fill it from the Vulkan backend
 * ({@code radiance$loadFromTextureImageWithoutUI}), hand it to the callback, and cancel MC's
 * GL readback.
 */
@Mixin(Screenshot.class)
public class ScreenshotRecorderMixins {

    @Inject(
        method = "takeScreenshot(Lcom/mojang/blaze3d/pipeline/RenderTarget;ILjava/util/function/Consumer;)V",
        at = @At("HEAD"),
        cancellable = true)
    private static void redirectTakeScreenshot(RenderTarget target, int downscaleFactor,
        Consumer<NativeImage> callback, CallbackInfo ci) {
        NativeImage nativeImage = new NativeImage(target.width, target.height, false);
        ((INativeImageExt) (Object) nativeImage).radiance$loadFromTextureImageWithoutUI(0, true);
        callback.accept(nativeImage);
        ci.cancel();
    }
}
