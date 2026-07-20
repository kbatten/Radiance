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
    // One-time-per-distinct-key breakdown of which RenderPipeline took which draw path (and, for
    // replayed indexed draws, what textures it bound). Answers "does the button/GUI-sprite pipeline use
    // the un-replayed non-indexed draw() path, or does it drawIndexed but sample the wrong slot?" --
    // which the aggregate counters above cannot. Logged once per key so it never spams the frame loop.
    private static final java.util.Set<String> seenPipelines = new java.util.HashSet<>();

    private DrawInterceptStats() {
    }

    /**
     * Record, once per distinct (path, pipeline, extra) key, which draw path a RenderPipeline used.
     * {@code extra} carries the bound texture map for indexed draws (name -> GL id) and is null for the
     * others. Temporary diagnostic; strip with the rest of the scaffolding.
     */
    public static void notePipeline(String location, String path, String extra) {
        String key = path + " " + location + (extra == null ? "" : " " + extra);
        if (seenPipelines.add(key)) {
            RadianceDebug.log("[DrawPipeline] " + key);
        }
    }

    private static final java.util.Set<String> seenUniformMisses = new java.util.HashSet<>();

    /**
     * A non-sampler uniform field was left zero because its source UBO block was not bound on the draw
     * ({@code reason="no bound slice"}) or GeometryCapture had no CPU bytes for the bound slice
     * ({@code reason="not captured"}). A zeroed ModelViewMat/ProjMat collapses every vertex to the
     * origin -> the draw rasterizes nothing, i.e. an invisible-but-replayed pipeline. Logged once per
     * (shader, field). Temporary diagnostic; strip with the rest of the scaffolding.
     */
    public static void noteUniformMiss(String shaderName, String field, String block, String reason) {
        if (seenUniformMisses.add(shaderName + " " + field)) {
            RadianceDebug.log("[UniformMiss] " + shaderName + " " + field
                + " (block " + block + ") " + reason);
        }
    }

    private static final java.util.Set<String> seenUniformValues = new java.util.HashSet<>();

    /**
     * Dump the resolved transform/colour uniforms once per shader. If ModelViewMat[0] or ProjMat[0]
     * comes out 0 for an invisible-but-replayed pipeline, its geometry collapsed to the origin; if they
     * are sane and the pipeline is still invisible, the fault is elsewhere. Temporary diagnostic.
     */
    public static void noteUniformValues(String shaderName, String values) {
        if (seenUniformValues.add(shaderName)) {
            RadianceDebug.log("[UniformSample] " + shaderName + " " + values);
        }
    }

    private static final java.util.Map<String, Integer> vertexCounts = new java.util.HashMap<>();

    /**
     * Dump the first vertex of the first several replayed draws per pipeline: the source buffer identity
     * and slice/vertex offsets, plus position (first three floats) and the whole stride as hex. gui and
     * gui_text captured real positions while the first gui_textured came back all-zero, so this says
     * whether every gui_textured draw is zero (a GeometryCapture miss for its buffer/offset) or only the
     * first (a fullscreen sprite, look elsewhere), and whether it even shares the working pipelines'
     * buffer. Temporary diagnostic.
     */
    public static void noteVertex(String location, int bufferId, long sliceOffset, int vertexOffset,
        int firstIndex, int stride, byte[] vertexData) {
        if (vertexCounts.merge(location, 1, Integer::sum) > 24) {
            return;
        }
        int n = Math.min(stride, vertexData.length);
        StringBuilder hex = new StringBuilder();
        for (int i = 0; i < n; i++) {
            hex.append(String.format("%02x", vertexData[i] & 0xff));
        }
        String pos = "?";
        if (vertexData.length >= 12) {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(vertexData)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
            pos = String.format("(%.2f,%.2f,%.2f)", b.getFloat(0), b.getFloat(4), b.getFloat(8));
        }
        RadianceDebug.log("[Vertex0] " + location + " buf=" + Integer.toHexString(bufferId)
            + " sOff=" + sliceOffset + " vOff=" + vertexOffset + " fIdx=" + firstIndex
            + " stride=" + stride + " len=" + vertexData.length + " pos=" + pos + " raw=" + hex);
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
