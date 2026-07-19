package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.client.proxy.vulkan.GeometryCapture;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.proxy.vulkan.UniformCapture;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 pixel-upload path (counterpart to the allocation import in
 * {@link TextureUtilMixins}).
 *
 * <p>In 1.21.4 {@code vri/NativeImageMixins} hooked {@code NativeImage.uploadInternal(...)}
 * to mirror the pixel upload to the Vulkan backend via {@code TextureProxy.queueUpload}.
 * In 26.2 {@code NativeImage} no longer uploads itself -- uploads go through
 * {@link CommandEncoder#writeToTexture(GpuTexture, NativeImage, int, int, int, int)}
 * (the {@code (GpuTexture, NativeImage)} overload funnels through this one too). We
 * mirror that upload to Vulkan, keyed by the destination's {@link GlTexture#glId()}.
 *
 * <p>NativeImage writes always target UNORM_8 textures whose channel count matches the
 * source (validated by MC), i.e. exactly the R8/RG8/RGB8/RGBA8 formats imported in
 * {@link TextureUtilMixins}, so the destination id is always one the Vulkan backend
 * already knows.
 */
@Mixin(CommandEncoder.class)
public abstract class CommandEncoderMixins {

    @Inject(
        method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V",
        at = @At("HEAD"))
    private void radiance$mirrorWriteToTexture(GpuTexture destination, NativeImage source,
        int mipLevel, int depthOrLayer, int destX, int destY, CallbackInfo ci) {
        if (destination instanceof GlTexture glTexture) {
            TextureProxy.queueUpload(
                source.getPointer(),                   // srcPointer
                source.getPixelBytes().remaining(),    // srcSizeInBytes
                source.getWidth(),                     // srcRowPixels (full image width)
                glTexture.glId(),                      // dstId
                0, 0,                                  // srcOffsetX, srcOffsetY (whole source)
                destX, destY,                          // dstOffsetX, dstOffsetY
                source.getWidth(), source.getHeight(), // width, height
                mipLevel);                             // level
        }
    }

    /**
     * Capture buffer uploads (the built-in UBOs) so the RenderPass draw interception can read the
     * actual uniform values without a GPU readback (counterpart to the writeToTexture mirror above).
     */
    @Inject(
        method = "writeToBuffer(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Ljava/nio/ByteBuffer;)V",
        at = @At("HEAD"))
    private void radiance$captureWriteToBuffer(GpuBufferSlice destination, ByteBuffer data,
        CallbackInfo ci) {
        UniformCapture.capture(destination, data);
        // Also mirror into GeometryCapture: not everything is staged. The shared quad index buffer
        // arrives here, and StagingBuffer$Cpu routes all of its uploads through writeToBuffer, so the
        // draw path must be able to resolve geometry from this route as well as from staging.
        GeometryCapture.captureWrite(destination, data);
    }
}
