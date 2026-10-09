package com.pockyl.petrichor.weather;

/**
 * The natural course of the weather, derived only from the game time (which the server syncs to every client), so
 * all players see the same rain even when the server does not have this mod.
 *
 * <ul>
 *   <li>the type of rain is rolled for every window of {@link #WINDOW} ticks, so a long rain spell changes character
 *   a few times (a drizzle turning into a downpour and back); the rain level wanders a little around that type and
 *   only ever moves towards it step by step ({@link #approach}): rain starts as a drizzle, swells, and eases off again
 *   before it stops;</li>
 *   <li>some light rains in daylight are sun showers: the sun keeps shining through them;</li>
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
    private static final int WANDER_SEED = 0x5EED_0005;
    private static final int SUN_SEED = 0x5EED_0006;
    /** A sun shower stays light: never heavier than this rain level. */
    public static final float SUN_SHOWER_LEVEL = 0.8F;
    /** The rain eases off a little faster than it builds up. */
    private static final float EASE_OFF = 1.35F;

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
     * The rain level this time aims for if nobody overrides it: the window's type plus a slow wander, so a rain keeps
     * swelling and easing a little. Thunder aims for a thunderstorm.
     */
    public static float naturalLevel(long gameTime, boolean thundering, int drizzle, int rain, int downpour) {
        return naturalLevel(gameTime, thundering, drizzle, rain, downpour, 1.0F);
    }

    /**
     * {@link #naturalLevel(long, boolean, int, int, int)} with the wander scaled by {@code wander}: below 1 a steadier
     * rain, above 1 a more changeable one.
     */
    public static float naturalLevel(long gameTime, boolean thundering, int drizzle, int rain, int downpour, float wander) {
        if (thundering) {
            return RainType.MAX_LEVEL;
        }
        float base = naturalType(gameTime, false, drizzle, rain, downpour).level();
        float swell = (Noise.value(gameTime / 2400.0, WANDER_SEED) - 0.5F) * 0.7F * Math.max(0.0F, wander);
        return Math.clamp(base + swell, 0.0F, RainType.DOWNPOUR.level());
    }

    /**
     * Whether the rain of this window is a sun shower (if it is light and the sun is up).
     *
     * @param chance percent of light rain windows that are sunny
     */
    public static boolean sunShower(long gameTime, int chance, int drizzle, int rain, int downpour) {
        if (Noise.unit(Math.floorDiv(gameTime, WINDOW), SUN_SEED) * 100.0F >= chance) {
            return false;
        }
        RainType type = naturalType(gameTime, false, drizzle, rain, downpour);
        return type == RainType.DRIZZLE || type == RainType.RAIN;
    }

    /**
     * One tick of the rain level moving towards {@code target}: at most one type up per {@code stepTicks}, a little
     * faster down.
     */
    public static float approach(float level, float target, float stepTicks) {
        float up = 1.0F / Math.max(1.0F, stepTicks);
        return level + Math.clamp(target - level, -up * EASE_OFF, up);
    }

    /**
     * The highest level from which the rain can still ease off to a drizzle in the ticks left until it stops (or until
     * a thunderstorm ends).
     */
    public static float easeOffCap(long ticksLeft, float stepTicks) {
        return ticksLeft * EASE_OFF * 0.85F / Math.max(1.0F, stepTicks);
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
