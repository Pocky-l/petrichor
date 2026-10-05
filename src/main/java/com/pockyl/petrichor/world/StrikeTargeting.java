package com.pockyl.petrichor.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.List;
import java.util.Optional;

/**
 * Where an extra storm strike lands. Lightning seeks the shortest path to the ground, so a lightning rod in range wins,
 * otherwise the tallest of several random columns - lone trees, towers and hilltops get hit, open ground rarely.
 */
public final class StrikeTargeting {
    /** Same reach as the vanilla lightning rod. */
    public static final int ROD_RANGE = 128;
    private static final int CANDIDATES = 7;

    private StrikeTargeting() {
    }

    /** A strike position somewhere within {@code radius} of {@code center}, or empty if no candidate sees the sky. */
    public static Optional<BlockPos> pick(ServerLevel level, BlockPos center, int radius, boolean attractToTall, RandomSource random) {
        BlockPos first = surface(level, center.offset(random.nextInt(radius * 2 + 1) - radius, 0,
                random.nextInt(radius * 2 + 1) - radius));
        Optional<BlockPos> rod = findRod(level, first);
        if (rod.isPresent()) {
            return rod;
        }
        if (!attractToTall) {
            return level.isRainingAt(first) ? Optional.of(first) : Optional.empty();
        }
        BlockPos best = null;
        for (int c = 0; c < CANDIDATES; c++) {
            BlockPos candidate = c == 0 ? first : surface(level, first.offset(random.nextInt(33) - 16, 0, random.nextInt(33) - 16));
            if (!level.isRainingAt(candidate)) {
                continue;
            }
            if (best == null || candidate.getY() > best.getY()) {
                best = candidate;
            }
        }
        return Optional.ofNullable(best);
    }

    /** The tallest of the given columns, as a strike position on top of it. */
    public static BlockPos tallest(ServerLevel level, List<BlockPos> columns) {
        BlockPos best = null;
        for (BlockPos column : columns) {
            BlockPos candidate = surface(level, column);
            if (best == null || candidate.getY() > best.getY()) {
                best = candidate;
            }
        }
        return best;
    }

    private static BlockPos surface(ServerLevel level, BlockPos pos) {
        return level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
    }

    private static Optional<BlockPos> findRod(ServerLevel level, BlockPos pos) {
        return level.getPoiManager()
                .findClosest(holder -> holder.is(PoiTypes.LIGHTNING_ROD),
                        rod -> rod.getY() == level.getHeight(Heightmap.Types.WORLD_SURFACE, rod.getX(), rod.getZ()) - 1,
                        pos, ROD_RANGE, PoiManager.Occupancy.ANY)
                .map(BlockPos::above);
    }
}
