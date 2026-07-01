package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.shader.ShaderRegistry;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ReloadableResourceManager.class)
public class ReloadableResourceManagerImplMixins {

    // 26.2: reload(...) -> createReload(...); ResourcePack -> PackResources,
    // ResourceReload -> ReloadInstance.
    @Inject(method = "createReload", at = @At("HEAD"))
    private void clearShaderCache(Executor backgroundExecutor, Executor mainThreadExecutor,
        CompletableFuture<Unit> initialTask, List<PackResources> resourcePacks,
        CallbackInfoReturnable<ReloadInstance> cir) {
        ShaderRegistry.clear();
    }
}
