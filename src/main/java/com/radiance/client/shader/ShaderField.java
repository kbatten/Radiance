package com.radiance.client.shader;

public record ShaderField(String name, String fieldName, Kind kind, int componentCount,
                          int offset, int size, int samplerSlot) {

    public enum Kind {
        INT,
        FLOAT,
        MATRIX,
        SAMPLER,
        // A samplerCube. Packed identically to SAMPLER (a uint bindless index into the cube array), but
        // resolved against a separate cubeTextures[] binding because GLSL cannot index a sampler2D[]
        // with a cube type. The panorama background is the only cube sampler in 26.2's core shaders.
        SAMPLER_CUBE
    }

    public boolean isSampler() {
        return kind == Kind.SAMPLER || kind == Kind.SAMPLER_CUBE;
    }

    public boolean isCubeSampler() {
        return kind == Kind.SAMPLER_CUBE;
    }

    public String glslType() {
        return switch (kind) {
            case INT -> switch (componentCount) {
                case 1 -> "int";
                case 2 -> "ivec2";
                case 3 -> "ivec3";
                case 4 -> "ivec4";
                default -> throw new IllegalStateException(
                    "Unsupported int vector size: " + componentCount);
            };
            case FLOAT -> switch (componentCount) {
                case 1 -> "float";
                case 2 -> "vec2";
                case 3 -> "vec3";
                case 4 -> "vec4";
                default -> throw new IllegalStateException(
                    "Unsupported float vector size: " + componentCount);
            };
            case MATRIX -> switch (componentCount) {
                case 2 -> "mat2";
                case 3 -> "mat3";
                case 4 -> "mat4";
                default -> throw new IllegalStateException(
                    "Unsupported matrix size: " + componentCount);
            };
            case SAMPLER, SAMPLER_CUBE -> "uint";
        };
    }
}
