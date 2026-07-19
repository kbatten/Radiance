package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.textures.GpuTexture;
import com.radiance.client.proxy.vulkan.GeometryCapture;
import com.radiance.client.proxy.vulkan.TextureProxy;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2 pixel-upload path (counterpart to the allocation import in
 * {@link TextureUtilMixins}).
 *
 * <p>In 1.21.4 {@code vri/NativeImageMixins} hooked {@code NativeImage.uploadInternal(...)}
 * to mirror the pixel upload to the Vulkan backend via {@code TextureProxy.queueUpload}.
 * In 26.2 {@code NativeImage} no longer uploads itself -- uploads go through
 * {@link CommandEncoder#writeToTexture(GpuTexture, NativeImage, int, int, int, int)}
 * (the {@code (GpuTexture, NativeImage)} overload funnels through this one too). We
 * mirror that upload to Vulkan, keyed by the destination's {@link GlTexture#glId()}.
 *
 * <p>NativeImage writes always target UNORM_8 textures whose channel count matches the
 * source (validated by MC), i.e. exactly the R8/RG8/RGB8/RGBA8 formats imported in
 * {@link TextureUtilMixins}, so the destination id is always one the Vulkan backend
 * already knows.
 */
@Mixin(CommandEncoder.class)
public abstract class CommandEncoderMixins {

    @Inject(
        method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Lcom/mojang/blaze3d/platform/NativeImage;IIII)V",
        at = @At("HEAD"))
    private void radiance$mirrorWriteToTexture(GpuTexture destination, NativeImage source,
        int mipLevel, int depthOrLayer, int destX, int destY, CallbackInfo ci) {
        if (destination instanceof GlTexture glTexture) {
            TextureProxy.queueUpload(
                source.getPointer(),                   // srcPointer
                source.getPixelBytes().remaining(),    // srcSizeInBytes
                source.getWidth(),                     // srcRowPixels (full image width)
                glTexture.glId(),                      // dstId
                0, 0,                                  // srcOffsetX, srcOffsetY (whole source)
                destX, destY,                          // dstOffsetX, dstOffsetY
                source.getWidth(), source.getHeight(), // width, height
                mipLevel);                             // level
        }
    }

    /**
     * Mirror the {@code writeToTexture(GpuTexture, ByteBuffer, ...)} route -- the second texture-write
     * path, taken when pixels arrive already packed in a direct buffer rather than a NativeImage. The
     * unihex font provider ({@code UnihexProvider$Glyph.upload}) uploads every unicode/CJK glyph this
     * way, so without this only the ASCII bitmap font (handled by the copyBufferToTexture mirror below)
     * would render; unicode glyphs would sample an empty sheet and discard.
     *
     * <p>Args decoded from GlCommandEncoder: {@code (mipLevel, depthOrLayer, destX, destY, width,
     * height)}, with the source tightly packed at {@code width} row length (GL sets UNPACK_ROW_LENGTH =
     * width, skip rows/pixels = 0). The buffer is filled before this call, so its address is handed
     * straight to {@code queueUpload}, which copies synchronously -- no temp buffer, and MC is free to
     * free the source afterwards. Layer 0 only (2D), matching the reasoning on the cube route below.
     */
    @Inject(
        method = "writeToTexture(Lcom/mojang/blaze3d/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V",
        at = @At("HEAD"))
    private void radiance$mirrorWriteToTextureBuffer(GpuTexture destination, ByteBuffer source,
        int mipLevel, int depthOrLayer, int destX, int destY, int width, int height,
        CallbackInfo ci) {
        if (depthOrLayer != 0 || !(destination instanceof GlTexture glTexture) || !source.isDirect()) {
            return;
        }
        TextureProxy.queueUpload(
            MemoryUtil.memAddress(source), // srcPointer -- direct buffer at its current position
            source.remaining(),            // srcSizeInBytes
            width,                         // srcRowPixels (GL UNPACK_ROW_LENGTH == width here)
            glTexture.glId(),              // dstId
            0, 0,                          // srcOffsetX, srcOffsetY (whole source)
            destX, destY,                  // dstOffsetX, dstOffsetY
            width, height,                 // width, height
            mipLevel);                     // level
    }

    /**
     * Mirror {@code writeToBuffer} uploads into {@link GeometryCapture} so the RenderPass draw
     * interception can read the actual uniform and geometry bytes without a GPU readback (counterpart
     * to the writeToTexture mirror above). This is one of four buffer-write routes GeometryCapture
     * covers; the built-in {@code Projection} UBO and the shared quad index buffer arrive here, while
     * others (e.g. {@code DynamicTransforms}) arrive via the mapped-view route.
     */
    @Inject(
        method = "writeToBuffer(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Ljava/nio/ByteBuffer;)V",
        at = @At("HEAD"))
    private void radiance$captureWriteToBuffer(GpuBufferSlice destination, ByteBuffer data,
        CallbackInfo ci) {
        GeometryCapture.captureWrite(destination, data);
    }

    /**
     * Carry captured bytes from a staging slice into the buffer a draw will actually bind.
     * StagedVertexBuffer maps + writes a pooled staging slice, then moves it here. Argument order is
     * (source, destination) -- confirmed from GlCommandEncoder, which passes the first slice as
     * glCopyBufferSubData's readBuffer -- and is the reverse of writeToBuffer's convention.
     */
    @Inject(
        method = "copyToBuffer(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
            + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
        at = @At("HEAD"))
    private void radiance$captureCopyToBuffer(GpuBufferSlice source, GpuBufferSlice destination,
        CallbackInfo ci) {
        GeometryCapture.recordCopy(source, destination);
    }

    /**
     * Mirror {@code copyBufferToTexture} into Vulkan -- the third texture-write route, used when the
     * source pixels originate in a GPU buffer rather than a NativeImage. The default bitmap font
     * uploads every glyph this way ({@code BitmapProvider$Glyph.upload} -> {@code copyBufferToTexture}),
     * and animated sprites use it for later frames. Without it the font sheet stays empty in the Vulkan
     * backend even though {@code createTexture} imported the image: {@code gui_text} then samples
     * nothing, each glyph hits its {@code if (color.a < 0.1) discard}, and <em>all</em> text is
     * invisible -- while the title logo, which travels the {@code writeToTexture(NativeImage)} route
     * mirrored above, still shows. That is the "MINECRAFT JAVA EDITION visible but no button labels"
     * symptom.
     *
     * <p>The source bytes live in a GPU buffer that MC populated through the mapped-view route, which
     * {@link GeometryCapture} already mirrors, so they are read back here with no GPU readback. The int
     * arguments (decoded from GlCommandEncoder's glTexSubImage2D, whose PBO read offset is
     * {@code sliceOffset + (skipPixels + skipRows * rowLength) * blockSize}) are, in order: source
     * skip-pixels (X) and skip-rows (Y), source row length and image height, then destination X/Y,
     * region width/height, mip level and array layer. The native {@code queueUpload} indexes the
     * appended source blob the same way from srcOffsetX/srcOffsetY, so the whole captured source image
     * is handed over with those skips rather than pre-sliced.
     *
     * <p>Only layer 0 is mirrored: {@code queueUpload} targets a 2D image and takes no array-layer
     * argument, so cube faces (the panorama) are left to the future samplerCube work -- its shader is
     * unavailable today and falls back to GL regardless.
     */
    @Inject(
        method = "copyBufferToTexture(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;IIII"
            + "Lcom/mojang/blaze3d/textures/GpuTexture;IIIIII)V",
        at = @At("HEAD"))
    private void radiance$mirrorCopyBufferToTexture(GpuBufferSlice source, int srcSkipPixels,
        int srcSkipRows, int srcRowLength, int srcImageHeight, GpuTexture destination, int destX,
        int destY, int width, int height, int mipLevel, int depthOrLayer, CallbackInfo ci) {
        if (depthOrLayer != 0 || !(destination instanceof GlTexture glTexture)) {
            return;
        }
        byte[] bytes = GeometryCapture.get(source);
        if (bytes == null) {
            return; // source buffer not captured (e.g. panorama cube) -- leave it to the GL path
        }
        ByteBuffer src = MemoryUtil.memAlloc(bytes.length);
        try {
            src.put(bytes).flip();
            TextureProxy.queueUpload(
                MemoryUtil.memAddress(src), // srcPointer -- base (pixel 0,0) of the source image
                bytes.length,               // srcSizeInBytes
                srcRowLength,               // srcRowPixels
                glTexture.glId(),           // dstId
                srcSkipPixels,              // srcOffsetX
                srcSkipRows,                // srcOffsetY
                destX, destY,               // dstOffsetX, dstOffsetY
                width, height,              // width, height
                mipLevel);                  // level
        } finally {
            MemoryUtil.memFree(src); // queueUpload copies synchronously, so this is safe to free now
        }
    }
}
