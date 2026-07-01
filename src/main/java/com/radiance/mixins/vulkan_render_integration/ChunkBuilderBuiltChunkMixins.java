package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.proxy.world.ChunkProxy;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: {@code ChunkBuilder.BuiltChunk} -> {@code SectionRenderDispatcher.RenderSection}. The old
 * lifecycle hooks (clear/scheduleRebuild -> enqueue, setSectionPos -> relocate, the {@code <init>}
 * stream.collect redirect + delete cancel) collapse: 26.2's single "rebuild this section" trigger
 * is {@code compileAsync}, so it is hooked HEAD-cancellable to enqueue + suppress MC's own compile
 * (the mod compiles + renders via its native backend). {@code setSectionPos} -> {@code setSectionNode}.
 * The {@code <init>} redirect and {@code delete} cancel are obsolete (RenderSection's constructor is
 * just {@code (int index, long sectionNode)} with no per-section buffer setup, and disposal moved to
 * the dispatcher).
 */
@Mixin(SectionRenderDispatcher.RenderSection.class)
public class ChunkBuilderBuiltChunkMixins {

    @Inject(method = "compileAsync", at = @At(value = "HEAD"), cancellable = true)
    private void enqueueOnCompile(RenderSectionRegion region, CallbackInfo ci) {
        SectionRenderDispatcher.RenderSection self = (SectionRenderDispatcher.RenderSection) (Object) this;
        ChunkProxy.enqueueRebuild(self);
        ci.cancel();
    }

    @Inject(method = "setSectionNode(J)V", at = @At(value = "TAIL"))
    private void syncNativeChunkSlot(long sectionNode, CallbackInfo ci) {
        // setSectionNode is also called from the RenderSection constructor (before ViewArea's
        // <init> tail inits the native side), so guard against relocating an uninitialized slot.
        if (!ChunkProxy.isInitialized()) {
            return;
        }
        SectionRenderDispatcher.RenderSection self = (SectionRenderDispatcher.RenderSection) (Object) this;
        ChunkProxy.relocateSingle(self.index, self.getRenderOrigin().getX(),
            self.getRenderOrigin().getY(), self.getRenderOrigin().getZ());
    }
}
