package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.radiance.client.proxy.vulkan.GeometryCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The other half of {@link StagingBufferMixins}: where staged bytes acquire a destination.
 *
 * <p>{@code Uploader.copyTo} is the one place that holds the handle, the target {@link GpuBuffer}
 * and the offset together, which is exactly the correlation the draw interception needs -- the
 * underlying {@code copyToBuffer} sees only a GPU-side source, whose contents live in mapped memory
 * we would otherwise have to read back.
 */
@Mixin(StagingBuffer.Uploader.class)
public class StagingBufferUploaderMixins {

    @Inject(method = "copyTo(Lcom/mojang/blaze3d/vertex/StagingBuffer$BufferHandle;"
        + "Lcom/mojang/blaze3d/buffers/GpuBuffer;J)V", at = @At("HEAD"))
    private void radiance$onCopyTo(StagingBuffer.BufferHandle handle, GpuBuffer destination,
        long destinationOffset, CallbackInfo ci) {
        GeometryCapture.recordCopy(handle, destination, destinationOffset);
    }
}
