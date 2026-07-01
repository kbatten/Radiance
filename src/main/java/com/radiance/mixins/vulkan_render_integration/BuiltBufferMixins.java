package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: BuiltBuffer -> MeshData; the quad-centroid helper (used for translucency sorting) was
 * renamed {@code collectCentroids} -> {@code decodeQuadCentroids} and now writes into a
 * {@link CompactVectorArray} rather than returning a {@code Vector3f[]}. It looks the position
 * element up by name ("Position") and throws if absent -- which is exactly what happens for the
 * mod's PBR format (whose position attribute is "Pos"). This intercepts only that case (leaving
 * vanilla formats to the original method) and computes the PBR centroids so an accidental resort
 * of a PBR mesh can't crash on the mismatched stride.
 */
@Mixin(MeshData.class)
public class BuiltBufferMixins {

    @Inject(method = "decodeQuadCentroids", at = @At(value = "HEAD"), cancellable = true)
    private static void addPBRPosition(ByteBuffer vertexBuffer, int vertexCount,
        VertexFormat format, CompactVectorArray output, int outputIndex, CallbackInfo ci) {
        if (format.getElement("Position") != null) {
            return; // standard formats are handled by the vanilla method
        }
        VertexFormatElement positionElement = format.getElement("Pos");
        if (positionElement == null) {
            return; // let the vanilla method raise its own "no position element" error
        }

        int positionOffset = vertexBuffer.position() + positionElement.offset();
        int vertexStride = format.getVertexSize();
        int quadStride = vertexStride * 4;
        int quadCount = vertexCount / 4;

        for (int i = 0; i < quadCount; i++) {
            int firstPosOffset = i * quadStride + positionOffset;
            int secondPosOffset = firstPosOffset + vertexStride * 2;
            float x0 = vertexBuffer.getFloat(firstPosOffset);
            float y0 = vertexBuffer.getFloat(firstPosOffset + 4);
            float z0 = vertexBuffer.getFloat(firstPosOffset + 8);
            float x1 = vertexBuffer.getFloat(secondPosOffset);
            float y1 = vertexBuffer.getFloat(secondPosOffset + 4);
            float z1 = vertexBuffer.getFloat(secondPosOffset + 8);
            output.set(outputIndex + i, (x0 + x1) / 2.0F, (y0 + y1) / 2.0F, (z0 + z1) / 2.0F);
        }

        ci.cancel();
    }
}
