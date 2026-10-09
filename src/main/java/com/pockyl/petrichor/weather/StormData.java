package com.pockyl.petrichor.weather;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.ServerLevelData;

import com.pockyl.petrichor.Config;

/**
 * The server's weather state for one level: the soaked ground, the rain level of the current spell and a rain type
 * forced by command. Saved with the world so puddles are still there after a restart.
 */
public final class StormData extends SavedData {
    private static final String NAME = "petrichor_storm";
    private static final SavedData.Factory<StormData> FACTORY = new SavedData.Factory<>(StormData::new, StormData::load);

    private int overrideType = -1;
    private boolean overrideSunny;
    private long overrideUntil;
    private float wetness;
    /** Where the current rain is on the scale of {@link RainType#mix}; a new rain starts at 0, a drizzle. */
    private float rainLevel;

    public static StormData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    /** Whether this level has weather at all (the overworld and dimensions like it). */
    public static boolean hasWeather(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    private static StormData load(CompoundTag tag, HolderLookup.Provider registries) {
        StormData data = new StormData();
        data.overrideType = tag.contains("override") ? tag.getInt("override") : -1;
        data.overrideSunny = tag.getBoolean("overrideSunny");
        data.overrideUntil = tag.getLong("overrideUntil");
        data.wetness = tag.getFloat("wetness");
        data.rainLevel = tag.getFloat("rainLevel");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("override", overrideType);
        tag.putBoolean("overrideSunny", overrideSunny);
        tag.putLong("overrideUntil", overrideUntil);
        tag.putFloat("wetness", wetness);
        tag.putFloat("rainLevel", rainLevel);
        return tag;
    }

    private RainType forcedType(ServerLevel level) {
        RainType forced = RainType.byOrdinal(overrideType);
        return forced != null && level.getGameTime() < overrideUntil ? forced : null;
    }

    /** The rain falling now, or {@code null} when it does not rain. */
    public RainType currentType(ServerLevel level) {
        // The weather flags, not the rain level: that one fades in and out over seconds.
        if (!level.getLevelData().isRaining()) {
            return null;
        }
        return RainType.at(rainLevel);
    }

    /** The rain level now, see {@link RainType#mix}. */
    public float rainLevel() {
        return rainLevel;
    }

    /** Whether the current rain is a sun shower (the clients show the sun only while it is up). */
    public boolean sunShower(ServerLevel level) {
        if (!level.getLevelData().isRaining() || level.getLevelData().isThundering()) {
            return false;
        }
        return forcedType(level) != null ? overrideSunny : Config.naturalSunShower(level.getGameTime());
    }

    /** The level the rain is heading for now. */
    public float targetLevel(ServerLevel level) {
        boolean thundering = level.getLevelData().isThundering();
        RainType forced = forcedType(level);
        float target;
        if (forced != null) {
            target = overrideSunny ? RainSchedule.SUN_SHOWER_LEVEL : forced.level();
        } else {
            target = Config.naturalLevel(level.getGameTime(), false);
            if (thundering) {
                // A thunderstorm builds up from the rain that was falling and dies down into it again.
                float storm = RainType.MAX_LEVEL;
                if (cycling(level) && level.getLevelData() instanceof ServerLevelData data) {
                    storm = Math.min(storm, target + RainSchedule.easeOffCap(data.getThunderTime(), Config.stepTicks()));
                }
                target = Math.max(target, storm);
            } else if (sunShower(level) && level.isDay()) {
                target = Math.min(target, RainSchedule.SUN_SHOWER_LEVEL);
            }
        }
        // Ease off before the rain stops.
        if (cycling(level) && level.getLevelData() instanceof ServerLevelData data) {
            target = Math.min(target, RainSchedule.easeOffCap(data.getRainTime(), Config.stepTicks()));
        }
        return target;
    }

    /** Whether the weather runs its course, so the time left of the rain is known. */
    private static boolean cycling(ServerLevel level) {
        return level.getGameRules().getBoolean(GameRules.RULE_WEATHER_CYCLE);
    }

    public void forceType(RainType type, long until) {
        forceType(type, false, until);
    }

    public void forceType(RainType type, boolean sunny, long until) {
        overrideType = type == null ? -1 : type.ordinal();
        overrideSunny = type != null && sunny;
        overrideUntil = until;
        setDirty();
    }

    public float wetness() {
        return wetness;
    }

    public void setWetness(float value) {
        wetness = Math.clamp(value, 0.0F, 1.0F);
        setDirty();
    }

    /** One server tick: move the rain level, soak or dry the ground, drop an expired override. */
    public void tick(ServerLevel level) {
        boolean raining = level.getLevelData().isRaining();
        if (overrideType >= 0 && (level.getGameTime() >= overrideUntil || !raining)) {
            overrideType = -1;
            overrideSunny = false;
            setDirty();
        }
        float stepTicks = Config.stepTicks();
        if (raining) {
            rainLevel = RainSchedule.approach(rainLevel, targetLevel(level), stepTicks);
        } else if (level.getRainLevel(1.0F) > 0.0F) {
            // The last of the rain fading out.
            rainLevel = RainSchedule.approach(rainLevel, 0.0F, stepTicks);
        } else {
            // The next rain starts as a drizzle.
            rainLevel = 0.0F;
        }
        boolean day = level.isDay();
        float next = Wetness.step(wetness, level.getRainLevel(1.0F), raining ? rainLevel : -1.0F, day,
                Config.FILL_SPEED.get(), Config.DRYING_SPEED.get());
        if (next != wetness) {
            wetness = next;
        }
        // The rain level and wetness change a little every tick: saved every ten seconds.
        if (level.getGameTime() % 200 == 0) {
            setDirty();
        }
    }
}
