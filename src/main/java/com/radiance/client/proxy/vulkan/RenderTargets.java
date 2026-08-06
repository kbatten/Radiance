package com.radiance.client.proxy.vulkan;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * Render-target awareness for the draw-replay path.
 *
 * <p>26.2 renders some content into off-screen textures rather than the main framebuffer -- most
 * importantly {@code GuiItemAtlas}, which renders each GUI item's 3D model into a slot of its own
 * {@code GpuTexture} and then blits that atlas as flat quads. The mod otherwise routes every draw to
 * the single native overlay, so those item-model draws never reach the atlas texture and every icon
 * samples the same uninitialized garbage.
 *
 * <p>This registry lets the draw path tell "off-screen render target" from "the main framebuffer":
 * a texture is a render target when it was created with {@code USAGE_RENDER_ATTACHMENT} and is not
 * the {@code Minecraft.mainRenderTarget()} color texture ({@link RenderPassMixins}-side check). It
 * also carries the pending per-slot region clear (issued via
 * {@code CommandEncoder.clearColorAndDepthTextures} just before the atlas render pass) so the native
 * RTT pass can replay it as a scissored clear that preserves the other, already-cached slots.
 */
public final class RenderTargets {

    private RenderTargets() {
    }

    /** GL ids of color textures created with USAGE_RENDER_ATTACHMENT (candidates for RTT routing). */
    private static final IntSet colorTargets = new IntOpenHashSet();

    /** Pending region clear per color-target GL id, consumed at the next RTT render-pass begin. */
    private static final Int2ObjectMap<PendingClear> pendingClears = new Int2ObjectOpenHashMap<>();

    public record PendingClear(int x, int y, int width, int height, float r, float g, float b,
                               float a, double depth) {
    }

    public static synchronized void registerColorTarget(int glId) {
        colorTargets.add(glId);
    }

    public static synchronized boolean isColorTarget(int glId) {
        return colorTargets.contains(glId);
    }

    public static synchronized void setPendingClear(int colorGlId, PendingClear clear) {
        pendingClears.put(colorGlId, clear);
    }

    /** Consume (and remove) the pending clear for a target; null if none is queued. */
    public static synchronized PendingClear takePendingClear(int colorGlId) {
        return pendingClears.remove(colorGlId);
    }
}
