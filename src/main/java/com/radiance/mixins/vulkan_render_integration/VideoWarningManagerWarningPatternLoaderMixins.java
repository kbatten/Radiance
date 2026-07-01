package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.systems.DeviceInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// 26.2: VideoWarningManager.WarningPatternLoader.buildWarnings -> GpuWarnlistManager.Preparations.
// apply, which reads the GPU renderer/version/vendor from RenderSystem.getDevice().getDeviceInfo()
// (a com.mojang.blaze3d.systems.DeviceInfo) instead of the removed GlDebugInfo static getters. The
// GlDevice still reports backendName "OpenGL" (so the warn block runs), so keep faking the values
// to the mod's Vulkan renderer identity to avoid false GL warnings.
// Preparations is protected, so target it by name rather than by .class.
@Mixin(targets = "net.minecraft.client.renderer.GpuWarnlistManager$Preparations")
public class VideoWarningManagerWarningPatternLoaderMixins {

    @Redirect(method = "apply()Lcom/google/common/collect/ImmutableMap;",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/DeviceInfo;name()Ljava/lang/String;"))
    private String setRendererName(DeviceInfo instance) {
        return "NeoVoxelRT - Vulkan";
    }

    @Redirect(method = "apply()Lcom/google/common/collect/ImmutableMap;",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/DeviceInfo;driverInfo()Ljava/lang/String;"))
    private String setRendererVersion(DeviceInfo instance) {
        return "1.3";
    }

    @Redirect(method = "apply()Lcom/google/common/collect/ImmutableMap;",
        at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/systems/DeviceInfo;vendorName()Ljava/lang/String;"))
    private String setRendererVendor(DeviceInfo instance) {
        return "Cross Platform";
    }
}
