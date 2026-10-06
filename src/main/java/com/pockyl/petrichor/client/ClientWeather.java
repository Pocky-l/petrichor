package com.pockyl.petrichor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.pockyl.petrichor.Config;
import com.pockyl.petrichor.weather.RainSchedule;
import com.pockyl.petrichor.weather.RainType;
import com.pockyl.petrichor.weather.Wetness;

/**
 * The weather as the client draws and plays it, updated every tick.
 *
 * <p>The rain type comes from the server when it has this mod, otherwise from the shared {@link RainSchedule}. All
 * look-and-sound parameters glide towards the current type over ~10 seconds, so a drizzle swells into a downpour instead
 * of switching. Wetness follows the server too, or is simulated locally the same way.
 *
 * <p>The rain level follows the land around the listener: where it snows (or never rains, like a desert) the mod's
 * rain fades out, and in snowy land the mod steps aside completely - vanilla draws the snowfall, the light and the fog.
 */
public final class ClientWeather {
    /** Server state older than this is ignored (the server stopped sending: it does not have the mod). */
    private static final int SERVER_TIMEOUT = 200;
    private static final float BLEND = 0.006F;

    private static long ticks;
    private static long lastSync = Long.MIN_VALUE;
    private static int serverType = -1;
    private static float serverWetness;
    private static boolean initialized;

    private static RainType type = RainType.RAIN;
    /** The world's rain level, before the land around the listener is taken into account. */
    private static float worldRain;
    private static float rain;
    /** Share of the land around the listener where rain (not snow) falls, gliding. */
    private static float presence = 1.0F;
    private static float presenceTarget = 1.0F;
    private static boolean snowy;
    private static float thunder;
    private static float gust = 1.0F;
    private static float windX;
    private static float windZ;
    private static float wetness;

    // The blended parameters of the current type.
    public static float density;
    public static float fallSpeed;
    public static float streakLength;
    public static float streakWidth;
    public static float alpha;
    public static float gustiness;
    public static float fogDistance;
    public static float splash;
    public static float heaviness;
    private static float windBase;

    private ClientWeather() {
    }

    public static void onServerSync(int type, float wetness) {
        serverType = type;
        serverWetness = wetness;
        lastSync = ticks;
    }

    public static void reset() {
        initialized = false;
        lastSync = Long.MIN_VALUE;
        serverType = -1;
        wetness = 0.0F;
        rain = 0.0F;
        thunder = 0.0F;
        presence = presenceTarget = 1.0F;
        snowy = false;
    }

    private static boolean serverActive() {
        return ticks - lastSync < SERVER_TIMEOUT;
    }

    public static void tick(ClientLevel level) {
        ticks++;
        if (ticks % 10 == 1 || !initialized) {
            surveyLand(level);
        }
        presence = initialized ? presence + Math.clamp(presenceTarget - presence, -0.02F, 0.02F) : presenceTarget;
        worldRain = level.getRainLevel(1.0F);
        rain = worldRain * presence;
        thunder = level.getThunderLevel(1.0F) * presence;
        RainType target = targetType(level);
        if (target != null) {
            type = target;
        }
        if (!initialized) {
            initialized = true;
            snapTo(type);
            wetness = serverActive() ? serverWetness : rain * type.wetnessCap * 0.6F;
        } else {
            blendTo(type);
        }
        double time = level.getGameTime();
        gust = RainSchedule.gust(time, gustiness);
        float angle = RainSchedule.windAngle(time);
        float speed = RainSchedule.windSpeed(time, windBase, gustiness) * (0.4F + 0.6F * rain);
        windX = (float) Math.cos(angle) * speed;
        windZ = (float) Math.sin(angle) * speed;

        if (serverActive()) {
            wetness += Math.clamp(serverWetness - wetness, -0.02F, 0.02F);
        } else {
            wetness = Wetness.step(wetness, rain, rain > 0.0F ? type : null, level.isDay(),
                    Config.FILL_SPEED.get(), Config.DRYING_SPEED.get());
        }
    }

    /**
     * Where around the listener it rains and where it snows: 25 points over a 48-block square, each checked at the
     * height the rain or snow reaches there (mountain tops can be snowy above rainy valleys).
     */
    private static void surveyLand(ClientLevel level) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int rainy = 0;
        int snowing = 0;
        boolean snowHere = false;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                int x = Mth.floor(cam.x) + i * 12;
                int z = Mth.floor(cam.z) + j * 12;
                pos.set(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
                Biome biome = level.getBiome(pos).value();
                if (!biome.hasPrecipitation()) {
                    continue;
                }
                if (biome.getPrecipitationAt(pos) == Biome.Precipitation.SNOW) {
                    snowing++;
                    snowHere |= i == 0 && j == 0;
                } else {
                    rainy++;
                }
            }
        }
        presenceTarget = rainy / 25.0F;
        // Some hysteresis, so walking along a biome border does not flip between the mod and vanilla.
        if (snowHere || snowing > 10) {
            snowy = true;
        } else if (snowing < 5) {
            snowy = false;
        }
        if (snowy) {
            presenceTarget = 0.0F;
        }
    }

    private static RainType targetType(ClientLevel level) {
        if (serverActive()) {
            return RainType.byOrdinal(serverType);
        }
        if (worldRain <= 0.0F) {
            return null;
        }
        return RainSchedule.naturalType(level.getGameTime(), thunder > 0.5F,
                Config.DRIZZLE_WEIGHT.get(), Config.RAIN_WEIGHT.get(), Config.DOWNPOUR_WEIGHT.get());
    }

    private static void snapTo(RainType t) {
        density = t.density;
        fallSpeed = t.fallSpeed;
        streakLength = t.streakLength;
        streakWidth = t.streakWidth;
        alpha = t.alpha;
        windBase = t.wind;
        gustiness = t.gustiness;
        fogDistance = t.fogDistance;
        splash = t.splash;
        heaviness = t.heaviness;
    }

    private static void blendTo(RainType t) {
        density += (t.density - density) * BLEND;
        fallSpeed += (t.fallSpeed - fallSpeed) * BLEND;
        streakLength += (t.streakLength - streakLength) * BLEND;
        streakWidth += (t.streakWidth - streakWidth) * BLEND;
        alpha += (t.alpha - alpha) * BLEND;
        windBase += (t.wind - windBase) * BLEND;
        gustiness += (t.gustiness - gustiness) * BLEND;
        fogDistance += (t.fogDistance - fogDistance) * BLEND;
        splash += (t.splash - splash) * BLEND;
        heaviness += (t.heaviness - heaviness) * BLEND;
    }

    public static RainType type() {
        return type;
    }

    /**
     * Rain level, 0..1: vanilla's (fades in and out when rain starts and stops) times the share of the land around
     * the listener where it rains rather than snows.
     */
    public static float rain() {
        return rain;
    }

    /** In snowy land the mod leaves the weather to vanilla entirely. */
    public static boolean snowy() {
        return snowy;
    }

    public static float thunder() {
        return thunder;
    }

    /** How hard it rains right now: the rain level with gusts. */
    public static float intensity() {
        return rain * gust;
    }

    public static float windX() {
        return windX;
    }

    public static float windZ() {
        return windZ;
    }

    public static float wetness() {
        return wetness;
    }

    public static boolean syncedWithServer() {
        return serverActive();
    }
}
