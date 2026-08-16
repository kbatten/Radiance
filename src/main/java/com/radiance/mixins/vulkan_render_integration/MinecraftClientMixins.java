package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.GpuSurface;
import com.radiance.client.option.Options;
import com.radiance.client.pipeline.Pipeline;
import com.radiance.client.proxy.vulkan.RendererProxy;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.client.texture.AuxiliaryTextureReloader;
import net.minecraft.client.Minecraft;
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
 * takes over presentation. MC stays on its own (GL) backend so {@code GlDevice} initialises with a
 * real context and the mod's GL-command capture mixins fire; MCVR attaches its Vulkan surface to the
 * raw window natively (see MCVR's platform surface creation), so no {@code GLFW_NO_API} window is
 * needed. MC's device init happens normally; the mod's renderer init is injected <em>after</em> the
 * window is created, and presentation is taken over by a renderFrame TAIL inject (MC's own surface
 * configure/acquire/present are no-oped).
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
    // MCVR owns the window's presentation (its own native Vulkan surface + swapchain), so MC must not
    // drive the window surface itself. No-op MC's configure()/acquireNextTexture() so MC never
    // "acquires" -- which also means its windowSurface.present() (only reached while acquired) never
    // runs -- and take over presentation with an unconditional renderFrame TAIL inject.
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

    // Tear down the native Vulkan renderer right BEFORE MC destroys its GLFW window -- not at TAIL.
    // Minecraft.close() frees its own resources first (TextureManager.close() -> our NativeImage frees,
    // which need the device alive), then near the end calls window.close() (glfwDestroyWindow) and
    // glfwTerminate(). Our vk::Window destructor does vkDestroySurfaceKHR on the surface created from
    // that HWND, and the swapchain is bound to it; destroying them after the window/HWND is gone (and
    // GLFW terminated) crashes the Windows/NVIDIA WSI on every exit. Injecting before Window.close()
    // means: device still valid (textures already freed), window/HWND still valid -> clean teardown.
    // (Was @At TAIL == after window.close() + glfwTerminate() -> the shutdown-order exit crash.)
    @Inject(method = "close()V",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/Window;close()V"))
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
