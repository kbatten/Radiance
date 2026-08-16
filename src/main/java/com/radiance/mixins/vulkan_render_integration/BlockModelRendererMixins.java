package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.texture.EmissiveBlockColor;
import com.radiance.client.vertex.BlockEmissionContext;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IBlockColorsExt;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 PBR block emission. The old mixin fully replaced {@code BlockModelRenderer.renderQuad} to
 * recompute tint + emission and write them into the PBR vertex consumer. In 26.2 {@code renderQuad}
 * is gone: block quads flow through {@code putQuadWithTint(output, ...)} ->
 * {@code BlockQuadOutput.put} -> a per-section {@code PBRVertexConsumer} (supplied by
 * {@code SectionBuilderMixins}), and tint is now applied natively
 * ({@code quadInstance.multiplyColor}). The one thing the engine does not do is the mod's per-quad
 * PBR emission.
 *
 * <p>The PBR consumer sits behind the {@code BlockQuadOutput} functional interface and is unreachable
 * from here, so emission is bridged through {@link BlockEmissionContext}: this hook computes the
 * quad's emission from the block-color extension and stashes it on a thread-local around the quad's
 * {@code output.put}, and {@code PBRVertexConsumer.addVertex} writes it into the AlbedoEmission
 * channel for the quad's four vertices. Cleared on TAIL so non-emissive geometry (fluids, the next
 * quad, ...) never inherits a stale value.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class BlockModelRendererMixins {

    @Shadow
    @Final
    private BlockColors blockColors;

    @Inject(method = "putQuadWithTint(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFF"
        + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
        + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/client/resources/model/geometry/BakedQuad;)V", at = @At("HEAD"))
    private void setBlockEmission(BlockQuadOutput output, float x, float y, float z,
        BlockAndTintGetter level, BlockState state, BlockPos pos, BakedQuad quad, CallbackInfo ci) {
        int tintIndex = quad.materialInfo().tintIndex();
        float emission = tintIndex != -1
            ? ((IBlockColorsExt) this.blockColors).radiance$getEmission(state, level, pos, tintIndex)
            : 0.0F;
        // Vanilla fallback: emissive blocks (torch, glowstone, lava, fire, sea lantern, ...) carry a
        // 0-15 light-emission level but usually no LabPBR specular emission and no tinted emission
        // provider, so the above yields 0 and they never light the scene. Derive emission from the
        // block's light level so they act as path-tracer light emitters even without a PBR pack. Native
        // buildLightInfos turns a nonzero per-vertex albedoEmission into a whole-quad area light when no
        // LabPBR emission cell covers the quad (so a PBR pack, once wired, still wins where it marks a
        // block emissive).
        if (emission <= 0.0F) {
            int lightLevel = state.getLightEmission();
            if (lightLevel > 0) {
                emission = lightLevel / 15.0F;
            }
        }
        if (emission > 0.0F) {
            // Give the synthesized area light the block's own texture hue (warm torch, red redstone,
            // cool soul torch) instead of a flat white glow. From the quad's sprite, cached.
            float[] color = EmissiveBlockColor.of(quad.materialInfo().sprite());
            BlockEmissionContext.set(emission, color[0], color[1], color[2]);
            // Cache it per block so the handheld light (#23) can match a placed torch's color.
            EmissiveBlockColor.recordBlock(state.getBlock(), color);
        } else {
            BlockEmissionContext.set(0.0F);
        }
    }

    @Inject(method = "putQuadWithTint(Lnet/minecraft/client/renderer/block/BlockQuadOutput;FFF"
        + "Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
        + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;"
        + "Lnet/minecraft/client/resources/model/geometry/BakedQuad;)V", at = @At("TAIL"))
    private void clearBlockEmission(BlockQuadOutput output, float x, float y, float z,
        BlockAndTintGetter level, BlockState state, BlockPos pos, BakedQuad quad, CallbackInfo ci) {
        BlockEmissionContext.clear();
    }
}
