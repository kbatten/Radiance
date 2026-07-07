package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.radiance.client.option.Options;
import com.radiance.client.pipeline.Pipeline;
import com.radiance.client.proxy.vulkan.RendererProxy;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.client.texture.AuxiliaryTextureReloader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.main.GameConfig;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 client integration. MinecraftClient became {@link Minecraft} and its rendering backend was
 * rearchitected around a {@code GpuDevice}/{@code GpuSurface} abstraction (26.2 even ships a native
 * VulkanBackend): the GL {@code Framebuffer} field is gone (the main target is now
 * {@code gameRenderer.mainRenderTarget()}), presentation goes through
 * {@code windowSurface.blitFromTexture(...)} + {@code windowSurface.present()}, the render method was
 * renamed {@code render(Z)} -> {@code renderFrame(Z)}, and {@code RunArgs} -> {@code GameConfig}.
 *
 * <p>The mod runs its own native (MCVR) Vulkan ray-tracing renderer via {@code RendererProxy} and
 * takes over presentation. Because 26.2 constantly uses {@code RenderSystem.getDevice()} (including in
 * the present path), MC's device must still initialise -- so the mod's renderer init is now injected
 * <em>after</em> the window is created (rather than replacing {@code RenderSystem.initRenderer}), and
 * presentation is taken over by redirecting {@code windowSurface.present()}.
 *
 * <p>Dropped as obsolete in 26.2 (their targets dissolved into the GpuDevice/frame-graph): the whole
 * GL-framebuffer suppression block (the {@code new WindowFramebuffer} / framebuffer-field / clear /
 * texture-size / beginWrite / draw / resize redirects), the {@code GlTimer} disable, and
 * {@code isAmbientOcclusionEnabled} (gone from Minecraft; AO is per-section options now), and the
 * disconnect {@code render} cancel (disconnect no longer drives a render). {@code scheduleStop} is
 * gone too, so the native renderer is closed at {@code close()} TAIL.
 *
 * <p>NOTE: the renderer-init and present takeovers are the mod's core Vulkan integration and depend
 * on how the MCVR renderer coexists with 26.2's GpuDevice/windowSurface -- they compile and preserve
 * the original intent, but want runtime validation.
 */
@Mixin(Minecraft.class)
public class MinecraftClientMixins {

    @Shadow
    @Final
    private Window window;

    @Shadow
    @Final
    private ReloadableResourceManager resourceManager;

    /**
     * 26.2: MCVR needs a Vulkan ({@code GLFW_NO_API}) window surface, which MC's OpenGL backend
     * cannot provide -- {@code glfwCreateWindowSurface} then fails with "requires the window to have
     * the client API set to GLFW_NO_API". The saved graphics-API preference is often OpenGL (and 26.2
     * resets it to OpenGL after an unclean shutdown), so force the Vulkan backend here: the window is
     * created context-less and the native renderer's surface can attach.
     */
    @Redirect(method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/PreferredGraphicsApi;"
            + "getBackendsToTry()[Lcom/mojang/blaze3d/systems/GpuBackend;"))
    private GpuBackend[] radiance$forceVulkanBackend(PreferredGraphicsApi instance) {
        return new GpuBackend[]{new VulkanBackend()};
    }

    // region <init>
    @Inject(method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;window:Lcom/mojang/blaze3d/platform/Window;",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void initRenderer(GameConfig gameConfig, CallbackInfo ci) {
        long stackSize = 512 * 1024 * 1024;
        Runnable myRunnable = () -> {
            RendererProxy.initRenderer(this.window);
            Pipeline.collectNativeModules();
        };

        Thread myThread = new Thread(null, myRunnable, "", stackSize);
        myThread.start();
        try {
            myThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        Pipeline.loadPipeline();
        Pipeline.build();
    }

    @Inject(method = "<init>(Lnet/minecraft/client/main/GameConfig;)V",
        at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/Minecraft;resourceManager:"
                + "Lnet/minecraft/server/packs/resources/ReloadableResourceManager;",
            opcode = Opcodes.PUTFIELD,
            shift = At.Shift.AFTER))
    private void registerAuxiliaryTextureReloader(GameConfig gameConfig, CallbackInfo ci) {
        this.resourceManager.registerReloadListener(new AuxiliaryTextureReloader());
    }
    // endregion

    // region <renderFrame>
    // MCVR owns the window's Vulkan swapchain (created in RendererProxy.initRenderer during <init>),
    // so MC's own swapchain lifecycle must be suppressed: otherwise windowSurface.configure() fails
    // with VK_ERROR_NATIVE_WINDOW_IN_USE and -- crucially -- MC never "acquires", so its
    // windowSurface.present() (only reached while acquired) never runs. That is why the present
    // takeover is an unconditional renderFrame TAIL inject, not a present() redirect.
    @Redirect(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/GpuSurface;configure"
            + "(Lcom/mojang/blaze3d/systems/GpuSurface$Configuration;)V"))
    public void cancelSurfaceConfigure(GpuSurface instance, GpuSurface.Configuration config) {

    }

    @Redirect(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/GpuSurface;acquireNextTexture()V"))
    public void cancelSurfaceAcquire(GpuSurface instance) {

    }

    @Inject(method = "renderFrame(Z)V", at = @At("TAIL"))
    public void takeOverPresent(boolean advanceGameTime, CallbackInfo ci) {
        ChunkProxy.waitImportantChunkRebuild();
        synchronized (TextureProxy.class) {
            RendererProxy.submitCommandAndPresent();
            RendererProxy.acquireContext();
        }
    }

    @Redirect(method = "renderFrame(Z)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/FramerateLimiter;limitDisplayFPS(I)V"))
    public void disableFPSLimit(int fps) {

    }
    // endregion

    // region <close>
    @Inject(method = "close()V", at = @At(value = "HEAD"))
    public void saveConfigOnClose(CallbackInfo ci) {
        Options.overwriteConfig();
    }

    @Inject(method = "close()V", at = @At(value = "TAIL"))
    public void closeRenderer(CallbackInfo ci) {
        RendererProxy.close();
    }
    // endregion

    // region <disconnect>
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("HEAD"))
    public void resetBuiltChunkNum(Screen screen, boolean keepResourcePacks, CallbackInfo ci) {
        ChunkProxy.builtChunkNum = 0;
    }
    // endregion
}
