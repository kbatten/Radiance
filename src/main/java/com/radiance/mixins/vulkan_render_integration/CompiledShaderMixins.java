package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.opengl.GlShaderModule;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ICompiledShaderExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * 26.2: {@code net.minecraft.client.gl.CompiledShader} -> {@code com.mojang.blaze3d.opengl
 * .GlShaderModule}. Carries the resolved GLSL source on the shader module for the mod's native shader
 * translation; the source is recorded by {@link GlDeviceMixins} at {@code getOrCompileShader} RETURN.
 *
 * <p>Unlike 1.21.11 (which cancelled {@code close()} because no real GL shader existed), the 26.2 port
 * lets MC compile the real GL shader on its own backend, so {@code close()} is left alone to free it.
 */
@Mixin(GlShaderModule.class)
public abstract class CompiledShaderMixins implements ICompiledShaderExt {

    @Unique
    private String radiance$resolvedSource;

    @Override
    public String radiance$getResolvedSource() {
        return this.radiance$resolvedSource;
    }

    @Override
    public void radiance$setResolvedSource(String resolvedSource) {
        this.radiance$resolvedSource = resolvedSource;
    }
}
