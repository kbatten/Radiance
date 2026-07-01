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
}
