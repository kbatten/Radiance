package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.vertex.FeatureGeometryCapture;
import com.radiance.client.vertex.GlintContext;
import com.radiance.client.vertex.StorageVertexConsumerProvider;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 enchantment-glint capture.
 *
 * <p>An enchanted item draws in TWO passes: {@code prepareMainSubmit} (the item model) and, before
 * it, {@code prepareFoilSubmit} (the item's baked quads re-run with the glint render type). Left
 * alone, the mod's generic capture hook ({@link RenderTypeFeatureRendererMixins}) picked up the foil
 * pass as a SEPARATE opaque geometry layer keyed on {@code RenderTypes.glint()} -- ray-traced as a
 * solid glint-textured surface (the "rectangle" over a held enchanted item). Folding that foil pass
 * into the item's buffer instead would DOUBLE the geometry (two coplanar copies), which z-fights and
 * reads as a flat, depthless item.
 *
 * <p>The RT supports glint as a per-vertex OVERLAY on the item itself ({@code default.rchit}:
 * {@code useGlint}/{@code glintUV}/{@code glintTexture}, additive {@code tint = albedo + glint},
 * scrolled by {@code worldUBO.textureMat} -- the scroll matrix comes from {@code WorldRendererMixins}).
 * So we reproduce 1.21's behaviour, where the item was drawn ONCE with glint folded in: stamp the
 * glint overlay onto the item's OWN vertices during {@code prepareMainSubmit} (via {@link
 * GlintContext}, read by {@code PBRVertexConsumer}), and SKIP the separate {@code prepareFoilSubmit}
 * geometry. One geometry copy, full depth, no rectangle.
 *
 * <p>Worn-armor glint ({@code ENCHANTED_GLINT_ARMOR}, a different feature renderer) is not handled
 * here -- separate follow-up.
 */
@Mixin(ItemFeatureRenderer.class)
public class ItemFeatureRendererMixins {

    private static final String MAIN_SUBMIT =
        "prepareMainSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V";
    private static final String FOIL_SUBMIT =
        "prepareFoilSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V";

    /**
     * Activate the glint overlay for an enchanted item's main-pass vertices. Sets deterministically
     * (glint id when enchanted, else clears) so a non-enchanted item can never inherit a stale glint,
     * even if a prior main pass threw before its RETURN clear.
     */
    @Inject(method = MAIN_SUBMIT, at = @At("HEAD"))
    private void radiance$beginGlint(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        if (FeatureGeometryCapture.active() != null
            && submit.foilType() != ItemStackRenderState.FoilType.NONE) {
            // Both glint layers bind ENCHANTED_GLINT_ITEM as Sampler0; the id is blend-independent.
            GlintContext.set(StorageVertexConsumerProvider.resolveTextureId(RenderTypes.glint()));
        } else {
            GlintContext.clear();
        }
    }

    /** Always clear -- the item's main pass is the only geometry that should carry the glint. */
    @Inject(method = MAIN_SUBMIT, at = @At("RETURN"))
    private void radiance$endGlint(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        GlintContext.clear();
    }

    /**
     * Skip the separate foil re-draw during capture: the glint now rides on the main geometry, so a
     * second (opaque, coplanar-doubling) layer would only bring back the rectangle / flat look. When
     * not capturing, leave vanilla foil rendering untouched.
     */
    @Inject(method = FOIL_SUBMIT, at = @At("HEAD"), cancellable = true)
    private void radiance$skipFoil(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        if (FeatureGeometryCapture.active() != null) {
            ci.cancel();
        }
    }
}
