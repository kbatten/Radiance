package com.radiance.mixin_related.extensions.vulkan_render_integration;

/**
 * 26.2: {@code RenderLayer.name} (public in Yarn) became {@code RenderType.name} (protected). The mod
 * still needs the layer name to classify geometry (geometry-type lookup, {@code water_mask}
 * detection, native group name), so this exposes it.
 */
public interface IRenderTypeExt {

    String radiance$getName();
}
