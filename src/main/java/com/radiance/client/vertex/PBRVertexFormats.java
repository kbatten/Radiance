package com.radiance.client.vertex;

import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_ALBEDO_EMISSION;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_COLOR_LAYER;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_COORDINATE;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_GLINT_TEXTURE;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_GLINT_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_LIGHT_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_NORM;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_OVERLAY_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_POS;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_POST_BASE;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_TEXTURE_ID;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_TEXTURE_UV;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_USE_COLOR_LAYER;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_USE_GLINT;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_USE_LIGHT;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_USE_NORM;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_USE_OVERLAY;
import static com.radiance.client.vertex.PBRVertexFormatElements.PBR_USE_TEXTURE;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

public class PBRVertexFormats {

    // 26.2: VertexFormat.builder() -> builder(int stepRate); add(name, element) ->
    // addAttribute(name, GpuFormat). The Builder has no skip()/padding, so the original
    // trailing skip(4) is expressed as an explicit 4-byte "Padding" attribute to keep the
    // vertex stride the shaders/native backend expect.
    public static final VertexFormat
        PBR_TRIANGLE =
        VertexFormat.builder(0)
            .addAttribute("Pos", PBR_POS)
            .addAttribute("UseNorm", PBR_USE_NORM)

            .addAttribute("Norm", PBR_NORM)
            .addAttribute("UseColorLayer", PBR_USE_COLOR_LAYER)

            .addAttribute("ColorLayer", PBR_COLOR_LAYER)

            .addAttribute("UseTexture", PBR_USE_TEXTURE)
            .addAttribute("UseOverlay", PBR_USE_OVERLAY)
            .addAttribute("TextureUV", PBR_TEXTURE_UV)

            .addAttribute("OverlayUV", PBR_OVERLAY_UV)
            .addAttribute("UseGlint", PBR_USE_GLINT)
            .addAttribute("TextureID", PBR_TEXTURE_ID)

            .addAttribute("GlintUV", PBR_GLINT_UV)
            .addAttribute("GlintTexture", PBR_GLINT_TEXTURE)
            .addAttribute("UseLight", PBR_USE_LIGHT)

            .addAttribute("LightUV", PBR_LIGHT_UV)
            .addAttribute("Coordinate", PBR_COORDINATE)
            .addAttribute("AlbedoEmission", PBR_ALBEDO_EMISSION)

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

    public static final int STRIDE = PBR_TRIANGLE.getVertexSize();

    public static final int OFF_POS = off("Pos");
    public static final int OFF_USE_NORM = off("UseNorm");
    public static final int OFF_NORM = off("Norm");
    public static final int OFF_USE_COLOR_LAYER = off("UseColorLayer");
    public static final int OFF_COLOR_LAYER = off("ColorLayer");
    public static final int OFF_USE_TEXTURE = off("UseTexture");
    public static final int OFF_USE_OVERLAY = off("UseOverlay");
    public static final int OFF_TEXTURE_UV = off("TextureUV");
    public static final int OFF_OVERLAY_UV = off("OverlayUV");
    public static final int OFF_USE_GLINT = off("UseGlint");
    public static final int OFF_TEXTURE_ID = off("TextureID");
    public static final int OFF_GLINT_UV = off("GlintUV");
    public static final int OFF_GLINT_TEXTURE = off("GlintTexture");
    public static final int OFF_USE_LIGHT = off("UseLight");
    public static final int OFF_LIGHT_UV = off("LightUV");
    public static final int OFF_POST_BASE = off("PostBase");
    public static final int OFF_ALBEDO_EMISSION = off("AlbedoEmission");
}
