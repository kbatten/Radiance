package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.radiance.client.vertex.PBRVertexConsumer;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IAbstractTextureExt;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisGraph;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2: {@code SectionBuilder.build} -> {@code SectionCompiler.compile}. The geometry-extraction
 * model changed from "render each block into a VertexConsumer" to a {@code BlockQuadOutput}
 * (baked-quad callback) + {@code BufferBuilder.putBlockBakedQuad}. {@code putBlockBakedQuad} is a
 * default on {@code VertexConsumer} that routes through the standard per-vertex setters, so the
 * mod's {@link PBRVertexConsumer} still captures everything -- we just supply a
 * {@code BlockQuadOutput}/{@code FluidRenderer.Output} that funnel each layer's quads into a
 * per-{@link ChunkSectionLayer} PBR consumer instead of the pack's {@code BufferBuilder}.
 *
 * <p>Render-state resolution simplifies: the terrain texture is always the block atlas, and
 * alphaMode maps straight off the {@code ChunkSectionLayer} enum -- no RenderPhase inspection.
 */
@Mixin(SectionCompiler.class)
public abstract class SectionBuilderMixins {

    @Shadow
    @Final
    private boolean ambientOcclusion;

    @Shadow
    @Final
    private boolean cutoutLeaves;

    @Shadow
    @Final
    private BlockStateModelSet blockModelSet;

    @Shadow
    @Final
    private FluidStateModelSet fluidModelSet;

    @Shadow
    @Final
    private BlockColors blockColors;

    @Shadow
    abstract <E extends BlockEntity> void handleBlockEntity(SectionCompiler.Results results,
        E blockEntity);

    @Inject(method = "compile", at = @At("HEAD"), cancellable = true)
    public void redirectCompile(SectionPos sectionPos, RenderSectionRegion region,
        VertexSorting vertexSorting, SectionBufferBuilderPack builders,
        CallbackInfoReturnable<SectionCompiler.Results> cir) {
        SectionCompiler.Results results = new SectionCompiler.Results();
        BlockPos minPos = sectionPos.origin();
        BlockPos maxPos = minPos.offset(15, 15, 15);
        VisGraph visGraph = new VisGraph();
        BlockModelLighter.enableCaching();
        ModelBlockRenderer blockRenderer = new ModelBlockRenderer(this.ambientOcclusion, true,
            this.blockColors);
        FluidRenderer fluidRenderer = new FluidRenderer(this.fluidModelSet);
        int atlasGlId = blockAtlasGlId();
        Map<ChunkSectionLayer, PBRVertexConsumer> map = new EnumMap<>(ChunkSectionLayer.class);

        BlockQuadOutput quadOutput = (x, y, z, quad, instance) ->
            beginBufferBuilding(map, builders, quad.materialInfo().layer(), atlasGlId)
                .putBlockBakedQuad(x, y, z, quad, instance);
        BlockQuadOutput opaqueQuadOutput = (x, y, z, quad, instance) ->
            beginBufferBuilding(map, builders, ChunkSectionLayer.SOLID, atlasGlId)
                .putBlockBakedQuad(x, y, z, quad, instance);
        FluidRenderer.Output fluidOutput = layer -> beginBufferBuilding(map, builders, layer,
            atlasGlId);

        for (BlockPos pos : BlockPos.betweenClosed(minPos, maxPos)) {
            BlockState blockState = region.getBlockState(pos);
            if (blockState.isAir()) {
                continue;
            }

            if (blockState.isSolidRender()) {
                visGraph.setOpaque(pos);
            }

            if (blockState.hasBlockEntity()) {
                BlockEntity blockEntity = region.getBlockEntity(pos);
                if (blockEntity != null) {
                    this.handleBlockEntity(results, blockEntity);
                }
            }

            FluidState fluidState = blockState.getFluidState();
            if (!fluidState.isEmpty()) {
                fluidRenderer.tesselate(region, pos, fluidOutput, blockState, fluidState);
            }

            if (blockState.getRenderShape() == RenderShape.MODEL) {
                blockRenderer.tesselateBlock(
                    ModelBlockRenderer.forceOpaque(this.cutoutLeaves, blockState)
                        ? opaqueQuadOutput : quadOutput,
                    SectionPos.sectionRelative(pos.getX()),
                    SectionPos.sectionRelative(pos.getY()),
                    SectionPos.sectionRelative(pos.getZ()),
                    region,
                    pos,
                    blockState,
                    this.blockModelSet.get(blockState),
                    blockState.getSeed(pos));
            }
        }

        for (Map.Entry<ChunkSectionLayer, PBRVertexConsumer> entry : map.entrySet()) {
            MeshData mesh = entry.getValue().endNullable();
            if (mesh != null) {
                results.renderedLayers.put(entry.getKey(), mesh);
            }
        }

        BlockModelLighter.clearCache();
        results.visibilitySet = visGraph.resolve();

        cir.setReturnValue(results);
    }

    @Unique
    private static PBRVertexConsumer beginBufferBuilding(
        Map<ChunkSectionLayer, PBRVertexConsumer> builders, SectionBufferBuilderPack pack,
        ChunkSectionLayer layer, int atlasGlId) {
        PBRVertexConsumer consumer = builders.get(layer);
        if (consumer == null) {
            consumer = new PBRVertexConsumer(pack.buffer(layer), atlasGlId, alphaModeFor(layer));
            builders.put(layer, consumer);
        }
        return consumer;
    }

    @Unique
    private static int alphaModeFor(ChunkSectionLayer layer) {
        return switch (layer) {
            case SOLID -> PBRVertexConsumer.ALPHA_MODE_OPAQUE;
            case CUTOUT -> PBRVertexConsumer.ALPHA_MODE_CUTOUT;
            case TRANSLUCENT -> PBRVertexConsumer.ALPHA_MODE_TRANSPARENT;
        };
    }

    @Unique
    private static int blockAtlasGlId() {
        return ((IAbstractTextureExt) Minecraft.getInstance()
            .getTextureManager()
            .getTexture(TextureAtlas.LOCATION_BLOCKS)).radiance$getGlIDUnsafe();
    }
}
