package com.pockyl.petrichor.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;
import com.pockyl.petrichor.client.WeatherClient;

/**
 * Curtains of rain in the distance: rings of tall sheets around the camera, textured with falling streaks, at several
 * distances so they overlap in depth and dissolve into the haze. Their density swells and fades in bands that travel
 * with the wind - the sheets of a storm sweeping across a field. Beyond the last ring the atmosphere takes over with
 * soft showers: textured curtains far away read as a wall, not as rain. Terrain in front hides them through the depth
 * test; each curtain stands on the ground the rain reaches there, so it never hangs inside a cave or under a roof.
 */
public final class RainVeils {
    public static final ResourceLocation TEXTURE = Petrichor.id("textures/fx/rain_sheet.png");
    private static final float[] RADII = {11.0F, 16.0F, 24.0F, 34.0F, 48.0F, 68.0F};
    private static final int SEGMENTS = 56;
    /** Longest stretch of a ring between two ground samples, so the curtains follow the land. */
    private static final float SEGMENT_LENGTH = 3.0F;
    /** Height over the ground in which a curtain fades in: at eye level it is already dense. */
    private static final float FADE_IN = 2.0F;
    /** Blocks covered by one width / height of the texture next to the camera; far rings use coarser streaks. */
    private static final float TILE_WIDTH = 7.0F;
    private static final float TILE_HEIGHT = 14.0F;
    private static final float BELOW = 20.0F;
    private static final float ABOVE = 46.0F;

    private RainVeils() {
    }

    /**
     * @param fog    current fog colour; the curtains are a little lighter than the haze
     * @param time   game ticks with the partial tick
     * @param haze   haze per block of the atmosphere, 0 when it is off
     */
    public static void render(VertexConsumer out, Columns columns, double camX, double camY, double camZ, double time, float[] fog,
            float fogEnd, float haze) {
        float rain = ClientWeather.rain();
        if (rain <= 0.0F || columns.precipitation(Mth.floor(camX), Mth.floor(camZ)) == Columns.SNOW) {
            return;
        }
        // Every rain fills the middle distance: faintly in a drizzle, as a grey veil in rain, in dense sheets in a downpour.
        float strength = Math.min(1.2F, ClientWeather.intensity()) * (0.12F + 0.88F * ClientWeather.heaviness) * 1.15F;
        // The rings stand around the camera wherever it is: in a cave larger than the nearest ring they would hang in
        // the air inside it, so they are only drawn where the sky can be seen.
        strength *= WeatherClient.skyView((float) (time - Math.floor(time)));
        if (strength <= 0.001F) {
            return;
        }
        float windX = ClientWeather.windX();
        float windZ = ClientWeather.windZ();
        float windSpeed = Mth.sqrt(windX * windX + windZ * windZ);
        float windAngle = (float) Math.atan2(windZ, windX);
        float slant = Math.min(0.6F, windSpeed / Math.max(ClientWeather.fallSpeed, 0.05F));
        float seconds = (float) (time / 20.0 % 3600.0);
        float partialTick = (float) (time - Math.floor(time));
        double fallen = ClientWeather.fallen(partialTick);
        float sweep = (float) (ClientWeather.travel(partialTick) % 100000.0);
        // Gusty storms sweep in sheets with clear gaps between them; steady rain hangs evenly.
        float contrast = 0.35F + 0.4F * ClientWeather.gustiness;
        float lightR = Math.min(1.0F, fog[0] * 1.15F + 0.16F);
        float lightG = Math.min(1.0F, fog[1] * 1.15F + 0.17F);
        float lightB = Math.min(1.0F, fog[2] * 1.15F + 0.2F);
        int light = LightTexture.pack(0, 15);
        float limit = fogEnd * 1.1F;
        for (int ring = 0; ring < RADII.length; ring++) {
            float radius = RADII[ring];
            if (radius > limit) {
                break;
            }
            // A curtain stands in the haze like the land behind it: further ones are paler and fainter.
            float hazed = haze > 0.0F ? 1.0F - (float) Math.exp(-haze * radius) : 0.0F;
            // The outermost ring fades out, so the curtains end softly in the haze.
            float edge = ring == RADII.length - 1 ? 0.5F : 1.0F;
            float ringAlpha = strength * 0.09F * (1.0F - hazed * 0.55F) * edge;
            float r = Mth.lerp(hazed * 0.6F, lightR, fog[0]);
            float g = Mth.lerp(hazed * 0.6F, lightG, fog[1]);
            float b = Mth.lerp(hazed * 0.6F, lightB, fog[2]);
            float coarse = 1.0F + radius / 120.0F;
            float tileWidth = TILE_WIDTH * coarse;
            float tileHeight = TILE_HEIGHT * coarse;
            float top = ABOVE + radius * 0.15F;
            float scroll = (float) (fallen / tileHeight % 1000.0) * (0.9F + ring * 0.04F) + ring * 0.37F;
            float circumference = Mth.TWO_PI * radius;
            int segments = Math.max(SEGMENTS, Mth.ceil(circumference / SEGMENT_LENGTH));
            float previousGround = ground(columns, camX, camY, camZ, radius, 0.0F);
            for (int s = 0; s < segments; s++) {
                float a0 = s * Mth.TWO_PI / segments;
                float a1 = (s + 1) * Mth.TWO_PI / segments;
                float x0 = Mth.cos(a0) * radius;
                float z0 = Mth.sin(a0) * radius;
                float x1 = Mth.cos(a1) * radius;
                float z1 = Mth.sin(a1) * radius;
                float g0 = previousGround;
                float g1 = ground(columns, camX, camY, camZ, radius, a1);
                previousGround = g1;
                float m0 = g0 + FADE_IN;
                float m1 = g1 + FADE_IN;
                if (m0 >= top && m1 >= top) {
                    // Higher land than the curtain: no rain hangs there.
                    continue;
                }
                // Bands of heavier rain sweeping past with the wind.
                float mid = (a0 + a1) * 0.5F;
                float along = Mth.cos(mid - windAngle) * radius;
                float alpha = ringAlpha * band(along, mid, seconds, sweep, ring, contrast);
                if (alpha < 0.002F) {
                    continue;
                }
                float u0 = s * circumference / segments / tileWidth + ring * 0.21F;
                float u1 = (s + 1) * circumference / segments / tileWidth + ring * 0.21F;
                // Slant: the top of the curtain leans away from where the wind comes from.
                float lean0 = -Mth.sin(a0 - windAngle) * slant / tileWidth;
                float lean1 = -Mth.sin(a1 - windAngle) * slant / tileWidth;
                quad(out, x0, z0, x1, z1, g0, g1, m0, m1, u0, u1, lean0, lean1, scroll, tileHeight, r, g, b, 0.0F, alpha, light);
                quad(out, x0, z0, x1, z1, m0, m1, top, top, u0, u1, lean0, lean1, scroll, tileHeight, r, g, b, alpha, 0.0F, light);
            }
        }
    }

    /** Height (relative to the camera) of the ground the rain reaches at a point of a ring, not below the curtain's foot. */
    private static float ground(Columns columns, double camX, double camY, double camZ, float radius, float angle) {
        int x = Mth.floor(camX + Mth.cos(angle) * radius);
        int z = Mth.floor(camZ + Mth.sin(angle) * radius);
        return Math.max(-BELOW, (float) (columns.height(x, z) - camY));
    }

    private static float band(float along, float angle, float seconds, float sweep, int ring, float contrast) {
        float wave = Mth.sin((along - sweep) / 18.0F + ring * 1.7F);
        // A second, slower pattern around the ring, so the sheets are not all the same width.
        float patch = Mth.sin(angle * 3.0F + seconds * 0.05F + ring * 0.9F);
        float slow = Mth.sin(seconds * 0.07F + ring * 2.3F);
        return Mth.clamp(0.55F + contrast * wave + 0.12F * patch + 0.12F * slow, 0.0F, 1.3F);
    }

    /** A band of curtain from the lower edge ({@code b0} at the first end, {@code b1} at the second) to the upper one. */
    private static void quad(VertexConsumer out, float x0, float z0, float x1, float z1, float b0, float b1, float t0, float t1,
            float u0, float u1, float lean0, float lean1, float scroll, float tileHeight, float r, float g, float b, float alpha0,
            float alpha1, int light) {
        if (t0 <= b0 && t1 <= b1) {
            return;
        }
        t0 = Math.max(t0, b0);
        t1 = Math.max(t1, b1);
        out.vertex(x0, b0, z0).uv(u0 + lean0 * b0, -b0 / tileHeight - scroll).color(r, g, b, alpha0).uv2(light).endVertex();
        out.vertex(x1, b1, z1).uv(u1 + lean1 * b1, -b1 / tileHeight - scroll).color(r, g, b, alpha0).uv2(light).endVertex();
        out.vertex(x1, t1, z1).uv(u1 + lean1 * t1, -t1 / tileHeight - scroll).color(r, g, b, alpha1).uv2(light).endVertex();
        out.vertex(x0, t0, z0).uv(u0 + lean0 * t0, -t0 / tileHeight - scroll).color(r, g, b, alpha1).uv2(light).endVertex();
    }
}
