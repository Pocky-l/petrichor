package com.pockyl.petrichor.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
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

    /** The kind of a top block plus the height of its flat top within the block (0..1). */
    public record Shape(SurfaceKind kind, float top, boolean soil) {
    }

    public static Shape classify(BlockState state) {
        return CACHE.computeIfAbsent(state, SurfaceKind::compute);
    }

    /** Tags decide some kinds, so the cache is dropped whenever tags are reloaded. */
    public static void clearCache() {
        CACHE.clear();
    }

    private static Shape compute(BlockState state) {
        if (state.getFluidState().is(FluidTags.WATER)) {
            return new Shape(WATER, 0.9F, false);
        }
        if (HotSurface.of(state) != null) {
            return new Shape(HOT, 1.0F, false);
        }
        if (state.is(BlockTags.LEAVES)) {
            return new Shape(LEAVES, 1.0F, false);
        }
        VoxelShape shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        if (shape.isEmpty()) {
            return new Shape(OTHER, 1.0F, false);
        }
        float top = (float) shape.max(Direction.Axis.Y);
        if (state.is(BlockTags.SAND) || state.is(BlockTags.ICE) || state.is(BlockTags.SNOW) || state.is(BlockTags.IMPERMEABLE)
                || state.is(Blocks.SNOW) || state.is(Blocks.POWDER_SNOW)) {
            return new Shape(OTHER, Math.min(top, 1.0F), false);
        }
        List<AABB> boxes = shape.toAabbs();
        if (boxes.size() != 1 || top > 1.0F) {
            return new Shape(OTHER, Math.min(top, 1.0F), false);
        }
        AABB box = boxes.getFirst();
        boolean full = box.minX <= 0.001 && box.minZ <= 0.001 && box.maxX >= 0.999 && box.maxZ >= 0.999;
        boolean soil = state.is(BlockTags.DIRT) || state.is(Blocks.FARMLAND) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.MUD);
        return new Shape(full ? GROUND : OTHER, top, soil);
    }
}
