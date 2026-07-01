package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.opengl.GlShaderModule;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.radiance.mixin_related.extensions.vulkan_render_integration.ICompiledShaderExt;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 26.2 shader source-capture. In 1.21.4 the mod hooked {@code CompiledShader.compile(id, type,
 * source)} to skip the GL compile and keep the GLSL source. 26.2 compiles shaders inside
 * {@code GlDevice.compileShader(ShaderCompilationKey, ShaderSource)} — but that key type is private
 * and unnameable by a mixin, so hook the protected {@code getOrCompileShader(Identifier, ShaderType,
 * ShaderDefines, ShaderSource)} instead: resolve the GLSL via {@code shaderSource.get(id, type)} and
 * return a virtual {@link GlShaderModule} (public ctor, no reflection) carrying that source, without
 * touching OpenGL (the mod runs without a GL context; its native backend translates the source).
 */
// GlDevice is package-private, so target it by name rather than by .class.
@Mixin(targets = "com.mojang.blaze3d.opengl.GlDevice")
public class GlDeviceMixins {

    @Unique
    private static final AtomicInteger radiance$NEXT_VIRTUAL_SHADER_ID = new AtomicInteger(1);

    @Inject(method = "getOrCompileShader", at = @At("HEAD"), cancellable = true)
    private void captureShaderSource(Identifier id, ShaderType type, ShaderDefines defines,
        ShaderSource shaderSource, CallbackInfoReturnable<GlShaderModule> cir) {
        String source = shaderSource.get(id, type);
        if (source == null) {
            return; // let vanilla log the failure + return INVALID_SHADER
        }
        GlShaderModule module = new GlShaderModule(
            radiance$NEXT_VIRTUAL_SHADER_ID.getAndIncrement(), id, type);
        ((ICompiledShaderExt) (Object) module).radiance$setResolvedSource(source);
        cir.setReturnValue(module);
    }
}
