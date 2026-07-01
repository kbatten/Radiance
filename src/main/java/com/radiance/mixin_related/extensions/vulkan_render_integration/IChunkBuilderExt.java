package com.radiance.mixin_related.extensions.vulkan_render_integration;

import net.minecraft.client.renderer.chunk.SectionCompiler;

/**
 * 26.2: {@code ChunkBuilder} -> {@code SectionRenderDispatcher}. The old accessor exposed the
 * section builder, the client world and the buffer-allocator storage. In 26.2 only the compiler
 * lives on the dispatcher: the level now comes from {@code Minecraft#level}, and the mod builds
 * its own per-thread {@code SectionBufferBuilderPack} rather than borrowing the dispatcher's, so
 * this narrows to the compiler handle.
 */
public interface IChunkBuilderExt {

    SectionCompiler radiance$getSectionCompiler();
}
