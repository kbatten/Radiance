package com.radiance.mixin_related.extensions.vulkan_render_integration;

import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * 26.2: the metadata the mod captures per shader now lives on {@code com.mojang.blaze3d.opengl
 * .GlProgram} (was {@code net.minecraft.client.gl.ShaderProgram}). The per-{@code GlUniform} list and
 * the program's sampler-texture map are gone: uniform values come from the built-in UBOs (captured
 * via {@code GeometryCapture} + resolved through {@code BuiltinUniforms}), sampler names are parsed
 * from the GLSL, and sampler textures are captured from the draw's {@code RenderPass.bindTexture}
 * calls. So this narrows to the name, vertex format and vertex/fragment GLSL source.
 */
public interface IShaderProgramExt {

    String radiance$getShaderName();

    void radiance$setShaderName(String shaderName);

    VertexFormat radiance$getVertexFormat();

    void radiance$setVertexFormat(VertexFormat vertexFormat);

    String radiance$getVertexSource();

    void radiance$setVertexSource(String vertexSource);

    String radiance$getFragmentSource();

    void radiance$setFragmentSource(String fragmentSource);
}
