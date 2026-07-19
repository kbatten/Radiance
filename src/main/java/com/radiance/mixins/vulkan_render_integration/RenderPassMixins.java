package com.radiance.mixins.vulkan_render_integration;

import static com.radiance.client.constant.VulkanConstants.VkBufferUsageFlagBits.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static com.radiance.client.constant.VulkanConstants.VkBufferUsageFlagBits.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.radiance.client.DrawInterceptStats;
import com.radiance.client.constant.Constants;
import com.radiance.client.proxy.vulkan.BufferProxy;
import com.radiance.client.proxy.vulkan.GeometryCapture;
import com.radiance.client.proxy.vulkan.ShaderProxy;
import com.radiance.client.proxy.vulkan.UniformCapture;
import com.radiance.client.shader.ShaderDefinition;
import com.radiance.client.shader.ShaderRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 draw interception -- the replacement for {@code BufferRendererMixins} (BufferRenderer was
 * removed). All immediate-mode geometry draws through {@code RenderPass}: the pipeline, vertex/index
 * buffers, the built-in UBOs (by name) and sampler textures (by name) are all bound on the pass, then
 * {@code drawIndexed} issues the draw. This captures that per-draw state and, at {@code drawIndexed},
 * resolves the mod's shader from the pipeline, packs the native uniform blob from the captured UBO
 * bytes ({@link UniformCapture}), feeds the geometry + uniforms to the native backend and cancels the
 * GL draw.
 *
 * <p>Runtime-validation notes (compile-first migration): the geometry is uploaded per draw via the
 * raw {@code BufferProxy} buffer API; per-draw native buffer allocation/recycling and firstIndex/
 * vertexOffset handling need validating once the mod runs.
 */
@Mixin(RenderPass.class)
public abstract class RenderPassMixins {

    @Unique
    private RenderPipeline radiance$pipeline;
    @Unique
    private final Map<String, GpuBufferSlice> radiance$uniforms = new HashMap<>();
    @Unique
    private final Object2IntMap<String> radiance$textures = new Object2IntOpenHashMap<>();
    @Unique
    private GpuBufferSlice radiance$vertexBuffer;
    @Unique
    private GpuBuffer radiance$indexBuffer;
    @Unique
    private IndexType radiance$indexType;

    @Inject(method = "setPipeline", at = @At("HEAD"))
    private void radiance$onSetPipeline(RenderPipeline pipeline, CallbackInfo ci) {
        this.radiance$pipeline = pipeline;
    }

    @Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At("HEAD"))
    private void radiance$onSetUniform(String name, GpuBufferSlice value, CallbackInfo ci) {
        this.radiance$uniforms.put(name, value);
    }

    @Inject(method = "setVertexBuffer", at = @At("HEAD"))
    private void radiance$onSetVertexBuffer(int slot, GpuBufferSlice vertexBuffer, CallbackInfo ci) {
        if (slot == 0) {
            this.radiance$vertexBuffer = vertexBuffer;
        }
    }

    @Inject(method = "setIndexBuffer", at = @At("HEAD"))
    private void radiance$onSetIndexBuffer(GpuBuffer indexBuffer, IndexType indexType,
        CallbackInfo ci) {
        this.radiance$indexBuffer = indexBuffer;
        this.radiance$indexType = indexType;
    }

    @Inject(method = "bindTexture", at = @At("HEAD"))
    private void radiance$onBindTexture(String name, GpuTextureView textureView, GpuSampler sampler,
        CallbackInfo ci) {
        int glId = 0;
        if (textureView != null && textureView.texture() instanceof GlTexture glTexture) {
            glId = glTexture.glId();
        }
        this.radiance$textures.put(name, glId);
    }

    // Diagnostic only -- 26.2's RenderPass has nine draw entry points and only drawIndexed below is
    // intercepted. If MC issues this geometry through one of the others it reaches vanilla GL and
    // never becomes Vulkan work, which would look exactly like the blank screen. Count, do not
    // cancel. Descriptors taken from javap -s so a bad selector cannot abort startup.
    @Inject(method = "draw(IIII)V", at = @At("HEAD"))
    private void radiance$countDraw(int a, int b, int c, int d, CallbackInfo ci) {
        DrawInterceptStats.otherDraw("draw");
    }

    @Inject(method = "drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;"
        + "Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V",
        at = @At("HEAD"))
    private void radiance$countDrawMultipleIndexed(Collection<?> draws, GpuBuffer indexBuffer,
        IndexType indexType, Collection<String> uniforms, Object userData, CallbackInfo ci) {
        DrawInterceptStats.otherDraw("drawMultipleIndexed");
    }

    @Inject(method = "drawIndexed", at = @At("HEAD"), cancellable = true)
    private void radiance$onDrawIndexed(int indexCount, int instanceCount, int firstIndex,
        int vertexOffset, int firstInstance, CallbackInfo ci) {
        DrawInterceptStats.entered();
        if (this.radiance$pipeline == null || this.radiance$vertexBuffer == null
            || this.radiance$indexBuffer == null || this.radiance$indexType == null) {
            DrawInterceptStats.noState();
            return; // not enough captured state -- let vanilla draw
        }
        if (!(RenderSystem.getDevice().precompilePipeline(this.radiance$pipeline)
            instanceof GlRenderPipeline glPipeline)) {
            DrawInterceptStats.notGlPipeline();
            return;
        }
        GlProgram program = glPipeline.program();
        if (program == GlProgram.INVALID_PROGRAM) {
            DrawInterceptStats.invalidProgram();
            return;
        }

        // Geometry comes from GeometryCapture, not UniformCapture: 26.2 stages vertex data through
        // StagingBuffer and moves it with copyToBuffer, so it never reaches the writeToBuffer hook
        // UniformCapture is built on. GeometryCapture also resolves ranges, which matters because a
        // draw binds a sub-slice of a larger shared vertex buffer rather than a whole buffer.
        byte[] vertexData = GeometryCapture.get(this.radiance$vertexBuffer);
        byte[] indexData = GeometryCapture.get(this.radiance$indexBuffer, 0L,
            this.radiance$indexBuffer.size());
        // Split so the counters distinguish which of the two lookups failed -- vertex and index data
        // reach UniformCapture by different routes, so they can fail independently.
        if (vertexData == null) {
            DrawInterceptStats.noVertexData();
            return;
        }
        if (indexData == null) {
            DrawInterceptStats.noIndexData();
            return;
        }

        ShaderDefinition shader = ShaderRegistry.getOrCreate(program);

        int vertexId = BufferProxy.allocateBuffer();
        BufferProxy.initializeBuffer(vertexId, vertexData.length,
            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT.getValue());
        int indexId = BufferProxy.allocateBuffer();
        BufferProxy.initializeBuffer(indexId, indexData.length,
            VK_BUFFER_USAGE_INDEX_BUFFER_BIT.getValue());

        ByteBuffer vertexBuf = MemoryUtil.memAlloc(vertexData.length);
        ByteBuffer indexBuf = MemoryUtil.memAlloc(indexData.length);
        try {
            vertexBuf.put(vertexData).flip();
            indexBuf.put(indexData).flip();
            BufferProxy.queueUpload(MemoryUtil.memAddress(vertexBuf), vertexId);
            BufferProxy.queueUpload(MemoryUtil.memAddress(indexBuf), indexId);
            BufferProxy.performQueuedUpload();

            try (MemoryStack stack = MemoryStack.stackPush()) {
                ShaderProxy.UniformHandle uniform = ShaderProxy.createUniform(shader,
                    this.radiance$uniforms, this.radiance$textures, stack);
                ShaderProxy.draw(vertexId, indexId, shader.nativeId(), indexCount,
                    Constants.IndexTypes.getValue(this.radiance$indexType), uniform.addr(),
                    uniform.size());
            }
        } finally {
            MemoryUtil.memFree(vertexBuf);
            MemoryUtil.memFree(indexBuf);
        }

        DrawInterceptStats.replayed();
        ci.cancel();
    }
}
