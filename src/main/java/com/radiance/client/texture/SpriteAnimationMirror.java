package com.radiance.client.texture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Reads the current animation frame of a {@code SpriteContents$AnimationState} so
 * {@link com.radiance.mixins.vulkan_render_integration.TextureAtlasMixins} can mirror it into the
 * Vulkan atlas each client tick.
 *
 * <p>26.2 animates atlas sprites entirely on the GPU: {@code TextureAtlas.uploadAnimationFrames}
 * renders the current frame into the atlas through a render pass ({@code AnimationState.drawToAtlas}),
 * which the Vulkan backend does not replay -- so the Vulkan atlas keeps frame 0 and water/lava/fire/
 * portal look frozen. The current frame index lives on MC's private inner classes
 * ({@code SpriteContents$AnimationState} / {@code $AnimatedTexture} / {@code $FrameInfo}), whose
 * <em>types</em> the mod cannot name (they are package-private), so shadowing them would mean
 * inaccessible-typed {@code @Shadow} fields. Read them reflectively instead: the handles are cached and
 * only a handful of sprites animate at 20 Hz, so the cost is negligible, and any reflection failure
 * degrades to "leave the sprite on its last frame" rather than crashing.
 *
 * <p>Fields (Mojmap, 26.2): {@code AnimationState.frame} (index into the frame list),
 * {@code AnimationState.isDirty} (advanced since last draw), {@code AnimationState.animationInfo}
 * ({@code AnimatedTexture}); {@code AnimatedTexture.frames} ({@code List<FrameInfo>}),
 * {@code AnimatedTexture.frameRowSize}, {@code AnimatedTexture.this$0} (the owning
 * {@code SpriteContents}); {@code FrameInfo.index()} (grid index of the frame in the sprite strip).
 */
public final class SpriteAnimationMirror {

    private static Field stateFrame;
    private static Field stateIsDirty;
    private static Field stateAnimationInfo;
    private static Field animFrames;
    private static Field animFrameRowSize;
    private static Field animOwner;
    private static Method frameInfoIndex;
    private static boolean initialized;
    private static boolean available;

    private SpriteAnimationMirror() {
    }

    private static synchronized void init(Object animationState) {
        if (initialized) {
            return;
        }
        initialized = true;
        try {
            Class<?> stateClass = animationState.getClass();
            stateFrame = stateClass.getDeclaredField("frame");
            stateIsDirty = stateClass.getDeclaredField("isDirty");
            stateAnimationInfo = stateClass.getDeclaredField("animationInfo");
            stateFrame.setAccessible(true);
            stateIsDirty.setAccessible(true);
            stateAnimationInfo.setAccessible(true);

            Class<?> animClass = stateAnimationInfo.getType();
            animFrames = animClass.getDeclaredField("frames");
            animFrameRowSize = animClass.getDeclaredField("frameRowSize");
            animOwner = animClass.getDeclaredField("this$0");
            animFrames.setAccessible(true);
            animFrameRowSize.setAccessible(true);
            animOwner.setAccessible(true);
            available = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            available = false;
        }
    }

    /** True once the animation has advanced since it was last drawn (needs a re-upload). */
    public static boolean isDirty(Object animationState) {
        init(animationState);
        if (!available) {
            return false;
        }
        try {
            return stateIsDirty.getBoolean(animationState);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** The {@code SpriteContents} that owns this animation state, for matching it to its stitched sprite. */
    public static Object spriteContentsOf(Object animationState) {
        init(animationState);
        if (!available) {
            return null;
        }
        try {
            Object animationInfo = stateAnimationInfo.get(animationState);
            return animOwner.get(animationInfo);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Grid index of the current frame within the sprite's frame strip, or -1 if unavailable. */
    public static int currentGridIndex(Object animationState) {
        init(animationState);
        if (!available) {
            return -1;
        }
        try {
            int frame = stateFrame.getInt(animationState);
            Object animationInfo = stateAnimationInfo.get(animationState);
            List<?> frames = (List<?>) animFrames.get(animationInfo);
            if (frames == null || frame < 0 || frame >= frames.size()) {
                return -1;
            }
            Object frameInfo = frames.get(frame);
            if (frameInfoIndex == null) {
                frameInfoIndex = frameInfo.getClass().getMethod("index");
                frameInfoIndex.setAccessible(true);
            }
            return (int) frameInfoIndex.invoke(frameInfo);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }

    /** Number of frames per row in the sprite strip (frames are laid out in a grid), or -1. */
    public static int frameRowSize(Object animationState) {
        init(animationState);
        if (!available) {
            return -1;
        }
        try {
            Object animationInfo = stateAnimationInfo.get(animationState);
            return animFrameRowSize.getInt(animationInfo);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return -1;
        }
    }
}
