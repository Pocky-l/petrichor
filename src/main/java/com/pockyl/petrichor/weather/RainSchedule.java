package com.pockyl.petrichor.weather;

/**
 * The natural course of the weather, derived only from the game time (which the server syncs to every client), so
 * all players see the same rain even when the server does not have this mod.
 *
 * <ul>
 *   <li>the type of rain is rolled for every window of {@link #WINDOW} ticks, so a long rain spell changes character
 *   a few times (a drizzle turning into a downpour and back);</li>
 *   <li>intensity breathes with slow and fast noise - the slow part is a passing heavier cloud, the fast one gusts;</li>
 *   <li>wind turns slowly over minutes and pulses with the gusts.</li>
 * </ul>
 */
public final class RainSchedule {
    /** Five minutes. */
    public static final int WINDOW = 6000;
    private static final int TYPE_SEED = 0x5EED_0001;
    private static final int GUST_SEED = 0x5EED_0002;
    private static final int WIND_ANGLE_SEED = 0x5EED_0003;
    private static final int WIND_SPEED_SEED = 0x5EED_0004;

    private RainSchedule() {
    }

    /**
     * The type of rain at this time if nobody overrides it.
     *
     * @param weights relative chances of drizzle, rain and downpour; thunderstorms come from vanilla thunder
     */
    public static RainType naturalType(long gameTime, boolean thundering, int drizzle, int rain, int downpour) {
        if (thundering) {
            return RainType.THUNDERSTORM;
        }
        int total = Math.max(0, drizzle) + Math.max(0, rain) + Math.max(0, downpour);
        if (total <= 0) {
            return RainType.RAIN;
        }
        long window = Math.floorDiv(gameTime, WINDOW);
        float roll = Noise.unit(window, TYPE_SEED) * total;
        if (roll < Math.max(0, drizzle)) {
            return RainType.DRIZZLE;
        }
        if (roll < Math.max(0, drizzle) + Math.max(0, rain)) {
            return RainType.RAIN;
        }
        return RainType.DOWNPOUR;
    }

    /**
     * Intensity multiplier around 1 for the given gustiness: slow swells over ~40 s plus quick gusts over ~4 s.
     */
    public static float gust(double time, float gustiness) {
        float slow = Noise.value(time / 800.0, GUST_SEED) - 0.5F;
        float fast = Noise.value(time / 80.0, GUST_SEED + 1) - 0.5F;
        return Math.max(0.25F, 1.0F + gustiness * (slow * 1.1F + fast * 0.5F));
    }

    /** Wind direction in radians; turns slowly over tens of minutes. */
    public static float windAngle(double time) {
        return (float) (Noise.value(time / 24000.0, WIND_ANGLE_SEED) * Math.PI * 4.0);
    }

    /** Wind speed in blocks per tick for a rain whose base wind is {@code base}. */
    public static float windSpeed(double time, float base, float gustiness) {
        float swell = 0.55F + 0.9F * Noise.value(time / 1200.0, WIND_SPEED_SEED);
        float gusts = 1.0F + gustiness * 0.8F * (Noise.value(time / 60.0, WIND_SPEED_SEED + 1) - 0.4F);
        return Math.max(0.0F, base * swell * gusts);
    }
}
