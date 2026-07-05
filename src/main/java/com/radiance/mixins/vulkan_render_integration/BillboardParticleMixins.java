package com.radiance.mixins.vulkan_render_integration;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.particle.WhiteAshParticle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 26.2: BillboardParticle became {@link SingleQuadParticle}, and its per-vertex quad build
 * ({@code method_60374}/{@code method_60375} into a {@code VertexConsumer}) was replaced by a single
 * {@code QuadParticleRenderState.add(...)} call inside {@code extractRotatedQuad}, sized from
 * {@code getQuadSize(partialTick)}. The old mixin hand-built a fixed +/-1/8 quad for WhiteAsh; the
 * equivalent 26.2 hook is to pin {@code getQuadSize} to that fixed extent for WhiteAsh, which the
 * render-state quad build then uses.
 */
@Mixin(SingleQuadParticle.class)
public abstract class BillboardParticleMixins {

    @Redirect(method = "extractRotatedQuad(Lnet/minecraft/client/renderer/state/level/"
        + "QuadParticleRenderState;Lorg/joml/Quaternionf;FFFF)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/particle/SingleQuadParticle;getQuadSize(F)F"))
    private float radiance$fixWhiteAshSize(SingleQuadParticle instance, float partialTickTime) {
        if (instance instanceof WhiteAshParticle) {
            // Preserve the old fixed-size WhiteAsh override (was a hand-built +/-1/8 quad).
            return 0.125F;
        }
        return instance.getQuadSize(partialTickTime);
    }
}
