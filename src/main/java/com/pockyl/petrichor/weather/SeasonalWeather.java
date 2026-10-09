package com.pockyl.petrichor.weather;

import java.util.Locale;

/**
 * How the seasons of a season mod tilt the rain: which types fall more often, how often the sun shines through, how
 * much a rain swells and eases, how often extra lightning strikes. Pure numbers, so it is the same on server and
 * client and needs no season mod to test.
 *
 * <p>The year is twelve sub-seasons from early spring to late winter. Each season has a {@link Profile} that applies in
 * full in its middle; the early and late parts lean a third of the way towards the neighbouring season, so the weather
 * turns over the year instead of switching on the first day of a season.
 */
public final class SeasonalWeather {
    /** The number of sub-seasons in a year. */
    public static final int SUB_SEASONS = 12;

    private SeasonalWeather() {
    }

    /** The four seasons, in the order of the year. */
    public enum Season {
        // drizzle, rain, downpour, sunShowers, wander, strikes
        /** Changeable: drizzles and sun showers, few heavy rains. */
        SPRING(new Profile(1.6F, 1.0F, 0.6F, 2.0F, 1.0F, 0.8F)),
        /** Warm air holds a lot of water: short heavy downpours and fierce thunderstorms. */
        SUMMER(new Profile(0.6F, 0.8F, 1.8F, 1.5F, 1.3F, 1.6F)),
        /** Fronts: steady, even rain that hardly swells or eases. */
        AUTUMN(new Profile(0.9F, 1.7F, 0.7F, 0.5F, 0.45F, 0.8F)),
        /** Where it is still warm enough to rain: cold, light rain and drizzle, hardly any lightning. */
        WINTER(new Profile(1.4F, 1.2F, 0.4F, 0.5F, 0.7F, 0.4F));

        private static final Season[] VALUES = values();

        public final Profile profile;

        Season(Profile profile) {
            this.profile = profile;
        }

        Season next() {
            return VALUES[(ordinal() + 1) % VALUES.length];
        }

        Season previous() {
            return VALUES[(ordinal() + VALUES.length - 1) % VALUES.length];
        }
    }

    /**
     * Multipliers of the rain rules, 1 leaves a rule as configured.
     *
     * @param drizzle    chance of a drizzle
     * @param rain       chance of normal rain
     * @param downpour   chance of a downpour
     * @param sunShowers chance of a sun shower
     * @param wander     how much the rain swells and eases around its type
     * @param strikes    extra lightning strikes in thunderstorms
     */
    public record Profile(float drizzle, float rain, float downpour, float sunShowers, float wander, float strikes) {
        /** No season: everything as configured. */
        public static final Profile NEUTRAL = new Profile(1.0F, 1.0F, 1.0F, 1.0F, 1.0F, 1.0F);

        /** This profile moved {@code t} of the way towards {@code other}; {@code t} above 1 overshoots. */
        public Profile towards(Profile other, float t) {
            return new Profile(lerp(drizzle, other.drizzle, t), lerp(rain, other.rain, t), lerp(downpour, other.downpour, t),
                    lerp(sunShowers, other.sunShowers, t), lerp(wander, other.wander, t), lerp(strikes, other.strikes, t));
        }

        /** The weight of a drizzle with this profile. */
        public int drizzleWeight(int weight) {
            return scale(weight, drizzle);
        }

        public int rainWeight(int weight) {
            return scale(weight, rain);
        }

        public int downpourWeight(int weight) {
            return scale(weight, downpour);
        }

        /** The percent chance of a sun shower with this profile, at most 100. */
        public int sunShowerChance(int chance) {
            return Math.min(100, scale(chance, sunShowers));
        }

        private static float lerp(float a, float b, float t) {
            // Never below zero: a strong setting may overshoot, but a chance cannot turn negative.
            return Math.max(0.0F, a + (b - a) * t);
        }

        private static int scale(int value, float multiplier) {
            return Math.max(0, Math.round(Math.max(0, value) * multiplier));
        }
    }

    /** The id of a sub-season, 0 (early spring) .. 11 (late winter): {@code early_spring} .. {@code late_winter}. */
    public static String subSeasonId(int subSeason) {
        int index = Math.floorMod(subSeason, SUB_SEASONS);
        String part = index % 3 == 0 ? "early_" : index % 3 == 1 ? "mid_" : "late_";
        return part + season(index).name().toLowerCase(Locale.ROOT);
    }

    /** The season of a sub-season, 0 (early spring) .. 11 (late winter). */
    public static Season season(int subSeason) {
        return Season.VALUES[Math.floorMod(subSeason, SUB_SEASONS) / 3];
    }

    /**
     * The profile of a sub-season, 0 (early spring) .. 11 (late winter).
     *
     * @param strength how strongly the seasons count: 0 is no season at all, 1 the profiles as they are, 2 twice as
     *                 far from neutral
     */
    public static Profile profile(int subSeason, float strength) {
        int index = Math.floorMod(subSeason, SUB_SEASONS);
        Season season = season(index);
        Profile profile = season.profile;
        if (index % 3 == 0) {
            profile = profile.towards(season.previous().profile, 1.0F / 3.0F);
        } else if (index % 3 == 2) {
            profile = profile.towards(season.next().profile, 1.0F / 3.0F);
        }
        return Profile.NEUTRAL.towards(profile, Math.max(0.0F, strength));
    }
}
