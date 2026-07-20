package com.radiance.mixin_related.extensions.vulkan_render_integration;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Exposes {@code SpriteContents}' private {@code byMipLevel} images.
 *
 * <p>26.2 fills a texture atlas by <em>rendering</em> each sprite into it: the sprite's pixels go to a
 * throwaway per-sprite texture, then a render pass draws that texture into the atlas at the sprite's
 * position with the {@code animate_sprite_blit} shader. The Vulkan backend replays only overlay
 * {@code drawIndexed} calls to the swapchain -- it does not replay that non-indexed render-to-texture
 * blit -- so the Vulkan atlas would keep nothing but its clear (transparent black) and every sprite
 * would sample zero alpha and be discarded. {@code TextureAtlasMixins} instead copies the sprites into
 * the Vulkan atlas CPU-side, and needs the source pixels these accessors expose.
 */
public interface ISpriteContentsExt {

    /** The sprite's per-mip-level source images; index 0 is the full-resolution image. */
    NativeImage[] radiance$getMipLevels();
}
