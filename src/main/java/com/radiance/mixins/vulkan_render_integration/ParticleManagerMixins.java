package com.radiance.mixins.vulkan_render_integration;

import com.radiance.mixin_related.extensions.vulkan_render_integration.IParticleExt;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2 particle content-name tagging. ParticleManager became {@link ParticleEngine} and the particle
 * pipeline was rearchitected: particles now extract into a {@code ParticlesRenderState} (per
 * {@code ParticleGroup}) and submit through the feature dispatcher, particle throttling is handled
 * natively ({@code trackedParticleCounts} / {@code ParticleLimit}), and per-particle ticking/removal
 * is internal to each {@code ParticleGroup} (there is no {@code tickParticles(Collection)} hook).
 *
 * <p>As a result the old bookkeeping this mixin carried is obsolete in 26.2 and was dropped: the
 * {@code PARTICLE_COUNTERS} counting (native now) and the {@code IParticleManagerExt} collection
 * accessors (the old iterate-and-capture approach; nothing consumed them, and the collection shape
 * changed from {@code Queue<Particle>} to {@code ParticleGroup<?>}). What survives is tagging each
 * particle with a stable content name ({@code /particle/<id>}) via {@link IParticleExt}, so the
 * eventual 26.2 particle capture can name the geometry.
 */
@Mixin(ParticleEngine.class)
public class ParticleManagerMixins {

    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)"
        + "Lnet/minecraft/client/particle/Particle;", at = @At("RETURN"))
    private void assignParticleContentName(ParticleOptions parameters, double x, double y, double z,
        double xa, double ya, double za, CallbackInfoReturnable<Particle> cir) {
        Particle particle = cir.getReturnValue();
        if (particle == null) {
            return;
        }

        Identifier particleId = BuiltInRegistries.PARTICLE_TYPE.getKey(parameters.getType());
        if (particleId != null) {
            ((IParticleExt) particle).radiance$setContentName(toParticleContentName(particleId));
        }
    }

    private static String toParticleContentName(Identifier particleId) {
        if ("minecraft".equals(particleId.getNamespace())) {
            return "/particle/" + particleId.getPath();
        }
        return "/particle/" + particleId.getNamespace() + "/" + particleId.getPath();
    }
}
