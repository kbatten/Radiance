package com.radiance.client.proxy.vulkan;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.client.shader.BuiltinUniforms;
import com.radiance.client.shader.ShaderDefinition;
import com.radiance.client.shader.ShaderField;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IAbstractTextureExt;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

public final class ShaderProxy {

    private static final Identifier WHITE_TEXTURE_ID = Identifier.fromNamespaceAndPath("radiance",
        "generated/white");
    private static Integer whiteTextureId;

    private ShaderProxy() {
    }

    public static native int registerShader(String shaderKey, int vertexFormatType,
        int drawMode, int uniformSize, String vertexShaderPath, String fragmentShaderPath,
        String[] defineNames, String[] defineValues);

    public static native void draw(int vertexId, int indexId, int shaderId, int indexCount,
        int indexType, long uniformPtr, int uniformSize);

    public static void draw(BufferProxy.VertexIndexBufferHandle handle, int shaderId, int indexCount,
        int indexType, long uniformPtr, int uniformSize) {
        draw(handle.vertexId, handle.indexId, shaderId, indexCount, indexType, uniformPtr,
            uniformSize);
    }

    /**
     * 26.2: uniform values no longer live in per-uniform GlUniforms -- they are in the built-in UBOs
     * bound to the draw. {@code boundUniforms} maps each UBO block name
     * ("Projection"/"DynamicTransforms"/"Globals"/"Lighting"/"Fog") to the {@link GpuBufferSlice}
     * bound via {@code RenderPass.setUniform}. Each {@link ShaderField}'s value is read from its
     * built-in UBO at the std140 offset ({@link BuiltinUniforms}) and packed into the mod's native
     * blob at {@code field.offset()}; sampler fields take the draw's bound textures.
     *
     * <p>The CPU bytes come from {@link GeometryCapture}, not {@link UniformCapture}. The built-in
     * UBOs do not all travel one route: {@code Projection} is written with
     * {@code CommandEncoder.writeToBuffer} (which UniformCapture hooks), but {@code DynamicTransforms}
     * -- carrying ModelViewMat -- is written through {@code DynamicUniformStorage.writeUniform}, which
     * maps the slice and writes through {@code MappedView} instead. UniformCapture never saw that, so
     * ModelViewMat resolved to a zero matrix and every gui vertex collapsed to the origin
     * ({@code gl_Position = ProjMat * ModelViewMat * ...}), which is a black screen even though the
     * draw replayed. GeometryCapture already mirrors all four buffer-write routes (writeToBuffer,
     * mapped view, copyToBuffer, createBuffer) and resolves sub-ranges, so it covers every UBO.
     */
    public static UniformHandle createUniform(ShaderDefinition shader,
        Map<String, GpuBufferSlice> boundUniforms, Object2IntMap<String> boundTextures,
        MemoryStack stack) {
        ByteBuffer bb = stack.calloc(shader.uniformBufferSize());
        for (ShaderField field : shader.fields()) {
            if (field.isSampler()) {
                bb.putInt(field.offset(), resolveSamplerTextureId(boundTextures, field));
                continue;
            }
            BuiltinUniforms.Entry entry = BuiltinUniforms.get(field.name());
            if (entry == null) {
                continue;
            }
            GpuBufferSlice slice = boundUniforms.get(entry.block());
            if (slice == null) {
                com.radiance.client.DrawInterceptStats.noteUniformMiss(shader.name(), field.name(),
                    entry.block(), "no bound slice");
                continue;
            }
            byte[] captured = GeometryCapture.get(slice);
            if (captured == null) {
                com.radiance.client.DrawInterceptStats.noteUniformMiss(shader.name(), field.name(),
                    entry.block(), "not captured");
                continue;
            }
            ByteBuffer src = ByteBuffer.wrap(captured).order(ByteOrder.nativeOrder());
            putUniform(bb, field, src, entry.uboOffset());
        }
        // Diagnostic: dump the resolved transform/colour uniforms once per shader so an
        // invisible-but-replayed pipeline (e.g. gui_textured) can be diagnosed -- a zeroed
        // ModelViewMat/ProjMat collapses every vertex to the origin.
        StringBuilder dbg = new StringBuilder();
        for (ShaderField field : shader.fields()) {
            switch (field.name()) {
                case "ModelViewMat", "ProjMat", "ColorModulator" -> dbg.append(field.name())
                    .append("[0]=").append(bb.getFloat(field.offset())).append(' ');
                default -> { }
            }
        }
        com.radiance.client.DrawInterceptStats.noteUniformValues(shader.name(), dbg.toString());
        return new UniformHandle(MemoryUtil.memAddress(bb), shader.uniformBufferSize());
    }

    public record UniformHandle(long addr, int size) {

    }

    private static int resolveSamplerTextureId(Object2IntMap<String> boundTextures,
        ShaderField field) {
        if (boundTextures.containsKey(field.name())) {
            int textureId = boundTextures.getInt(field.name());
            if (textureId != 0) {
                return textureId;
            }
        }
        if ("Sampler2".equals(field.name())) {
            return getWhiteTextureId();
        }
        return 0;
    }

    public static int getWhiteTextureId() {
        Integer cached = whiteTextureId;
        if (cached != null) {
            return cached;
        }

        NativeImage image = new NativeImage(16, 16, false);
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                image.setPixel(x, y, 0xFFFFFFFF);
            }
        }
        DynamicTexture texture = new DynamicTexture(() -> "radiance_white", image);
        Minecraft.getInstance()
            .getTextureManager()
            .register(WHITE_TEXTURE_ID, texture);
        whiteTextureId = ((IAbstractTextureExt) (Object) texture).radiance$getGlIDUnsafe();
        return whiteTextureId;
    }

    private static void putUniform(ByteBuffer blob, ShaderField field, ByteBuffer src,
        int srcOffset) {
        switch (field.kind()) {
            case INT -> putInts(blob, field.offset(), src, srcOffset, field.componentCount());
            case FLOAT -> putFloats(blob, field.offset(), src, srcOffset, field.componentCount());
            case MATRIX -> putMatrix(blob, field.offset(), field.componentCount(), field.name(),
                src, srcOffset);
            case SAMPLER -> throw new IllegalStateException("Sampler fields are written separately");
        }
    }

    private static void putInts(ByteBuffer blob, int offset, ByteBuffer src, int srcOffset,
        int componentCount) {
        for (int i = 0; i < componentCount; i++) {
            blob.putInt(offset + i * Integer.BYTES, src.getInt(srcOffset + i * Integer.BYTES));
        }
    }

    private static void putFloats(ByteBuffer blob, int offset, ByteBuffer src, int srcOffset,
        int componentCount) {
        for (int i = 0; i < componentCount; i++) {
            blob.putFloat(offset + i * Float.BYTES, src.getFloat(srcOffset + i * Float.BYTES));
        }
    }

    private static void putMatrix(ByteBuffer blob, int offset, int dimension, String uniformName,
        ByteBuffer src, int srcOffset) {
        if (dimension == 4) {
            float[] matrix = new float[16];
            for (int i = 0; i < 16; i++) {
                matrix[i] = src.getFloat(srcOffset + i * Float.BYTES);
            }
            if ("ProjMat".equals(uniformName)) {
                mapProjectionMatrix(matrix);
            }
            for (int i = 0; i < 16; i++) {
                blob.putFloat(offset + i * Float.BYTES, matrix[i]);
            }
            return;
        }

        // Built-in uniforms only use mat4; smaller matrices are kept for completeness.
        int columnStride = Float.BYTES * 4;
        for (int column = 0; column < dimension; column++) {
            for (int row = 0; row < dimension; row++) {
                blob.putFloat(offset + column * columnStride + row * Float.BYTES,
                    src.getFloat(srcOffset + (column * dimension + row) * Float.BYTES));
            }
        }
    }

    private static void mapProjectionMatrix(float[] matrix) {
        for (int column = 0; column < 4; column++) {
            int base = column * 4;
            float row0 = matrix[base];
            float row1 = matrix[base + 1];
            float row2 = matrix[base + 2];
            float row3 = matrix[base + 3];
            matrix[base] = row0;
            matrix[base + 1] = -row1;
            matrix[base + 2] = row2 * 0.5F + row3 * 0.5F;
            matrix[base + 3] = row3;
        }
    }
}
