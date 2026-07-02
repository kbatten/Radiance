package com.radiance.mixins.vulkan_render_integration;

import com.radiance.mixin_related.extensions.vulkan_render_integration.IRenderTypeExt;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 26.2: exposes the protected {@code RenderType.name} to the mod's capture pipeline (see
 * {@link IRenderTypeExt}).
 */
@Mixin(RenderType.class)
public abstract class RenderTypeMixins implements IRenderTypeExt {

    @Final
    @Shadow
    protected String name;

    @Override
    public String radiance$getName() {
        return this.name;
    }
}
