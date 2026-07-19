package com.radiance.client.vertex;

import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_COLOR_LAYER;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_GLINT_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_LIGHT_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_NORM;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_OVERLAY_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_POS;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_POST_BASE;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_TEXTURE_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_USE_COLOR_LAYER;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_USE_NORM;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public class PBRVertexFormats {

    // 26.2: VertexFormat.builder() -> builder(int stepRate); add(name, element) ->
    // addAttribute(name, GpuFormat). The Builder has no skip()/padding, so the original
    // trailing skip(4) is expressed as an explicit 4-byte "Padding" attribute to keep the
    // vertex stride the shaders/native backend expect.
    //
    // 26.2 also caps a VertexFormat at 16 attributes (VertexFormat$Builder.createAttribute throws
    // "Having more than 16 attributes are not supported" on the 17th). The natural layout below is
    // 19, so four pairs of ADJACENT single-uint flags are declared as one RG32_UINT each. This is a
    // declaration change only: RG32_UINT at offset N occupies exactly the same eight bytes as two
    // R32_UINTs at N and N+4, so the 128-byte struct is byte-for-byte unchanged and neither the
    // native backend's Vulkan vertex-input layout nor PBRVertexConsumer's writes are affected.
    // Alignment is safe by construction -- GpuFormat.byteAlignment() is componentType().byteSize(),
    // i.e. 4 for every 32-bit format here, and all merged offsets are multiples of 8.
    //
    // The merged pairs are unrelated fields that merely sit next to each other; the OFF_* constants
    // below keep addressing them individually, so nothing downstream needs to know.
    public static final VertexFormat
        PBR_TRIANGLE =
        VertexFormat.builder(0)
            .addAttribute("Pos", PBR_POS)
            .addAttribute("UseNorm", PBR_USE_NORM)

            .addAttribute("Norm", PBR_NORM)
            .addAttribute("UseColorLayer", PBR_USE_COLOR_LAYER)

            .addAttribute("ColorLayer", PBR_COLOR_LAYER)

            // UseTexture + UseOverlay
            .addAttribute("UseTextureOverlay", GpuFormat.RG32_UINT)
            .addAttribute("TextureUV", PBR_TEXTURE_UV)

            .addAttribute("OverlayUV", PBR_OVERLAY_UV)
            // UseGlint + TextureID
            .addAttribute("UseGlintTextureId", GpuFormat.RG32_UINT)

            .addAttribute("GlintUV", PBR_GLINT_UV)
            // GlintTexture + UseLight
            .addAttribute("GlintTextureUseLight", GpuFormat.RG32_UINT)

            .addAttribute("LightUV", PBR_LIGHT_UV)
            // Coordinate + AlbedoEmission
            .addAttribute("CoordinateAlbedoEmission", GpuFormat.RG32_UINT)

            .addAttribute("PostBase", PBR_POST_BASE)

            .addAttribute("Padding", GpuFormat.R32_FLOAT)
            .build();

    // 26.2: Yarn's VertexFormatElement carried a numeric id used to index
    // getOffsetsByElementId(); the 26.2 VertexFormatElement is a record(name, offset,
    // GpuFormat) that stores the byte offset directly. Since the PBR layout is a fixed
    // 128-byte struct we control, precompute each attribute's byte offset here (from the
    // built format, so it stays in sync with the attribute order above) and let
    // PBRVertexConsumer write to fixed offsets instead of the removed mask/id machinery.
    private static int off(String name) {
        VertexFormatElement element = PBR_TRIANGLE.getElement(name);
        if (element == null) {
            throw new IllegalStateException("PBR vertex format is missing attribute: " + name);
        }
        return element.offset();
    }

    /** Byte offset of the second uint inside a merged RG32_UINT pair. */
    private static final int SECOND_UINT = 4;

    public static final int STRIDE = PBR_TRIANGLE.getVertexSize();

    public static final int OFF_POS = off("Pos");
    public static final int OFF_USE_NORM = off("UseNorm");
    public static final int OFF_NORM = off("Norm");
    public static final int OFF_USE_COLOR_LAYER = off("UseColorLayer");
    public static final int OFF_COLOR_LAYER = off("ColorLayer");
    public static final int OFF_USE_TEXTURE = off("UseTextureOverlay");
    public static final int OFF_USE_OVERLAY = off("UseTextureOverlay") + SECOND_UINT;
    public static final int OFF_TEXTURE_UV = off("TextureUV");
    public static final int OFF_OVERLAY_UV = off("OverlayUV");
    public static final int OFF_USE_GLINT = off("UseGlintTextureId");
    public static final int OFF_TEXTURE_ID = off("UseGlintTextureId") + SECOND_UINT;
    public static final int OFF_GLINT_UV = off("GlintUV");
    public static final int OFF_GLINT_TEXTURE = off("GlintTextureUseLight");
    public static final int OFF_USE_LIGHT = off("GlintTextureUseLight") + SECOND_UINT;
    public static final int OFF_LIGHT_UV = off("LightUV");
    public static final int OFF_POST_BASE = off("PostBase");
    public static final int OFF_ALBEDO_EMISSION = off("CoordinateAlbedoEmission") + SECOND_UINT;

    /** Offset of the Coordinate uint, which shares its attribute with AlbedoEmission. */
    public static final int OFF_COORDINATE = off("CoordinateAlbedoEmission");

    // The native backend reads this struct with a hand-written Vulkan vertex-input layout, so a
    // silent change in size or field placement would corrupt geometry rather than fail loudly.
    // Pin both here: the attribute merges above are only safe because they preserve them exactly.
    static {
        if (STRIDE != 128) {
            throw new IllegalStateException(
                "PBR vertex stride changed: expected 128, got " + STRIDE);
        }
        int[] expected = {
            0, 12, 16, 28, 32, 48, 52, 56, 64, 72, 76, 80, 88, 92, 96, 104, 108, 112,
        };
        int[] actual = {
            OFF_POS, OFF_USE_NORM, OFF_NORM, OFF_USE_COLOR_LAYER, OFF_COLOR_LAYER,
            OFF_USE_TEXTURE, OFF_USE_OVERLAY, OFF_TEXTURE_UV, OFF_OVERLAY_UV, OFF_USE_GLINT,
            OFF_TEXTURE_ID, OFF_GLINT_UV, OFF_GLINT_TEXTURE, OFF_USE_LIGHT, OFF_LIGHT_UV,
            OFF_COORDINATE, OFF_ALBEDO_EMISSION, OFF_POST_BASE,
        };
        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != actual[i]) {
                throw new IllegalStateException("PBR vertex layout drifted at field " + i
                    + ": expected offset " + expected[i] + ", got " + actual[i]);
            }
        }
    }
}
