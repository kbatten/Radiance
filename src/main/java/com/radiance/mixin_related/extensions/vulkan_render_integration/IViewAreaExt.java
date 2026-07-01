package com.radiance.mixin_related.extensions.vulkan_render_integration;

import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * 26.2: {@code BuiltChunkStorage} -> {@code ViewArea}, whose {@code RenderSection}s live in a
 * private {@code RotatingSectionStorage}. Exposes it so {@code ChunkProxy#rebuildAll} can
 * enumerate every section (was {@code BuiltChunkStorage.chunks[]}).
 */
public interface IViewAreaExt {

    RotatingSectionStorage<SectionRenderDispatcher.RenderSection> radiance$getSections();
}
