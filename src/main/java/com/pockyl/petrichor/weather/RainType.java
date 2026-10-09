package com.pockyl.petrichor.weather;

import java.util.Locale;

/**
 * The kinds of rain, in order of strength. Each carries the look, sound and effect on the ground of that rain.
 *
 * <p>The weather moves along a continuous <em>rain level</em>: 0 is a drizzle, 1 rain, 2 a downpour, 3 a thunderstorm,
 * and levels in between blend the two neighbouring types ({@link #mix}). A rain spell climbs and eases along this scale
 * step by step, so it never jumps from a drizzle straight into a downpour.
 *
 * <p>Speeds are in blocks per tick, sizes in blocks.
 */
public enum RainType {
    // density, fall, streak, width, alpha, wind, gust, visibility, splash, wetCap, wetRate, heaviness
    DRIZZLE(1.1F, 0.2F, 0.2F, 0.01F, 0.42F, 0.05F, 0.1F, 520.0F, 0.0F, 0.45F, 0.5F, 0.15F),
    RAIN(0.9F, 0.55F, 0.45F, 0.014F, 0.38F, 0.09F, 0.3F, 340.0F, 0.9F, 0.80F, 1.0F, 0.5F),
    DOWNPOUR(2.6F, 0.95F, 1.1F, 0.024F, 0.55F, 0.18F, 0.6F, 170.0F, 2.6F, 1.0F, 2.2F, 1.0F),
    THUNDERSTORM(2.2F, 1.0F, 1.1F, 0.024F, 0.55F, 0.34F, 0.9F, 220.0F, 2.2F, 1.0F, 1.8F, 0.9F);

    private static final RainType[] VALUES = values();
    /** The highest rain level: a thunderstorm. */
    public static final float MAX_LEVEL = VALUES.length - 1;

    /** Relative number of drops. */
    public final float density;
    public final float fallSpeed;
    public final float streakLength;
    public final float streakWidth;
    public final float alpha;
    /** Base horizontal wind speed. */
    public final float wind;
    /** How strongly intensity and wind vary over time, 0..1. */
    public final float gustiness;
    /** Blocks through which the rain's haze hides the land at full intensity (95% haze). */
    public final float visibility;
    /** Relative number of splashes on the ground. */
    public final float splash;
    /** The wetness this rain soaks the ground to, 0..1. */
    public final float wetnessCap;
    /** How fast it soaks the ground, relative to normal rain. */
    public final float wetnessRate;
    /** How heavy this rain sounds, 0.15 for a drizzle .. 1 for a downpour: picks and mixes the recordings. */
    public final float heaviness;

    RainType(float density, float fallSpeed, float streakLength, float streakWidth, float alpha, float wind, float gustiness,
            float visibility, float splash, float wetnessCap, float wetnessRate, float heaviness) {
        this.density = density;
        this.fallSpeed = fallSpeed;
        this.streakLength = streakLength;
        this.streakWidth = streakWidth;
        this.alpha = alpha;
        this.wind = wind;
        this.gustiness = gustiness;
        this.visibility = visibility;
        this.splash = splash;
        this.wetnessCap = wetnessCap;
        this.wetnessRate = wetnessRate;
        this.heaviness = heaviness;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "petrichor.rain_type." + id();
    }

    /** The level at which this type falls in its pure form. */
    public float level() {
        return ordinal();
    }

    /** The type closest to a rain level. */
    public static RainType at(float level) {
        return VALUES[Math.clamp(Math.round(level), 0, VALUES.length - 1)];
    }

    /** A parameter at a rain level, blended between the two types around it. */
    public static float mix(float level, Param param) {
        float clamped = Math.clamp(level, 0.0F, MAX_LEVEL);
        int lower = Math.min((int) clamped, VALUES.length - 2);
        float a = param.of(VALUES[lower]);
        return a + (param.of(VALUES[lower + 1]) - a) * (clamped - lower);
    }

    /** One of the parameters of a type. */
    @FunctionalInterface
    public interface Param {
        float of(RainType type);
    }

    /** The type with this ordinal, or {@code null} for -1 or an unknown value. */
    public static RainType byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    public static RainType byId(String id) {
        for (RainType type : VALUES) {
            if (type.id().equals(id)) {
                return type;
            }
        }
        return null;
    }
}
