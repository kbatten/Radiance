package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.vulkan.BufferProxy;
import com.radiance.client.proxy.vulkan.RendererProxy;
import com.radiance.client.proxy.world.EntityProxy;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 render-loop driver (partner of {@code WorldRendererMixins}). GameRenderer was rearchitected
 * around the frame graph: {@code renderWorld} became {@link GameRenderer#renderLevel} (which builds
 * the clean {@code modelViewMatrix} and calls {@code levelRenderer.render}), the blur is now a post
 * chain ({@code processBlurEffect}), the hand submits through {@code renderItemInHand} +
 * {@code FeatureRenderDispatcher}, and the old {@code Framebuffer.beginWrite} present path is gone
 * (the main render target is driven by {@code createCommandEncoder}/the frame graph).
 *
 * <p>This mixin keeps only the hooks that still have a live target:
 * <ul>
 *   <li>blur takeover ({@code processBlurEffect});</li>
 *   <li>first-person hand capture ({@code renderItemInHand} -&gt;
 *       {@link EntityProxy#queueHandRebuild});</li>
 *   <li>native frame finalize at {@code renderLevel} TAIL ({@link EntityProxy#build()} +
 *       {@code RendererProxy.fuseWorld()}), after both the world ({@code levelRenderer.render}) and
 *       hand queueing;</li>
 *   <li>per-frame world-render gate at {@code render} HEAD.</li>
 * </ul>
 *
 * <p>Dropped as no-longer-applicable in 26.2: the B*V matrix redirects (the clean modelViewMatrix is
 * now read directly in {@code WorldRendererMixins}, so {@code IGameRendererExt} /
 * {@code radiance$getRotationMatrix} is no longer needed), the {@code Framebuffer.beginWrite} cancels,
 * the first-person overlay GUI-reprojection (screen effects now submit-drain inside {@code
 * renderLevel}; capture is a TODO), and the world-icon screenshot redirect.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixins {

    @Shadow
    @Final
    public ItemInHandRenderer itemInHandRenderer;

    @Shadow
    @Final
    private GameRenderState gameRenderState;

    @Inject(method = "processBlurEffect()V", at = @At(value = "HEAD"), cancellable = true)
    public void redirectRenderBlur(CallbackInfo ci) {
        int blurriness = this.gameRenderState.optionsRenderState.menuBackgroundBlurriness;
        if (blurriness >= 1) {
            BufferProxy.updateOverlayPostUniform(blurriness);
            RendererProxy.postBlur();
        }

        ci.cancel();
    }

    @Inject(method = "renderItemInHand(Lnet/minecraft/client/renderer/state/level/CameraRenderState;"
        + "FLorg/joml/Matrix4fc;)V", at = @At(value = "HEAD"), cancellable = true)
    public void redirectRenderHand(CameraRenderState cameraState, float deltaPartialTick,
        Matrix4fc modelViewMatrix, CallbackInfo ci) {
        // Rescale the fixed-FOV hand into the (dynamic) world FOV, as the old getFov(true)/getFov(false)
        // ratio did. World tan(fov/2) = 1 / projectionMatrix.m11; hand FOV is cameraState.hudFov.
        float worldHalfTan = 1.0F / cameraState.projectionMatrix.m11();
        float handHalfTan = (float) Math.tan(Math.toRadians(cameraState.hudFov) * 0.5);
        float handProjectionScale = worldHalfTan / handHalfTan;
        EntityProxy.queueHandRebuild(deltaPartialTick, this.itemInHandRenderer, handProjectionScale);
        ci.cancel();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At(value = "TAIL"))
    public void buildEntities(DeltaTracker deltaTracker, CallbackInfo ci) {
        EntityProxy.build();
    }

    @Inject(method = "renderLevel(Lnet/minecraft/client/DeltaTracker;)V", at = @At(value = "TAIL"))
    public void fuseWorld(DeltaTracker deltaTracker, CallbackInfo ci) {
        RendererProxy.fuseWorld();
    }

    @Inject(method = "render(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At(value = "HEAD"))
    public void shouldRenderWorld(DeltaTracker deltaTracker, boolean advanceGameTime,
        CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        RendererProxy.shouldRenderWorld(
            client.isGameLoadFinished() && advanceGameTime && client.level != null);
    }
}
