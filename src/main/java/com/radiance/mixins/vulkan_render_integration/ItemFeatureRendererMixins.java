package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.client.vertex.FeatureGeometryCapture;
import com.radiance.client.vertex.PBRVertexConsumer;
import com.radiance.client.vertex.StorageVertexConsumerProvider;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2 enchantment-glint capture. Enchanted items re-draw their baked quads a second time with the
 * glint render type ({@code RenderTypes.glint()} / {@code glintTranslucent()}) via
 * {@code ItemFeatureRenderer.getFoilBuffer(RenderType, PoseStack.Pose)}. Left alone, the mod's
 * generic capture hook ({@link RenderTypeFeatureRendererMixins}) picks that up as a SEPARATE opaque
 * geometry layer keyed on the glint render type -- so its {@code textureID} resolves to the glint
 * sheet and it ray-traces as a solid glint-textured surface (the "rectangle" hiding part of the
 * screen on a held enchanted item).
 *
 * <p>The RT already supports glint as a per-vertex OVERLAY on the item itself (see
 * {@code default.rchit}: {@code useGlint}/{@code glintUV}/{@code glintTexture}, additive
 * {@code tint = albedo + glint}, scrolled by {@code worldUBO.textureMat}). This is the 1.21 wiring
 * (then in {@code ItemRendererMixins.getItemGlintConsumer}); 26.2 renamed the seam to
 * {@code getFoilBuffer}. Intercept it: fold the foil re-draw into the item's OWN buffer -- the same
 * {@code PBRVertexConsumer} the main pass used, since both pass
 * {@code materialInfo.itemRenderType()} as the key -- via {@link PBRVertexConsumer.GLint} (UV-space
 * glint, {@code pose == null}) / {@link PBRVertexConsumer.GLintOverlay} (position-space decal glint,
 * {@code pose != null}, mirroring vanilla's {@code SheetedDecalTextureGenerator}). Cancelling here
 * also skips vanilla's {@code getVertexBuilder(glint)}, so no separate glint geometry is captured.
 * The glint scroll matrix fed to {@code worldUBO.textureMat} lives in {@code WorldRendererMixins}.
 */
@Mixin(ItemFeatureRenderer.class)
public class ItemFeatureRendererMixins {

    // Matches vanilla SheetedDecalTextureGenerator's texture scale (1/128) for decal-pose foil.
    private static final float RADIANCE_FOIL_DECAL_SCALE = 0.0078125F;

    @Inject(method = "getFoilBuffer(Lnet/minecraft/client/renderer/rendertype/RenderType;"
        + "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)Lcom/mojang/blaze3d/vertex/VertexConsumer;",
        at = @At("HEAD"), cancellable = true)
    private void radiance$captureFoilAsGlintOverlay(RenderType renderType, PoseStack.Pose pose,
        CallbackInfoReturnable<VertexConsumer> cir) {
        StorageVertexConsumerProvider store = FeatureGeometryCapture.active();
        if (store == null) {
            // Not in a mod capture pass -- let vanilla foil rendering proceed untouched.
            return;
        }
        // The item's MAIN buffer: prepareMainSubmit and prepareFoilSubmit both key on
        // materialInfo.itemRenderType(), so this is the very consumer the item quads went into.
        VertexConsumer itemBuffer = store.getBuffer(renderType);
        if (!(itemBuffer instanceof PBRVertexConsumer pbr)) {
            return;
        }
        // Both glint layers bind ENCHANTED_GLINT_ITEM as Sampler0; the id is blend-independent.
        int glintTextureID = StorageVertexConsumerProvider.resolveTextureId(RenderTypes.glint());
        if (pose == null) {
            cir.setReturnValue(new PBRVertexConsumer.GLint(pbr, glintTextureID));
        } else {
            cir.setReturnValue(new PBRVertexConsumer.GLintOverlay(pbr, glintTextureID, pose,
                RADIANCE_FOIL_DECAL_SCALE));
        }
    }
}
