package com.pockyl.petrichor.weather;

/**
 * How soaked the ground is, 0..1. Puddles grow with it and runoff starts once the ground is wet enough; after the rain
 * the ground dries slowly, faster in daylight.
 */
public final class Wetness {
    /** Ticks for normal rain to soak dry ground to its cap. */
    private static final float FILL_TICKS = 20.0F * 150.0F;
    private static final float DRY_TICKS_DAY = 20.0F * 240.0F;
    private static final float DRY_TICKS_NIGHT = 20.0F * 600.0F;
    /** Runoff starts at this wetness. */
    public static final float RUNOFF_START = 0.3F;

    private Wetness() {
    }

    /**
     * One tick of soaking or drying.
     *
     * @param rain   the vanilla rain level, 0..1
     * @param level  the current rain level ({@link RainType#mix}), or a negative value when it does not rain
     * @param day    whether the sun is up
     * @param fill   fill speed multiplier from the config
     * @param drying drying speed multiplier from the config
     */
    public static float step(float wetness, float rain, float level, boolean day, double fill, double drying) {
        if (level >= 0.0F && rain > 0.2F) {
            // Soaks towards the cap of this rain; ground wetter than that (after a downpour) stays as it is.
            float cap = RainType.mix(level, type -> type.wetnessCap);
            if (wetness < cap) {
                float rate = RainType.mix(level, type -> type.wetnessRate);
                return Math.min(cap, wetness + (float) (rain * rate * fill / FILL_TICKS));
            }
            return wetness;
        }
        float dry = (float) (drying / (day ? DRY_TICKS_DAY : DRY_TICKS_NIGHT));
        return Math.max(0.0F, wetness - dry);
    }

    /** How strongly water runs off the ground, 0..1: needs both rain and soaked ground. */
    public static float runoff(float wetness, float rainIntensity) {
        float soaked = Math.clamp((wetness - RUNOFF_START) / (1.0F - RUNOFF_START), 0.0F, 1.0F);
        return Math.clamp(soaked * rainIntensity * 1.4F, 0.0F, 1.0F);
    }
}
