package com.pockyl.petrichor.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import com.pockyl.petrichor.compat.Seasons;
import com.pockyl.petrichor.compat.SubLevelRoofs;
import com.pockyl.petrichor.world.DropPath;
import com.pockyl.petrichor.world.SurfaceKind;

import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * Per-column facts the rain needs thousands of times a frame - where the rain stops (block and exact height), whether
 * it falls as rain or snow, and the light there - cached in a world-anchored ring of 128x128 columns. Entries expire
 * after a couple of seconds (spread out, so they are not all recomputed in the same tick). Roofs of moving structures
 * (Sable) are laid over the cached columns every tick.
 */
public final class Columns implements DropPath.Tops {
    public static final byte NONE = 0;
    public static final byte RAIN = 1;
    public static final byte SNOW = 2;

    private static final int SIZE = 128;
    private static final int MASK = SIZE - 1;
    private static final int EXPIRE = 50;

    private final long[] keys = new long[SIZE * SIZE];
    private final int[] stamps = new int[SIZE * SIZE];
    private final int[] heights = new int[SIZE * SIZE];
    private final float[] tops = new float[SIZE * SIZE];
    private final BlockState[] surfaces = new BlockState[SIZE * SIZE];
    private final byte[] precipitation = new byte[SIZE * SIZE];
    private final int[] lights = new int[SIZE * SIZE];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final SubLevelRoofs roofs = new SubLevelRoofs();
    private ClientLevel level;
    private int now;

    public Columns() {
        Arrays.fill(keys, Long.MIN_VALUE);
    }

    public void begin(ClientLevel level, int tick, double camX, double camZ) {
        if (this.level != level) {
            this.level = level;
            Arrays.fill(keys, Long.MIN_VALUE);
        }
        now = tick;
        roofs.update(level, camX, camZ, SIZE / 2, tick);
    }

    private int slot(int x, int z) {
        int s = (z & MASK) * SIZE + (x & MASK);
        long key = (long) x << 32 | (z & 0xFFFFFFFFL);
        if (keys[s] != key || now - stamps[s] > EXPIRE + (s & 15)) {
            fill(s, key, x, z);
        }
        return s;
    }

    private void fill(int s, long key, int x, int z) {
        keys[s] = key;
        stamps[s] = now;
        int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        heights[s] = h;
        pos.set(x, h - 1, z);
        BlockState state = level.getBlockState(pos);
        surfaces[s] = state;
        if (state.getFluidState().is(FluidTags.WATER)) {
            tops[s] = h - 1 + state.getFluidState().getHeight(level, pos);
        } else {
            tops[s] = h - 1 + SurfaceKind.classify(state).top();
        }
        pos.set(x, h, z);
        precipitation[s] = switch (Seasons.precipitationAt(level, pos)) {
            case NONE -> NONE;
            case RAIN -> RAIN;
            case SNOW -> SNOW;
        };
        lights[s] = LevelRenderer.getLightColor(level, pos);
    }

    /** The first y the rain cannot fall into. */
    public int height(int x, int z) {
        SubLevelRoofs.Roof roof = roof(x, z);
        return roof != null ? roof.height() : heights[slot(x, z)];
    }

    /** Exact height where the rain meets the column's surface: the top of a slab, of the water, of the block. */
    @Override
    public float top(int x, int z) {
        SubLevelRoofs.Roof roof = roof(x, z);
        return roof != null ? roof.top() : tops[slot(x, z)];
    }

    /** The block the rain lands on: the one under {@link #height}, or a moving structure's. */
    public BlockState surface(int x, int z) {
        SubLevelRoofs.Roof roof = roof(x, z);
        return roof != null ? roof.state() : surfaces[slot(x, z)];
    }

    /** The roof of a moving structure (Sable) when the rain lands on one in this column, otherwise null. */
    @Nullable
    public SubLevelRoofs.Roof roof(int x, int z) {
        SubLevelRoofs.Roof roof = roofs.at(x, z);
        return roof != null && roof.top() > tops[slot(x, z)] ? roof : null;
    }

    public byte precipitation(int x, int z) {
        return precipitation[slot(x, z)];
    }

    /** Packed light just above the column's surface. */
    public int light(int x, int z) {
        return lights[slot(x, z)];
    }
}
