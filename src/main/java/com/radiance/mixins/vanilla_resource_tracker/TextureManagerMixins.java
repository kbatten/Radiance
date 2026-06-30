package com.radiance.mixins.vanilla_resource_tracker;

import com.radiance.client.texture.TextureTracker;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TextureManager.class)
public abstract class TextureManagerMixins {

    // 26.2: TextureManager#registerTexture -> register, and the texture has no GL id
    // yet at registration (the GpuTexture is created lazily). Record the texture so
    // its GL id can be resolved lazily via IAbstractTextureExt#radiance$getGlIDUnsafe().
    @Inject(method = "register(Lnet/minecraft/resources/Identifier;Lnet/minecraft/client/renderer/texture/AbstractTexture;)V", at = @At("HEAD"))
    private void profileTextureRegister(Identifier id, AbstractTexture texture, CallbackInfo ci) {
        TextureTracker.id2Texture.put(id, texture);
    }
}
