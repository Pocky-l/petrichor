package com.pockyl.petrichor.compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.season.SeasonHooks;

import java.util.Locale;

/**
 * The only class that touches <a href="https://www.curseforge.com/minecraft/mc-mods/serene-seasons">Serene Seasons</a>.
 * Loaded through {@link Seasons} only when the mod is installed, so its classes are never looked up without it.
 */
final class SereneSeasonsCompat {
    private SereneSeasonsCompat() {
    }

    /** The sub-season of this level, 0 (early spring) .. 11 (late winter), or -1 where the level has no seasons. */
    static int subSeason(Level level) {
        ISeasonState state = state(level);
        // Serene Seasons lists its sub-seasons in the order of the year from early spring, like SeasonalWeather.
        return state == null ? -1 : state.getSubSeason().ordinal();
    }

    /** Whether the biome has a wet and a dry season instead of the four seasons. */
    static boolean tropical(Holder<Biome> biome) {
        return SeasonHelper.usesTropicalSeasons(biome);
    }

    /** The id of the wet or dry season in a tropical biome, like {@code early_wet}, or {@code null} elsewhere. */
    static String tropicalSeasonId(Level level, Holder<Biome> biome) {
        ISeasonState state = state(level);
        if (state == null || !tropical(biome)) {
            return null;
        }
        return state.getTropicalSeason().name().toLowerCase(Locale.ROOT);
    }

    /**
     * What falls here in this season: snow where the season makes the biome cold enough, nothing in a tropical dry
     * season. Asked of Serene Seasons directly rather than through the biome, whose answer it changes only on the
     * client and only in some versions.
     */
    static Biome.Precipitation precipitationAt(Level level, Holder<Biome> biome, BlockPos pos) {
        return SeasonHooks.getPrecipitationAtSeasonal(level, biome, pos);
    }

    private static ISeasonState state(Level level) {
        return SeasonHelper.getSeasonState(level);
    }
}
