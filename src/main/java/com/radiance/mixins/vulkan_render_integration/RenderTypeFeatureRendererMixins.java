package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.client.vertex.FeatureGeometryCapture;
import com.radiance.client.vertex.StorageVertexConsumerProvider;
import net.minecraft.client.renderer.feature.RenderTypeFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2 centralized entity/item/BE geometry capture (the replacement for the removed per-renderer
 * {@code VertexConsumerProvider} swaps). Every {@code RenderTypeFeatureRenderer} subclass
 * (models, items, ...) obtains its draw buffer through {@code getVertexBuilder(RenderType)} during a
 * {@code FeatureRenderDispatcher} drain. While the mod's per-entity drain pass has a capture store
 * active ({@link FeatureGeometryCapture}), redirect that buffer to the store's capturing consumer so
 * the geometry is captured for the Vulkan pipeline instead of the vanilla staged buffer.
 */
@Mixin(RenderTypeFeatureRenderer.class)
public abstract class RenderTypeFeatureRendererMixins {

    @Inject(method = "getVertexBuilder(Lnet/minecraft/client/renderer/rendertype/RenderType;)"
        + "Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At("HEAD"), cancellable = true)
    private void radiance$captureGeometry(RenderType renderType,
        CallbackInfoReturnable<VertexConsumer> cir) {
        StorageVertexConsumerProvider store = FeatureGeometryCapture.active();
        if (store != null) {
            cir.setReturnValue(store.getBuffer(renderType));
        }
    }
}
