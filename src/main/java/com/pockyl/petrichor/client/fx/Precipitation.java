package com.pockyl.petrichor.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import org.joml.Vector3f;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;
import com.pockyl.petrichor.weather.Noise;

/**
 * Falling rain drops and snowflakes around the camera.
 *
 * <p>No state per drop: every column of the world owns a few drops whose height is a pure function of time
 * (they fall through a repeating 48-block window anchored to the world), so the field costs nothing to keep and looks
 * the same from frame to frame. Drops fall at the speed of the rain type, slant with the wind, stop at the first block
 * in their way, and come in bands of heavier rain that sweep along with the wind.
 */
public final class Precipitation {
    private static final int CYCLE = 48;
    private static final float MAX_TYPE_DENSITY = 2.4F;
    private static final int SEED_PHASE = 0x0D20_0001;
    private static final int SEED_X = 0x0D20_0002;
    private static final int SEED_Z = 0x0D20_0003;
    private static final int SEED_COUNT = 0x0D20_0004;
    private static final float SNOW_SPEED = 0.055F;

    private int lastDrops;

    public int lastDrops() {
        return lastDrops;
    }

    /**
     * @param time game ticks with the partial tick
     */
    public void render(VertexConsumer out, Columns columns, double camX, double camY, double camZ, double time, Vector3f left, Vector3f up,
            float[] fog) {
        ClientConfig.Quality quality = ClientConfig.quality();
        int radius = quality.rainRadius;
        float rain = ClientWeather.rain();
        float intensity = ClientWeather.intensity();
        if (rain <= 0.0F) {
            lastDrops = 0;
            return;
        }
        float budget = (float) (quality.maxDrops * ClientConfig.RAIN_DENSITY.get());
        float perColumn = budget / (Mth.PI * radius * radius * MAX_TYPE_DENSITY);
        float rainExpected = perColumn * ClientWeather.density * Math.min(intensity, 1.6F);
        float snowExpected = perColumn * 0.8F * Math.min(rain, 1.0F);
        float wind = (float) (double) ClientConfig.WIND.get();
        float fall = ClientWeather.fallSpeed;
        float windX = ClientWeather.windX() * wind;
        float windZ = ClientWeather.windZ() * wind;
        float slantX = Math.clamp(windX / fall, -0.8F, 0.8F);
        float slantZ = Math.clamp(windZ / fall, -0.8F, 0.8F);
        float snowSlantX = Math.clamp(windX / SNOW_SPEED * 0.5F, -1.5F, 1.5F);
        float snowSlantZ = Math.clamp(windZ / SNOW_SPEED * 0.5F, -1.5F, 1.5F);
        float windSpeed = Mth.sqrt(windX * windX + windZ * windZ);
        float bandX = windSpeed > 1.0E-4F ? windX / windSpeed : 1.0F;
        float bandZ = windSpeed > 1.0E-4F ? windZ / windSpeed : 0.0F;
        float bandPhase = (float) (time * Math.max(windSpeed, 0.02F) * 0.6 % 2200.0);
        float bands = ClientWeather.gustiness * 0.55F;
        float length = ClientWeather.streakLength;
        float width = ClientWeather.streakWidth;
        float baseAlpha = ClientWeather.alpha * Math.min(1.0F, 0.35F + rain * 0.65F);
        double anchor = Math.floor(camY / 16.0) * 16.0 - 16.0;
        // Drops take the colour of the light around them: a little brighter than the haze.
        float dropR = Math.min(1.0F, fog[0] * 0.6F + 0.42F);
        float dropG = Math.min(1.0F, fog[1] * 0.6F + 0.45F);
        float dropB = Math.min(1.0F, fog[2] * 0.6F + 0.5F);
        double rainShift = time * fall;
        double snowShift = time * SNOW_SPEED;
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
                byte kind = columns.precipitation(cx, cz);
                if (kind == Columns.NONE) {
                    continue;
                }
                boolean snow = kind == Columns.SNOW;
                float expected;
                if (snow) {
                    expected = snowExpected;
                } else {
                    float wave = Mth.sin(((cx * bandX + cz * bandZ) / 22.0F - bandPhase / 22.0F) * Mth.TWO_PI);
                    expected = rainExpected * (1.0F + bands * wave);
                }
                float columnDistance = Mth.sqrt(dist2);
                float edgeFade = 1.0F - Math.clamp((columnDistance - fadeStart) / (radius - fadeStart), 0.0F, 1.0F);
                // Most drops close to the eye, where they can be seen; the distance is the curtains' job.
                if (!snow) {
                    expected *= Math.clamp(1.9F - columnDistance / 12.0F, 0.3F, 1.9F);
                }
                int n = (int) expected;
                if (Noise.unit(cx, cz, SEED_COUNT) < expected - n) {
                    n++;
                }
                n = Math.min(n, 12);
                for (int i = 0; i < n; i++) {
                    float phase = Noise.unit(cx, cz, i, SEED_PHASE);
                    double shift = snow ? snowShift : rainShift;
                    double fallen = (shift + phase * CYCLE) % CYCLE;
                    double y = anchor + CYCLE - fallen;
                    float jx = Noise.unit(cx, cz, i, SEED_X);
                    float jz = Noise.unit(cx, cz, i, SEED_Z);
                    double x;
                    double z;
                    if (snow) {
                        float sway = Mth.sin((float) (time * 0.035 % Mth.TWO_PI) + phase * 40.0F) * 0.3F;
                        x = cx + jx + snowSlantX * fallen + sway;
                        z = cz + jz + snowSlantZ * fallen + sway * 0.6F;
                    } else {
                        x = cx + jx + slantX * fallen;
                        z = cz + jz + slantZ * fallen;
                    }
                    int ix = Mth.floor(x);
                    int iz = Mth.floor(z);
                    if (y < columns.height(ix, iz)) {
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
                    if (snow) {
                        float a = 0.85F * edgeFade * window * near;
                        float half = 0.035F + jx * 0.03F;
                        snowflake(out, hx, hy, hz, half, a, light, left, up);
                    } else {
                        // Drops close to the eye are seen larger and blurred; far ones keep at least a pixel of width.
                        float close = Math.clamp(1.0F - (d - 1.5F) / 5.0F, 0.0F, 1.0F);
                        float w = Math.max(width * 2.2F * (1.0F + close * 1.2F), d * 0.0045F);
                        float a = Math.min(0.9F, baseAlpha * 1.6F * edgeFade * window * near * Mth.sqrt(width / w) * (1.0F + close * 0.6F));
                        Streaks.streak(out, hx, hy, hz, slantX, -1.0F, slantZ, length * (0.8F + jz * 0.4F) * (1.0F + close * 0.5F), w,
                                FxAtlas.STREAK, a, 0.0F, light, dropR, dropG, dropB);
                    }
                    drops++;
                }
            }
        }
        lastDrops = drops;
    }

    private static void snowflake(VertexConsumer out, float cx, float cy, float cz, float half, float a, int light, Vector3f left,
            Vector3f up) {
        float lx = left.x() * half;
        float ly = left.y() * half;
        float lz = left.z() * half;
        float ux = up.x() * half;
        float uy = up.y() * half;
        float uz = up.z() * half;
        float u0 = FxAtlas.u0(FxAtlas.FLAKE);
        float v0 = FxAtlas.v0(FxAtlas.FLAKE);
        float u1 = FxAtlas.u1(FxAtlas.FLAKE);
        float v1 = FxAtlas.v1(FxAtlas.FLAKE);
        out.addVertex(cx + lx - ux, cy + ly - uy, cz + lz - uz).setUv(u0, v1).setColor(1.0F, 1.0F, 1.0F, a).setLight(light);
        out.addVertex(cx - lx - ux, cy - ly - uy, cz - lz - uz).setUv(u1, v1).setColor(1.0F, 1.0F, 1.0F, a).setLight(light);
        out.addVertex(cx - lx + ux, cy - ly + uy, cz - lz + uz).setUv(u1, v0).setColor(1.0F, 1.0F, 1.0F, a).setLight(light);
        out.addVertex(cx + lx + ux, cy + ly + uy, cz + lz + uz).setUv(u0, v0).setColor(1.0F, 1.0F, 1.0F, a).setLight(light);
    }
}
