package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ILightMapManagerExt;
import net.minecraft.client.renderer.Lightmap;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: {@code LightmapTextureManager} was replaced by {@link Lightmap}, and the lightmap is no
 * longer a CPU-filled {@code NativeImage} on a GL framebuffer -- it is a {@link GpuTexture} filled by
 * the {@code LIGHTMAP} shader from a {@link LightmapRenderState} (computed by
 * {@code LightmapRenderStateExtractor}). The mod's old CPU per-pixel lightmap computation therefore
 * no longer applies; this narrows to what the pipeline actually needs: the lightmap texture id and the
 * lighting factors, both sourced from vanilla's render state.
 *
 * <p>The last {@link LightmapRenderState} is cached at {@code render()} so the factor getters can
 * expose it. {@code radiance$getTextureId} resolves the {@link GpuTexture}'s GL id
 * ({@link GlTexture}); wiring the Vulkan-backed texture handle is a runtime concern to validate once
 * the mod runs. Two legacy factors ({@code ambientLightFactor}, {@code useBrightLightmap}) have no
 * direct {@code LightmapRenderState} equivalent (they are baked into the tint colors now) and return
 * neutral defaults.
 */
@Mixin(Lightmap.class)
public abstract class LightmapTextureManagerMixins implements ILightMapManagerExt {

    @Final
    @Shadow
    private GpuTexture texture;

    @Unique
    private LightmapRenderState radiance$lastState;

    @Inject(method = "render(Lnet/minecraft/client/renderer/state/LightmapRenderState;)V",
        at = @At("HEAD"))
    private void radiance$captureState(LightmapRenderState renderState, CallbackInfo ci) {
        this.radiance$lastState = renderState;
    }

    @Override
    public int radiance$getTextureId() {
        return this.texture instanceof GlTexture glTexture ? glTexture.glId() : 0;
    }

    @Override
    public float radiance$getAmbientLightFactor() {
        // 26.2: no scalar ambient factor in LightmapRenderState (folded into ambientColor).
        return 0.0F;
    }

    @Override
    public float radiance$getSkyFactor() {
        return this.radiance$lastState != null ? this.radiance$lastState.skyFactor : 0.0F;
    }

    @Override
    public float radiance$getBlockFactor() {
        return this.radiance$lastState != null ? this.radiance$lastState.blockFactor : 0.0F;
    }

    @Override
    public boolean radiance$isUseBrightLightmap() {
        // 26.2: bright-lightmap is folded into the tint colors; no dedicated flag.
        return false;
    }

    @Override
    public Vector3f radiance$getSkyLightColor() {
        return this.radiance$lastState != null
            ? new Vector3f(this.radiance$lastState.skyLightColor)
            : new Vector3f();
    }

    @Override
    public float radiance$getNightVisionFactor() {
        return this.radiance$lastState != null
            ? this.radiance$lastState.nightVisionEffectIntensity : 0.0F;
    }

    @Override
    public float radiance$getDarknessScale() {
        return this.radiance$lastState != null
            ? this.radiance$lastState.darknessEffectScale : 0.0F;
    }

    @Override
    public float radiance$getDarkenWorldFactor() {
        return this.radiance$lastState != null
            ? this.radiance$lastState.bossOverlayWorldDarkening : 0.0F;
    }

    @Override
    public float radiance$getBrightnessFactor() {
        return this.radiance$lastState != null ? this.radiance$lastState.brightness : 0.0F;
    }
}
