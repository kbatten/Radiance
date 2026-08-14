package com.radiance.client.vertex;

/**
 * Thread-local bridge for per-block-quad PBR emission (scalar strength + light color).
 *
 * <p>26.2 decouples block-model quad emission (computed in {@code ModelBlockRenderer}, which knows
 * the block state/pos/tint index) from the PBR vertex writer (a per-section {@link PBRVertexConsumer}
 * that sits behind the {@code BlockQuadOutput} functional interface and is therefore unreachable from
 * the block renderer). {@code BlockModelRendererMixins}/{@code FluidRendererMixins} stash the current
 * quad's emission here around the quad's {@code output.put}, and {@link PBRVertexConsumer#addVertex}
 * reads it while the quad's vertices are written into the {@code AlbedoEmission} + {@code
 * EmissionColor} channels.
 *
 * <p>The color (rgb, 0 = unset) is the emissive block's normalized texture hue; native
 * {@code buildLightInfos} tints the synthesized vanilla area light with it so a torch glows warm and
 * a soul torch stays cool, instead of a flat white glow. When unset, native falls back to the vertex
 * tint (previous behavior).
 *
 * <p>Section building runs on worker threads, so a thread-local keeps concurrent sections (and any
 * other PBR capture on other threads) independent. The values are cleared back to zero after each
 * quad, so non-emissive geometry (entities, fluids, ...) never inherits a stale emission.
 */
public final class BlockEmissionContext {

    // [0] = emission strength, [1..3] = emissive light color RGB (0,0,0 = unset).
    private static final ThreadLocal<float[]> STATE = ThreadLocal.withInitial(() -> new float[4]);

    private BlockEmissionContext() {
    }

    /** Emissive strength only; no color (native uses the vertex tint). */
    public static void set(float emission) {
        float[] s = STATE.get();
        s[0] = emission;
        s[1] = 0.0F;
        s[2] = 0.0F;
        s[3] = 0.0F;
    }

    /** Emissive strength plus the block's light color (rgb). */
    public static void set(float emission, float r, float g, float b) {
        float[] s = STATE.get();
        s[0] = emission;
        s[1] = r;
        s[2] = g;
        s[3] = b;
    }

    public static float get() {
        return STATE.get()[0];
    }

    /** The raw [emission, r, g, b] state, read directly by {@link PBRVertexConsumer}. */
    public static float[] raw() {
        return STATE.get();
    }

    public static void clear() {
        float[] s = STATE.get();
        s[0] = 0.0F;
        s[1] = 0.0F;
        s[2] = 0.0F;
        s[3] = 0.0F;
    }
}
