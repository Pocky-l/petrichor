package com.pockyl.petrichor.weather;

import java.util.Locale;

/**
 * The kinds of rain. Each carries the look, sound and effect on the ground of that rain; the client blends between
 * them so that a change of type never pops.
 *
 * <p>Speeds are in blocks per tick, sizes in blocks.
 */
public enum RainType {
    // density, fall, streak, width, alpha, wind, gust, fogDistance, splash, wetCap, wetRate, light, medium, heavy
    DRIZZLE(0.9F, 0.16F, 0.09F, 0.006F, 0.2F, 0.05F, 0.1F, 170.0F, 0.12F, 0.45F, 0.5F, 1.0F, 0.0F, 0.0F),
    RAIN(0.9F, 0.55F, 0.45F, 0.014F, 0.38F, 0.09F, 0.3F, 160.0F, 0.9F, 0.80F, 1.0F, 0.35F, 1.0F, 0.0F),
    DOWNPOUR(2.6F, 0.95F, 1.1F, 0.024F, 0.55F, 0.18F, 0.6F, 70.0F, 2.6F, 1.0F, 2.2F, 0.0F, 0.45F, 1.0F),
    THUNDERSTORM(2.2F, 1.0F, 1.1F, 0.024F, 0.55F, 0.34F, 0.9F, 90.0F, 2.2F, 1.0F, 1.8F, 0.0F, 0.55F, 0.9F);

    private static final RainType[] VALUES = values();

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
    /** Distance the fog closes in to at full intensity. */
    public final float fogDistance;
    /** Relative number of splashes on the ground. */
    public final float splash;
    /** The wetness this rain soaks the ground to, 0..1. */
    public final float wetnessCap;
    /** How fast it soaks the ground, relative to normal rain. */
    public final float wetnessRate;
    /** Volumes of the light, medium and heavy rain sound loops. */
    public final float soundLight;
    public final float soundMedium;
    public final float soundHeavy;

    RainType(float density, float fallSpeed, float streakLength, float streakWidth, float alpha, float wind, float gustiness,
            float fogDistance, float splash, float wetnessCap, float wetnessRate, float soundLight, float soundMedium, float soundHeavy) {
        this.density = density;
        this.fallSpeed = fallSpeed;
        this.streakLength = streakLength;
        this.streakWidth = streakWidth;
        this.alpha = alpha;
        this.wind = wind;
        this.gustiness = gustiness;
        this.fogDistance = fogDistance;
        this.splash = splash;
        this.wetnessCap = wetnessCap;
        this.wetnessRate = wetnessRate;
        this.soundLight = soundLight;
        this.soundMedium = soundMedium;
        this.soundHeavy = soundHeavy;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String translationKey() {
        return "petrichor.rain_type." + id();
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
