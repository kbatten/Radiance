package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.constant.Constants;
import com.radiance.client.proxy.world.EntityProxy;
import com.radiance.client.vertex.PBRVertexConsumer;
import com.radiance.client.vertex.StorageVertexConsumerProvider;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: {@code CloudRenderer} was rearchitected -- clouds render on the GPU (UBO + shader + GPU
 * buffer), {@code renderClouds(...)} became {@code render(...)}, {@code CloudRenderMode}->{@link
 * CloudStatus}, {@code CloudCells}->{@code CloudRenderer.TextureData}, and the view-mode enum
 * ({@code RelativeCameraPos}) is now private. The mod still intercepts cloud rendering and CPU-
 * tessellates the (unchanged) cell bit-format into a {@link PBRVertexConsumer} for the Vulkan
 * pipeline, so this keeps that tessellation but re-targets it: it reads only the public {@code
 * texture} (TextureData) field and keeps its own rebuild cache in {@code @Unique} fields (using int
 * view-modes to avoid the private enum). There is no cloud {@code RenderType} in 26.2, so the
 * untextured colored quads are keyed on {@link RenderTypes#debugQuads()}.
 */
@Mixin(CloudRenderer.class)
public class CloudRendererMixins {

    @Unique
    private static final int RADIANCE_ABOVE_CLOUDS = 0;
    @Unique
    private static final int RADIANCE_INSIDE_CLOUDS = 1;
    @Unique
    private static final int RADIANCE_BELOW_CLOUDS = 2;

    @Shadow
    private CloudRenderer.TextureData texture;

    @Unique
    private boolean radiance$needsRebuild = true;
    @Unique
    private int radiance$prevCellX = Integer.MIN_VALUE;
    @Unique
    private int radiance$prevCellZ = Integer.MIN_VALUE;
    @Unique
    private int radiance$prevViewMode = RADIANCE_INSIDE_CLOUDS;
    @Unique
    private CloudStatus radiance$prevCloudStatus = null;

    @Unique
    private StorageVertexConsumerProvider storageVertexConsumerProvider = null;

    @Unique
    private EntityProxy.EntityRenderDataList entityRenderDataList = null;

    @Unique
    private static int unpackColor(long packed) {
        return (int) (packed >> 4 & 4294967295L);
    }

    @Unique
    private static boolean hasBorderNorth(long packed) {
        return (packed >> 3 & 1L) != 0L;
    }

    @Unique
    private static boolean hasBorderEast(long packed) {
        return (packed >> 2 & 1L) != 0L;
    }

    @Unique
    private static boolean hasBorderSouth(long packed) {
        return (packed >> 1 & 1L) != 0L;
    }

    @Unique
    private static boolean hasBorderWest(long packed) {
        return (packed >> 0 & 1L) != 0L;
    }

    @Inject(method = "render(ILnet/minecraft/client/CloudStatus;FILnet/minecraft/world/phys/Vec3;JF)V",
        at = @At(value = "HEAD"), cancellable = true)
    public void redirectCloudRendering(int color,
        CloudStatus cloudStatus,
        float bottomY,
        int range,
        Vec3 cameraPosition,
        long gameTime,
        float partialTicks,
        CallbackInfo ci) {
        if (this.texture != null) {
            float f = (float) (bottomY - cameraPosition.y);
            float g = f + 4.0F;
            int viewMode;
            if (g < 0.0F) {
                viewMode = RADIANCE_ABOVE_CLOUDS;
            } else if (f > 0.0F) {
                viewMode = RADIANCE_BELOW_CLOUDS;
            } else {
                viewMode = RADIANCE_INSIDE_CLOUDS;
            }

            double d = cameraPosition.x + partialTicks * 0.030000001F;
            double e = cameraPosition.z + 3.96F;
            double h = this.texture.width() * 12.0;
            double i = this.texture.height() * 12.0;
            d -= Mth.floor(d / h) * h;
            e -= Mth.floor(e / i) * i;
            int j = Mth.floor(d / 12.0);
            int k = Mth.floor(e / 12.0);
            float l = (float) (d - j * 12.0F);
            float m = (float) (e - k * 12.0F);
            RenderType renderLayer = RenderTypes.debugQuads();

            if (this.radiance$needsRebuild || j != this.radiance$prevCellX
                || k != this.radiance$prevCellZ || viewMode != this.radiance$prevViewMode
                || cloudStatus != this.radiance$prevCloudStatus) {
                this.radiance$needsRebuild = false;
                this.radiance$prevCellX = j;
                this.radiance$prevCellZ = k;
                this.radiance$prevViewMode = viewMode;
                this.radiance$prevCloudStatus = cloudStatus;

                this.tessellateClouds(color, j, k, cloudStatus, viewMode, renderLayer);
            }

            if (storageVertexConsumerProvider != null) {
                for (EntityProxy.EntityRenderData data : entityRenderDataList) {
                    data.setX((float) (cameraPosition.x - l));
                    data.setY(bottomY);
                    data.setZ((float) (cameraPosition.z - m));
                }

                EntityProxy.queueBuildWithoutClose(entityRenderDataList);
            }

        }

        ci.cancel();
    }

    @Unique
    private void tessellateClouds(int color, int x, int z, CloudStatus renderMode,
        int viewMode, RenderType layer) {
        float red = ARGB.redFloat(color);
        float green = ARGB.greenFloat(color);
        float blue = ARGB.blueFloat(color);
        int i = ARGB.colorFromFloat(0.8F, red, green, blue);
        int j = ARGB.colorFromFloat(0.8F, 0.9F * red, 0.9F * green, 0.9F * blue);
        int k = ARGB.colorFromFloat(0.8F, 0.7F * red, 0.7F * green, 0.7F * blue);
        int l = ARGB.colorFromFloat(0.8F, 0.8F * red, 0.8F * green, 0.8F * blue);

        if (storageVertexConsumerProvider != null) {
            for (EntityProxy.EntityRenderData entityRenderData : entityRenderDataList) {
                for (EntityProxy.EntityRenderLayer entityRenderLayer : entityRenderData) {
                    MeshData vertexBuffer = entityRenderLayer.builtBuffer();
                    vertexBuffer.close();
                }
            }

            storageVertexConsumerProvider.close();
        }

        storageVertexConsumerProvider = new StorageVertexConsumerProvider(0);
        entityRenderDataList = new EntityProxy.EntityRenderDataList();

        VertexConsumer vertexConsumer = storageVertexConsumerProvider.getBuffer(layer);
        if (vertexConsumer instanceof PBRVertexConsumer pbrVertexConsumer) {
            this.buildCloudCells(viewMode, pbrVertexConsumer, x, z, k, i, j, l,
                renderMode == CloudStatus.FANCY);
        } else {
            throw new RuntimeException("CloudRenderer only supports PBRVertexConsumer");
        }

        EntityProxy.processWorldEntityRenderData(storageVertexConsumerProvider,
            System.identityHashCode("clouds"),
            0,
            0,
            0,
            Constants.RayTracingFlags.CLOUD,
            false,
            entityRenderDataList);
    }

    @Unique
    private void buildCloudCells(int viewMode,
        VertexConsumer builder,
        int x,
        int z,
        int bottomColor,
        int topColor,
        int northSouthColor,
        int eastWestColor,
        boolean fancy) {
        if (this.texture != null) {
            long[] ls = this.texture.cells();
            int j = this.texture.width();
            int k = this.texture.height();

            for (int l = -32; l <= 32; l++) {
                for (int m = -32; m <= 32; m++) {
                    int n = Math.floorMod(x + m, j);
                    int o = Math.floorMod(z + l, k);
                    long p = ls[n + o * j];
                    if (p != 0L) {
                        int q = unpackColor(p);
                        if (fancy) {
                            this.buildCloudCellFancy(viewMode,
                                builder,
                                ARGB.multiply(bottomColor, q),
                                ARGB.multiply(topColor, q),
                                ARGB.multiply(northSouthColor, q),
                                ARGB.multiply(eastWestColor, q),
                                m,
                                l,
                                p);
                        } else {
                            this.buildCloudCellFast(builder, ARGB.multiply(topColor, q), m, l);
                        }
                    }
                }
            }
        }
    }

    @Unique
    private void buildCloudCellFast(VertexConsumer builder, int color, int x, int z) {
        float f = x * 12.0F;
        float g = f + 12.0F;
        float h = z * 12.0F;
        float i = h + 12.0F;

        builder.addVertex(f, 0.0F, h).setNormal(0.0F, 1.0F, 0.0F).setColor(color);
        builder.addVertex(f, 0.0F, i).setNormal(0.0F, 1.0F, 0.0F).setColor(color);
        builder.addVertex(g, 0.0F, i).setNormal(0.0F, 1.0F, 0.0F).setColor(color);
        builder.addVertex(g, 0.0F, h).setNormal(0.0F, 1.0F, 0.0F).setColor(color);
    }

    @Unique
    private void buildCloudCellFancy(int viewMode,
        VertexConsumer builder,
        int bottomColor,
        int topColor,
        int northSouthColor,
        int eastWestColor,
        int x,
        int z,
        long cell) {
        float f = x * 12.0F;
        float g = f + 12.0F;
        float j = z * 12.0F;
        float k = j + 12.0F;

        if (viewMode != RADIANCE_BELOW_CLOUDS) {
            builder.addVertex(f, 4.0F, j).setNormal(0.0F, 1.0F, 0.0F).setColor(topColor);
            builder.addVertex(f, 4.0F, k).setNormal(0.0F, 1.0F, 0.0F).setColor(topColor);
            builder.addVertex(g, 4.0F, k).setNormal(0.0F, 1.0F, 0.0F).setColor(topColor);
            builder.addVertex(g, 4.0F, j).setNormal(0.0F, 1.0F, 0.0F).setColor(topColor);
        }

        if (viewMode != RADIANCE_ABOVE_CLOUDS) {
            builder.addVertex(g, 0.0F, j).setNormal(0.0F, -1.0F, 0.0F).setColor(bottomColor);
            builder.addVertex(g, 0.0F, k).setNormal(0.0F, -1.0F, 0.0F).setColor(bottomColor);
            builder.addVertex(f, 0.0F, k).setNormal(0.0F, -1.0F, 0.0F).setColor(bottomColor);
            builder.addVertex(f, 0.0F, j).setNormal(0.0F, -1.0F, 0.0F).setColor(bottomColor);
        }

        if (hasBorderNorth(cell) && z > 0) {
            builder.addVertex(f, 0.0F, j).setNormal(0.0F, 0.0F, -1.0F).setColor(eastWestColor);
            builder.addVertex(f, 4.0F, j).setNormal(0.0F, 0.0F, -1.0F).setColor(eastWestColor);
            builder.addVertex(g, 4.0F, j).setNormal(0.0F, 0.0F, -1.0F).setColor(eastWestColor);
            builder.addVertex(g, 0.0F, j).setNormal(0.0F, 0.0F, -1.0F).setColor(eastWestColor);
        }

        if (hasBorderSouth(cell) && z < 0) {
            builder.addVertex(g, 0.0F, k).setNormal(0.0F, 0.0F, 1.0F).setColor(eastWestColor);
            builder.addVertex(g, 4.0F, k).setNormal(0.0F, 0.0F, 1.0F).setColor(eastWestColor);
            builder.addVertex(f, 4.0F, k).setNormal(0.0F, 0.0F, 1.0F).setColor(eastWestColor);
            builder.addVertex(f, 0.0F, k).setNormal(0.0F, 0.0F, 1.0F).setColor(eastWestColor);
        }

        if (hasBorderWest(cell) && x > 0) {
            builder.addVertex(f, 0.0F, k).setNormal(-1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(f, 4.0F, k).setNormal(-1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(f, 4.0F, j).setNormal(-1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(f, 0.0F, j).setNormal(-1.0F, 0.0F, 0.0F).setColor(northSouthColor);
        }

        if (hasBorderEast(cell) && x < 0) {
            builder.addVertex(g, 0.0F, j).setNormal(1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(g, 4.0F, j).setNormal(1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(g, 4.0F, k).setNormal(1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(g, 0.0F, k).setNormal(1.0F, 0.0F, 0.0F).setColor(northSouthColor);
        }

        boolean bl = Math.abs(x) <= 1 && Math.abs(z) <= 1;
        if (bl) {
            builder.addVertex(g, 4.0F, j).setNormal(0.0F, 1.0F, 0.0F).setColor(topColor);
            builder.addVertex(g, 4.0F, k).setNormal(0.0F, 1.0F, 0.0F).setColor(topColor);
            builder.addVertex(f, 4.0F, k).setNormal(0.0F, 1.0F, 0.0F).setColor(topColor);
            builder.addVertex(f, 4.0F, j).setNormal(0.0F, 1.0F, 0.0F).setColor(topColor);

            builder.addVertex(f, 0.0F, j).setNormal(0.0F, 1.0F, 0.0F).setColor(bottomColor);
            builder.addVertex(f, 0.0F, k).setNormal(0.0F, 1.0F, 0.0F).setColor(bottomColor);
            builder.addVertex(g, 0.0F, k).setNormal(0.0F, 1.0F, 0.0F).setColor(bottomColor);
            builder.addVertex(g, 0.0F, j).setNormal(0.0F, 1.0F, 0.0F).setColor(bottomColor);

            builder.addVertex(g, 0.0F, j).setNormal(0.0F, 0.0F, 1.0F).setColor(eastWestColor);
            builder.addVertex(g, 4.0F, j).setNormal(0.0F, 0.0F, 1.0F).setColor(eastWestColor);
            builder.addVertex(f, 4.0F, j).setNormal(0.0F, 0.0F, 1.0F).setColor(eastWestColor);
            builder.addVertex(f, 0.0F, j).setNormal(0.0F, 0.0F, 1.0F).setColor(eastWestColor);

            builder.addVertex(f, 0.0F, k).setNormal(0.0F, 0.0F, -1.0F).setColor(eastWestColor);
            builder.addVertex(f, 4.0F, k).setNormal(0.0F, 0.0F, -1.0F).setColor(eastWestColor);
            builder.addVertex(g, 4.0F, k).setNormal(0.0F, 0.0F, -1.0F).setColor(eastWestColor);
            builder.addVertex(g, 0.0F, k).setNormal(0.0F, 0.0F, -1.0F).setColor(eastWestColor);

            builder.addVertex(f, 0.0F, j).setNormal(1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(f, 4.0F, j).setNormal(1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(f, 4.0F, k).setNormal(1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(f, 0.0F, k).setNormal(1.0F, 0.0F, 0.0F).setColor(northSouthColor);

            builder.addVertex(g, 0.0F, k).setNormal(-1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(g, 4.0F, k).setNormal(-1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(g, 4.0F, j).setNormal(-1.0F, 0.0F, 0.0F).setColor(northSouthColor);
            builder.addVertex(g, 0.0F, j).setNormal(-1.0F, 0.0F, 0.0F).setColor(northSouthColor);
        }
    }
}
