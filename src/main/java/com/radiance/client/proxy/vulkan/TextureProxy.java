package com.radiance.client.proxy.vulkan;

import static org.lwjgl.system.MemoryUtil.memAddress;

import com.mojang.blaze3d.GpuFormat;
import com.radiance.client.constant.VulkanConstants;
import com.radiance.client.option.Options;
import com.radiance.client.texture.EmissionRecorder;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.lwjgl.system.MemoryUtil;

public class TextureProxy {

    private record EmissionTileKey(int textureId, long tileKey) {
    }

    private static final Map<EmissionTileKey, EmissionRecorder.TileUpdate> emissionTileCache =
        new ConcurrentHashMap<>();

    public synchronized static native int generateTextureId();

    public synchronized static native void prepareImage(int id, int mipLevels, int width,
        int height, int format);

    // Render-target color textures (e.g. GuiItemAtlas) need the backend image created with
    // COLOR_ATTACHMENT usage (in addition to SAMPLED) so the draw-replay path can render INTO them,
    // while the GUI still samples them by GL id like any 2D texture. Single mip, no mipmap sampler.
    public synchronized static native void prepareRenderTargetImage(int id, int width, int height,
        int format);

    // Cube textures (the panorama) are imported and uploaded separately from 2D textures: they need a
    // 6-layer cube image and a samplerCube bindless slot, neither of which the 2D path provides.
    public synchronized static native void prepareCubeImage(int id, int maxLevel, int faceWidth,
        int faceHeight, int format);

    public synchronized static native void uploadCube(int id, long srcPointer);

    public static void prepareImage(int id, int mipLevels, int width, int height,
        VulkanConstants.VkFormat format) {
        clearEmissionTiles(id);
        prepareImage(id, mipLevels, width, height, format.getValue());
    }

    public synchronized static native void setFilter(int id, int samplingMode, int mipmapMode);

    public synchronized static native void setClamp(int id, int addressMode);

    public synchronized static native void queueUpload(long srcPointer,
        int srcSizeInBytes,
        int srcRowPixels,
        int dstId,
        int srcOffsetX,
        int srcOffsetY,
        int dstOffsetX,
        int dstOffsetY,
        int width,
        int height,
        int level);

    private synchronized static native void uploadEmissionTileNative(int textureId, long tileKey,
        long cellsPtr, int cellCount);

    public static void uploadEmissionTile(EmissionRecorder.TileUpdate tileUpdate) {
        if (tileUpdate == null) {
            return;
        }

        emissionTileCache.put(new EmissionTileKey(tileUpdate.textureId, tileUpdate.tileKey),
            tileUpdate);
        if (!Options.collectChunkEmission) {
            return;
        }

        uploadEmissionTileToNative(tileUpdate);
    }

    public static void flushEmissionTiles() {
        if (!Options.collectChunkEmission) {
            return;
        }

        for (EmissionRecorder.TileUpdate tileUpdate : emissionTileCache.values()) {
            uploadEmissionTileToNative(tileUpdate);
        }
    }

    public static boolean hasEmissionTile(int textureId, long tileKey) {
        return emissionTileCache.containsKey(new EmissionTileKey(textureId, tileKey));
    }

    private static void clearEmissionTiles(int textureId) {
        emissionTileCache.keySet().removeIf(key -> key.textureId == textureId);
    }

    private static void uploadEmissionTileToNative(EmissionRecorder.TileUpdate tileUpdate) {
        if (tileUpdate == null) {
            return;
        }

        ByteBuffer cellsBuffer = null;
        try {
            int cellCount = tileUpdate.cells.size();
            long cellsAddr = 0L;
            if (cellCount > 0) {
                cellsBuffer = MemoryUtil.memAlloc(cellCount * 8 * Float.BYTES);
                int base = 0;
                for (EmissionRecorder.EmissionCell cell : tileUpdate.cells) {
                    cellsBuffer.putFloat(base, cell.u0);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.v0);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.u1);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.v1);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.avgEmission);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.avgR);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.avgG);
                    base += Float.BYTES;
                    cellsBuffer.putFloat(base, cell.avgB);
                    base += Float.BYTES;
                }
                cellsAddr = memAddress(cellsBuffer);
            }

            uploadEmissionTileNative(tileUpdate.textureId, tileUpdate.tileKey, cellsAddr, cellCount);
        } finally {
            if (cellsBuffer != null) {
                MemoryUtil.memFree(cellsBuffer);
            }
        }
    }

    // 26.2: NativeImage.InternalFormat was removed; texture formats are now
    // com.mojang.blaze3d.GpuFormat. Imports a GL texture's storage into the Vulkan
    // backend for the formats the backend tracks (others are ignored).
    public static void prepareImage(GpuFormat format, int id, int mipLevels, int width,
        int height) {
        VulkanConstants.VkFormat vkFormat = VulkanConstants.VkFormat.fromGpuFormat(format);
        if (vkFormat != null) {
            prepareImage(id, mipLevels, width, height, vkFormat);
        }
    }

    public static void prepareCubeImage(GpuFormat format, int id, int mipLevels, int faceWidth,
        int faceHeight) {
        VulkanConstants.VkFormat vkFormat = VulkanConstants.VkFormat.fromGpuFormat(format);
        if (vkFormat != null) {
            prepareCubeImage(id, mipLevels, faceWidth, faceHeight, vkFormat.getValue());
        }
    }

    // Import a render-target COLOR texture (color-attachment + sampled). The depth attachment of a
    // render target is not imported: it is a depth format the 2D backend does not sample, and the
    // RTT path allocates its own matching depth image natively. Registers the GL id so the draw path
    // can recognise it as an off-screen render target.
    public static void prepareRenderTargetImage(GpuFormat format, int id, int width, int height) {
        VulkanConstants.VkFormat vkFormat = VulkanConstants.VkFormat.fromGpuFormat(format);
        if (vkFormat == null) {
            return; // depth (or otherwise-untracked) format -- backend handles depth itself
        }
        prepareRenderTargetImage(id, width, height, vkFormat.getValue());
        RenderTargets.registerColorTarget(id);
    }
}
