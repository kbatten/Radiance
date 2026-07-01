package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.opengl.GlShaderModule;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ICompiledShaderExt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 26.2: {@code net.minecraft.client.gl.CompiledShader} -> {@code com.mojang.blaze3d.opengl
 * .GlShaderModule}. This half of the source-capture just carries the resolved GLSL source on the
 * (virtual) shader module for the mod's native shader translation; the module is created — without
 * a real GL compile — by {@link GlDeviceMixins} hooking {@code getOrCompileShader}. {@code close()}
 * is cancelled because no real GL shader was created (and the mod runs without a GL context).
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

    @Inject(method = "close", at = @At("HEAD"), cancellable = true)
    private void closeWithoutOpenGL(CallbackInfo ci) {
        ci.cancel();
    }
}
