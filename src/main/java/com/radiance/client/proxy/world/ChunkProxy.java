package com.radiance.client.proxy.world;

import static org.lwjgl.system.MemoryUtil.memAddress;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.radiance.client.constant.Constants;
import com.radiance.client.option.Options;
import com.radiance.client.proxy.vulkan.BufferProxy;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IAbstractTextureExt;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IChunkBuilderExt;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IViewAreaExt;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher.RenderSection;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.lwjgl.system.MemoryUtil;

public class ChunkProxy {

    // 26.2: ChunkBuilder.ChunkData -> SectionMesh. These sentinels are consulted by
    // WorldRendererMixins; a section that can't see through any face renders nothing on MC's side
    // (the mod renders terrain through its native backend).
    public static final SectionMesh PROCESSED = new SectionMesh() {
        @Override
        public boolean facesCanSeeEachother(Direction direction1, Direction direction2) {
            return false;
        }
    };
    public static final SectionMesh TERRAIN_EMPTY = new SectionMesh() {
        @Override
        public boolean facesCanSeeEachother(Direction direction1, Direction direction2) {
            return false;
        }
    };
    private static final Map<Integer, RenderSection> rebuildQueue = new ConcurrentHashMap<>();
    private static final java.util.Set<Integer> forcedRebuildIndices = ConcurrentHashMap.newKeySet();
    private static final List<Future<?>> rebuildTasks = new ArrayList<>();
    private static ViewArea currentStorage = null;
    private static SectionRenderDispatcher currentDispatcher = null;
    private static volatile boolean initialized = false;
    private static boolean pendingRebuildAll = false;
    // TEMP diagnostic: terrain never builds. rebuildSingle runs on worker threads, so this throttle is atomic.
    private static final java.util.concurrent.atomic.AtomicInteger radianceRebuildBailLog =
        new java.util.concurrent.atomic.AtomicInteger();
    private static int numChunkRebuildThreads = getChunkRebuildThreadCount();
    private static final int numImportantChunkRebuildThreads = 1;
    private static int numNormalChunkRebuildThreads = Math.max(1,
        numChunkRebuildThreads - numImportantChunkRebuildThreads);
    private static final ExecutorService
        importantChunkRebuildExecutor =
        Executors.newFixedThreadPool(numImportantChunkRebuildThreads, r -> {
            Thread thread = new Thread(r);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });
    private static final ThreadLocal<SectionBufferBuilderPack>
        sectionBufferBuilderPackThreadLocal =
        ThreadLocal.withInitial(SectionBufferBuilderPack::new);
    public static int builtChunkNum = 0;
    private static ExecutorService backgroundChunkRebuildExecutor = Executors.newFixedThreadPool(
        numNormalChunkRebuildThreads, r -> {
            Thread thread = new Thread(r);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        });

    public static native void initNative(int numChunks, int sizeX, int sizeY, int sizeZ,
        int bottomSectionCoord);

    public static native void updateSectionPosNative(int sectionX, int sectionY, int sectionZ);

    public static void init(int numChunks, int sizeX, int sizeY, int sizeZ,
        int bottomSectionCoord) {
        clear();
        initNative(numChunks, sizeX, sizeY, sizeZ, bottomSectionCoord);
        initialized = true;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void updateSectionPos(SectionPos sectionPos) {
        updateSectionPosNative(sectionPos.x(), sectionPos.y(), sectionPos.z());
    }

    public static void setStorage(ViewArea storage) {
        currentStorage = storage;
        if (currentStorage != null && pendingRebuildAll) {
            pendingRebuildAll = false;
            queueRebuildAll(currentStorage);
        }
    }

    public static void setDispatcher(SectionRenderDispatcher dispatcher) {
        currentDispatcher = dispatcher;
    }

    private static int getChunkRebuildThreadCount() {
        int expectedBufferTotal = Arrays.stream(ChunkSectionLayer.values())
            .mapToInt(ChunkSectionLayer::bufferSize)
            .sum();
        int memoryLimited = Math.max(1,
            (int) (Runtime.getRuntime().maxMemory() * 0.3) / (expectedBufferTotal * 4) - 1);
        int userThreads = Options.chunkBuildingThreads;
        return Math.max(2,
            Math.min(userThreads, Math.min(Options.getMaxChunkBuildingThreads(), memoryLimited)));
    }

    public static AutoCloseable scopedBlockBufferAllocatorStorage() {
        final SectionBufferBuilderPack s = sectionBufferBuilderPackThreadLocal.get();
        s.clearAll();
        return s::clearAll;
    }

    public static void clear() {
        waitImportantChunkRebuild();

        backgroundChunkRebuildExecutor.shutdown();
        try {
            backgroundChunkRebuildExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.DAYS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        numChunkRebuildThreads = getChunkRebuildThreadCount();
        numNormalChunkRebuildThreads = Math.max(1,
            numChunkRebuildThreads - numImportantChunkRebuildThreads);
        backgroundChunkRebuildExecutor = Executors.newFixedThreadPool(numNormalChunkRebuildThreads,
            r -> {
                Thread thread = new Thread(r);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            });

        rebuildQueue.clear();
        forcedRebuildIndices.clear();
        rebuildTasks.clear();
        currentStorage = null;
        pendingRebuildAll = false;
    }

    public static void enqueueRebuild(RenderSection chunk) {
        rebuildQueue.put(chunk.index, chunk);
    }

    public static void rebuildAll() {
        if (currentStorage == null) {
            pendingRebuildAll = true;
            return;
        }

        queueRebuildAll(currentStorage);
    }

    private static void queueRebuildAll(ViewArea storage) {
        if (storage == null) {
            pendingRebuildAll = true;
            return;
        }

        for (RenderSection renderSection : ((IViewAreaExt) storage).radiance$getSections()) {
            if (renderSection == null) {
                continue;
            }
            forcedRebuildIndices.add(renderSection.index);
            enqueueRebuild(renderSection);
        }
    }

    public static void rebuild(Camera camera) {

        BlockPos blockPos = camera.blockPosition();
        // 26.2: RenderSection dropped needsRebuild/shouldBuild/cancelRebuild/needsImportantRebuild;
        // the dirty state moved into MC's section manager and reaches us via the compileAsync hook,
        // so queue membership already means "needs building" -- we just prioritise by distance.
        for (RenderSection renderSection : rebuildQueue.values()) {
            forcedRebuildIndices.remove(renderSection.index);

            BlockPos chunkCenterPos = renderSection.getRenderOrigin().offset(8, 8, 8);
            boolean isImportant = chunkCenterPos.distSqr(blockPos) < 768.0;

            if (isImportant) {
                Future<?> rebuildTask = importantChunkRebuildExecutor.submit(() -> {
                    rebuildSingle(renderSection, true);
                });
                rebuildTasks.add(rebuildTask);
            } else {
                backgroundChunkRebuildExecutor.execute(() -> {
                    rebuildSingle(renderSection, false);
                });
            }
        }

        rebuildQueue.clear();
    }

    public static void waitImportantChunkRebuild() {
        if (rebuildTasks.isEmpty()) {
            return;
        }

        for (Future<?> rebuildTask : rebuildTasks) {
            try {
                rebuildTask.get();
            } catch (InterruptedException | ExecutionException e) {
                throw new RuntimeException(e);
            }
        }

        rebuildTasks.clear();
    }

    private static void rebuildSingle(RenderSection renderSection, boolean important) {
        try (var scope = scopedBlockBufferAllocatorStorage()) {
            ClientLevel level = Minecraft.getInstance().level;
            SectionCompiler sectionCompiler = currentDispatcher == null ? null
                : ((IChunkBuilderExt) currentDispatcher).radiance$getSectionCompiler();
            if (level == null || sectionCompiler == null) {
                if (radianceRebuildBailLog.getAndIncrement() % 200 == 0) {
                    com.radiance.client.RadianceClient.LOGGER.info("[RebuildBail] level={} dispatcher={} sectionCompiler=null",
                        level != null, currentDispatcher != null);
                }
                invalidateSingle(renderSection.index);
                return;
            }

            RenderSectionRegion
                region =
                new RenderRegionCache().createRegion(level, renderSection.getSectionNode());

            if (region == null) {
                if (radianceRebuildBailLog.getAndIncrement() % 200 == 0) {
                    com.radiance.client.RadianceClient.LOGGER.info("[RebuildBail] region=null (createRegion returned null)");
                }
                invalidateSingle(renderSection.index);
                renderSection.sectionMesh.set(CompiledSectionMesh.UNCOMPILED);
                return;
            }

            SectionBufferBuilderPack storage = sectionBufferBuilderPackThreadLocal.get();
            rebuildSingle(region, sectionCompiler, renderSection, storage, important);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void rebuildSingle(RenderSectionRegion region, SectionCompiler sectionCompiler,
        RenderSection renderSection, SectionBufferBuilderPack storage, boolean important) {

        SectionPos sectionPos = SectionPos.of(renderSection.getSectionNode());

        // The mod sorts translucency in its native backend, so SectionBuilderMixins ignores the
        // VertexSorting passed to compile(); any value works here.
        SectionCompiler.Results
            results =
            sectionCompiler.compile(sectionPos, region, VertexSorting.byDistance(0.0F, 0.0F, 0.0F),
                storage);

        Map<ChunkSectionLayer, MeshData> buffers = results.renderedLayers;

        SectionMesh sectionMesh = new SectionMesh() {
            @Override
            public boolean facesCanSeeEachother(Direction direction1, Direction direction2) {
                return results.visibilitySet.visibilityBetween(direction1, direction2);
            }

            @Override
            public List<BlockEntity> getRenderableBlockEntities() {
                return results.blockEntities;
            }

            @Override
            public boolean isEmpty(ChunkSectionLayer layer) {
                return !buffers.containsKey(layer);
            }
        };
        renderSection.sectionMesh.set(sectionMesh);
        builtChunkNum++;

        if (buffers.isEmpty()) {
            invalidateSingle(renderSection.index);
        } else {
            int atlasGlId = blockAtlasGlId();
            ByteBuffer geometryTypeBB = null;
            ByteBuffer geometryGroupNameBB = null;
            ByteBuffer geometryTextureBB = null;
            ByteBuffer vertexFormatBB = null;
            ByteBuffer vertexCountBB = null;
            ByteBuffer verticesBB = null;
            List<ByteBuffer> geometryGroupNameBuffers = new ArrayList<>(buffers.size());

            try {
                int geometryTypeSize = buffers.size() * Integer.BYTES;
                geometryTypeBB = MemoryUtil.memAlloc(geometryTypeSize);
                long geometryTypeAddr = memAddress(geometryTypeBB);
                int geometryTypeBaseAddr = 0;

                int geometryGroupNameSize = buffers.size() * Long.BYTES;
                geometryGroupNameBB = MemoryUtil.memAlloc(geometryGroupNameSize);
                long geometryGroupNameAddr = memAddress(geometryGroupNameBB);
                int geometryGroupNameBaseAddr = 0;

                int geometryTextureSize = buffers.size() * Integer.BYTES;
                geometryTextureBB = MemoryUtil.memAlloc(geometryTextureSize);
                long geometryTextureAddr = memAddress(geometryTextureBB);
                int geometryTextureBaseAddr = 0;

                int vertexFormatSize = buffers.size() * Integer.BYTES;
                vertexFormatBB = MemoryUtil.memAlloc(vertexFormatSize);
                long vertexFormatAddr = memAddress(vertexFormatBB);
                int vertexFormatBaseAddr = 0;

                int vertexCountSize = buffers.size() * Integer.BYTES;
                vertexCountBB = MemoryUtil.memAlloc(vertexCountSize);
                long vertexCountAddr = memAddress(vertexCountBB);
                int vertexCountBaseAddr = 0;

                int verticesSize = buffers.size() * Long.BYTES;
                verticesBB = MemoryUtil.memAlloc(verticesSize);
                long verticesAddr = memAddress(verticesBB);
                int verticesBaseAddr = 0;

                for (Map.Entry<ChunkSectionLayer, MeshData> entry : buffers.entrySet()) {
                    ChunkSectionLayer layer = entry.getKey();
                    MeshData meshData = entry.getValue();
                    MeshData.DrawState drawState = meshData.drawState();
                    assert drawState.primitiveTopology() == PrimitiveTopology.QUADS;

                    BufferProxy.BufferInfo vertexBufferInfo = BufferProxy.getBufferInfo(
                        meshData.vertexBuffer());
                    assert drawState.indexCount() == drawState.vertexCount() / 4 * 6;

                    int
                        geometryTypeID =
                        Constants.GeometryTypes.getGeometryType(layer.label(), true)
                            .getValue();
                    int vertexFormatID = Constants.VertexFormats.getValue(drawState.format());

                    geometryTypeBB.putInt(geometryTypeBaseAddr, geometryTypeID);
                    geometryTypeBaseAddr += Integer.BYTES;

                    ByteBuffer geometryGroupNameBuffer = MemoryUtil.memUTF8(layer.label(), true);
                    geometryGroupNameBuffers.add(geometryGroupNameBuffer);
                    geometryGroupNameBB.putLong(geometryGroupNameBaseAddr,
                        memAddress(geometryGroupNameBuffer));
                    geometryGroupNameBaseAddr += Long.BYTES;

                    geometryTextureBB.putInt(geometryTextureBaseAddr, atlasGlId);
                    geometryTextureBaseAddr += Integer.BYTES;

                    vertexFormatBB.putInt(vertexFormatBaseAddr, vertexFormatID);
                    vertexFormatBaseAddr += Integer.BYTES;

                    vertexCountBB.putInt(vertexCountBaseAddr, drawState.vertexCount());
                    vertexCountBaseAddr += Integer.BYTES;

                    verticesBB.putLong(verticesBaseAddr, vertexBufferInfo.addr());
                    verticesBaseAddr += Long.BYTES;
                }

                BlockPos origin = renderSection.getRenderOrigin();
                rebuildSingle(origin.getX(),
                    origin.getY(),
                    origin.getZ(),
                    renderSection.index,
                    buffers.size(),
                    geometryTypeAddr,
                    geometryGroupNameAddr,
                    geometryTextureAddr,
                    vertexFormatAddr,
                    vertexCountAddr,
                    verticesAddr,
                    important);
            } finally {
                if (geometryTypeBB != null) {
                    MemoryUtil.memFree(geometryTypeBB);
                }
                if (geometryGroupNameBB != null) {
                    MemoryUtil.memFree(geometryGroupNameBB);
                }
                if (geometryTextureBB != null) {
                    MemoryUtil.memFree(geometryTextureBB);
                }
                if (vertexFormatBB != null) {
                    MemoryUtil.memFree(vertexFormatBB);
                }
                if (vertexCountBB != null) {
                    MemoryUtil.memFree(vertexCountBB);
                }
                if (verticesBB != null) {
                    MemoryUtil.memFree(verticesBB);
                }
                for (ByteBuffer geometryGroupNameBuffer : geometryGroupNameBuffers) {
                    MemoryUtil.memFree(geometryGroupNameBuffer);
                }
            }
        }

        for (Map.Entry<ChunkSectionLayer, MeshData> entry : buffers.entrySet()) {
            entry.getValue()
                .close();
        }
    }

    private static int blockAtlasGlId() {
        return ((IAbstractTextureExt) Minecraft.getInstance()
            .getTextureManager()
            .getTexture(TextureAtlas.LOCATION_BLOCKS)).radiance$getGlIDUnsafe();
    }

    private static native void rebuildSingle(int originX,
        int originY,
        int originZ,
        long index,
        int size,
        long geometryTypes,
        long geometryGroupNames,
        long geometryTextures,
        long vertexFormats,
        long vertexCounts,
        long vertices,
        boolean important);

    public static native boolean isChunkReady(long index);

    public static boolean isChunkReady(RenderSection renderSection) {
        return isChunkReady(renderSection.index);
    }

    public static native void relocateSingle(long index, int originX, int originY, int originZ);

    public static native void invalidateSingle(long index);
}
