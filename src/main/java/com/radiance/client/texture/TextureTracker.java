package com.radiance.client.texture;

import com.mojang.blaze3d.GpuFormat;
import com.radiance.client.constant.VulkanConstants;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

public class TextureTracker {

    // 26.2: GpuTextures (and thus GL ids) are created lazily by the GpuDevice, so we
    // can no longer capture an int GL id at registration time. Track the texture and
    // resolve its GL id lazily via ((IAbstractTextureExt) tex).radiance$getGlIDUnsafe().
    public static Map<Identifier, AbstractTexture> id2Texture = new ConcurrentHashMap<>();
    public static Map<Integer, Texture> GLID2Texture = new ConcurrentHashMap<>();
    public static Map<Integer, Integer> GLID2SpecularGLID = new ConcurrentHashMap<>();
    public static Map<Integer, Integer> GLID2NormalGLID = new ConcurrentHashMap<>();
    public static Map<Integer, Integer> GLID2FlagGLID = new ConcurrentHashMap<>();

    public record Texture(int width, int height, int channel, VulkanConstants.VkFormat format,
                          int maxLayer) {

        public Texture {
            if (width <= 0 || height <= 0 || channel <= 0 || maxLayer < 0) {
                throw new IllegalArgumentException(
                    "Invalid texture width, height, channel, or maxLayer: " + width + ", " + height
                        + ", " + channel + ", " + maxLayer);
            }
        }

        // 26.2: built from com.mojang.blaze3d.GpuFormat (was NativeImage.InternalFormat).
        public Texture(int width, int height, GpuFormat format, int maxLayer) {
            this(width, height, getChannel(format), getFormat(format), maxLayer);
        }

        private static int getChannel(GpuFormat format) {
            return switch (format) {
                case RGBA8_UNORM -> 4;
                case RGB8_UNORM -> 3;
                case RG8_UNORM -> 2;
                case R8_UNORM -> 1;
                default -> throw new IllegalArgumentException("Unsupported GPU format: " + format);
            };
        }

        private static VulkanConstants.VkFormat getFormat(GpuFormat format) {
            return switch (format) {
                case RGBA8_UNORM -> VulkanConstants.VkFormat.VK_FORMAT_R8G8B8A8_UNORM;
                case RGB8_UNORM -> VulkanConstants.VkFormat.VK_FORMAT_R8G8B8_UNORM;
                case RG8_UNORM -> VulkanConstants.VkFormat.VK_FORMAT_R8G8_UNORM;
                case R8_UNORM -> VulkanConstants.VkFormat.VK_FORMAT_R8_UNORM;
                default -> throw new IllegalArgumentException("Unsupported GPU format: " + format);
            };
        }
    }
}
