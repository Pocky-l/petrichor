package com.pockyl.petrichor.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;

/**
 * Per-column facts the rain needs thousands of times a frame - where the rain stops, whether it falls as rain or snow,
 * and the light there - cached in a world-anchored ring of 128x128 columns. Entries expire after a couple of seconds
 * (spread out, so they are not all recomputed in the same tick).
 */
public final class Columns {
    public static final byte NONE = 0;
    public static final byte RAIN = 1;
    public static final byte SNOW = 2;

    private static final int SIZE = 128;
    private static final int MASK = SIZE - 1;
    private static final int EXPIRE = 50;

    private final long[] keys = new long[SIZE * SIZE];
    private final int[] stamps = new int[SIZE * SIZE];
    private final int[] heights = new int[SIZE * SIZE];
    private final byte[] precipitation = new byte[SIZE * SIZE];
    private final int[] lights = new int[SIZE * SIZE];
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private ClientLevel level;
    private int now;

    public Columns() {
        Arrays.fill(keys, Long.MIN_VALUE);
    }

    public void begin(ClientLevel level, int tick) {
        if (this.level != level) {
            this.level = level;
            Arrays.fill(keys, Long.MIN_VALUE);
        }
        now = tick;
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
        pos.set(x, h, z);
        Biome biome = level.getBiome(pos).value();
        if (!biome.hasPrecipitation()) {
            precipitation[s] = NONE;
        } else {
            precipitation[s] = biome.getPrecipitationAt(pos) == Biome.Precipitation.SNOW ? SNOW : RAIN;
        }
        lights[s] = LevelRenderer.getLightColor(level, pos);
    }

    /** The first y the rain cannot fall into. */
    public int height(int x, int z) {
        return heights[slot(x, z)];
    }

    public byte precipitation(int x, int z) {
        return precipitation[slot(x, z)];
    }

    /** Packed light just above the column's surface. */
    public int light(int x, int z) {
        return lights[slot(x, z)];
    }
}
