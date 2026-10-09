package com.pockyl.petrichor.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.fml.ModList;

import com.pockyl.petrichor.Config;
import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.weather.SeasonalWeather;

import java.util.Optional;

/**
 * Seasons from an optional season mod, for the rest of the mod: without one (or with the integration turned off) every
 * answer is the plain one - no season, the biome's own precipitation.
 *
 * <p>Only {@link SereneSeasonsCompat} touches Serene Seasons, and it is reached only once {@link #loaded()} said the
 * mod is there. If its API ever changes under us, the integration switches itself off instead of crashing the game.
 */
public final class Seasons {
    public static final String SERENE_SEASONS = "sereneseasons";

    private static Boolean loaded;
    private static volatile boolean broken;

    private Seasons() {
    }

    /** Whether Serene Seasons is installed. */
    public static boolean loaded() {
        if (loaded == null) {
            ModList mods = ModList.get();
            loaded = mods != null && mods.isLoaded(SERENE_SEASONS);
        }
        return loaded;
    }

    /** Whether the seasons shape the weather now: Serene Seasons is installed and the integration is on. */
    public static boolean active() {
        return !broken && loaded() && Config.SERENE_SEASONS.get();
    }

    /** The sub-season of this level, 0 (early spring) .. 11 (late winter), or -1 without seasons. */
    public static int subSeason(Level level) {
        if (!active()) {
            return -1;
        }
        try {
            return SereneSeasonsCompat.subSeason(level);
        } catch (LinkageError | RuntimeException e) {
            disable(e);
            return -1;
        }
    }

    /** How the season of this level tilts the rain; neutral without seasons. */
    public static SeasonalWeather.Profile profile(Level level) {
        int subSeason = subSeason(level);
        if (subSeason < 0) {
            return SeasonalWeather.Profile.NEUTRAL;
        }
        return SeasonalWeather.profile(subSeason, Config.SEASON_STRENGTH.get().floatValue());
    }

    /** Multiplier of the extra lightning strikes in this level's season. */
    public static float strikeMultiplier(Level level) {
        return Config.SEASONAL_LIGHTNING.get() ? profile(level).strikes() : 1.0F;
    }

    /** What falls at {@code pos} when it rains: the season's answer if seasons are active, else the biome's. */
    public static Biome.Precipitation precipitationAt(Level level, BlockPos pos) {
        Holder<Biome> biome = level.getBiome(pos);
        if (active()) {
            try {
                return SereneSeasonsCompat.precipitationAt(level, biome, pos);
            } catch (LinkageError | RuntimeException e) {
                disable(e);
            }
        }
        return biome.value().getPrecipitationAt(pos);
    }

    /**
     * The season at {@code pos} for {@code /petrichor status}: the sub-season, and the wet or dry season in a tropical
     * biome. Empty without seasons.
     */
    public static Optional<Component> describe(Level level, BlockPos pos) {
        int subSeason = subSeason(level);
        if (subSeason < 0) {
            return Optional.empty();
        }
        try {
            String tropical = SereneSeasonsCompat.tropicalSeasonId(level, level.getBiome(pos));
            Component season = name(SeasonalWeather.subSeasonId(subSeason));
            if (tropical == null) {
                return Optional.of(season);
            }
            return Optional.of(Component.translatableWithFallback("petrichor.season.with_tropical", "%s, here %s", season,
                    name(tropical)));
        } catch (LinkageError | RuntimeException e) {
            disable(e);
            return Optional.empty();
        }
    }

    private static Component name(String id) {
        String words = id.replace('_', ' ');
        return Component.translatableWithFallback("petrichor.season." + id,
                Character.toUpperCase(words.charAt(0)) + words.substring(1));
    }

    private static void disable(Throwable e) {
        if (!broken) {
            broken = true;
            Petrichor.LOGGER.warn("Serene Seasons integration turned off: this version of Serene Seasons is not supported", e);
        }
    }
}
