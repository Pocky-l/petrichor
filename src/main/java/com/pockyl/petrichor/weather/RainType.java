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
 *
 * <p>The types follow real rain of 1, 5, 25 and 50 mm/h (one block = one metre):
 * <ul>
 *   <li>density = the number of drops large enough to be seen (over 0.5 mm) per cubic metre, from the Marshall-Palmer
 *   drop size distribution: about 250, 640, 1360 and 1790 - relative 0.48 : 1.22 : 2.6 : 3.42;</li>
 *   <li>fall speed = the terminal velocity of the typical drop (median 0.9, 1.3, 1.8 and 2 mm, Gunn and Kinzer: 3.7,
 *   5.0, 6.1 and 6.6 m/s) times 3: drawn drops have no motion blur, and at real speed rain looks like it floats;</li>
 *   <li>streak length grows with the speed (a fixed exposure), width with the drop size;</li>
 *   <li>splashes follow the drops reaching the ground (drops x speed), wetting follows them too, so puddles grow as
 *   fast as the rain looks.</li>
 * </ul>
 */
public enum RainType {
    // density, fall, streak, width, alpha, wind, gust, visibility, splash, wetCap, wetRate, heaviness
    DRIZZLE(0.48F, 0.55F, 0.45F, 0.011F, 0.36F, 0.05F, 0.1F, 520.0F, 0.3F, 0.35F, 0.3F, 0.15F),
    RAIN(1.22F, 0.75F, 0.7F, 0.016F, 0.42F, 0.09F, 0.3F, 340.0F, 1.0F, 0.80F, 1.0F, 0.5F),
    DOWNPOUR(2.6F, 0.92F, 1.05F, 0.024F, 0.55F, 0.18F, 0.6F, 170.0F, 2.6F, 1.0F, 2.6F, 1.0F),
    THUNDERSTORM(3.42F, 0.99F, 1.15F, 0.026F, 0.55F, 0.34F, 0.9F, 140.0F, 3.4F, 1.0F, 3.5F, 1.0F);

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
