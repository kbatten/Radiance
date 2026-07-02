package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.opengl.GlProgram;
import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ICompiledShaderExt;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IShaderProgramExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2: the mod's captured shader metadata moves from {@code ShaderProgram} (hooked create/set/bind
 * to build a GL-less virtual program) to {@code com.mojang.blaze3d.opengl.GlProgram}. There is no
 * virtual program anymore -- the mod simply records the name / vertex format / GLSL source on the
 * real linked program at {@code GlProgram.link}. The GLSL still never reaches OpenGL (the shader
 * modules are the virtual ones from {@link GlDeviceMixins}); the mod's native backend translates the
 * captured source. Uniform/sampler binding happens at draw time on the {@code RenderPass}
 * ({@code RenderPassMixins}), so the old GlUniform/sampler shadows are gone.
 */
@Mixin(GlProgram.class)
public abstract class ShaderProgramMixins implements IShaderProgramExt {

    @Unique
    private String radiance$shaderName;
    @Unique
    private VertexFormat radiance$vertexFormat;
    @Unique
    private String radiance$vertexSource;
    @Unique
    private String radiance$fragmentSource;

    @Inject(method = "link", at = @At("RETURN"))
    private static void radiance$captureProgram(GlShaderModule vertexShader,
        GlShaderModule fragmentShader, VertexFormat[] vertexBindings, String debugLabel,
        CallbackInfoReturnable<GlProgram> cir) {
        GlProgram program = cir.getReturnValue();
        if (program == GlProgram.INVALID_PROGRAM) {
            return;
        }
        IShaderProgramExt ext = (IShaderProgramExt) (Object) program;
        ext.radiance$setShaderName(debugLabel);
        ext.radiance$setVertexSource(
            ((ICompiledShaderExt) (Object) vertexShader).radiance$getResolvedSource());
        ext.radiance$setFragmentSource(
            ((ICompiledShaderExt) (Object) fragmentShader).radiance$getResolvedSource());
        if (vertexBindings != null && vertexBindings.length > 0 && vertexBindings[0] != null) {
            ext.radiance$setVertexFormat(vertexBindings[0]);
        }
    }

    @Override
    public String radiance$getShaderName() {
        return this.radiance$shaderName;
    }

    @Override
    public void radiance$setShaderName(String shaderName) {
        this.radiance$shaderName = shaderName;
    }

    @Override
    public VertexFormat radiance$getVertexFormat() {
        return this.radiance$vertexFormat;
    }

    @Override
    public void radiance$setVertexFormat(VertexFormat vertexFormat) {
        this.radiance$vertexFormat = vertexFormat;
    }

    @Override
    public String radiance$getVertexSource() {
        return this.radiance$vertexSource;
    }

    @Override
    public void radiance$setVertexSource(String vertexSource) {
        this.radiance$vertexSource = vertexSource;
    }

    @Override
    public String radiance$getFragmentSource() {
        return this.radiance$fragmentSource;
    }

    @Override
    public void radiance$setFragmentSource(String fragmentSource) {
        this.radiance$fragmentSource = fragmentSource;
    }
}
