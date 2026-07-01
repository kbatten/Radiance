package com.radiance.mixins.vulkan_render_integration;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import com.radiance.mixin_related.extensions.vanilla_resource_tracker.INativeImageExt;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// 26.2: client.texture.MipmapHelper.getMipmapLevelsImages -> renderer.texture.MipmapGenerator.
// generateMipLevels (NativeImage -> com.mojang.blaze3d.platform.NativeImage). Still propagate the
// tracked Identifier from each mip's source image to the newly-created downscaled image so the
// texture subsystem can attribute the upload; the injection site (data.getWidth(), the 2nd
// NativeImage#getWidth in the method) and the @Local NativeImage ordinals are unchanged.
@Mixin(MipmapGenerator.class)
public class MipmapHelperMixins {

    @Inject(method = "generateMipLevels", at = @At(value = "INVOKE",
        target = "Lcom/mojang/blaze3d/platform/NativeImage;getWidth()I", ordinal = 1))
    private static void addIdentifier(Identifier name, NativeImage[] currentMips, int newMipLevel,
        MipmapStrategy mipmapStrategy, float alphaCutoffBias, Transparency transparency,
        CallbackInfoReturnable<NativeImage[]> cir, @Local(ordinal = 0) NativeImage lastData,
        @Local(ordinal = 1) NativeImage data) {
        ((INativeImageExt) (Object) data).radiance$setIdentifier(
            ((INativeImageExt) (Object) lastData).radiance$getIdentifier());
    }
}
