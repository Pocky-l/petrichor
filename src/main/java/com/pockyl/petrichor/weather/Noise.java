package com.pockyl.petrichor.weather;

/**
 * Small deterministic hash and value noise. Everything that has to look the same for every player (the weather
 * schedule, the puddle layout) is derived from these functions and the synced game time or block coordinates.
 */
public final class Noise {
    private Noise() {
    }

    public static int hash(int x, int y, int seed) {
        int h = x * 0x27D4EB2D ^ y * 0x165667B1 ^ seed * 0x9E3779B9;
        h ^= h >>> 15;
        h *= 0x85EBCA6B;
        h ^= h >>> 13;
        h *= 0xC2B2AE35;
        h ^= h >>> 16;
        return h;
    }

    public static int hash(int x, int y, int z, int seed) {
        return hash(hash(x, y, seed), z, seed + 0x61C88647);
    }

    /** Uniform in [0, 1). */
    public static float unit(int x, int y, int seed) {
        return (hash(x, y, seed) >>> 8) * 0x1.0p-24F;
    }

    public static float unit(int x, int y, int z, int seed) {
        return (hash(x, y, z, seed) >>> 8) * 0x1.0p-24F;
    }

    public static float unit(long value, int seed) {
        return unit((int) value, (int) (value >>> 32), seed);
    }

    /** Smooth 2D value noise in [0, 1], one lattice cell per unit. */
    public static float value(double x, double z, int seed) {
        int x0 = (int) Math.floor(x);
        int z0 = (int) Math.floor(z);
        float fx = smooth((float) (x - x0));
        float fz = smooth((float) (z - z0));
        float a = unit(x0, z0, seed);
        float b = unit(x0 + 1, z0, seed);
        float c = unit(x0, z0 + 1, seed);
        float d = unit(x0 + 1, z0 + 1, seed);
        return lerp(lerp(a, b, fx), lerp(c, d, fx), fz);
    }

    /** Smooth 1D value noise in [0, 1], one lattice cell per unit. */
    public static float value(double t, int seed) {
        long i = (long) Math.floor(t);
        float f = smooth((float) (t - i));
        return lerp(unit(i, seed), unit(i + 1, seed), f);
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }
}
