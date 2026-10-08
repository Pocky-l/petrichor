package com.pockyl.petrichor.world;

import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What rain sounds like when it lands on a block. Decided by the block's sound type, so blocks of other mods sound
 * right as long as they say what they are made of.
 */
public enum SoundMaterial {
    /** Grass, dirt, sand, moss: the soft hush of the open ground. */
    SOFT,
    /** Stone, bricks, concrete, terracotta: a crisp patter. */
    HARD,
    WOOD,
    METAL,
    GLASS,
    /** Wool and carpets: a dull thud, like a tent. */
    FABRIC,
    LEAVES,
    WATER,
    /** Rain on ground covered by a puddle. Never returned by {@link #of}: puddles are not blocks. */
    PUDDLE,
    /** Snow and ice soak the drops up silently; lava hisses them away. */
    SILENT;

    private static final Map<BlockState, SoundMaterial> CACHE = new ConcurrentHashMap<>();

    public static SoundMaterial of(BlockState state) {
        return CACHE.computeIfAbsent(state, SoundMaterial::compute);
    }

    /** Tags decide some materials, so the cache is dropped whenever tags are reloaded. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static SoundMaterial compute(BlockState state) {
        if (state.getFluidState().is(FluidTags.WATER)) {
            return WATER;
        }
        if (state.getFluidState().is(FluidTags.LAVA) || state.is(BlockTags.SNOW) || state.is(BlockTags.ICE) || state.is(Blocks.SNOW)
                || state.is(Blocks.POWDER_SNOW)) {
            return SILENT;
        }
        if (state.is(BlockTags.LEAVES)) {
            return LEAVES;
        }
        if (state.is(BlockTags.WOOL) || state.is(BlockTags.WOOL_CARPETS)) {
            return FABRIC;
        }
        SoundType sound = state.getSoundType();
        if (sound == SoundType.GLASS) {
            return GLASS;
        }
        if (sound == SoundType.WOOD || sound == SoundType.NETHER_WOOD || sound == SoundType.CHERRY_WOOD || sound == SoundType.BAMBOO_WOOD
                || sound == SoundType.LADDER || sound == SoundType.SCAFFOLDING || sound == SoundType.CHISELED_BOOKSHELF
                || sound == SoundType.HANGING_SIGN || sound == SoundType.NETHER_WOOD_HANGING_SIGN
                || sound == SoundType.CHERRY_WOOD_HANGING_SIGN || sound == SoundType.BAMBOO_WOOD_HANGING_SIGN || state.is(BlockTags.PLANKS)
                || state.is(BlockTags.LOGS) || state.is(BlockTags.WOODEN_SLABS) || state.is(BlockTags.WOODEN_STAIRS)) {
            return WOOD;
        }
        if (sound == SoundType.METAL || sound == SoundType.COPPER || sound == SoundType.CHAIN || sound == SoundType.ANVIL
                || sound == SoundType.LANTERN || sound == SoundType.NETHERITE_BLOCK) {
            return METAL;
        }
        if (sound == SoundType.WOOL) {
            return FABRIC;
        }
        if (sound == SoundType.GRASS || sound == SoundType.GRAVEL || sound == SoundType.SAND || sound == SoundType.ROOTED_DIRT
                || sound == SoundType.MUD || sound == SoundType.MOSS || sound == SoundType.MOSS_CARPET || sound == SoundType.AZALEA
                || sound == SoundType.FLOWERING_AZALEA || sound == SoundType.CROP || sound == SoundType.HARD_CROP
                || sound == SoundType.SWEET_BERRY_BUSH || sound == SoundType.WET_GRASS || sound == SoundType.SOUL_SAND
                || sound == SoundType.SOUL_SOIL || sound == SoundType.NYLIUM || sound == SoundType.FUNGUS || sound == SoundType.ROOTS
                || sound == SoundType.VINE || sound == SoundType.BIG_DRIPLEAF || sound == SoundType.SMALL_DRIPLEAF
                || sound == SoundType.HANGING_ROOTS || sound == SoundType.PINK_PETALS || sound == SoundType.SPORE_BLOSSOM
                || sound == SoundType.CAVE_VINES || sound == SoundType.GLOW_LICHEN || sound == SoundType.SCULK
                || sound == SoundType.SCULK_VEIN || sound == SoundType.SLIME_BLOCK || sound == SoundType.HONEY_BLOCK
                || sound == SoundType.CORAL_BLOCK || sound == SoundType.WART_BLOCK || sound == SoundType.SHROOMLIGHT
                || sound == SoundType.FROGSPAWN || sound == SoundType.MANGROVE_ROOTS || sound == SoundType.MUDDY_MANGROVE_ROOTS
                || state.is(BlockTags.DIRT) || state.is(BlockTags.SAND) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH)
                || state.is(Blocks.HAY_BLOCK)) {
            return SOFT;
        }
        return HARD;
    }
}
