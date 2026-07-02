package com.radiance.mixins.vulkan_render_integration;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 26.2: menu-background blur moved out of {@code applyBlur()} (which drove a {@code Framebuffer}
 * write) into {@code Screen.extractBlurredBackground(GuiGraphicsExtractor)}, which now requests the
 * blur through {@code GuiGraphicsExtractor.blurBeforeThisStratum()}. To keep the blur disabled for the
 * mod's pipeline we redirect that call to a no-op (was: redirecting {@code Framebuffer.beginWrite}).
 */
@Mixin(Screen.class)
public class ScreenMixins {

    @Redirect(method = "extractBlurredBackground", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blurBeforeThisStratum()V"))
    public void cancelBlur(GuiGraphicsExtractor instance) {

    }
}
