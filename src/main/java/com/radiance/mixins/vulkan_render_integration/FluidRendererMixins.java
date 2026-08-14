package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.client.texture.EmissiveBlockColor;
import com.radiance.client.vertex.BlockEmissionContext;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HalfTransparentBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 fluid capture with normals. Vanilla fluid geometry carries a constant up-normal (0,1,0) on
 * every vertex, which is fine for raster shading but wrong for the mod's ray tracer (flat water,
 * incorrect reflections). This mixin re-implements the fluid tessellation exactly as 26.2 vanilla
 * does -- same faces, vertex order and UVs -- but computes a proper per-face normal (a slope normal
 * from the four corner heights for the top surface, the face direction for the sides, and straight
 * down for the bottom).
 *
 * <p>26.2 rearchitected FluidRenderer: {@code render(BlockRenderView, BlockPos, VertexConsumer, ...)}
 * became {@code tesselate(BlockAndTintGetter, BlockPos, FluidRenderer.Output, ...)}, sprites/tint/
 * layer now come from a {@link FluidModel} ({@code fluidModels.get(fluidState)}) rather than
 * {@code lava/water/overlay} sprite fields, lighting from {@code level.cardinalLighting()} rather
 * than {@code getBrightness}, and the vertex builder is obtained via {@code output.getBuilder(layer)}
 * -- which is the per-section PBRVertexConsumer supplied by SectionBuilderMixins, so the fluid (with
 * its normals) is captured for the Vulkan pipeline.
 */
@Mixin(FluidRenderer.class)
public abstract class FluidRendererMixins {

    @Shadow
    @Final
    public FluidStateModelSet fluidModels;

    @Shadow
    private static boolean isNeighborSameFluid(FluidState a, FluidState b) {
        throw new AssertionError();
    }

    @Shadow
    private static boolean isFaceOccludedByNeighbor(Direction direction, float height,
        BlockState neighborState) {
        throw new AssertionError();
    }

    @Shadow
    public static boolean shouldRenderFace(FluidState fluidState, BlockState blockState,
        Direction direction, FluidState neighborFluidState) {
        throw new AssertionError();
    }

    @Shadow
    private float getHeight(BlockAndTintGetter level, Fluid fluidType, BlockPos pos, BlockState state,
        FluidState fluidState) {
        throw new AssertionError();
    }

    @Shadow
    private float calculateAverageHeight(BlockAndTintGetter level, Fluid fluidType, float heightSelf,
        float height2, float height1, BlockPos cornerPos) {
        throw new AssertionError();
    }

    @Shadow
    private int getLightCoords(BlockAndTintGetter level, BlockPos pos) {
        throw new AssertionError();
    }

    @Unique
    private void radiance$vertex(VertexConsumer builder, float x, float y, float z, int color,
        float u, float v, int light, float nx, float ny, float nz) {
        builder.addVertex(x, y, z, color, u, v, OverlayTexture.NO_OVERLAY, light, nx, ny, nz);
    }

    /**
     * Emits a quad (matching vanilla's winding) with an explicit front normal, and optionally a
     * back-facing copy with a separate back normal.
     */
    @Unique
    private void radiance$addFace(VertexConsumer builder,
        float x0, float y0, float z0, float u0, float v0,
        float x1, float y1, float z1, float u1, float v1,
        float x2, float y2, float z2, float u2, float v2,
        float x3, float y3, float z3, float u3, float v3,
        int color, int light, boolean addBackFace,
        float nx, float ny, float nz, float bnx, float bny, float bnz) {
        radiance$vertex(builder, x0, y0, z0, color, u0, v0, light, nx, ny, nz);
        radiance$vertex(builder, x1, y1, z1, color, u1, v1, light, nx, ny, nz);
        radiance$vertex(builder, x2, y2, z2, color, u2, v2, light, nx, ny, nz);
        radiance$vertex(builder, x3, y3, z3, color, u3, v3, light, nx, ny, nz);
        if (addBackFace) {
            radiance$vertex(builder, x0, y0, z0, color, u0, v0, light, bnx, bny, bnz);
            radiance$vertex(builder, x3, y3, z3, color, u3, v3, light, bnx, bny, bnz);
            radiance$vertex(builder, x2, y2, z2, color, u2, v2, light, bnx, bny, bnz);
            radiance$vertex(builder, x1, y1, z1, color, u1, v1, light, bnx, bny, bnz);
        }
    }

    @Inject(method = "tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;"
        + "Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;"
        + "Lnet/minecraft/world/level/block/state/BlockState;"
        + "Lnet/minecraft/world/level/material/FluidState;)V", at = @At("HEAD"), cancellable = true)
    public void tesselateWithNormals(BlockAndTintGetter level, BlockPos pos,
        FluidRenderer.Output output, BlockState blockState, FluidState fluidState, CallbackInfo ci) {
        BlockState stateDown = level.getBlockState(pos.relative(Direction.DOWN));
        FluidState fluidDown = stateDown.getFluidState();
        BlockState stateUp = level.getBlockState(pos.relative(Direction.UP));
        FluidState fluidUp = stateUp.getFluidState();
        BlockState stateNorth = level.getBlockState(pos.relative(Direction.NORTH));
        FluidState fluidNorth = stateNorth.getFluidState();
        BlockState stateSouth = level.getBlockState(pos.relative(Direction.SOUTH));
        FluidState fluidSouth = stateSouth.getFluidState();
        BlockState stateWest = level.getBlockState(pos.relative(Direction.WEST));
        FluidState fluidWest = stateWest.getFluidState();
        BlockState stateEast = level.getBlockState(pos.relative(Direction.EAST));
        FluidState fluidEast = stateEast.getFluidState();

        boolean renderUp = !isNeighborSameFluid(fluidState, fluidUp);
        boolean renderDown = shouldRenderFace(fluidState, blockState, Direction.DOWN, fluidDown)
            && !isFaceOccludedByNeighbor(Direction.DOWN, 0.8888889F, stateDown);
        boolean renderNorth = shouldRenderFace(fluidState, blockState, Direction.NORTH, fluidNorth);
        boolean renderSouth = shouldRenderFace(fluidState, blockState, Direction.SOUTH, fluidSouth);
        boolean renderWest = shouldRenderFace(fluidState, blockState, Direction.WEST, fluidWest);
        boolean renderEast = shouldRenderFace(fluidState, blockState, Direction.EAST, fluidEast);

        if (renderUp || renderDown || renderEast || renderWest || renderNorth || renderSouth) {
            FluidModel model = this.fluidModels.get(fluidState);
            VertexConsumer builder = output.getBuilder(model.layer());
            // Emissive fluids (lava) must light the scene: feed the fluid block's light-emission level
            // (lava = 15, water = 0) into the per-vertex albedoEmission channel, the same vanilla
            // fallback solid emissive blocks use (BlockModelRendererMixins). PBRVertexConsumer writes it
            // for every fluid vertex below, and native buildLightInfos synthesizes a whole-quad area
            // light from it when no LabPBR emission cell covers the quad. Cleared before the method
            // returns so following geometry never inherits it. Water (level 0) is left untouched.
            float fluidEmission = blockState.getLightEmission() / 15.0F;
            if (fluidEmission > 0.0F) {
                // Lava's warm hue for its area light (from the still sprite), not a flat white glow.
                float[] color = EmissiveBlockColor.of(model.stillMaterial().sprite());
                BlockEmissionContext.set(fluidEmission, color[0], color[1], color[2]);
            } else {
                BlockEmissionContext.set(fluidEmission);
            }
            int tintColor = model.tintSource() != null
                ? model.tintSource().colorInWorld(blockState, level, pos)
                : -1;
            CardinalLighting lighting = level.cardinalLighting();
            Fluid type = fluidState.getType();

            float heightSelf = this.getHeight(level, type, pos, blockState, fluidState);
            float heightNorthEast;
            float heightNorthWest;
            float heightSouthEast;
            float heightSouthWest;
            if (heightSelf >= 1.0F) {
                heightNorthEast = 1.0F;
                heightNorthWest = 1.0F;
                heightSouthEast = 1.0F;
                heightSouthWest = 1.0F;
            } else {
                float heightNorth = this.getHeight(level, type, pos.north(), stateNorth, fluidNorth);
                float heightSouth = this.getHeight(level, type, pos.south(), stateSouth, fluidSouth);
                float heightEast = this.getHeight(level, type, pos.east(), stateEast, fluidEast);
                float heightWest = this.getHeight(level, type, pos.west(), stateWest, fluidWest);
                heightNorthEast = this.calculateAverageHeight(level, type, heightSelf, heightNorth,
                    heightEast, pos.relative(Direction.NORTH).relative(Direction.EAST));
                heightNorthWest = this.calculateAverageHeight(level, type, heightSelf, heightNorth,
                    heightWest, pos.relative(Direction.NORTH).relative(Direction.WEST));
                heightSouthEast = this.calculateAverageHeight(level, type, heightSelf, heightSouth,
                    heightEast, pos.relative(Direction.SOUTH).relative(Direction.EAST));
                heightSouthWest = this.calculateAverageHeight(level, type, heightSelf, heightSouth,
                    heightWest, pos.relative(Direction.SOUTH).relative(Direction.WEST));
            }

            float x = pos.getX() & 15;
            float y = pos.getY() & 15;
            float z = pos.getZ() & 15;
            float bottomOffs = renderDown ? 0.001F : 0.0F;

            // ===== Top surface (slope normal from the four corner heights) =====
            if (renderUp && !isFaceOccludedByNeighbor(Direction.UP,
                Math.min(Math.min(heightNorthWest, heightSouthWest),
                    Math.min(heightSouthEast, heightNorthEast)), stateUp)) {
                heightNorthWest -= 0.001F;
                heightSouthWest -= 0.001F;
                heightSouthEast -= 0.001F;
                heightNorthEast -= 0.001F;

                Vec3 flow = fluidState.getFlow(level, pos);
                float u00;
                float u01;
                float u10;
                float u11;
                float v00;
                float v01;
                float v10;
                float v11;
                if (flow.x == 0.0 && flow.z == 0.0) {
                    TextureAtlasSprite stillSprite = model.stillMaterial().sprite();
                    u00 = stillSprite.getU0();
                    v00 = stillSprite.getV0();
                    u01 = u00;
                    v01 = stillSprite.getV1();
                    u10 = stillSprite.getU1();
                    v10 = v01;
                    u11 = u10;
                    v11 = v00;
                } else {
                    float angle = (float) Mth.atan2(flow.z, flow.x) - (float) (Math.PI / 2);
                    float s = Mth.sin(angle) * 0.25F;
                    float c = Mth.cos(angle) * 0.25F;
                    TextureAtlasSprite flowingSprite = model.flowingMaterial().sprite();
                    u00 = flowingSprite.getU(0.5F + (-c - s));
                    v00 = flowingSprite.getV(0.5F + (-c + s));
                    u01 = flowingSprite.getU(0.5F + (-c + s));
                    v01 = flowingSprite.getV(0.5F + (c + s));
                    u10 = flowingSprite.getU(0.5F + (c + s));
                    v10 = flowingSprite.getV(0.5F + (c - s));
                    u11 = flowingSprite.getU(0.5F + (c - s));
                    v11 = flowingSprite.getV(0.5F + (-c - s));
                }

                float normalX = (heightNorthWest - heightNorthEast)
                    + (heightSouthWest - heightSouthEast);
                float normalZ = (heightNorthWest - heightSouthWest)
                    + (heightNorthEast - heightSouthEast);
                float normalY = 1.0F;
                float length = Mth.sqrt(normalX * normalX + normalY * normalY + normalZ * normalZ);
                normalX /= length;
                normalY /= length;
                normalZ /= length;

                int topColor = ARGB.scaleRGB(tintColor, lighting.up());
                int topLight = this.getLightCoords(level, pos);
                radiance$addFace(builder,
                    x + 0.0F, y + heightNorthWest, z + 0.0F, u00, v00,
                    x + 0.0F, y + heightSouthWest, z + 1.0F, u01, v01,
                    x + 1.0F, y + heightSouthEast, z + 1.0F, u10, v10,
                    x + 1.0F, y + heightNorthEast, z + 0.0F, u11, v11,
                    topColor, topLight, fluidState.shouldRenderBackwardUpFace(level, pos.above()),
                    normalX, normalY, normalZ, -normalX, -normalY, -normalZ);
            }

            // ===== Bottom (normal straight down) =====
            if (renderDown) {
                TextureAtlasSprite stillSprite = model.stillMaterial().sprite();
                float u0 = stillSprite.getU0();
                float u1 = stillSprite.getU1();
                float v0 = stillSprite.getV0();
                float v1 = stillSprite.getV1();
                int belowColor = ARGB.scaleRGB(tintColor, lighting.down());
                int belowLight = this.getLightCoords(level, pos.below());
                radiance$addFace(builder,
                    x, y + bottomOffs, z, u0, v0,
                    x + 1.0F, y + bottomOffs, z, u1, v0,
                    x + 1.0F, y + bottomOffs, z + 1.0F, u1, v1,
                    x, y + bottomOffs, z + 1.0F, u0, v1,
                    belowColor, belowLight, false,
                    0.0F, -1.0F, 0.0F, 0.0F, -1.0F, 0.0F);
            }

            // ===== Sides (normal = face direction) =====
            int sideLight = this.getLightCoords(level, pos);
            for (Direction faceDir : Direction.Plane.HORIZONTAL) {
                float hh0;
                float hh1;
                float x0;
                float z0;
                float x1;
                float z1;
                boolean renderCondition;
                BlockState faceState;
                switch (faceDir) {
                    case NORTH:
                        hh0 = heightNorthWest;
                        hh1 = heightNorthEast;
                        x0 = x;
                        x1 = x + 1.0F;
                        z0 = z + 0.001F;
                        z1 = z + 0.001F;
                        renderCondition = renderNorth;
                        faceState = stateNorth;
                        break;
                    case SOUTH:
                        hh0 = heightSouthEast;
                        hh1 = heightSouthWest;
                        x0 = x + 1.0F;
                        x1 = x;
                        z0 = z + 1.0F - 0.001F;
                        z1 = z + 1.0F - 0.001F;
                        renderCondition = renderSouth;
                        faceState = stateSouth;
                        break;
                    case WEST:
                        hh0 = heightSouthWest;
                        hh1 = heightNorthWest;
                        x0 = x + 0.001F;
                        x1 = x + 0.001F;
                        z0 = z + 1.0F;
                        z1 = z;
                        renderCondition = renderWest;
                        faceState = stateWest;
                        break;
                    default: // EAST
                        hh0 = heightNorthEast;
                        hh1 = heightSouthEast;
                        x0 = x + 1.0F - 0.001F;
                        x1 = x + 1.0F - 0.001F;
                        z0 = z;
                        z1 = z + 1.0F;
                        renderCondition = renderEast;
                        faceState = stateEast;
                }

                if (renderCondition
                    && !isFaceOccludedByNeighbor(faceDir, Math.max(hh0, hh1), faceState)) {
                    TextureAtlasSprite sprite = model.flowingMaterial().sprite();
                    boolean isOverlay = false;
                    if (model.overlayMaterial() != null) {
                        Block relativeBlock = faceState.getBlock();
                        if (relativeBlock instanceof HalfTransparentBlock
                            || relativeBlock instanceof LeavesBlock) {
                            sprite = model.overlayMaterial().sprite();
                            isOverlay = true;
                        }
                    }

                    float u0 = sprite.getU(0.0F);
                    float u1 = sprite.getU(0.5F);
                    float v01 = sprite.getV((1.0F - hh0) * 0.5F);
                    float v02 = sprite.getV((1.0F - hh1) * 0.5F);
                    float v1 = sprite.getV(0.5F);
                    float shadeSide = faceDir.getAxis() == Direction.Axis.Z
                        ? lighting.north() : lighting.west();
                    int faceColor = ARGB.scaleRGB(tintColor, lighting.up() * shadeSide);
                    float dirX = faceDir.getStepX();
                    float dirZ = faceDir.getStepZ();
                    radiance$addFace(builder,
                        x0, y + hh0, z0, u0, v01,
                        x1, y + hh1, z1, u1, v02,
                        x1, y + bottomOffs, z1, u1, v1,
                        x0, y + bottomOffs, z0, u0, v1,
                        faceColor, sideLight, !isOverlay,
                        dirX, 0.0F, dirZ, dirX, 0.0F, dirZ);
                }
            }
        }

        // Clear the emission the render block may have set so the next section geometry (a non-emissive
        // block/fluid) never inherits a stale value. Safe when unset.
        BlockEmissionContext.clear();
        ci.cancel();
    }
}
