package com.pockyl.petrichor.weather;

/**
 * How thick the low mist is, as in real life:
 * <ul>
 * <li>while it rains, drops evaporating into the cool air near the ground saturate it (precipitation fog): thickest in
 * a drizzle or a steady rain, thinner in a downpour, and a thunderstorm's wind sweeps most of it away;</li>
 * <li>after the rain the wet ground keeps breathing it out: it lingers in the evening, at night and at dawn, and the
 * sun burns it off by midday;</li>
 * <li>it gathers where the air stands still and damp - under the canopy of a forest, over water and swamps, in
 * hollows and valleys - and stays thin over open, high ground.</li>
 * </ul>
 */
public final class MistModel {
    /** Mist per block at its base at full strength: trees 30 blocks away in a misty forest are mostly hidden. */
    public static final float DENSITY = 0.03F;
    /** Mist while it rains, by rain level: drizzle, rain, downpour, thunderstorm. */
    private static final float[] DURING = {1.0F, 0.8F, 0.6F, 0.4F};

    private MistModel() {
    }

    /**
     * The mist per block at its base.
     *
     * @param rain      the rain falling around the listener, 0..1
     * @param level     the rain level, 0 (drizzle) .. 3 (thunderstorm)
     * @param wetness   how soaked the ground is, 0..1
     * @param sunHeight sine of the sun's elevation, -1..1
     * @param place     how much the surroundings hold mist (forest, water, hollows), 0..1
     * @param strength  config multiplier
     */
    public static float density(float rain, float level, float wetness, float sunHeight, float place, float strength) {
        float during = rain * during(level) * (0.85F + 0.15F * night(sunHeight));
        // After the rain: the wet ground steams, more in the cool of the night and the morning.
        float after = (float) Math.sqrt(Math.max(wetness, 0.0F)) * (1.0F - rain) * 0.8F * (0.25F + 0.75F * night(sunHeight));
        return DENSITY * strength * (during + after) * (0.3F + 0.7F * Math.clamp(place, 0.0F, 1.0F));
    }

    /** Mist during the rain at this rain level, 0..1. */
    public static float during(float level) {
        float t = Math.clamp(level, 0.0F, DURING.length - 1.0F);
        int i = Math.min((int) t, DURING.length - 2);
        return DURING[i] + (DURING[i + 1] - DURING[i]) * (t - i);
    }

    /** 1 at night and in the morning or evening, falling to 0 as the sun climbs high. */
    public static float night(float sunHeight) {
        float t = Math.clamp((sunHeight - 0.15F) / 0.45F, 0.0F, 1.0F);
        return 1.0F - t * t * (3.0F - 2.0F * t);
    }

    /**
     * How high the mist reaches (its scale height, blocks): up into the crowns in a forest, a low layer over open
     * ground.
     */
    public static float height(float cover, float canopy) {
        float forest = Math.clamp(canopy * 0.8F, 5.0F, 16.0F);
        return 5.0F + (forest - 5.0F) * Math.clamp(cover, 0.0F, 1.0F);
    }
}
