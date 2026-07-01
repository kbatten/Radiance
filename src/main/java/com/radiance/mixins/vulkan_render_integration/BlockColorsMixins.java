package com.radiance.mixins.vulkan_render_integration;

import com.radiance.client.util.BlockColorEmissionProvider;
import com.radiance.mixin_related.extensions.vulkan_render_integration.IBlockColorsExt;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 26.2: {@code BlockColors} moved from one {@code BlockColorProvider} per block (an
 * {@code IdList} indexed by block raw id) to a {@code Map<Block, List<BlockTintSource>>}, where the
 * tint index is the source's position in the list. So the emission lookup shadows the public
 * {@code getTintSource(state, tintIndex)} accessor instead of indexing a shadowed {@code providers}
 * field. (The redstone-wire emission source registration remains disabled, as upstream.)
 */
@Mixin(BlockColors.class)
public abstract class BlockColorsMixins implements IBlockColorsExt {

    @Shadow
    @Nullable
    public abstract BlockTintSource getTintSource(BlockState state, int layer);

    @Override
    public float radiance$getEmission(BlockState state, @Nullable BlockAndTintGetter world,
        @Nullable BlockPos pos, int tintIndex) {
        BlockTintSource source = this.getTintSource(state, tintIndex);
        if (source instanceof BlockColorEmissionProvider emissionProvider) {
            return emissionProvider.getEmission(state, world, pos);
        } else {
            return 0.0F;
        }
    }
}
