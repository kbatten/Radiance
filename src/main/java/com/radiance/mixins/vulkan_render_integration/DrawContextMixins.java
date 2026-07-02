package com.radiance.mixins.vulkan_render_integration;

import com.radiance.mixin_related.extensions.vulkan_render_integration.IDrawContextExt;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 26.2: {@code DrawContext} became {@code GuiGraphicsExtractor}. The old {@code drawOrientedQuad}
 * emitted a rotated quad through a {@code VertexConsumerProvider.Immediate} buffer (removed in 26.2)
 * using the mat4 pose. The GUI is now 2D: {@code pose()} returns a {@link Matrix3x2fStack} and colored
 * geometry is submitted via {@code fill}. A thick oriented line therefore becomes a length x thickness
 * {@code fill} rendered under a pose translated to the segment start and rotated to its angle.
 */
@Mixin(GuiGraphicsExtractor.class)
public abstract class DrawContextMixins implements IDrawContextExt {

    @Override
    public void radiance$drawOrientedQuad(float x1, float y1, float x2, float y2, float thickness,
        int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.1f) {
            return;
        }

        GuiGraphicsExtractor self = (GuiGraphicsExtractor) (Object) this;
        float angle = (float) Math.atan2(dy, dx);
        int half = Math.max(1, Math.round(thickness / 2f));

        Matrix3x2fStack pose = self.pose();
        pose.pushMatrix();
        pose.translate(x1, y1);
        pose.rotate(angle);
        self.fill(0, -half, Math.round(len), half, color);
        pose.popMatrix();
    }
}
