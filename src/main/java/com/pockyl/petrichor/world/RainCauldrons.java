package com.pockyl.petrichor.world;

import net.minecraft.server.level.ServerLevel;

import com.pockyl.petrichor.Config;
import com.pockyl.petrichor.weather.RainType;
import com.pockyl.petrichor.weather.StormData;

/** Cauldrons under the open sky fill as fast as the rain soaks the ground: slowly in a drizzle, fast in a downpour. */
public final class RainCauldrons {
    /** Vanilla's chance per precipitation tick that rain adds a level to a cauldron. */
    public static final float VANILLA_CHANCE = 0.05F;

    private RainCauldrons() {
    }

    /** The chance that rain adds a level to a cauldron this precipitation tick. */
    public static float chance(ServerLevel level) {
        if (!StormData.hasWeather(level)) {
            return VANILLA_CHANCE;
        }
        return chance(StormData.get(level).rainLevel(), Config.CAULDRON_FILLING.get().floatValue());
    }

    /** The chance at a rain level (see {@link RainType#mix}) with the {@code cauldronFilling} multiplier. */
    public static float chance(float rainLevel, float filling) {
        if (filling <= 0.0F) {
            return VANILLA_CHANCE;
        }
        return Math.min(1.0F, VANILLA_CHANCE * RainType.mix(rainLevel, type -> type.wetnessRate) * filling);
    }
}
