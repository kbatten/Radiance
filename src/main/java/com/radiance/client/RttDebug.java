package com.radiance.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dedup'd one-shot logging for the render-to-texture (GuiItemAtlas) bring-up. Temporary scaffolding
 * next to {@link RadianceDebug}; strip with the rest of the RTT probes once item icons render.
 *
 * <p>Deliberately a plain class, NOT state on {@code RenderPassMixins}: a mixin that declares
 * non-constant {@code static final} fields gains a {@code <clinit>}, and that stops Mixin from merging
 * the mixin's {@code @Unique} instance-field initializers into the target constructor -- which nulled
 * {@code RenderPass.radiance$uniforms} and crashed the first GUI frame at startup. Keeping these sets
 * off the mixin keeps its instance initializers intact.
 */
public final class RttDebug {

    private static final Set<Integer> LOGGED_ROUTES = ConcurrentHashMap.newKeySet();
    private static final Set<Integer> LOGGED_UNREGISTERED = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_SHADER_MISS = ConcurrentHashMap.newKeySet();

    private RttDebug() {
    }

    public static void logRouteOnce(int glId) {
        if (LOGGED_ROUTES.add(glId)) {
            RadianceDebug.log("[RTT] routing draws to render target glId=" + glId);
        }
    }

    public static void logUnregisteredOnce(int glId) {
        if (LOGGED_UNREGISTERED.add(glId)) {
            RadianceDebug.log("[RTT] active render target glId=" + glId
                + " is NOT registered as a color target (import missed?)");
        }
    }

    public static void logShaderMissOnce(String pipeline) {
        if (LOGGED_SHADER_MISS.add(pipeline)) {
            RadianceDebug.log("[RTT] shader UNAVAILABLE for render-target draw, pipeline=" + pipeline);
        }
    }
}
