package com.radiance.client.vertex;

/**
 * Thread-local bridge for per-item enchantment-glint capture (sibling of {@link
 * BlockEmissionContext}).
 *
 * <p>26.2 draws item glint as a SEPARATE {@code prepareFoilSubmit} pass that re-runs the item's baked
 * quads with the glint render type -- distinct from {@code prepareMainSubmit}. Folding that second
 * pass into the item's buffer (the naive port of 1.21's {@code getItemGlintConsumer}, which drew the
 * item ONCE with glint folded in) would DOUBLE the geometry: two coplanar copies of every quad, which
 * ray-traces as z-fighting and reads as a flat, depthless item. Instead the mod skips the foil pass
 * and stamps the glint overlay onto the item's OWN vertices during the main pass -- one geometry
 * copy, exactly like 1.21's single draw.
 *
 * <p>{@code ItemFeatureRendererMixins} stashes the resolved glint texture id here around
 * {@code prepareMainSubmit} for an enchanted item ({@code foilType != NONE}); {@link
 * PBRVertexConsumer#addVertex}/{@code setUv} read it while the item's vertices are written, setting
 * the per-vertex {@code glintTexture} + {@code useGlint} + {@code glintUV} the RT consumes
 * ({@code default.rchit}: additive {@code tint = albedo + glint}, scrolled by
 * {@code worldUBO.textureMat}). Cleared back to 0 after the main pass so non-enchanted geometry never
 * inherits a stale glint. Thread-local because capture can run on worker threads.
 */
public final class GlintContext {

    private static final ThreadLocal<int[]> GLINT = ThreadLocal.withInitial(() -> new int[1]);

    private GlintContext() {
    }

    /** Set the glint texture id to stamp on subsequent vertices (0 = inactive). */
    public static void set(int glintTextureID) {
        GLINT.get()[0] = glintTextureID;
    }

    /** The active glint texture id, or 0 when no glint should be applied. */
    public static int get() {
        return GLINT.get()[0];
    }

    public static void clear() {
        GLINT.get()[0] = 0;
    }
}
