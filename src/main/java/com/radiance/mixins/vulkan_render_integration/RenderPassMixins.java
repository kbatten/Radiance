package com.radiance.mixins.vulkan_render_integration;

import static com.radiance.client.constant.VulkanConstants.VkBufferUsageFlagBits.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static com.radiance.client.constant.VulkanConstants.VkBufferUsageFlagBits.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlRenderPipeline;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.platform.BlendOp;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.radiance.client.DrawInterceptStats;
import com.radiance.client.constant.Constants;
import com.radiance.client.proxy.vulkan.BufferProxy;
import com.radiance.client.proxy.vulkan.GeometryCapture;
import com.radiance.client.proxy.vulkan.PipelineStateProxy;
import com.radiance.client.proxy.vulkan.ShaderProxy;
import com.radiance.client.shader.ShaderDefinition;
import com.radiance.client.shader.ShaderRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
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
 * bytes ({@link GeometryCapture}), feeds the geometry + uniforms to the native backend and cancels
 * the GL draw.
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

    // Scissor has to be captured from the front RenderPass, not the GL state manager. MC applies it
    // lazily: enableScissor/disableScissor only mutate the pass's ScissorState, and the actual
    // GlStateManager._scissorBox/_enableScissorTest calls (which GlStateManagerMixins redirects to the
    // native overlay scissor) fire later inside the backend's per-draw trySetup. radiance$onDrawIndexed
    // cancels the front drawIndexed before it delegates to the backend, so that trySetup never runs for
    // a replayed draw and the native scissor would otherwise stay at whatever the pass-start
    // createRenderPass left it -- the full render area -- leaving scissored content (scrollable lists,
    // tooltips) unclipped in the Vulkan overlay. Driving the native scissor here, before the replayed
    // draw records into the overlay command buffer, restores the clip.
    //
    // The args are the same GL bottom-left framebuffer values _scissorBox receives (the backend passes
    // the ScissorState straight through), so they map onto ViewportState exactly as the
    // GlStateManagerMixins._scissorBox redirect does; setOverlayScissor performs the GL->Vulkan Y-flip.
    // disableScissor resets to the full swapchain extent, matching MC's reset-to-render-area intent for
    // the full-screen GUI pass.
    @Inject(method = "enableScissor(IIII)V", at = @At("HEAD"))
    private void radiance$onEnableScissor(int x, int y, int width, int height, CallbackInfo ci) {
        PipelineStateProxy.ViewportState.setScissor(x, y, width, height);
        PipelineStateProxy.ViewportState.setScissorEnabled(true);
    }

    @Inject(method = "disableScissor()V", at = @At("HEAD"))
    private void radiance$onDisableScissor(CallbackInfo ci) {
        PipelineStateProxy.ViewportState.setScissorEnabled(false);
    }

    // Diagnostic only -- 26.2's RenderPass has nine draw entry points and only drawIndexed below is
    // intercepted. If MC issues this geometry through one of the others it reaches vanilla GL and
    // never becomes Vulkan work, which would look exactly like the blank screen. Count, do not
    // cancel. Descriptors taken from javap -s so a bad selector cannot abort startup.
    @Inject(method = "draw(IIII)V", at = @At("HEAD"))
    private void radiance$countDraw(int a, int b, int c, int d, CallbackInfo ci) {
        DrawInterceptStats.otherDraw("draw");
        DrawInterceptStats.notePipeline(radiance$pipelineLoc(), "draw", null);
    }

    @Inject(method = "drawMultipleIndexed(Ljava/util/Collection;Lcom/mojang/blaze3d/buffers/GpuBuffer;"
        + "Lcom/mojang/blaze3d/IndexType;Ljava/util/Collection;Ljava/lang/Object;)V",
        at = @At("HEAD"))
    private void radiance$countDrawMultipleIndexed(Collection<?> draws, GpuBuffer indexBuffer,
        IndexType indexType, Collection<String> uniforms, Object userData, CallbackInfo ci) {
        DrawInterceptStats.otherDraw("drawMultipleIndexed");
        DrawInterceptStats.notePipeline(radiance$pipelineLoc(), "drawMultipleIndexed", null);
    }

    @Unique
    private String radiance$pipelineLoc() {
        return this.radiance$pipeline == null ? "<none>"
            : String.valueOf(this.radiance$pipeline.getLocation());
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

        // firstIndex and vertexOffset (baseVertex) both have to be honoured, not dropped: GuiRenderer
        // packs every GUI draw of a frame into a single pooled vertex+index buffer and tells the
        // draws apart purely by these two offsets. Binding the buffer without them would replay the
        // frame's first mesh for every draw.
        //
        // The native draw entry takes neither offset, so the windows are cut here instead.
        // glDrawElementsBaseVertex semantics make that exact: index i is read at firstIndex + i and
        // addresses vertex (index + baseVertex), so cutting vertices from baseVertex leaves the
        // stored index values directly valid against the cut array, with no rebasing.
        VertexFormat[] vertexFormats = this.radiance$pipeline.getVertexFormatBindings();
        if (vertexFormats.length == 0 || vertexFormats[0].getVertexSize() <= 0) {
            DrawInterceptStats.noState();
            return; // no vertex layout to compute a stride from -- let vanilla draw
        }
        int stride = vertexFormats[0].getVertexSize();

        // Vertex data runs from baseVertex to the end of what was captured: the draw carries no
        // vertex count, and GeometryCapture clips the request to the bytes MC actually wrote. That
        // hands the backend some trailing vertices belonging to later draws, which is harmless --
        // the index window below references only this draw's own.
        byte[] vertexData = GeometryCapture.get(this.radiance$vertexBuffer.buffer(),
            this.radiance$vertexBuffer.offset() + (long) vertexOffset * stride,
            this.radiance$vertexBuffer.length());
        byte[] indexData = GeometryCapture.get(this.radiance$indexBuffer,
            (long) firstIndex * this.radiance$indexType.bytes,
            (long) indexCount * this.radiance$indexType.bytes);
        // Split so the counters distinguish which of the two lookups failed -- vertex and index data
        // reach GeometryCapture by different routes, so they can fail independently.
        if (vertexData == null) {
            DrawInterceptStats.noVertexData();
            return;
        }
        if (indexData == null) {
            DrawInterceptStats.noIndexData();
            return;
        }

        ShaderDefinition shader = ShaderRegistry.getOrCreate(program,
            this.radiance$pipeline.getShaderDefines());
        // The native backend could not build this shader -- its translated GLSL uses something the
        // translator does not handle yet (samplerCube, for one). Leave the draw to Minecraft's own
        // GL path instead of issuing it with an invalid native id. ShaderRegistry caches the
        // definition, so this resolves once per program rather than retrying every frame.
        if (shader.nativeId() < 0) {
            DrawInterceptStats.shaderUnavailable();
            return;
        }

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

            radiance$applyPipelineBlend();

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
        DrawInterceptStats.notePipeline(String.valueOf(this.radiance$pipeline.getLocation()),
            "drawIndexed", this.radiance$textures.toString());
        DrawInterceptStats.noteVertex(String.valueOf(this.radiance$pipeline.getLocation()),
            System.identityHashCode(this.radiance$vertexBuffer.buffer()),
            this.radiance$vertexBuffer.offset(), vertexOffset, firstIndex, stride, vertexData);
        ci.cancel();
    }

    // 26.2 bakes blend state into each RenderPipeline's ColorTargetState. The replay path cancels the
    // vanilla draw before MC applies that state, so without this every replayed overlay draw kept the
    // last-set (or default opaque ONE,ZERO) blend -- the fullscreen vignette, which needs a multiply
    // blend, drew opaque and painted the composited world black. Push this pipeline's own blend to the
    // native overlay dynamic state before each draw so every pipeline blends as authored.
    @Unique
    private void radiance$applyPipelineBlend() {
        ColorTargetState target = this.radiance$pipeline.getColorTargetState();
        Optional<BlendFunction> blend = target.blendFunction();
        if (blend.isEmpty()) {
            PipelineStateProxy.ColorBlendState.setBlendEnable(false);
            return;
        }
        BlendFunction fn = blend.get();
        BlendEquation color = fn.color();
        BlendEquation alpha = fn.alpha();
        PipelineStateProxy.ColorBlendState.setBlendEnable(true);
        PipelineStateProxy.ColorBlendState.glSetBlendFuncSeparate(
            radiance$glBlendFactor(color.sourceFactor()), radiance$glBlendFactor(alpha.sourceFactor()),
            radiance$glBlendFactor(color.destFactor()), radiance$glBlendFactor(alpha.destFactor()));
        PipelineStateProxy.ColorBlendState.glSetBlendOpSeparate(
            radiance$glBlendOp(color.op()), radiance$glBlendOp(alpha.op()));
    }

    @Unique
    private static int radiance$glBlendFactor(BlendFactor f) {
        return switch (f) {
            case ZERO -> GL11.GL_ZERO;
            case ONE -> GL11.GL_ONE;
            case SRC_COLOR -> GL11.GL_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR -> GL11.GL_ONE_MINUS_SRC_COLOR;
            case DST_COLOR -> GL11.GL_DST_COLOR;
            case ONE_MINUS_DST_COLOR -> GL11.GL_ONE_MINUS_DST_COLOR;
            case SRC_ALPHA -> GL11.GL_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA -> GL11.GL_ONE_MINUS_SRC_ALPHA;
            case DST_ALPHA -> GL11.GL_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA -> GL11.GL_ONE_MINUS_DST_ALPHA;
            case CONSTANT_COLOR -> GL14.GL_CONSTANT_COLOR;
            case ONE_MINUS_CONSTANT_COLOR -> GL14.GL_ONE_MINUS_CONSTANT_COLOR;
            case CONSTANT_ALPHA -> GL14.GL_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_ALPHA -> GL14.GL_ONE_MINUS_CONSTANT_ALPHA;
            case SRC_ALPHA_SATURATE -> GL11.GL_SRC_ALPHA_SATURATE;
        };
    }

    @Unique
    private static int radiance$glBlendOp(BlendOp op) {
        return switch (op) {
            case ADD -> GL14.GL_FUNC_ADD;
            case SUBTRACT -> GL14.GL_FUNC_SUBTRACT;
            case REVERSE_SUBTRACT -> GL14.GL_FUNC_REVERSE_SUBTRACT;
            case MIN -> GL14.GL_MIN;
            case MAX -> GL14.GL_MAX;
        };
    }
}
