package com.pockyl.petrichor;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Visuals and sound. Only loaded on the client.
 */
public final class ClientConfig {
    /** Budgets that scale with the hardware. Individual options below multiply on top of these. */
    public enum Quality {
        // rainRadius, maxDrops, puddleRadius, maxEffects, splashBudget, rivulets, reflectionSteps
        LOW(14, 2200, 32, 1000, 25, false, 0),
        MEDIUM(20, 5000, 48, 2000, 50, true, 16),
        HIGH(28, 9000, 64, 3500, 90, true, 28),
        ULTRA(36, 15000, 96, 5000, 140, true, 48);

        public final int rainRadius;
        public final int maxDrops;
        public final int puddleRadius;
        public final int maxEffects;
        public final int splashBudget;
        public final boolean rivulets;
        /** Ray-march steps of the puddle reflections; 0 reflects only the sky. */
        public final int reflectionSteps;

        Quality(int rainRadius, int maxDrops, int puddleRadius, int maxEffects, int splashBudget, boolean rivulets,
                int reflectionSteps) {
            this.rainRadius = rainRadius;
            this.maxDrops = maxDrops;
            this.puddleRadius = puddleRadius;
            this.maxEffects = maxEffects;
            this.splashBudget = splashBudget;
            this.rivulets = rivulets;
            this.reflectionSteps = reflectionSteps;
        }
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.EnumValue<Quality> QUALITY = BUILDER
            .comment("Quality preset: how far and how many drops, puddles and effects are drawn. Lower it on weak computers.")
            .translation(key("quality"))
            .defineEnum("quality", Quality.HIGH);

    static {
        BUILDER.translation(key("rain")).push("rain");
    }

    public static final ModConfigSpec.BooleanValue RAIN = BUILDER
            .comment("Replace the vanilla rain and snowfall with the improved precipitation. Off: vanilla rain is drawn.")
            .translation(key("rainEnabled"))
            .define("enabled", true);
    public static final ModConfigSpec.DoubleValue RAIN_DENSITY = BUILDER
            .comment("Multiplier for the number of rain drops.")
            .translation(key("rainDensity"))
            .defineInRange("density", 1.0, 0.1, 3.0);
    public static final ModConfigSpec.DoubleValue WIND = BUILDER
            .comment("How strongly wind slants the rain. 0 = always straight down.")
            .translation(key("wind"))
            .defineInRange("wind", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.BooleanValue SPLASHES = BUILDER
            .comment("Splashes, ripples and spray where rain hits the ground, water and mobs.")
            .translation(key("splashes"))
            .define("splashes", true);
    public static final ModConfigSpec.DoubleValue SPLASH_DENSITY = BUILDER
            .comment("Multiplier for the number of splashes.")
            .translation(key("splashDensity"))
            .defineInRange("splashDensity", 1.0, 0.1, 3.0);
    public static final ModConfigSpec.DoubleValue FOG = BUILDER
            .comment("How much rain closes in the view distance. 0 = vanilla fog.")
            .translation(key("fog"))
            .defineInRange("fog", 1.0, 0.0, 2.0);

    static {
        BUILDER.pop();
        BUILDER.translation(key("puddles")).push("puddles");
    }

    public static final ModConfigSpec.BooleanValue PUDDLES = BUILDER
            .comment("Puddles and wet ground.")
            .translation(key("puddlesEnabled"))
            .define("enabled", true);
    public static final ModConfigSpec.DoubleValue PUDDLE_COVERAGE = BUILDER
            .comment("How much of the ground puddles cover at the same wetness.")
            .translation(key("puddleCoverage"))
            .defineInRange("coverage", 1.0, 0.2, 1.6);
    public static final ModConfigSpec.BooleanValue FOOTSTEPS = BUILDER
            .comment("Splashes and sounds when walking through puddles.")
            .translation(key("footsteps"))
            .define("footsteps", true);

    static {
        BUILDER.pop();
        BUILDER.translation(key("runoff")).push("runoff");
    }

    public static final ModConfigSpec.BooleanValue RIVULETS = BUILDER
            .comment("Rain water running over the ground towards edges and spilling down steps (sheets need quality MEDIUM or higher).")
            .translation(key("rivulets"))
            .define("rivulets", true);
    public static final ModConfigSpec.BooleanValue DRIPS = BUILDER
            .comment("Water pouring off roof edges and cliffs, and drops falling from leaves, also for a while after the rain.")
            .translation(key("drips"))
            .define("drips", true);
    public static final ModConfigSpec.DoubleValue DRIP_DENSITY = BUILDER
            .comment("Multiplier for the number of falling drips.")
            .translation(key("dripDensity"))
            .defineInRange("dripDensity", 1.0, 0.1, 3.0);

    static {
        BUILDER.pop();
        BUILDER.translation(key("lightning")).push("lightning");
    }

    public static final ModConfigSpec.BooleanValue BOLTS = BUILDER
            .comment("Branching lightning with a growing leader, return strokes and afterglow. Off: vanilla bolts.")
            .translation(key("bolts"))
            .define("customBolts", true);
    public static final ModConfigSpec.BooleanValue DELAYED_THUNDER = BUILDER
            .comment("Thunder arrives after the flash, delayed by distance at the speed of sound, and sounds deeper far away.")
            .translation(key("delayedThunder"))
            .define("delayedThunder", true);
    public static final ModConfigSpec.DoubleValue SOUND_SPEED = BUILDER
            .comment("Speed of sound in blocks per second for the thunder delay (one block = one meter).")
            .translation(key("soundSpeed"))
            .defineInRange("soundSpeed", 343.0, 50.0, 2000.0);
    public static final ModConfigSpec.DoubleValue FLASH = BUILDER
            .comment("Brightness of lightning flashes on the world and sky. Lower it if flashes are uncomfortable. "
                    + "The vanilla 'Hide Lightning Flashes' option also turns them off.")
            .translation(key("flash"))
            .defineInRange("flashBrightness", 1.0, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue DISTANT = BUILDER
            .comment("Frequency of distant, purely visual lightning and flashes inside the clouds during thunderstorms. 0 disables.")
            .translation(key("distant"))
            .defineInRange("distantLightning", 1.0, 0.0, 3.0);

    static {
        BUILDER.pop();
        BUILDER.translation(key("sound")).push("sound");
    }

    public static final ModConfigSpec.DoubleValue RAIN_VOLUME = BUILDER
            .comment("Volume of the rain ambience (also follows the Weather volume slider).")
            .translation(key("rainVolume"))
            .defineInRange("rainVolume", 1.0, 0.0, 2.0);
    public static final ModConfigSpec.BooleanValue ROOF = BUILDER
            .comment("Muffled rain drumming on the roof when you are under cover.")
            .translation(key("roof"))
            .define("roofSounds", true);
    public static final ModConfigSpec.DoubleValue THUNDER_VOLUME = BUILDER
            .comment("Volume of thunder.")
            .translation(key("thunderVolume"))
            .defineInRange("thunderVolume", 1.0, 0.0, 2.0);

    static {
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ClientConfig() {
    }

    public static Quality quality() {
        return QUALITY.get();
    }

    private static String key(String name) {
        return Petrichor.MOD_ID + ".configuration." + name;
    }
}
