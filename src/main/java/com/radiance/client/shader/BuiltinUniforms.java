package com.radiance.client.shader;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The 26.2 built-in shader uniforms and where each lives in its std140 UBO block. In 1.21.4 the mod
 * read uniform values from per-uniform {@code GlUniform}s; 26.2 removed those -- values live in five
 * UBOs bound per draw via {@code RenderPass.setUniform(block, slice)}. So this is the single source
 * of truth mapping a uniform {@code name} to its {@code (block, std140 offset, kind, componentCount)}:
 * {@code ShaderRegistry} uses it to build the native uniform-blob field list (for whichever uniforms
 * a shader actually references), and {@code ShaderProxy} uses it to read each field's value from the
 * captured UBO bytes ({@code GeometryCapture}) at draw time.
 *
 * <p>Offsets are taken directly from the vanilla UBO GLSL blocks
 * ({@code assets/minecraft/shaders/include/{dynamictransforms,projection,globals,light,fog}.glsl})
 * under std140 rules.
 */
public final class BuiltinUniforms {

    public record Entry(String name, String block, int uboOffset, ShaderField.Kind kind,
                        int componentCount) {

    }

    private static final Map<String, Entry> BY_NAME = new LinkedHashMap<>();

    private static void add(String name, String block, int uboOffset, ShaderField.Kind kind,
        int componentCount) {
        BY_NAME.put(name, new Entry(name, block, uboOffset, kind, componentCount));
    }

    static {
        // DynamicTransforms { mat4 ModelViewMat; vec4 ColorModulator; vec3 ModelOffset; mat4 TextureMat; }
        add("ModelViewMat", "DynamicTransforms", 0, ShaderField.Kind.MATRIX, 4);
        add("ColorModulator", "DynamicTransforms", 64, ShaderField.Kind.FLOAT, 4);
        add("ModelOffset", "DynamicTransforms", 80, ShaderField.Kind.FLOAT, 3);
        add("TextureMat", "DynamicTransforms", 96, ShaderField.Kind.MATRIX, 4);
        // Projection { mat4 ProjMat; }
        add("ProjMat", "Projection", 0, ShaderField.Kind.MATRIX, 4);
        // Globals { ivec3 CameraBlockPos; vec3 CameraOffset; vec2 ScreenSize; float GlintAlpha;
        //           float GameTime; int MenuBlurRadius; int UseRgss; }
        add("CameraBlockPos", "Globals", 0, ShaderField.Kind.INT, 3);
        add("CameraOffset", "Globals", 16, ShaderField.Kind.FLOAT, 3);
        add("ScreenSize", "Globals", 32, ShaderField.Kind.FLOAT, 2);
        add("GlintAlpha", "Globals", 40, ShaderField.Kind.FLOAT, 1);
        add("GameTime", "Globals", 44, ShaderField.Kind.FLOAT, 1);
        add("MenuBlurRadius", "Globals", 48, ShaderField.Kind.INT, 1);
        add("UseRgss", "Globals", 52, ShaderField.Kind.INT, 1);
        // Lighting { vec3 Light0_Direction; vec3 Light1_Direction; }
        add("Light0_Direction", "Lighting", 0, ShaderField.Kind.FLOAT, 3);
        add("Light1_Direction", "Lighting", 16, ShaderField.Kind.FLOAT, 3);
        // Fog { vec4 FogColor; float FogEnvironmentalStart; float FogEnvironmentalEnd;
        //       float FogRenderDistanceStart; float FogRenderDistanceEnd; float FogSkyEnd; float FogCloudsEnd; }
        add("FogColor", "Fog", 0, ShaderField.Kind.FLOAT, 4);
        add("FogEnvironmentalStart", "Fog", 16, ShaderField.Kind.FLOAT, 1);
        add("FogEnvironmentalEnd", "Fog", 20, ShaderField.Kind.FLOAT, 1);
        add("FogRenderDistanceStart", "Fog", 24, ShaderField.Kind.FLOAT, 1);
        add("FogRenderDistanceEnd", "Fog", 28, ShaderField.Kind.FLOAT, 1);
        add("FogSkyEnd", "Fog", 32, ShaderField.Kind.FLOAT, 1);
        add("FogCloudsEnd", "Fog", 36, ShaderField.Kind.FLOAT, 1);
    }

    private BuiltinUniforms() {
    }

    /** All built-in uniforms, in declaration order (used to build a shader's field list). */
    public static Iterable<Entry> all() {
        return BY_NAME.values();
    }

    /** The block/offset/kind/count for a uniform name, or {@code null} if not a built-in uniform. */
    public static Entry get(String name) {
        return BY_NAME.get(name);
    }
}
