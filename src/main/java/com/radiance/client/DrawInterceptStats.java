package com.radiance.client;

/**
 * Temporary 26.2-migration diagnostic for the draw-interception path.
 *
 * <p>{@code RenderPassMixins#radiance$onDrawIndexed} bails out to vanilla GL at several guards. Each
 * bail-out is silent, so a draw that was never replayed in Vulkan is indistinguishable from one that
 * was -- which is exactly the ambiguity behind the blank screen: the render loop is healthy and
 * Vulkan is legal, yet no geometry appears. Counting each exit says which guard is eating the draws
 * instead of guessing between fixes that have nothing in common.
 *
 * <p>Strip this together with the rest of the {@code radiance_*.log} scaffolding.
 */
public final class DrawInterceptStats {

    private static final long DUMP_INTERVAL_NANOS = 250_000_000L;

    private static long entered;
    private static long noState;
    private static long notGlPipeline;
    private static long invalidProgram;
    private static long noVertexData;
    private static long noIndexData;
    private static long shaderUnavailable;
    private static long replayed;
    // Seeded at class load, not left at 0: otherwise the very first counter call is already
    // DUMP_INTERVAL past "zero" and dumps immediately, burning the first report on an all-zero line
    // recorded before any outcome was decided.
    private static long lastDumpNanos = System.nanoTime();
    private static final java.util.Map<String, Long> others = new java.util.LinkedHashMap<>();

    private DrawInterceptStats() {
    }

    /**
     * drawIndexed was entered at all. Counted separately so that "no [DrawIntercept] output" can be
     * told apart from "the hook never fires" -- if 26.2 routes this geometry through some other
     * RenderPass draw method, every other counter would stay zero and say nothing.
     */
    public static void entered() {
        entered++;
        maybeDump();
    }

    /** Pipeline / vertex buffer / index buffer / index type was not captured before the draw. */
    public static void noState() {
        noState++;
        maybeDump();
    }

    /** precompilePipeline did not yield a GlRenderPipeline. */
    public static void notGlPipeline() {
        notGlPipeline++;
        maybeDump();
    }

    /** The pipeline resolved but its GlProgram is INVALID_PROGRAM. */
    public static void invalidProgram() {
        invalidProgram++;
        maybeDump();
    }

    /** GeometryCapture had no bytes covering this draw's vertex window. */
    public static void noVertexData() {
        noVertexData++;
        maybeDump();
    }

    /** GeometryCapture had no bytes covering this draw's index window. */
    public static void noIndexData() {
        noIndexData++;
        maybeDump();
    }

    /** The native backend could not build this draw's shader, so vanilla GL keeps it. */
    public static void shaderUnavailable() {
        shaderUnavailable++;
        maybeDump();
    }

    /** The draw was packed and handed to the native backend. */
    public static void replayed() {
        replayed++;
        maybeDump();
    }

    /**
     * A RenderPass draw entry point other than drawIndexed fired. 26.2's RenderPass exposes nine
     * draw methods and only drawIndexed is intercepted, so geometry issued through any of the others
     * reaches vanilla GL and never becomes Vulkan work. Counting them says whether the blank screen
     * is a guard tripping inside our hook or draws arriving through a door we never opened.
     */
    public static void otherDraw(String method) {
        others.merge(method, 1L, Long::sum);
        maybeDump();
    }

    private static void maybeDump() {
        long now = System.nanoTime();
        if (now - lastDumpNanos < DUMP_INTERVAL_NANOS) {
            return;
        }
        lastDumpNanos = now;
        RadianceDebug.log("[DrawIntercept] entered=" + entered
            + " replayed=" + replayed
            + " noState=" + noState
            + " notGlPipeline=" + notGlPipeline
            + " invalidProgram=" + invalidProgram
            + " noVertexData=" + noVertexData
            + " noIndexData=" + noIndexData
            + " shaderUnavailable=" + shaderUnavailable
            + (others.isEmpty() ? "" : " | other draw paths: " + others));
    }
}
