package com.radiance.mixins.vulkan_render_integration;

import com.radiance.mixin_related.extensions.vulkan_render_integration.IChunkBuilderExt;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

// 26.2: ChunkBuilder -> SectionRenderDispatcher; the shadowed section builder/world/buffers
// collapse to the dispatcher's SectionCompiler (set via setCompiler, hence non-final/volatile).
@Mixin(SectionRenderDispatcher.class)
public class ChunkBuilderMixins implements IChunkBuilderExt {

    @Shadow
    volatile SectionCompiler sectionCompiler;

    @Override
    public SectionCompiler radiance$getSectionCompiler() {
        return sectionCompiler;
    }
}
