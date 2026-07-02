package com.radiance.client.proxy.world;

import static org.lwjgl.system.MemoryUtil.memAddress;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.radiance.client.constant.Constants;
import com.radiance.client.constant.Constants.PostRenderFlags;
import com.radiance.client.constant.Constants.RayTracingFlags;
import com.radiance.client.proxy.vulkan.BufferProxy;
import com.radiance.client.vertex.FeatureGeometryCapture;
import com.radiance.client.vertex.PBRVertexConsumer;
import com.radiance.client.vertex.StorageVertexConsumerProvider;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IRenderTypeExt;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.FishingHook;
import org.lwjgl.system.MemoryUtil;

/**
 * 26.2 world-object capture orchestrator. This is the CORE of the entity crux: it drives the mod's
 * own per-entity submit-node drain pass and captures the resulting geometry for the Vulkan pipeline.
 *
 * <p>Rather than the removed per-renderer {@code VertexConsumerProvider} swap, each entity is
 * extracted ({@link EntityRenderDispatcher#extractEntity}) and submitted
 * ({@link EntityRenderDispatcher#submit}) into a {@link SubmitNodeStorage}, then drained by the game's
 * {@link FeatureRenderDispatcher}. While a per-entity {@link StorageVertexConsumerProvider} is
 * {@link FeatureGeometryCapture} active, the {@code RenderTypeFeatureRenderer.getVertexBuilder} hook
 * routes the model geometry into it; the captured layers are then packed into native buffers
 * ({@link #queueBuildInternal}) exactly as before.
 *
 * <p>26.2 migration note: the peripheral capture paths (block entities, crumbling overlays,
 * first-person hand, particles, block outline, weather/world-border) were removed here because each
 * uses a distinct heavily-changed 26.2 API and is invoked from render mixins that are themselves
 * still being migrated. They will be reintroduced alongside their caller mixins.
 */
public class EntityProxy {

    public static final ConcurrentMap<Class<? extends Particle>, AtomicInteger> PARTICLE_COUNTERS = new ConcurrentHashMap<>();

    private static final String WEATHER_DEFAULT_CONTENT = "/weather/default";
    private static final String PARTICLE_DEFAULT_CONTENT = "/particle/default";
    private static final String TEXT_DEFAULT_CONTENT = "/text/default";
    private static final String NAME_TAG_DEFAULT_CONTENT = "/name_tag/default";
    private static final java.util.Set<String> LOGGED_POST_CONTENT_KEYS = ConcurrentHashMap.newKeySet();

    public static void processWorldEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        int hashCode,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        Constants.RayTracingFlags rayTracingFlag,
        boolean reflect,
        EntityRenderDataList entityRenderDataList) {
        processEntityRenderData(storageVertexConsumerProvider,
            hashCode,
            entityPosX,
            entityPosY,
            entityPosZ,
            rayTracingFlag.getValue(),
            0,
            -1,
            reflect,
            null,
            false,
            entityRenderDataList);
    }

    public static void processPostEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        int hashCode,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        Constants.PostRenderFlags postRenderFlag,
        EntityRenderDataList entityRenderDataList) {
        processEntityRenderData(storageVertexConsumerProvider,
            hashCode,
            entityPosX,
            entityPosY,
            entityPosZ,
            0,
            postRenderFlag.getValue(),
            -1,
            false,
            renderType -> defaultPostContentName(postRenderFlag),
            true,
            entityRenderDataList);
    }

    public static void processPostEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        int hashCode,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        Constants.PostRenderFlags postRenderFlag,
        String contentName,
        EntityRenderDataList entityRenderDataList) {
        processEntityRenderData(storageVertexConsumerProvider,
            hashCode,
            entityPosX,
            entityPosY,
            entityPosZ,
            0,
            postRenderFlag.getValue(),
            -1,
            false,
            renderType -> contentName,
            true,
            entityRenderDataList);
    }

    public static void processPostEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        int hashCode,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        Constants.PostRenderFlags postRenderFlag,
        Function<RenderType, String> contentNameResolver,
        EntityRenderDataList entityRenderDataList) {
        processEntityRenderData(storageVertexConsumerProvider,
            hashCode,
            entityPosX,
            entityPosY,
            entityPosZ,
            0,
            postRenderFlag.getValue(),
            -1,
            false,
            contentNameResolver,
            true,
            entityRenderDataList);
    }

    private static void processEntityRenderData(
        StorageVertexConsumerProvider storageVertexConsumerProvider,
        int hashCode,
        double entityPosX,
        double entityPosY,
        double entityPosZ,
        int rayTracingFlag,
        int postRenderFlag,
        int prebuiltBLAS,
        boolean reflect,
        Function<RenderType, String> contentNameResolver,
        boolean post,
        EntityRenderDataList entityRenderDataList) {
        Map<RenderType, VertexConsumer> layerBuffers = storageVertexConsumerProvider.getLayers();
        EntityRenderData entityRenderData = new EntityRenderData(hashCode, entityPosX, entityPosY,
            entityPosZ, rayTracingFlag, postRenderFlag, prebuiltBLAS, post);
        EntityRenderData waterMaskRenderData = new EntityRenderData(hashCode, entityPosX, entityPosY,
            entityPosZ, RayTracingFlags.BOAT_WATER_MASK.getValue(), 0, prebuiltBLAS, post);
        for (Map.Entry<RenderType, VertexConsumer> layerBuffer : layerBuffers.entrySet()) {
            RenderType layer = layerBuffer.getKey();
            MeshData buffer = null;

            VertexConsumer vertexConsumer = layerBuffer.getValue();
            if (vertexConsumer instanceof BufferBuilder bufferBuilder) {
                buffer = bufferBuilder.build();
            } else if (vertexConsumer instanceof PBRVertexConsumer pbrVertexConsumer) {
                buffer = pbrVertexConsumer.endNullable();
            }

            PrimitiveTopology mode = layer.primitiveTopology();
            if (mode != PrimitiveTopology.QUADS && mode != PrimitiveTopology.TRIANGLE_STRIP
                && mode != PrimitiveTopology.DEBUG_LINE_STRIP && mode != PrimitiveTopology.LINES) {
                continue;
            }
            if (buffer == null) {
                continue;
            }

            String name = ((IRenderTypeExt) layer).radiance$getName();
            String contentName = contentNameResolver == null
                ? ""
                : Objects.requireNonNullElse(contentNameResolver.apply(layer), "");
            if (name.contains("water_mask")) {
                waterMaskRenderData.add(new EntityRenderLayer(layer, buffer, reflect, contentName));
            } else {
                entityRenderData.add(new EntityRenderLayer(layer, buffer, reflect, contentName));
            }
        }

        if (!entityRenderData.isEmpty()) {
            entityRenderDataList.add(entityRenderData);
        }
        if (!waterMaskRenderData.isEmpty()) {
            entityRenderDataList.add(waterMaskRenderData);
        }
    }

    /**
     * 26.2: drives the mod's own per-entity submit-node drain. For each entity: extract its render
     * state, submit into a fresh {@link SubmitNodeStorage}, then drain via the game's
     * {@link FeatureRenderDispatcher} with a capture store active so the geometry lands in the mod's
     * {@link StorageVertexConsumerProvider} (via the {@code RenderTypeFeatureRenderer} hook).
     */
    public static void queueEntitiesBuild(Camera camera,
        List<Entity> renderedEntities,
        EntityRenderDispatcher entityRenderDispatcher,
        DeltaTracker tickCounter,
        boolean canDrawEntityOutlines) {
        PoseStack poseStack = new PoseStack();
        Minecraft client = Minecraft.getInstance();
        var tickManager = Objects.requireNonNull(client.level).tickRateManager();
        CameraRenderState cameraRenderState =
            client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        FeatureRenderDispatcher featureRenderDispatcher = client.gameRenderer.featureRenderDispatcher();

        List<StorageVertexConsumerProvider> entityStorageVertexConsumerProviders = new ArrayList<>();
        EntityRenderDataList entityRenderDataList = new EntityRenderDataList();
        for (Entity entity : renderedEntities) {

            if (entity.tickCount == 0) {
                entity.xOld = entity.getX();
                entity.yOld = entity.getY();
                entity.zOld = entity.getZ();
            }

            float tickDelta = tickCounter.getGameTimeDeltaPartialTick(
                !tickManager.isEntityFrozen(entity));
            double entityPosX = Mth.lerp(tickDelta, entity.xOld, entity.getX());
            double entityPosY = Mth.lerp(tickDelta, entity.yOld, entity.getY());
            double entityPosZ = Mth.lerp(tickDelta, entity.zOld, entity.getZ());

            StorageVertexConsumerProvider entityStorageVertexConsumerProvider =
                new StorageVertexConsumerProvider(786432);
            entityStorageVertexConsumerProviders.add(entityStorageVertexConsumerProvider);

            EntityRenderState renderState = entityRenderDispatcher.extractEntity(entity, tickDelta);
            SubmitNodeStorage submitNodeStorage = new SubmitNodeStorage();
            entityRenderDispatcher.submit(renderState, cameraRenderState, 0, 0, 0, poseStack,
                submitNodeStorage);

            FeatureGeometryCapture.begin(entityStorageVertexConsumerProvider);
            try {
                featureRenderDispatcher.renderAllFeatures(submitNodeStorage);
            } finally {
                FeatureGeometryCapture.end();
            }

            Constants.RayTracingFlags flag;
            if (entity.equals(camera.entity())) {
                flag = Constants.RayTracingFlags.PLAYER;
            } else if (entity instanceof FishingHook) {
                flag = Constants.RayTracingFlags.FISHING_BOBBER;
            } else {
                flag = Constants.RayTracingFlags.WORLD;
            }
            processWorldEntityRenderData(entityStorageVertexConsumerProvider,
                System.identityHashCode(entity), entityPosX, entityPosY, entityPosZ, flag, true,
                entityRenderDataList);
        }

        queueBuild(entityStorageVertexConsumerProviders, entityRenderDataList);
    }

    public static void queueBuild(
        List<StorageVertexConsumerProvider> storageVertexConsumerProviders,
        EntityRenderDataList entityRenderDataList) {
        queueBuild(storageVertexConsumerProviders, entityRenderDataList, 0.0125f,
            Constants.Coordinates.WORLD, false);
    }

    public static void queueBuild(
        List<StorageVertexConsumerProvider> storageVertexConsumerProviders,
        EntityRenderDataList entityRenderDataList,
        float lineWidth,
        Constants.Coordinates coordinate,
        boolean normalOffset) {
        queueBuildInternal(storageVertexConsumerProviders, entityRenderDataList, lineWidth,
            coordinate, normalOffset, true);
    }

    public static void queueBuildWithoutClose(EntityRenderDataList entityRenderDataList) {
        queueBuildWithoutClose(entityRenderDataList, 0.0125f, Constants.Coordinates.WORLD, false);
    }

    public static void queueBuildWithoutClose(EntityRenderDataList entityRenderDataList,
        float lineWidth,
        Constants.Coordinates coordinate,
        boolean normalOffset) {
        queueBuildInternal(null, entityRenderDataList, lineWidth, coordinate, normalOffset, false);
    }

    private static void queueBuildInternal(
        List<StorageVertexConsumerProvider> storageVertexConsumerProviders,
        EntityRenderDataList entityRenderDataList,
        float lineWidth,
        Constants.Coordinates coordinate,
        boolean normalOffset,
        boolean closeAfterBuild) {
        List<ByteBuffer> geometryGroupNameBuffers = new ArrayList<>(
            entityRenderDataList.getTotalLayersCount());
        List<ByteBuffer> geometryContentNameBuffers = new ArrayList<>(
            entityRenderDataList.getTotalLayersCount());
        ByteBuffer entityHashCodeBB = null;
        ByteBuffer entityPosXBB = null;
        ByteBuffer entityPosYBB = null;
        ByteBuffer entityPosZBB = null;
        ByteBuffer entityRayTracingFlagBB = null;
        ByteBuffer entityPostRenderFlagBB = null;
        ByteBuffer entityPrebuiltBLASBB = null;
        ByteBuffer entityPostBB = null;
        ByteBuffer entityLayerCountBB = null;
        ByteBuffer geometryTypeBB = null;
        ByteBuffer geometryGroupNameBB = null;
        ByteBuffer geometryContentNameBB = null;
        ByteBuffer geometryTextureBB = null;
        ByteBuffer vertexFormatBB = null;
        ByteBuffer indexFormatBB = null;
        ByteBuffer vertexCountBB = null;
        ByteBuffer verticesBB = null;

        try {
            entityHashCodeBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalEntityCount() * Integer.BYTES);
            long entityHashCodeAddr = memAddress(entityHashCodeBB);
            int entityHashCodeBaseAddr = 0;

            entityPosXBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalEntityCount() * Double.BYTES);
            long entityPosXAddr = memAddress(entityPosXBB);
            int entityPosXBaseAddr = 0;

            entityPosYBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalEntityCount() * Double.BYTES);
            long entityPosYAddr = memAddress(entityPosYBB);
            int entityPosYBaseAddr = 0;

            entityPosZBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalEntityCount() * Double.BYTES);
            long entityPosZAddr = memAddress(entityPosZBB);
            int entityPosZBaseAddr = 0;

            entityRayTracingFlagBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalEntityCount() * Integer.BYTES);
            long entityRayTracingFlagAddr = memAddress(entityRayTracingFlagBB);
            int entityRayTracingFlagBaseAddr = 0;

            entityPostRenderFlagBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalEntityCount() * Integer.BYTES);
            long entityPostRenderFlagAddr = memAddress(entityPostRenderFlagBB);
            int entityPostRenderFlagBaseAddr = 0;

            entityPrebuiltBLASBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalEntityCount() * Integer.BYTES);
            long entityPrebuiltBLASAddr = memAddress(entityPrebuiltBLASBB);
            int entityPrebuiltBLASBaseAddr = 0;

            entityPostBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalEntityCount() * Integer.BYTES);
            long entityPostAddr = memAddress(entityPostBB);
            int entityPostBaseAddr = 0;

            entityLayerCountBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalEntityCount() * Integer.BYTES);
            long entityLayerCountAddr = memAddress(entityLayerCountBB);
            int entityLayerCountBaseAddr = 0;

            geometryTypeBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalLayersCount() * Integer.BYTES);
            long geometryTypeAddr = memAddress(geometryTypeBB);
            int geometryTypeBaseAddr = 0;

            geometryGroupNameBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalLayersCount() * Long.BYTES);
            long geometryGroupNameAddr = memAddress(geometryGroupNameBB);
            int geometryGroupNameBaseAddr = 0;

            geometryContentNameBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalLayersCount() * Long.BYTES);
            long geometryContentNameAddr = memAddress(geometryContentNameBB);
            int geometryContentNameBaseAddr = 0;

            geometryTextureBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalLayersCount() * Integer.BYTES);
            long geometryTextureAddr = memAddress(geometryTextureBB);
            int geometryTextureBaseAddr = 0;

            vertexFormatBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalLayersCount() * Integer.BYTES);
            long vertexFormatAddr = memAddress(vertexFormatBB);
            int vertexFormatBaseAddr = 0;

            indexFormatBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalLayersCount() * Integer.BYTES);
            long indexFormatAddr = memAddress(indexFormatBB);
            int indexFormatBaseAddr = 0;

            vertexCountBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalLayersCount() * Integer.BYTES);
            long vertexCountAddr = memAddress(vertexCountBB);
            int vertexCountBaseAddr = 0;

            verticesBB = MemoryUtil.memAlloc(
                entityRenderDataList.getTotalLayersCount() * Long.BYTES);
            long verticesAddr = memAddress(verticesBB);
            int verticesBaseAddr = 0;

            for (EntityRenderData entityRenderData : entityRenderDataList) {
                entityHashCodeBB.putInt(entityHashCodeBaseAddr, entityRenderData.hashCode);
                entityHashCodeBaseAddr += Integer.BYTES;

                entityPosXBB.putDouble(entityPosXBaseAddr, entityRenderData.x);
                entityPosXBaseAddr += Double.BYTES;

                entityPosYBB.putDouble(entityPosYBaseAddr, entityRenderData.y);
                entityPosYBaseAddr += Double.BYTES;

                entityPosZBB.putDouble(entityPosZBaseAddr, entityRenderData.z);
                entityPosZBaseAddr += Double.BYTES;

                entityRayTracingFlagBB.putInt(entityRayTracingFlagBaseAddr,
                    entityRenderData.rayTracingFlag);
                entityRayTracingFlagBaseAddr += Integer.BYTES;

                entityPostRenderFlagBB.putInt(entityPostRenderFlagBaseAddr,
                    entityRenderData.postRenderFlag);
                entityPostRenderFlagBaseAddr += Integer.BYTES;

                entityPrebuiltBLASBB.putInt(entityPrebuiltBLASBaseAddr,
                    entityRenderData.prebuiltBLAS);
                entityPrebuiltBLASBaseAddr += Integer.BYTES;

                entityPostBB.putInt(entityPostBaseAddr, entityRenderData.post ? 1 : 0);
                entityPostBaseAddr += Integer.BYTES;

                entityLayerCountBB.putInt(entityLayerCountBaseAddr, entityRenderData.size());
                entityLayerCountBaseAddr += Integer.BYTES;

                for (EntityRenderLayer entityRenderLayer : entityRenderData) {
                    if (entityRenderData.postRenderFlag != 0) {
                        logPostContentNameOnce(entityRenderData.postRenderFlag,
                            entityRenderLayer.contentName, entityRenderLayer.renderLayer);
                    }

                    RenderType renderLayer = entityRenderLayer.renderLayer;
                    MeshData vertexBuffer = entityRenderLayer.builtBuffer;
                    String name = ((IRenderTypeExt) renderLayer).radiance$getName();

                    int geometryTypeID = Constants.GeometryTypes.getGeometryType(name,
                        entityRenderLayer.reflect).getValue();
                    int geometryTextureID = resolveTextureGlId(renderLayer);
                    int vertexFormatID = Constants.VertexFormats.getValue(
                        vertexBuffer.drawState().format());
                    int indexFormatID = Constants.DrawModes.getValue(
                        vertexBuffer.drawState().primitiveTopology());

                    BufferProxy.BufferInfo vertexBufferInfo = BufferProxy.getBufferInfo(
                        vertexBuffer.vertexBuffer());
                    assert vertexBuffer.drawState().indexCount()
                        == vertexBuffer.drawState().vertexCount() / 4 * 6;

                    geometryTypeBB.putInt(geometryTypeBaseAddr, geometryTypeID);
                    geometryTypeBaseAddr += Integer.BYTES;

                    ByteBuffer geometryGroupNameBuffer = MemoryUtil.memUTF8(name, true);
                    geometryGroupNameBuffers.add(geometryGroupNameBuffer);
                    geometryGroupNameBB.putLong(geometryGroupNameBaseAddr,
                        memAddress(geometryGroupNameBuffer));
                    geometryGroupNameBaseAddr += Long.BYTES;

                    ByteBuffer geometryContentNameBuffer = MemoryUtil.memUTF8(
                        entityRenderLayer.contentName(), true);
                    geometryContentNameBuffers.add(geometryContentNameBuffer);
                    geometryContentNameBB.putLong(geometryContentNameBaseAddr,
                        memAddress(geometryContentNameBuffer));
                    geometryContentNameBaseAddr += Long.BYTES;

                    geometryTextureBB.putInt(geometryTextureBaseAddr, geometryTextureID);
                    geometryTextureBaseAddr += Integer.BYTES;

                    vertexFormatBB.putInt(vertexFormatBaseAddr, vertexFormatID);
                    vertexFormatBaseAddr += Integer.BYTES;

                    indexFormatBB.putInt(indexFormatBaseAddr, indexFormatID);
                    indexFormatBaseAddr += Integer.BYTES;

                    vertexCountBB.putInt(vertexCountBaseAddr,
                        vertexBuffer.drawState().vertexCount());
                    vertexCountBaseAddr += Integer.BYTES;

                    verticesBB.putLong(verticesBaseAddr, vertexBufferInfo.addr());
                    verticesBaseAddr += Long.BYTES;
                }
            }

            queueBuild(lineWidth,
                coordinate.getValue(),
                normalOffset,
                entityRenderDataList.getTotalEntityCount(),
                entityHashCodeAddr,
                entityPosXAddr,
                entityPosYAddr,
                entityPosZAddr,
                entityRayTracingFlagAddr,
                entityPostRenderFlagAddr,
                entityPrebuiltBLASAddr,
                entityPostAddr,
                entityLayerCountAddr,
                geometryTypeAddr,
                geometryGroupNameAddr,
                geometryContentNameAddr,
                geometryTextureAddr,
                vertexFormatAddr,
                indexFormatAddr,
                vertexCountAddr,
                verticesAddr);
        } finally {
            freeDirectBuffer(entityHashCodeBB);
            freeDirectBuffer(entityPosXBB);
            freeDirectBuffer(entityPosYBB);
            freeDirectBuffer(entityPosZBB);
            freeDirectBuffer(entityRayTracingFlagBB);
            freeDirectBuffer(entityPostRenderFlagBB);
            freeDirectBuffer(entityPrebuiltBLASBB);
            freeDirectBuffer(entityPostBB);
            freeDirectBuffer(entityLayerCountBB);
            freeDirectBuffer(geometryTypeBB);
            freeDirectBuffer(geometryGroupNameBB);
            freeDirectBuffer(geometryContentNameBB);
            freeDirectBuffer(geometryTextureBB);
            freeDirectBuffer(vertexFormatBB);
            freeDirectBuffer(indexFormatBB);
            freeDirectBuffer(vertexCountBB);
            freeDirectBuffer(verticesBB);
            for (ByteBuffer geometryGroupNameBuffer : geometryGroupNameBuffers) {
                MemoryUtil.memFree(geometryGroupNameBuffer);
            }
            for (ByteBuffer geometryContentNameBuffer : geometryContentNameBuffers) {
                MemoryUtil.memFree(geometryContentNameBuffer);
            }

            if (closeAfterBuild) {
                closeBuiltBuffers(entityRenderDataList);
                closeStorageVertexConsumerProviders(storageVertexConsumerProviders);
            }
        }
    }

    private static void freeDirectBuffer(ByteBuffer buffer) {
        if (buffer != null) {
            MemoryUtil.memFree(buffer);
        }
    }

    private static void closeBuiltBuffers(EntityRenderDataList entityRenderDataList) {
        for (EntityRenderData entityRenderData : entityRenderDataList) {
            for (EntityRenderLayer entityRenderLayer : entityRenderData) {
                entityRenderLayer.builtBuffer.close();
            }
        }
    }

    private static void closeStorageVertexConsumerProviders(
        List<StorageVertexConsumerProvider> storageVertexConsumerProviders) {
        if (storageVertexConsumerProviders == null) {
            return;
        }

        for (StorageVertexConsumerProvider storageVertexConsumerProvider : storageVertexConsumerProviders) {
            storageVertexConsumerProvider.close();
        }
    }

    /**
     * 26.2: RenderPhase is gone, so resolve the layer's GL texture id from {@link PreparedRenderType}
     * (first GL-backed sampler), mirroring {@code StorageVertexConsumerProvider}. Runtime-validate
     * that {@code prepare()} is safe off-frame (it reads RenderSystem state).
     */
    private static int resolveTextureGlId(RenderType renderType) {
        PreparedRenderType prepared = renderType.prepare();
        for (PreparedRenderType.Texture texture : prepared.textures()) {
            if (texture.textureView() != null
                && texture.textureView().texture() instanceof GlTexture glTexture) {
                return glTexture.glId();
            }
        }
        return 0;
    }

    private static native void queueBuild(float lineWidth,
        int coordinate,
        boolean normalOffset,
        int size,
        long entityHashCodes,
        long entityPosXs,
        long entityPosYs,
        long entityPosZs,
        long entityRayTracingFlags,
        long entityPostRenderFlags,
        long entityPrebuiltBLASs,
        long entityPosts,
        long entityLayerCounts,
        long geometryTypes,
        long geometryGroupNames,
        long geometryContentNames,
        long geometryTextures,
        long vertexFormats,
        long indexFormats,
        long vertexCounts,
        long vertices);

    public static native void build();

    private static String defaultPostContentName(Constants.PostRenderFlags postRenderFlag) {
        return switch (postRenderFlag) {
            case WEATHER -> WEATHER_DEFAULT_CONTENT;
            case PARTICLE -> PARTICLE_DEFAULT_CONTENT;
            case TEXT -> TEXT_DEFAULT_CONTENT;
            case NAME_TAG -> NAME_TAG_DEFAULT_CONTENT;
        };
    }

    private static void logPostContentNameOnce(int postRenderFlag, String contentName,
        RenderType renderLayer) {
        String normalizedContentName = Objects.requireNonNullElse(contentName, "");
        String postRenderFlagName = postRenderFlagName(postRenderFlag);
        String key = postRenderFlagName + "|" + normalizedContentName;
        LOGGED_POST_CONTENT_KEYS.add(key);
    }

    private static String postRenderFlagName(int postRenderFlag) {
        if (postRenderFlag == PostRenderFlags.WEATHER.getValue()) {
            return "WEATHER";
        }
        if (postRenderFlag == PostRenderFlags.PARTICLE.getValue()) {
            return "PARTICLE";
        }
        if (postRenderFlag == PostRenderFlags.TEXT.getValue()) {
            return "TEXT";
        }
        if (postRenderFlag == PostRenderFlags.NAME_TAG.getValue()) {
            return "NAME_TAG";
        }
        return "UNKNOWN(" + postRenderFlag + ")";
    }

    public record EntityRenderLayer(RenderType renderLayer, MeshData builtBuffer,
                                    boolean reflect, String contentName) {

    }

    public static class EntityRenderData extends ArrayList<EntityRenderLayer> {

        private final int hashCode;
        private final int rayTracingFlag;
        private final int postRenderFlag;
        private final int prebuiltBLAS;
        private final boolean post;
        private double x;
        private double y;
        private double z;

        public EntityRenderData(int hashCode, double x, double y, double z, int rayTracingFlag,
            int postRenderFlag,
            int prebuiltBLAS,
            boolean post) {
            this.hashCode = hashCode;
            this.x = x;
            this.y = y;
            this.z = z;
            this.rayTracingFlag = rayTracingFlag;
            this.postRenderFlag = postRenderFlag;
            this.prebuiltBLAS = prebuiltBLAS;
            this.post = post;
        }

        public double getX() {
            return x;
        }

        public void setX(double x) {
            this.x = x;
        }

        public double getY() {
            return y;
        }

        public void setY(double y) {
            this.y = y;
        }

        public double getZ() {
            return z;
        }

        public void setZ(double z) {
            this.z = z;
        }

        public int getRayTracingFlag() {
            return rayTracingFlag;
        }

        public int getPostRenderFlag() {
            return postRenderFlag;
        }

        public int getPrebuiltBLAS() {
            return prebuiltBLAS;
        }

        public int getHashCode() {
            return hashCode;
        }

        public boolean isPost() {
            return post;
        }
    }

    public static class EntityRenderDataList extends ArrayList<EntityRenderData> {

        private int totalLayersCount;

        @Override
        public boolean add(EntityRenderData entityRenderData) {
            totalLayersCount += entityRenderData.size();
            return super.add(entityRenderData);
        }

        public int getTotalLayersCount() {
            return totalLayersCount;
        }

        public int getTotalEntityCount() {
            return this.size();
        }
    }
}
