package com.pockyl.petrichor.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.pockyl.petrichor.Config;
import com.pockyl.petrichor.compat.Seasons;
import com.pockyl.petrichor.weather.RainSchedule;
import com.pockyl.petrichor.weather.RainType;
import com.pockyl.petrichor.weather.Wetness;

/**
 * The weather as the client draws and plays it, updated every tick.
 *
 * <p>The rain level (see {@link RainType#mix}) comes from the server when it has this mod, otherwise it follows the
 * shared {@link RainSchedule} the same way the server does: every rain starts as a drizzle, swells step by step and
 * eases off. All look-and-sound parameters are blended from the level, so nothing ever switches. Wetness and sun
 * showers follow the server too, or are worked out locally the same way.
 *
 * <p>The rain level for everything that fills the whole view or the ears (sound, haze, sky, light, curtains) follows
 * the land around the listener: towards land where it snows or never rains (deserts, badlands) it fades out, and in
 * such land it is zero - the weather there is vanilla's. Drops, splashes and puddles go column by column and use
 * {@link #localRain()}; snowfall is always drawn by vanilla.
 */
public final class ClientWeather {
    /** Server state older than this is ignored (the server stopped sending: it does not have the mod). */
    private static final int SERVER_TIMEOUT = 200;
    /** How fast the client's rain level follows the server's (which itself moves slowly). */
    private static final float FOLLOW = 0.05F;
    /** Sunshine comes out or hides behind the clouds over 15 seconds. */
    private static final float SUN_RATE = 1.0F / 300.0F;

    private static long ticks;
    private static long lastSync = Long.MIN_VALUE;
    private static int serverType = -1;
    private static float serverLevel;
    private static boolean serverSunShower;
    private static float serverWetness;
    private static boolean initialized;

    private static RainType type = RainType.RAIN;
    /** The rain level, 0 (drizzle) .. 3 (thunderstorm). */
    private static float level;
    /** How much the sun shines through the rain, 0..1: a sun shower. */
    private static float sunshine;
    /** The world's rain level, before the land around the listener is taken into account. */
    private static float worldRain;
    private static float rain;
    /** Share of the land around the listener where rain (not snow) falls, gliding. */
    private static float presence = 1.0F;
    private static float presenceTarget = 1.0F;
    private static boolean noRain;
    private static float thunder;
    private static float gust = 1.0F;
    private static float windX;
    private static float windZ;
    // Distances covered so far, summed tick by tick. Anything that moves with the wind or falls uses these, never
    // "speed x time": with a speed that changes (gusts, a new rain type) that product jumps back and forth.
    private static double driftX;
    private static double driftZ;
    private static double travel;
    private static double fallen;
    private static float wetness;

    // The blended parameters of the current type.
    public static float density;
    public static float fallSpeed;
    public static float streakLength;
    public static float streakWidth;
    public static float alpha;
    public static float gustiness;
    public static float visibility;
    public static float splash;
    public static float heaviness;
    private static float windBase;

    private ClientWeather() {
    }

    public static void onServerSync(int type, float rainLevel, boolean sunShower, float wetness) {
        serverType = type;
        serverLevel = rainLevel;
        serverSunShower = sunShower;
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
        sunshine = 0.0F;
        presence = presenceTarget = 1.0F;
        noRain = false;
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
        float stepTicks = Config.stepTicks();
        if (!initialized) {
            ClientWeather.level = worldRain > 0.0F ? targetLevel(level) : 0.0F;
        } else if (serverActive()) {
            ClientWeather.level += (serverLevel - ClientWeather.level) * FOLLOW;
        } else if (worldRain > 0.0F) {
            ClientWeather.level = RainSchedule.approach(ClientWeather.level, targetLevel(level), stepTicks);
        } else {
            // The next rain starts as a drizzle.
            ClientWeather.level = 0.0F;
        }
        type = RainType.at(ClientWeather.level);
        apply(ClientWeather.level);
        float sunTarget = sunShower(level) ? 1.0F : 0.0F;
        sunshine = initialized ? sunshine + Math.clamp(sunTarget - sunshine, -SUN_RATE, SUN_RATE) : sunTarget;
        if (!initialized) {
            initialized = true;
            wetness = serverActive() ? serverWetness : worldRain * type.wetnessCap * 0.6F;
        }
        double time = level.getGameTime();
        gust = RainSchedule.gust(time, gustiness);
        float angle = RainSchedule.windAngle(time);
        float speed = RainSchedule.windSpeed(time, windBase, gustiness) * (0.4F + 0.6F * rain);
        windX = (float) Math.cos(angle) * speed;
        windZ = (float) Math.sin(angle) * speed;
        driftX += windX;
        driftZ += windZ;
        travel += Math.max(speed, 0.075F);
        fallen += fallSpeed;

        if (serverActive()) {
            wetness += Math.clamp(serverWetness - wetness, -0.02F, 0.02F);
        } else {
            wetness = Wetness.step(wetness, worldRain, worldRain > 0.0F ? ClientWeather.level : -1.0F, level.isDay(),
                    Config.FILL_SPEED.get(), Config.DRYING_SPEED.get());
        }
    }

    /**
     * Where around the listener it rains: 25 points over a 48-block square, each checked at the height the rain
     * reaches there (mountain tops can be snowy above rainy valleys).
     */
    private static void surveyLand(ClientLevel level) {
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int rainy = 0;
        boolean rainHere = false;
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                int x = Mth.floor(cam.x) + i * 12;
                int z = Mth.floor(cam.z) + j * 12;
                pos.set(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
                if (Seasons.precipitationAt(level, pos) == Biome.Precipitation.RAIN) {
                    rainy++;
                    rainHere |= i == 0 && j == 0;
                }
            }
        }
        presenceTarget = rainy / 25.0F;
        // Snowy or dry land (deserts, badlands, savannas): no rain to hear or see around. Some hysteresis, so walking
        // along a biome border does not flip back and forth.
        int other = 25 - rainy;
        if (!rainHere || other > 10) {
            noRain = true;
        } else if (other < 5) {
            noRain = false;
        }
        if (noRain) {
            presenceTarget = 0.0F;
        }
    }

    /** The rain level to head for without the server: the shared schedule, light while the sun shines through. */
    private static float targetLevel(ClientLevel level) {
        boolean thundering = thunder > 0.5F;
        float target = Config.naturalLevel(level, thundering);
        if (!thundering && Config.naturalSunShower(level) && sunHeight(level, 1.0F) > 0.0F) {
            target = Math.min(target, RainSchedule.SUN_SHOWER_LEVEL);
        }
        return target;
    }

    /** Whether the sun shines through the rain now: a light rain in daylight, without thunder. */
    private static boolean sunShower(ClientLevel level) {
        boolean scheduled = serverActive() ? serverSunShower : Config.naturalSunShower(level);
        return scheduled && thunder < 0.1F && ClientWeather.level < RainSchedule.SUN_SHOWER_LEVEL + 0.5F
                && sunHeight(level, 1.0F) > 0.05F;
    }

    /** Height of the sun over the horizon: the sine of its elevation, -1..1. */
    public static float sunHeight(ClientLevel level, float partialTick) {
        return Mth.cos(level.getSunAngle(partialTick));
    }

    private static void apply(float level) {
        density = RainType.mix(level, t -> t.density);
        fallSpeed = RainType.mix(level, t -> t.fallSpeed);
        streakLength = RainType.mix(level, t -> t.streakLength);
        streakWidth = RainType.mix(level, t -> t.streakWidth);
        alpha = RainType.mix(level, t -> t.alpha);
        windBase = RainType.mix(level, t -> t.wind);
        gustiness = RainType.mix(level, t -> t.gustiness);
        visibility = RainType.mix(level, t -> t.visibility);
        splash = RainType.mix(level, t -> t.splash);
        heaviness = RainType.mix(level, t -> t.heaviness);
    }

    public static RainType type() {
        return type;
    }

    /** The rain level, 0 (drizzle) .. 3 (thunderstorm), see {@link RainType#mix}. */
    public static float level() {
        return level;
    }

    /**
     * How much the sun shines through the rain, 0..1. In a sun shower the sky stays blue and the light bright: what
     * darkens the world for rain gives way to it.
     */
    public static float sunshine() {
        return sunshine;
    }

    /** What is left of the rain's shade on sky and light in the sunshine, 0.15..1. */
    public static float shade() {
        return 1.0F - 0.85F * sunshine;
    }

    /**
     * Rain level, 0..1: vanilla's (fades in and out when rain starts and stops) times the share of the land around
     * the listener where it rains rather than snows.
     */
    public static float rain() {
        return rain;
    }

    /**
     * The world's rain level, for what happens column by column (drops, splashes, puddles): those only ever happen
     * where rain falls, so they need no fading at the edge of snowy or dry land.
     */
    public static float localRain() {
        return worldRain;
    }

    /** {@link #localRain()} with gusts. */
    public static float localIntensity() {
        return worldRain * gust;
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

    /** How far the wind has carried the air, in blocks, with the partial tick. */
    public static double driftX(float partialTick) {
        return driftX + windX * partialTick;
    }

    public static double driftZ(float partialTick) {
        return driftZ + windZ * partialTick;
    }

    /** How far gusts and sheets of rain have swept along the wind, in blocks (at least a slow drift in calm air). */
    public static double travel(float partialTick) {
        return travel + Math.max(Math.sqrt(windX * windX + windZ * windZ), 0.075) * partialTick;
    }

    /** How far the rain has fallen, in blocks. */
    public static double fallen(float partialTick) {
        return fallen + fallSpeed * partialTick;
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
