package com.radiance.client.vertex;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IRenderTypeExt;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;

/**
 * 26.2: {@code VertexConsumerProvider}/{@code MultiBufferSource} were removed (entity/item/BE
 * rendering moved to the deferred {@code SubmitNodeCollector} model, drained through
 * {@code StagedVertexBuffer}). This is no longer a {@code VertexConsumerProvider}; it is the mod's
 * per-{@link RenderType} capture store. The {@code StagedVertexBuffer} capture hook drives a mod
 * drain pass and, for each {@code RenderType}, asks this store for the capturing consumer
 * ({@link PBRVertexConsumer} for QUADS, else a plain {@link BufferBuilder}); {@code EntityProxy}
 * reads the geometry back via {@link #getLayers()}.
 *
 * <p>Render-state classification moved out of {@code PBRVertexConsumer} (which now takes
 * {@code textureID}/{@code alphaMode} directly) -- {@link #resolveTextureId}/{@link #resolveAlphaMode}
 * reconstruct them from the {@code RenderType} (RenderPhase is gone). Both are compile-first
 * heuristics to runtime-validate once the entity pass runs.
 */
@Environment(EnvType.CLIENT)
public class StorageVertexConsumerProvider {

    protected final Map<RenderType, VertexConsumer> pending = new HashMap<>();
    protected final Map<RenderType, ByteBufferBuilder> allocated = new HashMap<>();

    private final int size;

    public StorageVertexConsumerProvider(int size) {
        this.size = size;
    }

    public VertexConsumer getBuffer(RenderType renderType) {
        VertexConsumer vertexConsumer = this.pending.get(renderType);

        if (vertexConsumer == null) {
            ByteBufferBuilder allocator = new ByteBufferBuilder(size);
            allocated.put(renderType, allocator);

            if (renderType.primitiveTopology() == PrimitiveTopology.QUADS) {
                vertexConsumer = new PBRVertexConsumer(allocator, resolveTextureId(renderType),
                    resolveAlphaMode(renderType));
            } else {
                vertexConsumer = new BufferBuilder(allocator, renderType.primitiveTopology(),
                    renderType.format());
            }
            this.pending.put(renderType, vertexConsumer);
        }
        return vertexConsumer;
    }

    public Map<RenderType, VertexConsumer> getLayers() {
        return this.pending;
    }

    public void close() {
        for (ByteBufferBuilder allocator : this.allocated.values()) {
            allocator.close();
        }
        this.pending.clear();
    }

    /**
     * 26.2: RenderPhase is removed, so classify off the {@link RenderType}. {@code hasBlending()}
     * flags translucent; otherwise the pipeline location distinguishes cutout from solid.
     *
     * <p>Text render types carry a <b>post-text mode</b> (background / intensity / rgba, ± see-through
     * / polygon-offset) rather than a plain alpha mode, and the RT text hit group ({@code text.rahit},
     * selected by the render type name) reads exactly this value to pick the glyph-coverage channel:
     * INTENSITY samples the font's {@code .r}, RGBA samples {@code .a}. Without this branch every text
     * type fell through to {@code TRANSPARENT (2)} -- which the shader reads as INTENSITY -- so an
     * RGBA font ({@code text}/{@code text_polygon_offset}, e.g. sign text) sampled {@code .r} (opaque
     * everywhere) and the whole glyph cell rendered as a solid rectangle. The render type name is the
     * authoritative mode (MC picks {@code text} vs {@code text_intensity} per font), so defer to it.
     */
    static int resolveAlphaMode(RenderType renderType) {
        int textMode = PBRVertexConsumer.getPostTextMode(
            ((IRenderTypeExt) renderType).radiance$getName());
        if (textMode != PBRVertexConsumer.ALPHA_MODE_OPAQUE) {
            return textMode;
        }
        if (renderType.hasBlending()) {
            return PBRVertexConsumer.ALPHA_MODE_TRANSPARENT;
        }
        String location = renderType.pipeline().getLocation().toString();
        if (location.contains("cutout")) {
            return PBRVertexConsumer.ALPHA_MODE_CUTOUT;
        }
        return PBRVertexConsumer.ALPHA_MODE_OPAQUE;
    }

    /**
     * 26.2: the layer's texture lives in {@link PreparedRenderType}'s bound textures. Select the main
     * color sampler ({@code Sampler0}) by NAME -- NOT "first GL-backed". {@code
     * RenderSetup.prepareTextures} appends the overlay ({@code Sampler1}) and lightmap ({@code
     * Sampler2}) BEFORE the texture-map entries, so the list is ordered [overlay, lightmap, Sampler0].
     * "First GL-backed" therefore returned the overlay texture -- which is literally red (hurt, top
     * half) / white (bottom half) -- so ray-traced entities sampled it as albedo and rendered red and
     * white. Prefer {@code Sampler0}; fall back to the first GL-backed sampler that is not the
     * overlay/lightmap for render types whose primary texture uses a different name.
     * {@code prepare()} builds the prepared type; runtime-validate that calling it during capture
     * (outside a render pass) is acceptable.
     */
    static int resolveTextureId(RenderType renderType) {
        PreparedRenderType prepared = renderType.prepare();
        int fallback = 0;
        for (PreparedRenderType.Texture texture : prepared.textures()) {
            if (texture.textureView() == null
                || !(texture.textureView().texture() instanceof GlTexture glTexture)) {
                continue;
            }
            if ("Sampler0".equals(texture.name())) {
                return glTexture.glId();
            }
            if (fallback == 0 && !"Sampler1".equals(texture.name())
                && !"Sampler2".equals(texture.name())) {
                fallback = glTexture.glId();
            }
        }
        return fallback;
    }
}
