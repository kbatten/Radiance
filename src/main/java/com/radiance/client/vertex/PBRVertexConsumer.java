package com.radiance.client.vertex;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.nio.ByteOrder;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

/**
 * 26.2 keystone. Writes the mod's fixed 128-byte PBR vertex into a native buffer.
 *
 * <p>Migration notes:
 * <ul>
 *   <li>{@code net.minecraft.client.render.VertexConsumer} -&gt;
 *       {@code com.mojang.blaze3d.vertex.VertexConsumer}; the builder methods were renamed
 *       (vertex-&gt;addVertex, color-&gt;setColor, texture-&gt;setUv, overlay-&gt;setUv1,
 *       light-&gt;setUv2, normal-&gt;setNormal).</li>
 *   <li>{@code BufferAllocator}-&gt;{@link ByteBufferBuilder} (allocate-&gt;reserve,
 *       getAllocated-&gt;build); {@code BuiltBuffer}-&gt;{@link MeshData}
 *       ({@code DrawParameters}-&gt;{@link MeshData.DrawState}); {@code VertexFormat.DrawMode}
 *       -&gt;{@link PrimitiveTopology}; {@code VertexFormat.IndexType}-&gt;{@link IndexType}.</li>
 *   <li>The old per-element mask machinery (getRequiredMask/getOffsetsByElementId/getBit/id +
 *       beginElement) is gone: {@code VertexFormatElement} no longer carries an id, so the mask
 *       cannot be built. Because the required-mask was always 0 (the completeness check never
 *       fired) and the writable-mask covered every non-position attribute, the mask was purely
 *       an offset lookup + a redundant double-write guard. It is replaced by direct writes to
 *       the fixed offsets precomputed in {@link PBRVertexFormats}.</li>
 *   <li>Render-state classification (RenderLayer/RenderPhase -&gt; textureID + alphaMode) moved
 *       out of this class since {@code RenderPhase} was removed and chunk vs entity geometry now
 *       key on different types (ChunkSectionLayer vs RenderType). The caller resolves both ints
 *       and passes them in; {@link #getPostTextMode(String)} + the ALPHA_MODE constants remain
 *       here for reuse.</li>
 * </ul>
 */
public class PBRVertexConsumer implements VertexConsumer {

    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;
    public static final int ALPHA_MODE_OPAQUE = 0;
    public static final int ALPHA_MODE_CUTOUT = 1;
    public static final int ALPHA_MODE_TRANSPARENT = 2;
    private static final int POST_TEXT_MODE_BACKGROUND = 1;
    private static final int POST_TEXT_MODE_INTENSITY = 2;
    private static final int POST_TEXT_MODE_RGBA = 3;
    private static final int POST_TEXT_MODE_BACKGROUND_SEE_THROUGH = 4;
    private static final int POST_TEXT_MODE_INTENSITY_SEE_THROUGH = 5;
    private static final int POST_TEXT_MODE_RGBA_SEE_THROUGH = 6;
    private static final int POST_TEXT_MODE_INTENSITY_POLYGON_OFFSET = 7;
    private static final int POST_TEXT_MODE_RGBA_POLYGON_OFFSET = 8;

    private final ByteBufferBuilder allocator;
    private final VertexFormat format;
    private final PrimitiveTopology drawMode;

    private final int vertexSizeByte;
    private long vertexPointer = -1L;
    private int vertexCount = 0;
    private boolean building = true;
    private final int textureID;
    private final int alphaMode;
    private float baseX = 0;
    private float baseY = 0;
    private float baseZ = 0;

    public PBRVertexConsumer(ByteBufferBuilder allocator, int textureID, int alphaMode) {
        this.allocator = allocator;
        this.drawMode = PrimitiveTopology.QUADS;
        this.format = PBRVertexFormats.PBR_TRIANGLE;
        this.vertexSizeByte = format.getVertexSize();
        this.textureID = textureID;
        this.alphaMode = alphaMode;

        if (this.vertexSizeByte != 128) {
            throw new IllegalStateException(
                "PBR vertex stride must be 128, got " + this.vertexSizeByte);
        }
    }

    private static void putInt(long ptr, int v) {
        if (LITTLE_ENDIAN) {
            MemoryUtil.memPutInt(ptr, v);
        } else {
            MemoryUtil.memPutShort(ptr, (short) (v & 0xFFFF));
            MemoryUtil.memPutShort(ptr + 2L, (short) ((v >>> 16) & 0xFFFF));
        }
    }

    /**
     * Text render layers carry a post-processing mode packed into their name; this mapping is
     * unchanged from the removed RenderPhase-based classifier and is exposed for the callers
     * that reconstruct alphaMode from a RenderType name.
     */
    public static int getPostTextMode(String layerName) {
        return switch (layerName) {
            case "text_background" -> POST_TEXT_MODE_BACKGROUND;
            case "text_intensity" -> POST_TEXT_MODE_INTENSITY;
            case "text" -> POST_TEXT_MODE_RGBA;
            case "text_background_see_through" -> POST_TEXT_MODE_BACKGROUND_SEE_THROUGH;
            case "text_intensity_see_through" -> POST_TEXT_MODE_INTENSITY_SEE_THROUGH;
            case "text_see_through" -> POST_TEXT_MODE_RGBA_SEE_THROUGH;
            case "text_intensity_polygon_offset" -> POST_TEXT_MODE_INTENSITY_POLYGON_OFFSET;
            case "text_polygon_offset" -> POST_TEXT_MODE_RGBA_POLYGON_OFFSET;
            default -> ALPHA_MODE_OPAQUE;
        };
    }

    public VertexFormat getFormat() {
        return this.format;
    }

    public int getVertexCount() {
        return this.vertexCount;
    }

    public void setBase(float x, float y, float z) {
        this.baseX = x;
        this.baseY = y;
        this.baseZ = z;
    }

    private void ensureBuilding() {
        if (!building) {
            throw new IllegalStateException("Not building!");
        }
    }

    @Nullable
    public MeshData endNullable() {
        ensureBuilding();
        MeshData built = build();
        building = false;
        vertexPointer = -1L;
        return built;
    }

    public MeshData end() {
        MeshData built = endNullable();
        if (built == null) {
            throw new IllegalStateException("PBRBufferBuilder was empty");
        }
        return built;
    }

    @Nullable
    private MeshData build() {
        if (vertexCount == 0) {
            return null;
        }

        ByteBufferBuilder.Result buf = allocator.build();
        if (buf == null) {
            return null;
        }

        int indexCount = drawMode.indexCount(vertexCount);
        IndexType indexType = IndexType.least(vertexCount);
        return new MeshData(buf,
            new MeshData.DrawState(format, vertexCount, indexCount, drawMode, indexType));
    }

    private long beginVertex(int glintTextureID) {
        ensureBuilding();

        vertexCount++;
        long ptr = allocator.reserve(vertexSizeByte);
        vertexPointer = ptr;
        MemoryUtil.memSet(ptr, 0, vertexSizeByte);

        if (this.textureID != 0) {
            putInt(ptr + PBRVertexFormats.OFF_TEXTURE_ID, this.textureID);
        }

        long offBase = ptr + PBRVertexFormats.OFF_POST_BASE;
        MemoryUtil.memPutFloat(offBase, baseX);
        MemoryUtil.memPutFloat(offBase + 4L, baseY);
        MemoryUtil.memPutFloat(offBase + 8L, baseZ);
        // Reuse the trailing padding word after postBase for alpha mode.
        putInt(offBase + 12L, this.alphaMode);

        if (glintTextureID != 0) {
            putInt(ptr + PBRVertexFormats.OFF_GLINT_TEXTURE, glintTextureID);
        }

        return ptr;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        return addVertex(x, y, z, 0);
    }

    public VertexConsumer addVertex(float x, float y, float z, int glintTextureID) {
        long base = beginVertex(glintTextureID);
        long p = base + PBRVertexFormats.OFF_POS;

        if (Float.isNaN(x) || Float.isNaN(y) || Float.isNaN(z)) {
            MemoryUtil.memPutFloat(p, 0);
            MemoryUtil.memPutFloat(p + 4L, 0);
            MemoryUtil.memPutFloat(p + 8L, 0);
        } else {
            MemoryUtil.memPutFloat(p, x);
            MemoryUtil.memPutFloat(p + 4L, y);
            MemoryUtil.memPutFloat(p + 8L, z);
        }

        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int alpha) {
        putInt(vertexPointer + PBRVertexFormats.OFF_USE_COLOR_LAYER, 1);
        long p = vertexPointer + PBRVertexFormats.OFF_COLOR_LAYER;
        MemoryUtil.memPutFloat(p, red / 255.0f);
        MemoryUtil.memPutFloat(p + 4L, green / 255.0f);
        MemoryUtil.memPutFloat(p + 8L, blue / 255.0f);
        MemoryUtil.memPutFloat(p + 12L, alpha / 255.0f);
        return this;
    }

    @Override
    public VertexConsumer setColor(int color) {
        return setColor(color >> 16 & 0xFF, color >> 8 & 0xFF, color & 0xFF, color >>> 24);
    }

    @Override
    public VertexConsumer setLineWidth(float width) {
        // PBR geometry is never line-topology; nothing to record.
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        putInt(vertexPointer + PBRVertexFormats.OFF_USE_TEXTURE, 1);
        long p = vertexPointer + PBRVertexFormats.OFF_TEXTURE_UV;
        MemoryUtil.memPutFloat(p, u);
        MemoryUtil.memPutFloat(p + 4L, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        putInt(vertexPointer + PBRVertexFormats.OFF_USE_OVERLAY, 1);
        long p = vertexPointer + PBRVertexFormats.OFF_OVERLAY_UV;
        putInt(p, u);
        putInt(p + 4L, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        putInt(vertexPointer + PBRVertexFormats.OFF_USE_LIGHT, 1);
        long p = vertexPointer + PBRVertexFormats.OFF_LIGHT_UV;
        putInt(p, u);
        putInt(p + 4L, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        putInt(vertexPointer + PBRVertexFormats.OFF_USE_NORM, 1);
        long p = vertexPointer + PBRVertexFormats.OFF_NORM;
        MemoryUtil.memPutFloat(p, x);
        MemoryUtil.memPutFloat(p + 4L, y);
        MemoryUtil.memPutFloat(p + 8L, z);
        return this;
    }

    private void putGlint(float u, float v) {
        putInt(vertexPointer + PBRVertexFormats.OFF_USE_GLINT, 1);
        long p = vertexPointer + PBRVertexFormats.OFF_GLINT_UV;
        MemoryUtil.memPutFloat(p, u);
        MemoryUtil.memPutFloat(p + 4L, v);
    }

    public static class GLint implements VertexConsumer {

        private final PBRVertexConsumer delegate;
        private final int glintTextureID;

        public GLint(PBRVertexConsumer delegate, int glintTextureID) {
            this.delegate = delegate;
            this.glintTextureID = glintTextureID;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z, this.glintTextureID);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            delegate.putGlint(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }

    public static class GLintOverlay implements VertexConsumer {

        private final PBRVertexConsumer delegate;
        private final Matrix4f inverseTextureMatrix;
        private final Matrix3f inverseNormalMatrix;
        private final float textureScale;
        private final Vector3f normal = new Vector3f();
        private final Vector3f pos = new Vector3f();
        private final int glintTextureID;
        private float x;
        private float y;
        private float z;

        public GLintOverlay(PBRVertexConsumer delegate, int glintTextureID,
            PoseStack.Pose matrix, float textureScale) {
            this.delegate = delegate;
            this.glintTextureID = glintTextureID;
            this.inverseTextureMatrix = new Matrix4f(matrix.pose()).invert();
            this.inverseNormalMatrix = new Matrix3f(matrix.normal()).invert();
            this.textureScale = textureScale;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            delegate.addVertex(x, y, z, this.glintTextureID);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            delegate.setColor(color);
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            Vector3f vector3f = this.inverseNormalMatrix.transform(x, y, z, this.pos);
            Direction direction = Direction.getApproximateNearest(vector3f.x(), vector3f.y(),
                vector3f.z());
            Vector3f vector3f2 = this.inverseTextureMatrix.transformPosition(this.x, this.y, this.z,
                this.normal);
            vector3f2.rotateY((float) Math.PI);
            vector3f2.rotateX((float) (-Math.PI / 2));
            vector3f2.rotate(direction.getRotation());

            delegate.putGlint(-vector3f2.x() * this.textureScale,
                -vector3f2.y() * this.textureScale);
            return this;
        }
    }
}
