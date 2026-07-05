package com.radiance.mixins.vulkan_render_integration;

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
        BlockEmissionContext.set(emission);
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
