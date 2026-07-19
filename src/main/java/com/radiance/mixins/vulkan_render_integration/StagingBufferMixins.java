package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.StagingBuffer;
import com.radiance.client.proxy.vulkan.GeometryCapture;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2 geometry capture. Vertex data does not reach {@code CommandEncoder.writeToBuffer} on a
 * discrete GPU: it is staged here and moved with {@code copyToBuffer}, so the
 * {@link com.radiance.client.proxy.vulkan.UniformCapture} hook never saw it and every draw bailed
 * out at the vertex lookup.
 *
 * <p>Hooked on the abstract {@code StagingBuffer} rather than either implementation, so both
 * {@code Cpu} and {@code PersistentlyMapped} are covered by one injection.
 *
 * <p>Split across HEAD and RETURN deliberately: tryAppend consumes the buffer, so the bytes must be
 * snapshotted on the way in, while the handle that identifies them only exists on the way out.
 */
@Mixin(StagingBuffer.class)
public class StagingBufferMixins {

    @Inject(method = "tryAppend(Ljava/nio/ByteBuffer;)"
        + "Lcom/mojang/blaze3d/vertex/StagingBuffer$BufferHandle;", at = @At("HEAD"))
    private void radiance$snapshotStagedBytes(ByteBuffer data,
        CallbackInfoReturnable<StagingBuffer.BufferHandle> cir) {
        GeometryCapture.stageBytes(data);
    }

    @Inject(method = "tryAppend(Ljava/nio/ByteBuffer;)"
        + "Lcom/mojang/blaze3d/vertex/StagingBuffer$BufferHandle;", at = @At("RETURN"))
    private void radiance$bindStagedBytes(ByteBuffer data,
        CallbackInfoReturnable<StagingBuffer.BufferHandle> cir) {
        GeometryCapture.bindStaged(cir.getReturnValue());
    }
}
