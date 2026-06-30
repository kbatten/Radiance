package com.radiance.client.proxy.vulkan;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import com.radiance.client.constant.Constants;
import com.radiance.mixin_related.extensions.vulkan_render_integration.INativeImageExt;
import net.minecraft.client.Minecraft;

public class RendererProxy {

    public static native void initFolderPath(String folderPath);

    public static native void initRenderer(String[] glfwLibCandidates, long windowHandle);

    public static void initRenderer(Window window) {
        String mapped = System.mapLibraryName("glfw");
        String[] candidates = {mapped, "libglfw.so.3", "libglfw.3.dylib", "glfw3.dll"};
        RendererProxy.initRenderer(candidates, window.handle());
        // 26.2: RenderSystem.apiDescription was removed; the renderer/API string is now
        // reported via GpuDevice/GlDebugInfo (see VideoWarningManagerWarningPatternLoaderMixins).
    }

    public static native int maxSupportedTextureSize();

    public static native void acquireContext();

    public static native void submitCommand();

    public static native void present();

    public static void submitCommandAndPresent() {
        submitCommand();
        present();
    }

    public static native void fuseWorld();

    public static native void postBlur();

    public static native void close();

    public static native void shouldRenderWorld(boolean renderWorld);

    public static native void takeScreenshot(boolean withUI, int width, int height, int channel,
        long pointer);

    public static NativeImage takeScreenshotWithoutUI() {
        Minecraft mc = Minecraft.getInstance();
        int
            width =
            mc.getWindow()
                .getWidth();
        int
            height =
            mc.getWindow()
                .getHeight();
        NativeImage nativeImage = new NativeImage(width, height, false);
        ((INativeImageExt) (Object) nativeImage).radiance$loadFromTextureImageWithoutUI(0, true);
        return nativeImage;
    }
}
