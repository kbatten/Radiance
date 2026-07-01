package com.radiance.client.util;

import com.mojang.datafixers.util.Pair;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 26.2: {@code BlockColorProvider} -> {@code BlockTintSource}. Its sole abstract method is
 * {@code color(BlockState)}, and the tint index is now a source's position in {@code BlockColors}'
 * per-block list rather than a method parameter. This still bundles color + emission (as the
 * redstone-wire emission source did), exposing the color through {@code BlockTintSource#color} /
 * {@code #colorInWorld} and the emission through {@link #getEmission}. ({@code net.minecraft.util.Pair}
 * -> {@code com.mojang.datafixers.util.Pair}: getLeft/getRight -> getFirst/getSecond.)
 */
public interface BlockColorEmissionProvider extends BlockTintSource {

    Pair<Integer, Float> getColorEmission(BlockState state, @Nullable BlockAndTintGetter world,
        @Nullable BlockPos pos);

    @Override
    default int color(BlockState state) {
        return getColorEmission(state, null, null).getFirst();
    }

    @Override
    default int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
        return getColorEmission(state, level, pos).getFirst();
    }

    default float getEmission(BlockState state, @Nullable BlockAndTintGetter world,
        @Nullable BlockPos pos) {
        return getColorEmission(state, world, pos).getSecond();
    }
}
