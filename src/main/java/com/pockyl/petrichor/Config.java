package com.pockyl.petrichor;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Weather rules. On a server with this mod the server's values are used for everybody; a client on a server without
 * the mod falls back to its own copy.
 */
public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    static {
        BUILDER.translation(key("weather")).push("weather");
    }

    public static final ForgeConfigSpec.IntValue DRIZZLE_WEIGHT = BUILDER
            .comment("Relative chance of a drizzle in each five-minute window of rain.")
            .translation(key("drizzleWeight"))
            .defineInRange("drizzleWeight", 30, 0, 100);
    public static final ForgeConfigSpec.IntValue RAIN_WEIGHT = BUILDER
            .comment("Relative chance of normal rain.")
            .translation(key("rainWeight"))
            .defineInRange("rainWeight", 45, 0, 100);
    public static final ForgeConfigSpec.IntValue DOWNPOUR_WEIGHT = BUILDER
            .comment("Relative chance of a downpour. Thunderstorms follow vanilla thunder.")
            .translation(key("downpourWeight"))
            .defineInRange("downpourWeight", 25, 0, 100);
    public static final ForgeConfigSpec.DoubleValue FILL_SPEED = BUILDER
            .comment("How fast rain soaks the ground and fills puddles.")
            .translation(key("fillSpeed"))
            .defineInRange("fillSpeed", 1.0, 0.1, 10.0);
    public static final ForgeConfigSpec.DoubleValue DRYING_SPEED = BUILDER
            .comment("How fast the ground dries and puddles shrink after rain.")
            .translation(key("dryingSpeed"))
            .defineInRange("dryingSpeed", 1.0, 0.1, 10.0);

    static {
        BUILDER.pop();
        BUILDER.translation(key("lightning")).push("lightning");
    }

    public static final ForgeConfigSpec.DoubleValue EXTRA_STRIKES = BUILDER
            .comment("Additional lightning strikes per minute near each player during thunderstorms (vanilla strikes still happen). 0 disables.")
            .translation(key("extraStrikesPerMinute"))
            .defineInRange("extraStrikesPerMinute", 1.5, 0.0, 30.0);
    public static final ForgeConfigSpec.IntValue STRIKE_RADIUS = BUILDER
            .comment("Extra strikes land within this many blocks of a player.")
            .translation(key("strikeRadius"))
            .defineInRange("strikeRadius", 96, 16, 256);
    public static final ForgeConfigSpec.BooleanValue TALL_ATTRACTION = BUILDER
            .comment("Extra strikes prefer the tallest spot nearby (trees, towers, hilltops) like real lightning.")
            .translation(key("tallObjectAttraction"))
            .define("tallObjectAttraction", true);

    static {
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    private static String key(String name) {
        return Petrichor.MOD_ID + ".configuration." + name;
    }
}
