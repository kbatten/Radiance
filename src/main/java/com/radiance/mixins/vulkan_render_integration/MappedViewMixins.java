package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.radiance.client.proxy.vulkan.GeometryCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures geometry written through a mapped buffer view -- the route 26.2 actually uses for
 * immediate-mode/GUI vertex data.
 *
 * <p>{@code StagedVertexBuffer.uploadDrawsToBuffers} maps a slice of a pooled staging buffer, writes
 * vertices into {@code MappedView.data()} with plain {@code ByteBuffer.put}, closes the view and then
 * issues {@code copyToBuffer} into the real vertex/index buffer. None of that touches
 * {@code writeToBuffer} or {@code com.mojang.blaze3d.vertex.StagingBuffer}, which is why capture hung
 * off either of those saw nothing and every draw bailed at the vertex lookup.
 *
 * <p>Hooked at {@code close()} rather than at {@code map()}: MC writes into the buffer after map
 * returns, so only by close is the content complete.
 */
@Mixin(GpuBufferSlice.MappedView.class)
public class MappedViewMixins {

    @Inject(method = "close()V", at = @At("HEAD"))
    private void radiance$captureMappedWrite(CallbackInfo ci) {
        GpuBufferSlice.MappedView self = (GpuBufferSlice.MappedView) (Object) this;
        GeometryCapture.captureMapped(self.slice(), self.data());
    }
}
