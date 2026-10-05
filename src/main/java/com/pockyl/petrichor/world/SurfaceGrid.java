package com.pockyl.petrichor.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

/**
 * The rain-facing surface of a square of columns: for every column the first free block above it, the height of the
 * surface and what it is made of. Puddles and runoff are computed on this snapshot, never on the live level.
 */
public final class SurfaceGrid {
    public static final int UNKNOWN_HEIGHT = Integer.MIN_VALUE;

    public final int x0;
    public final int z0;
    public final int size;
    /** The first free y above the column, as the motion-blocking heightmap reports it. */
    public final int[] height;
    /** World y of the surface the rain lands on. */
    public final float[] top;
    public final SurfaceKind[] kind;
    /** Dirt-like ground: puddles there are muddy. */
    public final boolean[] soil;

    public SurfaceGrid(int x0, int z0, int size) {
        this.x0 = x0;
        this.z0 = z0;
        this.size = size;
        int n = size * size;
        height = new int[n];
        top = new float[n];
        kind = new SurfaceKind[n];
        soil = new boolean[n];
        Arrays.fill(height, UNKNOWN_HEIGHT);
        Arrays.fill(kind, SurfaceKind.UNKNOWN);
    }

    public int index(int localX, int localZ) {
        return localZ * size + localX;
    }

    public boolean known(int i) {
        return kind[i] != SurfaceKind.UNKNOWN;
    }

    /** Sets a column by hand; used by tests and by {@link #sample}. */
    public void set(int localX, int localZ, int height, float top, SurfaceKind kind, boolean soil) {
        int i = index(localX, localZ);
        this.height[i] = height;
        this.top[i] = top;
        this.kind[i] = kind;
        this.soil[i] = soil;
    }

    public static SurfaceGrid sample(LevelReader level, int x0, int z0, int size) {
        SurfaceGrid grid = new SurfaceGrid(x0, z0, size);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int minY = level.getMinBuildHeight();
        for (int lz = 0; lz < size; lz++) {
            int z = z0 + lz;
            for (int lx = 0; lx < size; lx++) {
                int x = x0 + lx;
                if (!level.hasChunk(x >> 4, z >> 4)) {
                    continue;
                }
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                if (h <= minY) {
                    continue;
                }
                pos.set(x, h - 1, z);
                SurfaceKind.Shape shape = SurfaceKind.classify(level.getBlockState(pos));
                grid.set(lx, lz, h, h - 1 + shape.top(), shape.kind(), shape.soil());
            }
        }
        return grid;
    }
}
