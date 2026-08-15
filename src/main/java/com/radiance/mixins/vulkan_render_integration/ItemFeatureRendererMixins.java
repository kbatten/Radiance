package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.vertex.BlockEmissionContext;
import com.radiance.client.vertex.FeatureGeometryCapture;
import com.radiance.client.vertex.GlintContext;
import com.radiance.client.vertex.StorageVertexConsumerProvider;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-item capture state for the RT path: enchantment glint (#12/#15) and held-item self-glow (#21).
 *
 * <p>Both ride on {@code prepareMainSubmit}, which is where the item's baked quads are actually
 * WRITTEN into the capturing {@code PBRVertexConsumer} during a {@code FeatureGeometryCapture} drain
 * (not {@code renderItem}, which only submits nodes earlier). Setting the relevant thread-local at
 * HEAD and clearing it at RETURN therefore scopes it to exactly this item's vertices.
 *
 * <p><b>Glint:</b> 26.2 draws item glint as a SEPARATE {@code prepareFoilSubmit} pass; folding it into
 * the item buffer would double the geometry (coplanar z-fight -> flat item), so instead we stamp the
 * glint overlay onto the item's OWN main-pass vertices ({@link GlintContext}) and skip the foil pass.
 *
 * <p><b>Held-item glow:</b> a held emissive block-item (torch, lantern, glowstone, ...) should look
 * emissive. Item geometry never goes through {@code BlockModelRendererMixins} (the block emission
 * hook), so its flame rendered dull/scene-lit. Here, for a FIRST-PERSON hand item, set {@link
 * BlockEmissionContext} to the item's block light level so {@code PBRVertexConsumer} writes
 * {@code albedoEmission} and the RT self-glows it ({@code default.rchit}: {@code radiance += tint *
 * albedoEmission}). Scoped to first-person hands via {@code submit.displayContext()} so the arm and
 * GUI/ground/third-person items are untouched (buildLightInfos never runs for item geometry, so this
 * only affects self-glow, not scene lighting -- the cast light is #16's dynamic point light).
 */
@Mixin(ItemFeatureRenderer.class)
public class ItemFeatureRendererMixins {

    private static final String MAIN_SUBMIT =
        "prepareMainSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V";
    private static final String FOIL_SUBMIT =
        "prepareFoilSubmit(Lnet/minecraft/client/renderer/feature/ItemFeatureRenderer$Submit;)V";

    /**
     * Set per-item capture state (glint + held-item emission) for the main pass. Both set
     * deterministically (value or clear) so a following item can never inherit stale state.
     */
    @Inject(method = MAIN_SUBMIT, at = @At("HEAD"))
    private void radiance$beginItemState(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        if (FeatureGeometryCapture.active() == null) {
            GlintContext.clear();
            BlockEmissionContext.clear();
            return;
        }
        if (submit.foilType() != ItemStackRenderState.FoilType.NONE) {
            // Both glint layers bind ENCHANTED_GLINT_ITEM as Sampler0; the id is blend-independent.
            GlintContext.set(StorageVertexConsumerProvider.resolveTextureId(RenderTypes.glint()));
        } else {
            GlintContext.clear();
        }
        BlockEmissionContext.set(radiance$heldItemEmission(submit));
    }

    /** Clear both -- the item's main pass is the only geometry that should carry this state. */
    @Inject(method = MAIN_SUBMIT, at = @At("RETURN"))
    private void radiance$endItemState(ItemFeatureRenderer.Submit submit, CallbackInfo ci) {
        GlintContext.clear();
        BlockEmissionContext.clear();
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

    /**
     * Emission strength (0..1) for a first-person hand item, from its block light level -- e.g. a held
     * torch (level 14) self-glows. 0 for non-hand contexts (GUI/ground/third-person), empty hands, and
     * non-emitting items, so only the intended item glows.
     */
    private static float radiance$heldItemEmission(ItemFeatureRenderer.Submit submit) {
        ItemDisplayContext context = submit.displayContext();
        boolean rightHand;
        if (context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            rightHand = true;
        } else if (context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND) {
            rightHand = false;
        } else {
            return 0.0F;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return 0.0F;
        }
        boolean mainArmIsRight = player.getMainArm() == HumanoidArm.RIGHT;
        ItemStack stack = (rightHand == mainArmIsRight) ? player.getMainHandItem() : player.getOffhandItem();
        if (stack.isEmpty()) {
            return 0.0F;
        }
        int lightLevel = Block.byItem(stack.getItem()).defaultBlockState().getLightEmission();
        return lightLevel > 0 ? lightLevel / 15.0F : 0.0F;
    }
}
