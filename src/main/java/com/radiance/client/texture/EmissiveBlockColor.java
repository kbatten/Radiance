package com.radiance.client.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ISpriteContentsExt;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.world.level.block.Block;

/**
 * Computes an emissive block's representative light color from its atlas sprite, for the vanilla
 * area-light fallback (see native {@code chunks.cpp buildLightInfos}). Without this the synthesized
 * light used the vertex tint (~white for an untinted torch), so every emitter glowed flat white.
 *
 * <p>The color is a <em>luminance-weighted</em> average of the sprite's opaque texels: bright texels
 * dominate, so a torch's small orange flame beats its large dark handle, and a uniformly-bright block
 * (glowstone, sea lantern) just averages normally. The result is normalized so its brightest channel
 * is 1.0 -- it carries only the hue; the intensity comes from the block's light level (the emission
 * strength) in native, so a warmed light is no dimmer than the old white one, just tinted.
 *
 * <p>Cached per sprite (sprites are stable for an atlas's lifetime; a resource reload builds new
 * sprite instances, leaving at most a few dead entries here).
 */
public final class EmissiveBlockColor {

    private static final float[] NEUTRAL = {1.0F, 1.0F, 1.0F};
    private static final int ALPHA_THRESHOLD = 16;

    private static final Map<TextureAtlasSprite, float[]> CACHE = new ConcurrentHashMap<>();
    // Per-block color, populated as emissive blocks are rendered (BlockModelRendererMixins). Lets the
    // HANDHELD light (#23) look up the same color a PLACED torch emits, so the two match by construction.
    private static final Map<Block, float[]> BLOCK_CACHE = new ConcurrentHashMap<>();

    private EmissiveBlockColor() {
    }

    /** Normalized emissive light color (rgb, brightest channel 1.0) for the sprite; white if unknown. */
    public static float[] of(TextureAtlasSprite sprite) {
        if (sprite == null) {
            return NEUTRAL;
        }
        return CACHE.computeIfAbsent(sprite, EmissiveBlockColor::compute);
    }

    /** Record a block's emissive color (from its rendered sprite) so the handheld light can match it. */
    public static void recordBlock(Block block, float[] color) {
        if (block != null && color != null) {
            BLOCK_CACHE.put(block, color);
        }
    }

    /** The cached emissive color for a block if one has been rendered, else null. */
    public static float[] ofBlock(Block block) {
        return block == null ? null : BLOCK_CACHE.get(block);
    }

    // Warm torch tint used by the handheld light until the block has been rendered (and thus cached).
    private static final float[] WARM_FALLBACK = {1.0F, 0.75F, 0.45F};

    /** Cached emissive color for the block, or a warm torch tint if none has been rendered yet. */
    public static float[] ofBlockOrWarm(Block block) {
        float[] cached = ofBlock(block);
        return cached != null ? cached : WARM_FALLBACK;
    }

    private static float[] compute(TextureAtlasSprite sprite) {
        NativeImage image;
        try {
            NativeImage[] mips = ((ISpriteContentsExt) (Object) sprite.contents()).radiance$getMipLevels();
            if (mips == null || mips.length == 0 || mips[0] == null) {
                return NEUTRAL;
            }
            image = mips[0];
        } catch (RuntimeException e) {
            return NEUTRAL;
        }

        int width = image.getWidth();
        int height = image.getHeight();
        double sumWeight = 0.0;
        double sumR = 0.0;
        double sumG = 0.0;
        double sumB = 0.0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // NativeImage.getPixel returns ARGB (0xAARRGGBB) -- same decode EmissionRecorder uses.
                int argb = image.getPixel(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha < ALPHA_THRESHOLD) {
                    continue;
                }
                float r = ((argb >> 16) & 0xFF) / 255.0F;
                float g = ((argb >> 8) & 0xFF) / 255.0F;
                float b = (argb & 0xFF) / 255.0F;
                double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                double weight = (alpha / 255.0) * luminance;
                sumWeight += weight;
                sumR += weight * r;
                sumG += weight * g;
                sumB += weight * b;
            }
        }

        if (sumWeight <= 1.0e-6) {
            return NEUTRAL;
        }
        float r = (float) (sumR / sumWeight);
        float g = (float) (sumG / sumWeight);
        float b = (float) (sumB / sumWeight);
        float max = Math.max(r, Math.max(g, b));
        if (max <= 1.0e-4F) {
            return NEUTRAL;
        }
        return new float[] {r / max, g / max, b / max};
    }
}
