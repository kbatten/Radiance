package com.radiance.client.vertex;

/**
 * Thread-local bridge for per-block-quad PBR emission.
 *
 * <p>26.2 decouples block-model quad emission (computed in {@code ModelBlockRenderer}, which knows
 * the block state/pos/tint index) from the PBR vertex writer (a per-section {@link PBRVertexConsumer}
 * that sits behind the {@code BlockQuadOutput} functional interface and is therefore unreachable from
 * the block renderer). {@code BlockModelRendererMixins} stashes the current quad's emission here
 * around the quad's {@code output.put}, and {@link PBRVertexConsumer#addVertex} reads it while the
 * quad's vertices are written into the {@code AlbedoEmission} channel.
 *
 * <p>Section building runs on worker threads, so a thread-local keeps concurrent sections (and any
 * other PBR capture on other threads) independent. The value is cleared back to zero after each
 * quad, so non-emissive geometry (entities, fluids, ...) never inherits a stale emission.
 */
public final class BlockEmissionContext {

    private static final ThreadLocal<float[]> EMISSION = ThreadLocal.withInitial(() -> new float[1]);

    private BlockEmissionContext() {
    }

    public static void set(float emission) {
        EMISSION.get()[0] = emission;
    }

    public static float get() {
        return EMISSION.get()[0];
    }

    public static void clear() {
        EMISSION.get()[0] = 0.0F;
    }
}
