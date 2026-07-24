package com.radiance.mixin_related.extensions.vulkan_render_integration;

/**
 * Exposes {@code TextureAtlasSprite}'s private {@code padding} field.
 *
 * <p>A stitched sprite occupies a cell at {@code (getX(), getY())} but its pixels -- and the UVs the
 * GUI samples with -- are inset by {@code padding} on every side: {@code u0 = (x + padding) /
 * atlasWidth}. The border the padding creates is edge-bleed for mipmapping. {@code TextureAtlasMixins}
 * copies sprites into the Vulkan atlas CPU-side and must place each one at {@code (x + padding,
 * y + padding)} to line up with those UVs; uploading at the raw {@code (x, y)} shifts the sprite up and
 * left by {@code padding} pixels. See {@link ISpriteContentsExt} for why the atlas is filled CPU-side.
 */
public interface ITextureAtlasSpriteExt {

    /** Pixels of edge-bleed border between the sprite's cell origin and its actual content. */
    int radiance$getPadding();
}
