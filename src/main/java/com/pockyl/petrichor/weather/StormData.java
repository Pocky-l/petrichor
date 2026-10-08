package com.pockyl.petrichor.weather;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.saveddata.SavedData;

import com.pockyl.petrichor.Config;

/**
 * The server's weather state for one level: the soaked ground and a rain type forced by command. Saved with the world
 * so puddles are still there after a restart.
 */
public final class StormData extends SavedData {
    private static final String NAME = "petrichor_storm";

    private int overrideType = -1;
    private long overrideUntil;
    private float wetness;

    public static StormData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(StormData::load, StormData::new, NAME);
    }

    /** Whether this level has weather at all (the overworld and dimensions like it). */
    public static boolean hasWeather(ServerLevel level) {
        return level.dimensionType().hasSkyLight() && !level.dimensionType().hasCeiling();
    }

    private static StormData load(CompoundTag tag) {
        StormData data = new StormData();
        data.overrideType = tag.contains("override") ? tag.getInt("override") : -1;
        data.overrideUntil = tag.getLong("overrideUntil");
        data.wetness = tag.getFloat("wetness");
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("override", overrideType);
        tag.putLong("overrideUntil", overrideUntil);
        tag.putFloat("wetness", wetness);
        return tag;
    }

    /** The rain falling now, or {@code null} when it does not rain. */
    public RainType currentType(ServerLevel level) {
        // The weather flags, not the rain level: that one fades in and out over seconds.
        if (!level.getLevelData().isRaining()) {
            return null;
        }
        RainType forced = RainType.byOrdinal(overrideType);
        if (forced != null && level.getGameTime() < overrideUntil) {
            return forced;
        }
        return RainSchedule.naturalType(level.getGameTime(), level.getLevelData().isThundering(),
                Config.DRIZZLE_WEIGHT.get(), Config.RAIN_WEIGHT.get(), Config.DOWNPOUR_WEIGHT.get());
    }

    public void forceType(RainType type, long until) {
        overrideType = type == null ? -1 : type.ordinal();
        overrideUntil = until;
        setDirty();
    }

    public float wetness() {
        return wetness;
    }

    public void setWetness(float value) {
        wetness = Mth.clamp(value, 0.0F, 1.0F);
        setDirty();
    }

    /** One server tick: soak or dry the ground, drop an expired override. */
    public void tick(ServerLevel level) {
        if (overrideType >= 0 && (level.getGameTime() >= overrideUntil || !level.getLevelData().isRaining())) {
            overrideType = -1;
            setDirty();
        }
        boolean day = level.isDay();
        float next = Wetness.step(wetness, level.getRainLevel(1.0F), currentType(level), day,
                Config.FILL_SPEED.get(), Config.DRYING_SPEED.get());
        if (next != wetness) {
            wetness = next;
            if (level.getGameTime() % 200 == 0) {
                setDirty();
            }
        }
    }
}
