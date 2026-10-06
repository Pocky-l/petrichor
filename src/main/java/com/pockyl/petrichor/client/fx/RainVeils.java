package com.pockyl.petrichor.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;

/**
 * Curtains of rain in the distance: rings of tall sheets around the camera, textured with falling streaks, at several
 * distances so they overlap in depth and dissolve into the haze. Their density swells and fades in bands that travel
 * with the wind - the sheets of a storm sweeping across a field. Far curtains have coarser streaks and reach up towards
 * the clouds. Terrain in front hides them through the depth test.
 */
public final class RainVeils {
    public static final ResourceLocation TEXTURE = Petrichor.id("textures/fx/rain_sheet.png");
    private static final float[] RADII = {16.0F, 24.0F, 34.0F, 48.0F, 68.0F, 96.0F, 136.0F};
    private static final int SEGMENTS = 56;
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
     * @param haze   haze per block of the atmosphere, 0 when it is off (then the vanilla fog end limits the rings)
     */
    public static void render(VertexConsumer out, Columns columns, double camX, double camY, double camZ, double time, float[] fog,
            float fogEnd, float haze) {
        float rain = ClientWeather.rain();
        if (rain <= 0.0F || columns.precipitation(Mth.floor(camX), Mth.floor(camZ)) == Columns.SNOW) {
            return;
        }
        // Curtains belong to heavy rain: hardly any in a drizzle, dense ones in a downpour.
        float heaviness = Math.clamp((ClientWeather.density - 0.5F) / 2.1F, 0.0F, 1.0F);
        float strength = Math.min(1.2F, ClientWeather.intensity()) * heaviness * heaviness * 1.3F;
        if (strength <= 0.001F) {
            return;
        }
        float fall = ClientWeather.fallSpeed * 20.0F;
        float windX = ClientWeather.windX();
        float windZ = ClientWeather.windZ();
        float windSpeed = Mth.sqrt(windX * windX + windZ * windZ);
        float windAngle = (float) Math.atan2(windZ, windX);
        float slant = Math.min(0.6F, windSpeed / Math.max(ClientWeather.fallSpeed, 0.05F));
        float seconds = (float) (time / 20.0 % 3600.0);
        // Gusty storms sweep in sheets with clear gaps between them; steady rain hangs evenly.
        float contrast = 0.35F + 0.4F * ClientWeather.gustiness;
        float lightR = Math.min(1.0F, fog[0] * 1.15F + 0.16F);
        float lightG = Math.min(1.0F, fog[1] * 1.15F + 0.17F);
        float lightB = Math.min(1.0F, fog[2] * 1.15F + 0.2F);
        int light = LightTexture.pack(0, 15);
        float bottom = -BELOW;
        float middle = 6.0F;
        float limit = haze > 0.0F ? Float.MAX_VALUE : fogEnd * 1.1F;
        for (int ring = 0; ring < RADII.length; ring++) {
            float radius = RADII[ring];
            if (radius > limit) {
                break;
            }
            // A curtain stands in the haze like the land behind it: further ones are paler and fainter.
            float hazed = haze > 0.0F ? 1.0F - (float) Math.exp(-haze * radius) : 0.0F;
            float ringAlpha = strength * 0.09F * (1.0F - hazed * 0.55F);
            float r = Mth.lerp(hazed * 0.6F, lightR, fog[0]);
            float g = Mth.lerp(hazed * 0.6F, lightG, fog[1]);
            float b = Mth.lerp(hazed * 0.6F, lightB, fog[2]);
            float coarse = 1.0F + radius / 60.0F;
            float tileWidth = TILE_WIDTH * coarse;
            float tileHeight = TILE_HEIGHT * coarse;
            // Far curtains hang from higher up: the rain is seen falling from the clouds.
            float top = ABOVE + radius * 0.45F;
            float scroll = seconds * fall / tileHeight * (0.9F + ring * 0.04F) + ring * 0.37F;
            float circumference = Mth.TWO_PI * radius;
            for (int s = 0; s < SEGMENTS; s++) {
                float a0 = s * Mth.TWO_PI / SEGMENTS;
                float a1 = (s + 1) * Mth.TWO_PI / SEGMENTS;
                float x0 = Mth.cos(a0) * radius;
                float z0 = Mth.sin(a0) * radius;
                float x1 = Mth.cos(a1) * radius;
                float z1 = Mth.sin(a1) * radius;
                // Bands of heavier rain sweeping past with the wind.
                float mid = (a0 + a1) * 0.5F;
                float along = Mth.cos(mid - windAngle) * radius;
                float alpha = ringAlpha * band(along, mid, seconds, windSpeed, ring, contrast);
                if (alpha < 0.002F) {
                    continue;
                }
                float u0 = s * circumference / SEGMENTS / tileWidth + ring * 0.21F;
                float u1 = (s + 1) * circumference / SEGMENTS / tileWidth + ring * 0.21F;
                // Slant: the top of the curtain leans away from where the wind comes from.
                float lean0 = -Mth.sin(a0 - windAngle) * slant / tileWidth;
                float lean1 = -Mth.sin(a1 - windAngle) * slant / tileWidth;
                quad(out, x0, z0, x1, z1, bottom, middle, u0, u1, lean0, lean1, scroll, tileHeight, r, g, b, 0.0F, alpha, light);
                quad(out, x0, z0, x1, z1, middle, top, u0, u1, lean0, lean1, scroll, tileHeight, r, g, b, alpha, 0.0F, light);
            }
        }
    }

    private static float band(float along, float angle, float seconds, float windSpeed, int ring, float contrast) {
        float speed = Math.max(windSpeed * 20.0F, 1.5F);
        float wave = Mth.sin((along - seconds * speed) / 18.0F + ring * 1.7F);
        // A second, slower pattern around the ring, so the sheets are not all the same width.
        float patch = Mth.sin(angle * 3.0F + seconds * 0.05F + ring * 0.9F);
        float slow = Mth.sin(seconds * 0.07F + ring * 2.3F);
        return Math.clamp(0.55F + contrast * wave + 0.12F * patch + 0.12F * slow, 0.0F, 1.3F);
    }

    private static void quad(VertexConsumer out, float x0, float z0, float x1, float z1, float y0, float y1, float u0, float u1,
            float lean0, float lean1, float scroll, float tileHeight, float r, float g, float b, float alpha0, float alpha1, int light) {
        float v0 = -y0 / tileHeight - scroll;
        float v1 = -y1 / tileHeight - scroll;
        out.addVertex(x0, y0, z0).setUv(u0 + lean0 * y0, v0).setColor(r, g, b, alpha0).setLight(light);
        out.addVertex(x1, y0, z1).setUv(u1 + lean1 * y0, v0).setColor(r, g, b, alpha0).setLight(light);
        out.addVertex(x1, y1, z1).setUv(u1 + lean1 * y1, v1).setColor(r, g, b, alpha1).setLight(light);
        out.addVertex(x0, y1, z0).setUv(u0 + lean0 * y1, v1).setColor(r, g, b, alpha1).setLight(light);
    }
}
