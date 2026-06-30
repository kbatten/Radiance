package com.radiance.mixin_related.extensions.vanilla_resource_tracker;

import com.mojang.blaze3d.font.GlyphInfo;

public interface IRenderableGlyphExt extends GlyphInfo {

    void upload(int id, int x, int y);
}
