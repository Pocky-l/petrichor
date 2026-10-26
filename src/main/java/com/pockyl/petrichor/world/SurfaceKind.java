package com.pockyl.petrichor.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the rain lands on at the top of a column.
 */
public enum SurfaceKind {
    /** The chunk is not loaded. */
    UNKNOWN,
    /** A flat, full top that holds puddles and carries rivulets. */
    GROUND,
    /** Solid but uneven or absorbent (stairs, sand, glass...): water runs over it, no puddles. */
    OTHER,
    WATER,
    LEAVES,
    /** Lava, magma, lit campfires: rain sizzles (see {@link HotSurface}). */
    HOT;

    private static final Map<BlockState, Shape> CACHE = new ConcurrentHashMap<>();

    /**
     * The kind of a top block plus the height of its flat top within the block (0..1). Soil makes puddles muddy;
     * absorbent ground (grass, moss, podzol, mycelium) soaks the rain up, so only its real hollows hold puddles.
     */
    public record Shape(SurfaceKind kind, float top, boolean soil, boolean absorbent) {
    }

    public static Shape classify(BlockState state) {
        return CACHE.computeIfAbsent(state, SurfaceKind::compute);
    }

    /**
     * Whether a puddle may lie on the block at {@code ground}: flat ground with no fluid in it or on it, and, when the
     * ground is lower than a full block (a slab, a trapdoor of a jetty), no fluid right under it either - a puddle
     * must never be drawn as a second water surface over or under real water.
     */
    public static boolean holdsPuddle(BlockGetter level, BlockPos ground) {
        BlockState state = level.getBlockState(ground);
        Shape shape = classify(state);
        if (shape.kind() != GROUND || !state.getFluidState().isEmpty() || !level.getFluidState(ground.above()).isEmpty()) {
            return false;
        }
        return shape.top() >= 1.0F || level.getFluidState(ground.below()).isEmpty();
    }

    /** Tags decide some kinds, so the cache is dropped whenever tags are reloaded. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Shape compute(BlockState state) {
        if (state.getFluidState().is(FluidTags.WATER)) {
            return new Shape(WATER, 0.9F, false, false);
        }
        if (HotSurface.of(state) != null) {
            return new Shape(HOT, 1.0F, false, false);
        }
        if (state.is(BlockTags.LEAVES)) {
            return new Shape(LEAVES, 1.0F, false, false);
        }
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return new Shape(OTHER, 1.0F, false, false);
        }
        float top = (float) shape.max(Direction.Axis.Y);
        if (state.is(BlockTags.SAND) || state.is(BlockTags.ICE) || state.is(BlockTags.SNOW) || state.is(BlockTags.IMPERMEABLE)
                || state.is(Blocks.SNOW) || state.is(Blocks.POWDER_SNOW)) {
            return new Shape(OTHER, Math.min(top, 1.0F), false, false);
        }
        List<AABB> boxes = shape.toAabbs();
        if (boxes.size() != 1 || top > 1.0F) {
            return new Shape(OTHER, Math.min(top, 1.0F), false, false);
        }
        AABB box = boxes.getFirst();
        boolean full = box.minX <= 0.001 && box.minZ <= 0.001 && box.maxX >= 0.999 && box.maxZ >= 0.999;
        boolean soil = state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.MUD);
        boolean absorbent = state.getBlock() instanceof SnowyDirtBlock || state.is(Blocks.MOSS_BLOCK) || state.is(Blocks.ROOTED_DIRT);
        return new Shape(full ? GROUND : OTHER, top, soil, absorbent);
    }
}
