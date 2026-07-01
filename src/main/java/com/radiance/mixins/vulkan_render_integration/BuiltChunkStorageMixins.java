package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IViewAreaExt;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: {@code BuiltChunkStorage} -> {@code ViewArea}. createChunks/init -> the ViewArea
 * constructor (sections built there); updateCameraPosition -> repositionCamera; clear ->
 * releaseAllBuffers. Also exposes the section storage for {@code ChunkProxy#rebuildAll}.
 */
@Mixin(ViewArea.class)
public class BuiltChunkStorageMixins implements IViewAreaExt {

    @Shadow
    @Final
    private RotatingSectionStorage<SectionRenderDispatcher.RenderSection> sections;

    @Override
    public RotatingSectionStorage<SectionRenderDispatcher.RenderSection> radiance$getSections() {
        return sections;
    }

    @Inject(method = "<init>", at = @At(value = "TAIL"))
    private void initChunkProxy(SectionRenderDispatcher sectionRenderDispatcher, int minY, int maxY,
        int minSectionY, int maxSectionY, int renderDistance,
        SectionOcclusionGraph sectionOcclusionGraph, CallbackInfo ci) {
        ViewArea self = (ViewArea) (Object) this;
        int sizeXZ = renderDistance * 2 + 1;
        ChunkProxy.init(self.size(), sizeXZ, self.sectionCount(), sizeXZ, minSectionY);
        ChunkProxy.setStorage(self);
    }

    @Inject(method = "repositionCamera(Lnet/minecraft/core/SectionPos;)V", at = @At(value = "HEAD"))
    private void updateChunkStorageSectionPos(SectionPos cameraSectionPos, CallbackInfo ci) {
        ChunkProxy.updateSectionPos(cameraSectionPos);
    }

    @Inject(method = "releaseAllBuffers()V", at = @At(value = "HEAD"))
    private void clearChunkProxy(CallbackInfo ci) {
        ChunkProxy.clear();
    }
}
