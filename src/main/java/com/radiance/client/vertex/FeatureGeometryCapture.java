package com.radiance.client.vertex;

/**
 * 26.2 centralized entity/item/BE capture context. In 26.2 the per-renderer
 * {@code VertexConsumerProvider} interception is gone -- entity/item/BE renderers submit deferred
 * nodes drained by {@code FeatureRenderDispatcher}, and each feature renderer obtains its
 * {@code VertexConsumer} through {@code RenderTypeFeatureRenderer.getVertexBuilder(RenderType)}. The
 * mod drives its own per-entity drain pass ({@code EntityProxy}) and, while a capture store is
 * {@link #begin} active, {@code RenderTypeFeatureRendererMixins} redirects that
 * {@code getVertexBuilder} call into the store's {@link PBRVertexConsumer}. Routing the geometry into
 * the mod store also leaves the vanilla staged buffer empty, so the drain's execute phases issue no
 * GPU work.
 *
 * <p>Thread-local because the drain runs on the caller's thread; scope every {@link #begin} with a
 * {@link #end} in a finally.
 */
public final class FeatureGeometryCapture {

    private static final ThreadLocal<StorageVertexConsumerProvider> ACTIVE = new ThreadLocal<>();

    private FeatureGeometryCapture() {
    }

    public static void begin(StorageVertexConsumerProvider store) {
        ACTIVE.set(store);
    }

    public static void end() {
        ACTIVE.remove();
    }

    public static StorageVertexConsumerProvider active() {
        return ACTIVE.get();
    }
}
