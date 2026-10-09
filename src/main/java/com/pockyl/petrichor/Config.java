package com.pockyl.petrichor;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.ModConfigSpec;

import com.pockyl.petrichor.compat.Seasons;
import com.pockyl.petrichor.weather.RainSchedule;
import com.pockyl.petrichor.weather.SeasonalWeather;

/**
 * Weather rules. On a server with this mod the server's values are used for everybody; a client on a server without
 * the mod falls back to its own copy.
 */
public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    static {
        BUILDER.translation(key("weather")).push("weather");
    }

    public static final ModConfigSpec.IntValue DRIZZLE_WEIGHT = BUILDER
            .comment("Relative chance of a drizzle in each five-minute window of rain.")
            .translation(key("drizzleWeight"))
            .defineInRange("drizzleWeight", 30, 0, 100);
    public static final ModConfigSpec.IntValue RAIN_WEIGHT = BUILDER
            .comment("Relative chance of normal rain.")
            .translation(key("rainWeight"))
            .defineInRange("rainWeight", 45, 0, 100);
    public static final ModConfigSpec.IntValue DOWNPOUR_WEIGHT = BUILDER
            .comment("Relative chance of a downpour. Thunderstorms follow vanilla thunder.")
            .translation(key("downpourWeight"))
            .defineInRange("downpourWeight", 25, 0, 100);
    public static final ModConfigSpec.DoubleValue TRANSITION_MINUTES = BUILDER
            .comment("Minutes for the rain to change by one step (drizzle -> rain -> downpour -> thunderstorm). Rain starts as a "
                    + "drizzle, builds up and eases off again before it stops.")
            .translation(key("transitionMinutes"))
            .defineInRange("transitionMinutes", 0.5, 0.05, 10.0);
    public static final ModConfigSpec.IntValue SUN_SHOWER_CHANCE = BUILDER
            .comment("Percent of light rains in daytime that are sun showers: the sun keeps shining through the rain.")
            .translation(key("sunShowerChance"))
            .defineInRange("sunShowerChance", 20, 0, 100);
    public static final ModConfigSpec.DoubleValue FILL_SPEED = BUILDER
            .comment("How fast rain soaks the ground and fills puddles.")
            .translation(key("fillSpeed"))
            .defineInRange("fillSpeed", 1.0, 0.1, 10.0);
    public static final ModConfigSpec.DoubleValue DRYING_SPEED = BUILDER
            .comment("How fast the ground dries and puddles shrink after rain.")
            .translation(key("dryingSpeed"))
            .defineInRange("dryingSpeed", 1.0, 0.1, 10.0);

    static {
        BUILDER.pop();
        BUILDER.translation(key("lightning")).push("lightning");
    }

    public static final ModConfigSpec.DoubleValue EXTRA_STRIKES = BUILDER
            .comment("Additional lightning strikes per minute near each player during thunderstorms (vanilla strikes still happen). 0 disables.")
            .translation(key("extraStrikesPerMinute"))
            .defineInRange("extraStrikesPerMinute", 1.5, 0.0, 30.0);
    public static final ModConfigSpec.IntValue STRIKE_RADIUS = BUILDER
            .comment("Extra strikes land within this many blocks of a player.")
            .translation(key("strikeRadius"))
            .defineInRange("strikeRadius", 96, 16, 256);
    public static final ModConfigSpec.BooleanValue TALL_ATTRACTION = BUILDER
            .comment("Extra strikes prefer the tallest spot nearby (trees, towers, hilltops) like real lightning.")
            .translation(key("tallObjectAttraction"))
            .define("tallObjectAttraction", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("seasons")).push("seasons");
    }

    public static final ModConfigSpec.BooleanValue SERENE_SEASONS = BUILDER
            .comment("Serene Seasons integration, when Serene Seasons is installed: the season shapes the rain (drizzles and sun "
                    + "showers in spring, downpours and storms in summer, steady rain in autumn), and no rain is drawn or heard "
                    + "where the season turns it into snow.")
            .translation(key("sereneSeasons"))
            .define("sereneSeasons", true);
    public static final ModConfigSpec.DoubleValue SEASON_STRENGTH = BUILDER
            .comment("How strongly the seasons change the rain: the chances of each rain type and of sun showers, how steady it "
                    + "falls and the seasonal lightning. 0 keeps the rain as set above all year, 2 doubles the seasonal differences.")
            .translation(key("seasonStrength"))
            .defineInRange("seasonStrength", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.BooleanValue SEASONAL_LIGHTNING = BUILDER
            .comment("Summer thunderstorms bring more extra strikes, winter ones fewer.")
            .translation(key("seasonalLightning"))
            .define("seasonalLightning", true);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    /** Ticks for the rain to change by one step. */
    public static float stepTicks() {
        return (float) (TRANSITION_MINUTES.get() * 1200.0);
    }

    /** Whether the rain in this level now is a sun shower by the natural schedule (and the season, if any). */
    public static boolean naturalSunShower(Level level) {
        SeasonalWeather.Profile season = Seasons.profile(level);
        return RainSchedule.sunShower(level.getGameTime(), season.sunShowerChance(SUN_SHOWER_CHANCE.get()),
                season.drizzleWeight(DRIZZLE_WEIGHT.get()), season.rainWeight(RAIN_WEIGHT.get()),
                season.downpourWeight(DOWNPOUR_WEIGHT.get()));
    }

    /** The natural rain level in this level now (with the season, if any). */
    public static float naturalLevel(Level level, boolean thundering) {
        SeasonalWeather.Profile season = Seasons.profile(level);
        return RainSchedule.naturalLevel(level.getGameTime(), thundering, season.drizzleWeight(DRIZZLE_WEIGHT.get()),
                season.rainWeight(RAIN_WEIGHT.get()), season.downpourWeight(DOWNPOUR_WEIGHT.get()), season.wander());
    }

    private static String key(String name) {
        return Petrichor.MOD_ID + ".configuration." + name;
    }
}
