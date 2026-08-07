package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.texture.SpriteAnimationMirror;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IAbstractTextureExt;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ISpriteContentsExt;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ITextureAtlasSpriteExt;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
 * than per frame. Per-tick animation (water/lava/fire/portal) is handled by
 * {@link #radiance$mirrorAnimatedFrames} below, which mirrors the same way.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasMixins {

    @Shadow
    private List<TextureAtlasSprite> sprites;

    @Shadow
    private int mipLevelCount;

    // AnimationState is a public inner class (its *fields* are private, read reflectively via
    // SpriteAnimationMirror); the list is built in sprite order but we match by SpriteContents identity
    // rather than relying on that.
    @Shadow
    private List<SpriteContents.AnimationState> animatedTexturesStates;

    // Last atlas frame index mirrored per animated sprite, so an interpolated animation (which reports
    // dirty every tick while it cross-fades) only triggers a re-upload when its discrete frame actually
    // advances. Lazily created; keyed by SpriteContents identity.
    @Unique
    private Map<SpriteContents, Integer> radiance$lastAnimatedFrame;

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
            // A sprite's cell origin is (getX(), getY()), but its pixels -- and the UVs the GUI samples
            // with -- are inset by this padding on every side: u0 = (x + padding) / atlasWidth. Uploading
            // at the raw (x, y) lands the content padding pixels up-and-left of where it is sampled, so
            // add the padding to the destination origin.
            int padding = ((ITextureAtlasSpriteExt) sprite).radiance$getPadding();
            int originX = sprite.getX() + padding;
            int originY = sprite.getY() + padding;
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
                    originX >> level,                    // dstOffsetX
                    originY >> level,                    // dstOffsetY
                    width, height,
                    level);
            }
        }
    }

    /**
     * Mirror each animated sprite's <em>current</em> frame into the Vulkan atlas every tick.
     *
     * <p>26.2 advances animations by rendering the current frame into the atlas via a render pass
     * ({@code uploadAnimationFrames} -> {@code AnimationState.drawToAtlas}); the Vulkan backend does not
     * replay that render-to-texture, so the Vulkan atlas keeps frame 0 and water/lava/fire/portal look
     * frozen. Run just before Minecraft's own blit (HEAD, so {@code isDirty} is still set) and CPU-copy
     * the current frame's pixels the same way {@link #radiance$uploadSpritesToVulkan} copies the first
     * frame -- the only difference is the source offset, which now points at the current frame within
     * the sprite's frame strip instead of (0, 0). The frame index/layout are read reflectively (see
     * {@link SpriteAnimationMirror}) because they live on MC's package-private animation inner classes.
     *
     * <p>Not yet interpolated: interpolated animations (default water/lava) get discrete frames rather
     * than the smooth cross-fade Minecraft blends on the GPU. Left as follow-up.
     */
    @Inject(method = "uploadAnimationFrames", at = @At("HEAD"))
    private void radiance$mirrorAnimatedFrames(CallbackInfo ci) {
        GpuTexture gpuTexture = ((IAbstractTextureExt) this).radiance$getTexture();
        if (!(gpuTexture instanceof GlTexture glTexture) || this.sprites == null
            || this.animatedTexturesStates == null || this.animatedTexturesStates.isEmpty()) {
            return;
        }
        int atlasId = glTexture.glId();

        // Skip the sprite-map build on ticks where nothing advanced (e.g. an atlas with no active
        // animation): only proceed if at least one state is dirty.
        boolean anyDirty = false;
        for (SpriteContents.AnimationState state : this.animatedTexturesStates) {
            if (SpriteAnimationMirror.isDirty(state)) {
                anyDirty = true;
                break;
            }
        }
        if (!anyDirty) {
            return;
        }

        // Animation states carry no atlas position -- only the stitched sprite knows where it lives --
        // so match each state to its sprite by the SpriteContents they share.
        Map<SpriteContents, TextureAtlasSprite> spriteByContents = new IdentityHashMap<>();
        for (TextureAtlasSprite sprite : this.sprites) {
            spriteByContents.put(sprite.contents(), sprite);
        }

        for (SpriteContents.AnimationState state : this.animatedTexturesStates) {
            if (!SpriteAnimationMirror.isDirty(state)) {
                continue; // frame has not advanced since we last mirrored it
            }
            if (!(SpriteAnimationMirror.spriteContentsOf(state) instanceof SpriteContents contents)) {
                continue;
            }
            TextureAtlasSprite sprite = spriteByContents.get(contents);
            if (sprite == null) {
                continue;
            }
            int gridIndex = SpriteAnimationMirror.currentGridIndex(state);
            int rowSize = SpriteAnimationMirror.frameRowSize(state);
            if (gridIndex < 0 || rowSize <= 0) {
                continue;
            }
            // Only re-upload when the discrete frame changed (interpolated sprites report dirty every
            // tick even while showing the same frame).
            if (this.radiance$lastAnimatedFrame == null) {
                this.radiance$lastAnimatedFrame = new IdentityHashMap<>();
            }
            Integer lastFrame = this.radiance$lastAnimatedFrame.get(contents);
            if (lastFrame != null && lastFrame == gridIndex) {
                continue;
            }
            NativeImage[] mipLevels = ((ISpriteContentsExt) contents).radiance$getMipLevels();
            if (mipLevels == null) {
                continue;
            }
            int padding = ((ITextureAtlasSpriteExt) sprite).radiance$getPadding();
            int frameWidth = contents.width();
            int frameHeight = contents.height();
            // Source origin of the current frame within the sprite's frame strip (frames are laid out in
            // a grid frameRowSize wide); the first-frame upload above reads this same strip from (0, 0).
            int frameX = (gridIndex % rowSize) * frameWidth;
            int frameY = (gridIndex / rowSize) * frameHeight;
            int originX = sprite.getX() + padding;
            int originY = sprite.getY() + padding;
            int levels = Math.min(mipLevels.length, Math.max(this.mipLevelCount, 1));
            for (int level = 0; level < levels; level++) {
                NativeImage image = mipLevels[level];
                if (image == null) {
                    continue;
                }
                int width = Math.max(frameWidth >> level, 1);
                int height = Math.max(frameHeight >> level, 1);
                TextureProxy.queueUpload(
                    image.getPointer(),                  // srcPointer (whole strip)
                    image.getPixelBytes().remaining(),   // srcSizeInBytes (whole strip)
                    image.getWidth(),                    // srcRowPixels (strip width)
                    atlasId,                             // dstId
                    frameX >> level, frameY >> level,    // srcOffsetX, srcOffsetY -> current frame
                    originX >> level,                    // dstOffsetX
                    originY >> level,                    // dstOffsetY
                    width, height,
                    level);
            }
            this.radiance$lastAnimatedFrame.put(contents, gridIndex);
        }
    }
}
