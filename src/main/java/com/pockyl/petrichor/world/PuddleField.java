package com.pockyl.petrichor.world;

import net.minecraft.util.Mth;

import com.pockyl.petrichor.weather.Noise;

/**
 * Where puddles form. Every flat ground column gets a "puddle field" value 0..1: high in hollows (anything below the
 * brim of a dip it lies in), at the foot of walls and in closed dips, low next to drops where water runs away, plus two
 * octaves of noise so open ground gets the scattered puddles of real uneven earth. A puddle covers the ground where the field exceeds a threshold that falls
 * as the ground gets wetter ({@link #threshold}), so puddles grow from the deepest spots outwards and shrink back the
 * same way while drying.
 */
public final class PuddleField {
    private static final int SEED_LARGE = 0x9D1E_0001;
    private static final int SEED_SMALL = 0x9D1E_0002;
    private static final float CLOSED_BONUS = 0.32F;
    /** Per block of water standing in a filled hollow. */
    private static final float DEPTH_BONUS = 0.45F;

    private PuddleField() {
    }

    /** The field of every cell of the grid (0 for anything that is not flat ground). */
    public static float[] compute(SurfaceGrid grid, RunoffSolver runoff) {
        float[] field = new float[grid.size * grid.size];
        for (int lz = 0; lz < grid.size; lz++) {
            for (int lx = 0; lx < grid.size; lx++) {
                int i = grid.index(lx, lz);
                if (grid.kind[i] == SurfaceKind.GROUND) {
                    field[i] = cell(grid, runoff, lx, lz);
                }
            }
        }
        return field;
    }

    private static float cell(SurfaceGrid grid, RunoffSolver runoff, int lx, int lz) {
        int i = grid.index(lx, lz);
        int x = grid.x0 + lx;
        int z = grid.z0 + lz;
        float noise = Noise.value(x / 9.0, z / 9.0, SEED_LARGE) * 0.65F + Noise.value(x / 3.7, z / 3.7, SEED_SMALL) * 0.35F;
        float concavity = 0.0F;
        int counted = 0;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int nx = lx + dx;
                int nz = lz + dz;
                if (nx < 0 || nz < 0 || nx >= grid.size || nz >= grid.size) {
                    continue;
                }
                int j = grid.index(nx, nz);
                if (!grid.known(j)) {
                    continue;
                }
                counted++;
                int dh = grid.height[j] - grid.height[i];
                if (dh > 0) {
                    concavity += 1.0F;
                } else if (dh < 0) {
                    concavity -= 1.5F;
                }
            }
        }
        if (counted > 0) {
            concavity /= counted;
        }
        float field = noise * 0.8F + concavity * 0.5F;
        if (runoff != null) {
            if (runoff.depth[i] > 0) {
                field += DEPTH_BONUS * Math.min(runoff.depth[i], 2);
            } else if (runoff.closed[i]) {
                field += CLOSED_BONUS;
            }
        }
        return Mth.clamp(field, 0.0F, 1.0F);
    }

    /**
     * The field value above which the ground is under water.
     *
     * @param coverage the puddle amount multiplier from the config
     */
    public static float threshold(float wetness, float coverage) {
        return 1.05F - wetness * 0.62F * coverage;
    }

    /** Puddle cover 0..1 for a field value without the fine detail noise the shader adds. */
    public static float cover(float field, float wetness, float coverage) {
        float t = threshold(wetness, coverage);
        return Mth.clamp((field - t + 0.04F) / 0.08F, 0.0F, 1.0F);
    }
}
