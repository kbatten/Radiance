package com.radiance.mixins.vulkan_render_integration;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.List;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 26.2: weather (rain/snow) renders through {@code WeatherEffectRenderer}, which
 * {@code extractRenderState}s the visible precipitation columns into a {@link
 * net.minecraft.client.renderer.state.level.WeatherRenderState} and then, in {@code render}, uses
 * the private {@code renderInstances(...)} to tessellate those columns (as camera-relative quads)
 * into a {@code VertexConsumer} before uploading a GPU buffer and drawing to the weather target --
 * a path the Vulkan backend never sees.
 *
 * <p>Expose {@code renderInstances} so the mod can drive MC's own precipitation tessellation into a
 * {@code PBRVertexConsumer} (see {@code EntityProxy.queueWeatherBuild}) and feed the geometry to the
 * ray tracer, instead of reimplementing the rain-table column math.
 */
@Mixin(WeatherEffectRenderer.class)
public interface WeatherEffectRendererMixins {

    @Invoker("renderInstances")
    void radiance$renderInstances(VertexConsumer consumer,
        List<WeatherEffectRenderer.ColumnInstance> columns, Vec3 cameraPos, float verticalOffset,
        int radius, float intensity);
}
