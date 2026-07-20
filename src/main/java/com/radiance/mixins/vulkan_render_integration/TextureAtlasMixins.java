package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IAbstractTextureExt;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ISpriteContentsExt;
import java.util.List;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fills the Vulkan copy of a texture atlas, which Minecraft otherwise populates in a way the backend
 * cannot see.
 *
 * <p>26.2 does not upload sprite pixels into the atlas. It uploads each sprite to a throwaway
 * per-sprite texture and then <em>renders</em> that texture into the atlas at the sprite's position,
 * with a render pass whose colour target is the atlas and whose draw uses the
 * {@code animate_sprite_blit} shader. The backend replays only overlay {@code drawIndexed} calls into
 * the swapchain: it replays neither non-indexed draws nor render-to-texture, so the Vulkan atlas kept
 * nothing but its clear -- transparent black. Every widget sprite then sampled zero alpha and hit
 * {@code position_tex_color}'s {@code if (color.a == 0.0) discard}, which is why button backgrounds were
 * invisible while text (whose glyphs travel the mirrored {@code copyBufferToTexture} route) rendered.
 *
 * <p>So copy the sprites in directly instead: once Minecraft has finished its own blit, walk the atlas's
 * sprites and upload each one's pixels to the atlas's GL id at its stitched position. That is the same
 * {@code queueUpload} path every other texture already uses, and it runs once per resource reload rather
 * than per frame. Animated sprites get their first frame; their per-tick animation still goes through the
 * un-replayed blit and is left for follow-up work.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixins {

    @Shadow
    private List<TextureAtlasSprite> sprites;

    @Shadow
    private int mipLevelCount;

    @Inject(method = "uploadInitialContents", at = @At("RETURN"))
    private void radiance$uploadSpritesToVulkan(CallbackInfo ci) {
        // The atlas GpuTexture is created earlier in upload(), so TextureUtilMixins has already imported
        // it into the backend (as an empty image) under this GL id.
        GpuTexture gpuTexture = ((IAbstractTextureExt) this).radiance$getTexture();
        if (!(gpuTexture instanceof GlTexture glTexture) || this.sprites == null) {
            return;
        }
        int atlasId = glTexture.glId();

        for (TextureAtlasSprite sprite : this.sprites) {
            SpriteContents contents = sprite.contents();
            NativeImage[] mipLevels = ((ISpriteContentsExt) contents).radiance$getMipLevels();
            if (mipLevels == null) {
                continue;
            }
            // Upload every mip the atlas actually has, so mipmapped atlases (blocks, items) are complete
            // rather than only sharp at level 0.
            int levels = Math.min(mipLevels.length, Math.max(this.mipLevelCount, 1));
            for (int level = 0; level < levels; level++) {
                NativeImage image = mipLevels[level];
                if (image == null) {
                    continue;
                }
                int width = Math.max(contents.width() >> level, 1);
                int height = Math.max(contents.height() >> level, 1);
                // An animated sprite's image is the whole frame strip; reading width x height from its
                // origin at the image's own row stride takes exactly the first frame.
                TextureProxy.queueUpload(
                    image.getPointer(),                  // srcPointer
                    image.getPixelBytes().remaining(),   // srcSizeInBytes
                    image.getWidth(),                    // srcRowPixels
                    atlasId,                             // dstId
                    0, 0,                                // srcOffsetX, srcOffsetY
                    sprite.getX() >> level,              // dstOffsetX
                    sprite.getY() >> level,              // dstOffsetY
                    width, height,
                    level);
            }
        }
    }
}
