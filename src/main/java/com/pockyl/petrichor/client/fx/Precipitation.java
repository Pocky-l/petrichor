package com.pockyl.petrichor.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;
import com.pockyl.petrichor.weather.Noise;
import com.pockyl.petrichor.world.DropPath;

/**
 * Falling rain drops around the camera (snowfall stays vanilla).
 *
 * <p>No state per drop: every column of the world owns a few drops whose height is a pure function of time
 * (they fall through a repeating 48-block window anchored to the world), so the field costs nothing to keep and looks
 * the same from frame to frame. Drops fall at the speed of the rain type, slant with the wind, and come in bands of
 * heavier rain that sweep along with the wind.
 *
 * <p>A drop ends where its path meets the terrain - on the top of a block or, falling slanted, on the side of a wall -
 * and never comes out on the far side.
 */
public final class Precipitation {
    private static final int CYCLE = 48;
    private static final float MAX_TYPE_DENSITY = 2.6F;
    private static final int SEED_PHASE = 0x0D20_0001;
    private static final int SEED_X = 0x0D20_0002;
    private static final int SEED_Z = 0x0D20_0003;
    private static final int SEED_COUNT = 0x0D20_0004;
    private static final int SEED_VARY = 0x0D20_0005;

    /** Drops closer than this (blocks) are checked against the terrain along their path, not only where they are. */
    private static final float PATH_CHECK = 20.0F;

    private int lastDrops;

    public int lastDrops() {
        return lastDrops;
    }

    /**
     * @param rain drops, drawn with additive blending: rain is light caught in water, it brightens what is behind it
     * @param time  game ticks with the partial tick
     * @param flash lightning flash: a strobe that lights every drop and freezes its motion
     */
    public void render(VertexConsumer rain, Columns columns, double camX, double camY, double camZ, double time, float[] fog, float flash) {
        ClientConfig.Quality quality = ClientConfig.quality();
        int radius = quality.rainRadius;
        float rainLevel = ClientWeather.localRain();
        float intensity = ClientWeather.localIntensity();
        if (rainLevel <= 0.0F) {
            lastDrops = 0;
            return;
        }
        float budget = (float) (quality.maxDrops * ClientConfig.RAIN_DENSITY.get());
        float perColumn = budget / (Mth.PI * radius * radius * MAX_TYPE_DENSITY);
        float rainExpected = perColumn * ClientWeather.density * Math.min(intensity, 1.6F);
        float wind = (float) (double) ClientConfig.WIND.get();
        float fall = ClientWeather.fallSpeed;
        float windX = ClientWeather.windX() * wind;
        float windZ = ClientWeather.windZ() * wind;
        float slantX = Math.clamp(windX / fall, -0.8F, 0.8F);
        float slantZ = Math.clamp(windZ / fall, -0.8F, 0.8F);
        float windSpeed = Mth.sqrt(windX * windX + windZ * windZ);
        float bandX = windSpeed > 1.0E-4F ? windX / windSpeed : 1.0F;
        float bandZ = windSpeed > 1.0E-4F ? windZ / windSpeed : 0.0F;
        float partialTick = (float) (time - Math.floor(time));
        float bandPhase = (float) (ClientWeather.travel(partialTick) * 0.6 % 2200.0);
        float bands = ClientWeather.gustiness * 0.55F;
        float length = ClientWeather.streakLength;
        float width = ClientWeather.streakWidth;
        float baseAlpha = ClientWeather.alpha * Math.min(1.0F, 0.35F + rainLevel * 0.65F);
        double anchor = Math.floor(camY / 16.0) * 16.0 - 16.0;
        // Drops take the colour of the light around them: a little brighter than the haze.
        float dropR = Math.min(1.0F, fog[0] * 0.5F + 0.36F);
        float dropG = Math.min(1.0F, fog[1] * 0.5F + 0.39F);
        float dropB = Math.min(1.0F, fog[2] * 0.5F + 0.44F);
        // In the light of a flash every drop shines, and the short flash freezes it into a bead instead of a streak.
        float strobe = Math.min(1.0F, flash);
        dropR = Math.min(1.0F, dropR + strobe * 0.45F);
        dropG = Math.min(1.0F, dropG + strobe * 0.47F);
        dropB = Math.min(1.0F, dropB + strobe * 0.5F);
        float strobeLength = 1.0F - strobe * 0.65F;
        float strobeAlpha = 1.0F + strobe * 0.8F;
        double rainShift = ClientWeather.fallen(partialTick);
        int ccx = Mth.floor(camX);
        int ccz = Mth.floor(camZ);
        float radiusSq = radius * radius;
        float fadeStart = radius * 0.7F;
        int drops = 0;

        for (int cz = ccz - radius; cz <= ccz + radius; cz++) {
            for (int cx = ccx - radius; cx <= ccx + radius; cx++) {
                float ddx = (cx - ccx) + 0.5F - (float) (camX - ccx);
                float ddz = (cz - ccz) + 0.5F - (float) (camZ - ccz);
                float dist2 = ddx * ddx + ddz * ddz;
                if (dist2 > radiusSq) {
                    continue;
                }
                if (columns.precipitation(cx, cz) != Columns.RAIN) {
                    continue;
                }
                float band = Mth.sin(((cx * bandX + cz * bandZ) / 22.0F - bandPhase / 22.0F) * Mth.TWO_PI);
                float expected = rainExpected * (1.0F + bands * band);
                float columnDistance = Mth.sqrt(dist2);
                float edgeFade = 1.0F - Math.clamp((columnDistance - fadeStart) / (radius - fadeStart), 0.0F, 1.0F);
                // More drops close to the eye, where they can be seen one by one, but enough further out to fill the
                // middle distance until the curtains take over.
                expected *= Math.clamp(1.7F - columnDistance / 16.0F, 0.65F, 1.7F);
                int n = (int) expected;
                if (Noise.unit(cx, cz, SEED_COUNT) < expected - n) {
                    n++;
                }
                n = Math.min(n, 12);
                for (int i = 0; i < n; i++) {
                    float phase = Noise.unit(cx, cz, i, SEED_PHASE);
                    // Every drop a little different: size, and with it speed, length and brightness.
                    float vary = Noise.unit(cx, cz, i, SEED_VARY);
                    double shift = rainShift * (0.9 + vary * 0.2);
                    double fallen = (shift + phase * CYCLE) % CYCLE;
                    double y = anchor + CYCLE - fallen;
                    float jx = Noise.unit(cx, cz, i, SEED_X);
                    float jz = Noise.unit(cx, cz, i, SEED_Z);
                    double x = cx + jx + slantX * fallen;
                    double z = cz + jz + slantZ * fallen;
                    int ix = Mth.floor(x);
                    int iz = Mth.floor(z);
                    boolean gone = columnDistance < PATH_CHECK ? DropPath.blocked(columns, x, y, z, slantX, slantZ, fallen)
                            : y < columns.top(ix, iz);
                    if (gone) {
                        continue;
                    }
                    float hx = (float) (x - camX);
                    float hy = (float) (y - camY);
                    float hz = (float) (z - camZ);
                    float d2 = hx * hx + hy * hy + hz * hz;
                    if (d2 < 0.5F) {
                        continue;
                    }
                    float d = Mth.sqrt(d2);
                    float window = Math.min(1.0F, (float) (CYCLE - fallen) / 4.0F) * Math.min(1.0F, (float) fallen / 4.0F);
                    float near = Math.min(1.0F, (d - 0.7F) / 1.5F);
                    int light = columns.light(ix, iz);
                    // Close drops are big; the closest ones are out of focus - wide, long and faint.
                    float close = Math.clamp(1.0F - (d - 1.0F) / 4.0F, 0.0F, 1.0F);
                    float blur = Math.clamp(1.0F - (d - 0.6F) / 1.8F, 0.0F, 1.0F);
                    float size = 0.75F + vary * 0.5F;
                    // Far drops keep about a pixel of width - less for the fine drops of a drizzle.
                    float w = Math.max(width * 2.0F * size * (1.0F + close * 0.8F + blur * 2.5F),
                            d * 0.004F * Math.min(1.0F, width / 0.016F));
                    float len = length * (0.6F + vary * 0.8F) * (1.0F + close * 0.4F + blur * 0.6F) * strobeLength;
                    float sparkle = 0.55F + jz * 0.45F;
                    // Drops kept a pixel wide far away fade only a little: together they are the grey veil of rain.
                    float thin = (float) Math.pow(width * 2.0F / w, 0.3);
                    float a = Math.min(0.95F, baseAlpha * 1.3F * sparkle * edgeFade * window * near * thin * (1.0F - blur * 0.55F) * strobeAlpha);
                    float tilt = 1.0F + (vary - 0.5F) * 0.2F;
                    // Slow, fine drops (drizzle) waver in the air instead of falling in straight lines.
                    float floaty = Math.clamp((0.5F - fall) / 0.3F, 0.0F, 1.0F);
                    float swayX = 0.0F;
                    float swayZ = 0.0F;
                    if (floaty > 0.0F) {
                        float wave = (float) (time * 0.07 % Mth.TWO_PI) + phase * 37.0F;
                        swayX = Mth.sin(wave) * 0.18F * floaty;
                        swayZ = Mth.cos(wave * 0.8F) * 0.18F * floaty;
                        hx += swayX;
                        hz += swayZ;
                    }
                    Streaks.streak(rain, hx, hy, hz, slantX * tilt + swayX * 0.6F, -1.0F, slantZ * tilt + swayZ * 0.6F, len, w,
                            FxAtlas.STREAK, a, a, light, dropR, dropG, dropB);
                    drops++;
                }
            }
        }
        lastDrops = drops;
    }
}
