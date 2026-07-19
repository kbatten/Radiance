package com.radiance.client.proxy.vulkan;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.vertex.StagingBuffer;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * CPU-side mirror of the bytes that reach each vertex/index {@link GpuBuffer}, so the
 * {@code RenderPass} draw interception can rebuild geometry without a GPU readback.
 *
 * <p>This exists because {@link UniformCapture} cannot serve geometry in 26.2. That class hooks
 * {@code CommandEncoder.writeToBuffer} and keys on an exact {@code (buffer, offset)} pair, which is
 * right for the built-in UBOs it was written for. Geometry does not travel that way: 26.2 stages it
 * through {@link StagingBuffer}, and the implementation chosen on a discrete GPU
 * ({@code StagingBuffer$PersistentlyMapped}) memcpys into persistently mapped host memory and moves
 * it with {@code copyToBuffer}, a GPU-side buffer-to-buffer copy. {@code writeToBuffer} is never
 * called for it, so every vertex lookup missed and no draw was ever replayed.
 *
 * <p>Capture therefore happens where the bytes are still a {@link ByteBuffer} -- at
 * {@code StagingBuffer.tryAppend}, on the abstract base class so both implementations are covered --
 * and is bound to a destination later, when {@code StagingBuffer$Uploader.copyTo} names the target
 * buffer and offset for a previously staged handle.
 *
 * <p>Lookups resolve ranges rather than exact offsets: a draw binds a sub-slice of a larger shared
 * vertex buffer, so the covering write has to be found and the requested window extracted from it.
 */
public final class GeometryCapture {

    /** buffer -> (destination offset -> bytes written at that offset). */
    private static final Map<GpuBuffer, ConcurrentSkipListMap<Long, byte[]>> WRITES =
        new ConcurrentHashMap<>();

    /**
     * Staged bytes awaiting a destination, keyed by handle identity. {@code BufferHandle} has no
     * usable equality of its own, and identity is exactly the relation we want: the handle returned
     * by tryAppend is the same object later passed to copyTo.
     */
    private static final Map<StagingBuffer.BufferHandle, byte[]> STAGED =
        Collections.synchronizedMap(new IdentityHashMap<>());

    /** Bytes snapshotted at tryAppend HEAD, waiting for the handle the RETURN hook will supply. */
    private static final ThreadLocal<byte[]> PENDING = new ThreadLocal<>();

    private GeometryCapture() {
    }

    /**
     * Snapshot the bytes being appended. Taken at HEAD because tryAppend consumes the buffer; by
     * RETURN the position has already advanced past the data.
     */
    public static void stageBytes(ByteBuffer data) {
        if (data == null) {
            return;
        }
        ByteBuffer view = data.slice(); // independent view; leaves the caller's position untouched
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        PENDING.set(bytes);
    }

    /** Bind the bytes snapshotted by {@link #stageBytes} to the handle tryAppend handed back. */
    public static void bindStaged(StagingBuffer.BufferHandle handle) {
        byte[] bytes = PENDING.get();
        PENDING.remove();
        if (handle != null && bytes != null) {
            STAGED.put(handle, bytes);
        }
    }

    /** A staged handle is being copied into {@code destination} at {@code destinationOffset}. */
    public static void recordCopy(StagingBuffer.BufferHandle handle, GpuBuffer destination,
        long destinationOffset) {
        byte[] bytes = STAGED.remove(handle);
        if (bytes != null && destination != null) {
            record(destination, destinationOffset, bytes);
        }
    }

    /**
     * Record a direct {@code CommandEncoder.writeToBuffer}. Geometry does not all arrive by staging:
     * the shared quad index buffer is written this way, and {@code StagingBuffer$Cpu} uses this route
     * for everything, so both paths must land in the same store for lookups to succeed regardless of
     * which staging implementation the device selected.
     */
    public static void captureWrite(GpuBufferSlice destination, ByteBuffer data) {
        if (destination == null || data == null) {
            return;
        }
        ByteBuffer view = data.slice();
        byte[] bytes = new byte[view.remaining()];
        view.get(bytes);
        record(destination.buffer(), destination.offset(), bytes);
    }

    /**
     * Record a completed mapped write. This is the route GUI geometry actually takes:
     * {@code StagedVertexBuffer.uploadDrawsToBuffers} maps a slice, {@code put}s vertex bytes into
     * {@code MappedView.data()}, closes it, then issues {@code copyToBuffer} into the real vertex or
     * index buffer. Capturing at close means every write has landed.
     *
     * <p>Index 0 of the mapped buffer is {@code slice.offset()} of the underlying buffer, so the
     * bytes are recorded at that offset. Read through a duplicate positioned at 0, since the caller's
     * position has been advanced by its own puts.
     */
    public static void captureMapped(GpuBufferSlice slice, ByteBuffer data) {
        if (slice == null || data == null) {
            return;
        }
        ByteBuffer view = data.duplicate();
        int length = (int) Math.min(view.capacity(), slice.length());
        if (length <= 0) {
            return;
        }
        view.position(0);
        view.limit(length);
        byte[] bytes = new byte[length];
        view.get(bytes);
        record(slice.buffer(), slice.offset(), bytes);
    }

    /**
     * Carry captured bytes across a {@code CommandEncoder.copyToBuffer(source, destination)}, so a
     * mapped write into a staging slice is still resolvable once it has been moved into the buffer a
     * draw actually binds. Argument order confirmed from GlCommandEncoder, which passes the first
     * slice as glCopyBufferSubData's readBuffer.
     */
    public static void recordCopy(GpuBufferSlice source, GpuBufferSlice destination) {
        if (source == null || destination == null) {
            return;
        }
        byte[] bytes = get(source);
        if (bytes != null) {
            record(destination.buffer(), destination.offset(), bytes);
        }
    }

    /** Record bytes landing in a buffer directly, e.g. via {@code CommandEncoder.writeToBuffer}. */
    public static void record(GpuBuffer buffer, long offset, byte[] bytes) {
        if (buffer == null || bytes == null) {
            return;
        }
        WRITES.computeIfAbsent(buffer, key -> new ConcurrentSkipListMap<>()).put(offset, bytes);
    }

    /** Bytes backing {@code slice}, or {@code null} if no recorded write covers it. */
    public static byte[] get(GpuBufferSlice slice) {
        return slice == null ? null : get(slice.buffer(), slice.offset(), slice.length());
    }

    /** Bytes backing {@code [offset, offset + length)} of {@code buffer}, or {@code null}. */
    public static byte[] get(GpuBuffer buffer, long offset, long length) {
        ConcurrentSkipListMap<Long, byte[]> byOffset = WRITES.get(buffer);
        if (byOffset == null) {
            return null;
        }
        Map.Entry<Long, byte[]> covering = byOffset.floorEntry(offset);
        if (covering == null) {
            return null;
        }
        byte[] bytes = covering.getValue();
        long start = offset - covering.getKey();
        long end = start + length;
        if (start < 0 || end > bytes.length) {
            return null; // the write does not reach far enough to cover the request
        }
        if (start == 0 && end == bytes.length) {
            return bytes;
        }
        return Arrays.copyOfRange(bytes, (int) start, (int) end);
    }

    public static void clear() {
        WRITES.clear();
        STAGED.clear();
        PENDING.remove();
    }
}
