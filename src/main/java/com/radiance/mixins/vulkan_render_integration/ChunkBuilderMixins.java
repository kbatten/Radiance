package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IChunkBuilderExt;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// 26.2: ChunkBuilder -> SectionRenderDispatcher; the shadowed section builder/world/buffers
// collapse to the dispatcher's SectionCompiler (set via setCompiler, hence non-final/volatile).
// The mod drives its own rebuild loop, so it captures the single dispatcher instance (to reach
// the compiler) when the compiler is installed.
@Mixin(SectionRenderDispatcher.class)
public class ChunkBuilderMixins implements IChunkBuilderExt {

    @Shadow
    volatile SectionCompiler sectionCompiler;

    @Override
    public SectionCompiler radiance$getSectionCompiler() {
        return sectionCompiler;
    }

    @Inject(method = "setCompiler", at = @At(value = "TAIL"))
    private void captureDispatcher(SectionCompiler sectionCompiler, CallbackInfo ci) {
        ChunkProxy.setDispatcher((SectionRenderDispatcher) (Object) this);
    }
}
