package com.radiance.mixin_related.extensions.vulkan_render_integration;

/**
 * 26.2: {@code DrawContext} became {@code GuiGraphicsExtractor} and the GUI draws through the 2D
 * render-state model (no {@code VertexConsumerProvider}, no mat4 pose). {@code drawOrientedQuad} used
 * to build a rotated quad from a {@code RenderLayer} buffer; it is now expressed as a rotated
 * {@code fill} under the extractor's {@code pose()} ({@link Matrix3x2fStack}), so the layer argument
 * is gone.
 */
public interface IDrawContextExt {

    void radiance$drawOrientedQuad(float x1, float y1, float x2, float y2, float thickness,
        int color);
}
