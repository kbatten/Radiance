package com.radiance.client.proxy.vulkan;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Captures the CPU-side bytes uploaded to each GPU buffer slice so the {@code RenderPass} draw
 * interception can read the actual uniform values without a GPU readback -- the same approach the
 * texture subsystem uses to mirror {@code writeToTexture} uploads (see {@code CommandEncoderMixins}).
 *
 * <p>In 26.2 the built-in UBOs ("Projection"/"Fog"/"Globals"/"Lighting"/"DynamicTransforms") are
 * written via {@code CommandEncoder.writeToBuffer(GpuBufferSlice, ByteBuffer)} and later bound to a
 * draw via {@code RenderPass.setUniform(name, slice)}. Keying the captured bytes by the
 * destination's buffer identity + offset lets the draw resolve {@code (UBO block, std140 offset)}
 * -> value when it packs the mod's native uniform blob. Latest write to a slice wins.
 */
public final class UniformCapture {

    private record Key(GpuBuffer buffer, long offset) {

    }

    private static final Map<Key, byte[]> CAPTURED = new ConcurrentHashMap<>();

    private UniformCapture() {
    }

    /** Records the bytes written to {@code destination} (copied; the source buffer is transient). */
    public static void capture(GpuBufferSlice destination, ByteBuffer data) {
        ByteBuffer view = data.slice();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        CAPTURED.put(new Key(destination.buffer(), destination.offset()), bytes);
    }

    /** The most-recent bytes uploaded to the slice's buffer region, or {@code null} if unseen. */
    public static byte[] get(GpuBufferSlice slice) {
        return CAPTURED.get(new Key(slice.buffer(), slice.offset()));
    }

    public static void clear() {
        CAPTURED.clear();
    }
}
