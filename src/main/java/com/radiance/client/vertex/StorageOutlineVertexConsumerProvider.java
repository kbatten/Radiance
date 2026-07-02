package com.radiance.client.vertex;

import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.Optional;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.util.ARGB;

/**
 * 26.2: no longer a {@code VertexConsumerProvider} (removed) -- a thin colored-outline wrapper over
 * {@link StorageVertexConsumerProvider}, keyed on {@link RenderType}. The old union path
 * ({@code VertexConsumers.union}) is gone in 26.2 and this class is currently only referenced by
 * disabled (commented-out) outline code in {@code EntityProxy}; the affected-outline branch returns
 * the primary consumer until the union is reintroduced with a 26.2 replacement.
 */
@Environment(EnvType.CLIENT)
public class StorageOutlineVertexConsumerProvider {

    private final StorageVertexConsumerProvider parent;
    private int red = 255;
    private int green = 255;
    private int blue = 255;
    private int alpha = 255;

    public StorageOutlineVertexConsumerProvider(StorageVertexConsumerProvider parent) {
        this.parent = parent;
    }

    public VertexConsumer getBuffer(RenderType renderType) {
        if (renderType.isOutline()) {
            VertexConsumer vertexConsumer = this.parent.getBuffer(renderType);
            return new OutlineVertexConsumer(vertexConsumer, this.red, this.green, this.blue,
                this.alpha);
        } else {
            VertexConsumer vertexConsumer = this.parent.getBuffer(renderType);
            Optional<RenderType> optional = renderType.outline();
            if (optional.isPresent()) {
                // 26.2: VertexConsumers.union removed -- would combine the outline copy with the
                // primary geometry. Returns the primary consumer until a replacement is wired in.
                return vertexConsumer;
            } else {
                return vertexConsumer;
            }
        }
    }

    public void setColor(int red, int green, int blue, int alpha) {
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    @Environment(EnvType.CLIENT)
    record OutlineVertexConsumer(VertexConsumer delegate, int color) implements VertexConsumer {

        public OutlineVertexConsumer(VertexConsumer delegate, int red, int green, int blue,
            int alpha) {
            this(delegate, ARGB.color(alpha, red, green, blue));
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            this.delegate.addVertex(x, y, z).setColor(this.color);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            return this;
        }

        @Override
        public VertexConsumer setColor(int color) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            this.delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }

        @Override
        public VertexConsumer setLineWidth(float width) {
            return this;
        }
    }
}
