package com.pockyl.petrichor.world;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * Blocks hot enough to boil the rain away: drops landing on them hiss and turn to steam.
 */
public enum HotSurface {
    /** Lava, still or flowing: a sharp hiss, and a lake of it steams as a whole. */
    LAVA,
    MAGMA,
    /** A lit campfire or soul campfire: the softer hiss of water on embers. */
    CAMPFIRE;

    /** What kind of hot block {@code state} is, or null when rain just lands on it. */
    @Nullable
    public static HotSurface of(BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return LAVA;
        }
        if (state.is(Blocks.MAGMA_BLOCK)) {
            return MAGMA;
        }
        // Waterlogged campfires are put out, so the LIT check covers them too.
        if (state.is(BlockTags.CAMPFIRES) && state.getValue(CampfireBlock.LIT)) {
            return CAMPFIRE;
        }
        return null;
    }
}
