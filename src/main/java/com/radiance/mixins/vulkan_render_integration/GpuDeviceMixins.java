package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.GpuDevice;
import com.radiance.client.proxy.vulkan.GeometryCapture;
import java.nio.ByteBuffer;
import java.util.function.Supplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Captures buffers whose contents are supplied at creation rather than uploaded afterwards.
 *
 * <p>{@code GpuDevice.createBuffer(Supplier, int, ByteBuffer)} bakes the data in when the buffer is
 * made, so nothing about it ever reaches writeToBuffer, copyToBuffer or a mapped view -- the routes
 * every other capture hook watches. Any such buffer is therefore permanently unresolvable at draw
 * time unless it is caught here.
 *
 * <p>The case that matters is the shared sequential index buffer.
 * {@code RenderSystem$AutoStorageIndexBuffer.ensureStorage} generates the quad/line index pattern
 * into a ByteBuffer and creates the GPU buffer from it in one call, and
 * {@code StagedVertexBuffer.getExecuteInfo} hands that buffer to every <em>unsorted</em> draw
 * (only translucent, quadSorting-bearing draws get the pooled index buffer that the copyToBuffer
 * route captures). That split is exactly what the counters showed: vertices resolving while ~87% of
 * draws failed the index lookup.
 *
 * <p>Hooked on {@link GpuDevice} rather than a backend subclass so it holds for the GL and Vulkan
 * backends alike. Capturing the contents rather than regenerating the pattern ourselves keeps this
 * correct for whichever IndexGenerator MC used, with no assumption about winding or topology.
 */
@Mixin(GpuDevice.class)
public class GpuDeviceMixins {

    @Inject(
        method = "createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)"
            + "Lcom/mojang/blaze3d/buffers/GpuBuffer;",
        at = @At("HEAD"))
    private void radiance$stageCreatedBuffer(Supplier<String> label, int usage, ByteBuffer data,
        CallbackInfoReturnable<GpuBuffer> cir) {
        GeometryCapture.stageCreate(data);
    }

    @Inject(
        method = "createBuffer(Ljava/util/function/Supplier;ILjava/nio/ByteBuffer;)"
            + "Lcom/mojang/blaze3d/buffers/GpuBuffer;",
        at = @At("RETURN"))
    private void radiance$bindCreatedBuffer(Supplier<String> label, int usage, ByteBuffer data,
        CallbackInfoReturnable<GpuBuffer> cir) {
        GeometryCapture.bindCreated(cir.getReturnValue());
    }
}
