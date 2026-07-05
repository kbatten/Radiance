package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.platform.Window;
import com.radiance.client.proxy.vulkan.WindowProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: Window moved to {@code com.mojang.blaze3d.platform.Window}, and the OpenGL context setup was
 * pulled out of the constructor into {@code createGlfwWindow(..., GpuBackend)} /
 * {@code GpuBackend.setWindowHints()}. As a result the old GL-suppression hooks this mixin carried
 * (the six {@code glfwWindowHint} cancels + the injected {@code GLFW_NO_API} hint, the
 * {@code glfwMakeContextCurrent} / {@code GL.createCapabilities} /
 * {@code RenderSystem.maxSupportedTextureSize} / {@code glfwSetWindowSizeLimits} cancels) no longer
 * have a target in the Window constructor -- creating a context-less (Vulkan) window is now the
 * {@code GpuBackend} layer's job, not a Window mixin's. Those hooks are dropped here.
 *
 * <p>What survives is the framebuffer-resize notification to the native side. The GLFW callback was
 * renamed {@code onFramebufferSizeChanged} -> {@code onFramebufferResize}, and it notifies MC through
 * {@code WindowEventHandler.framebufferSizeChanged()} (was {@code onResolutionChanged()}).
 */
@Mixin(Window.class)
public class WindowMixins {

    @Inject(method = "onFramebufferResize(JII)V",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/WindowEventHandler;framebufferSizeChanged()V"))
    public void framebufferSizeChanged(long window, int width, int height, CallbackInfo ci) {
        WindowProxy.onFramebufferSizeChanged();
    }
}
