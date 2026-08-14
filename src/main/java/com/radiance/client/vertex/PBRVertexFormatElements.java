package com.radiance.client.vertex;

import com.mojang.blaze3d.GpuFormat;

/**
 * 26.2: Yarn's {@code VertexFormatElement.register(id, uv, ComponentType, Usage, count)}
 * became a minimal record {@code (name, offset, GpuFormat)} -- the shader-binding id /
 * {@code Usage} / {@code ComponentType}+count model is gone. A custom attribute is now just
 * its {@link GpuFormat}; the name/offset are supplied by the {@code VertexFormat} builder in
 * {@link PBRVertexFormats}. The mappings below preserve the original component type/count:
 * FLOATx3 -> RGB32_FLOAT, FLOATx4 -> RGBA32_FLOAT, FLOATx2 -> RG32_FLOAT, UINTx1 -> R32_UINT,
 * INTx2 -> RG32_SINT.
 */
public class PBRVertexFormatElements {

    public static final GpuFormat PBR_POS = GpuFormat.RGB32_FLOAT;            // FLOAT x3
    public static final GpuFormat PBR_USE_NORM = GpuFormat.R32_UINT;          // UINT x1
    public static final GpuFormat PBR_NORM = GpuFormat.RGB32_FLOAT;           // FLOAT x3
    public static final GpuFormat PBR_USE_COLOR_LAYER = GpuFormat.R32_UINT;   // UINT x1
    public static final GpuFormat PBR_COLOR_LAYER = GpuFormat.RGBA32_FLOAT;   // FLOAT x4
    public static final GpuFormat PBR_USE_TEXTURE = GpuFormat.R32_UINT;       // UINT x1
    public static final GpuFormat PBR_USE_OVERLAY = GpuFormat.R32_UINT;       // UINT x1
    public static final GpuFormat PBR_TEXTURE_UV = GpuFormat.RG32_FLOAT;      // FLOAT x2
    public static final GpuFormat PBR_OVERLAY_UV = GpuFormat.RG32_SINT;       // INT x2
    public static final GpuFormat PBR_USE_GLINT = GpuFormat.R32_UINT;         // UINT x1
    public static final GpuFormat PBR_TEXTURE_ID = GpuFormat.R32_UINT;        // UINT x1
    public static final GpuFormat PBR_GLINT_UV = GpuFormat.RG32_FLOAT;        // FLOAT x2
    public static final GpuFormat PBR_GLINT_TEXTURE = GpuFormat.R32_UINT;     // UINT x1
    public static final GpuFormat PBR_USE_LIGHT = GpuFormat.R32_UINT;         // UINT x1
    public static final GpuFormat PBR_LIGHT_UV = GpuFormat.RG32_SINT;         // INT x2
    public static final GpuFormat PBR_COORDINATE = GpuFormat.R32_UINT;        // UINT x1
    public static final GpuFormat PBR_POST_BASE = GpuFormat.RGB32_FLOAT;      // FLOAT x3
    public static final GpuFormat PBR_ALBEDO_EMISSION = GpuFormat.R32_UINT;   // UINT x1
    public static final GpuFormat PBR_EMISSION_COLOR = GpuFormat.RGBA32_FLOAT; // FLOAT x4
}
