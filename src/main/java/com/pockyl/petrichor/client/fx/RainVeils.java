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
 * with the wind - the "waves" of a heavy shower crossing a field. Terrain in front hides them through the depth test.
 */
public final class RainVeils {
    public static final ResourceLocation TEXTURE = Petrichor.id("textures/fx/rain_sheet.png");
    private static final float[] RADII = {12.0F, 19.0F, 29.0F, 44.0F, 66.0F, 96.0F};
    private static final int SEGMENTS = 48;
    /** Blocks covered by one width / height of the texture. */
    private static final float TILE_WIDTH = 7.0F;
    private static final float TILE_HEIGHT = 14.0F;
    private static final float BELOW = 20.0F;
    private static final float ABOVE = 46.0F;

    private RainVeils() {
    }

    /**
     * @param fog  current fog colour; the curtains are a little lighter than the haze
     * @param time game ticks with the partial tick
     */
    public static void render(VertexConsumer out, Columns columns, double camX, double camY, double camZ, double time, float[] fog,
            float fogEnd) {
        float rain = ClientWeather.rain();
        if (rain <= 0.0F || columns.precipitation(Mth.floor(camX), Mth.floor(camZ)) == Columns.SNOW) {
            return;
        }
        float strength = Math.min(1.2F, ClientWeather.intensity()) * Math.min(1.0F, 0.25F + ClientWeather.density * 0.4F);
        float fall = ClientWeather.fallSpeed * 20.0F;
        float windX = ClientWeather.windX();
        float windZ = ClientWeather.windZ();
        float windSpeed = Mth.sqrt(windX * windX + windZ * windZ);
        float windAngle = (float) Math.atan2(windZ, windX);
        float slant = Math.min(0.6F, windSpeed / Math.max(ClientWeather.fallSpeed, 0.05F));
        float seconds = (float) (time / 20.0 % 3600.0);
        float r = Math.min(1.0F, fog[0] * 1.15F + 0.16F);
        float g = Math.min(1.0F, fog[1] * 1.15F + 0.17F);
        float b = Math.min(1.0F, fog[2] * 1.15F + 0.2F);
        int light = LightTexture.pack(0, 15);
        float bottom = (float) -BELOW;
        float middle = 6.0F;
        float top = ABOVE;
        for (int ring = 0; ring < RADII.length; ring++) {
            float radius = RADII[ring];
            if (radius > fogEnd * 1.1F) {
                break;
            }
            // Near curtains are thin, far ones overlap with everything behind them.
            float ringAlpha = strength * (ring == 0 ? 0.14F : 0.24F);
            float scroll = seconds * fall / TILE_HEIGHT * (0.9F + ring * 0.04F) + ring * 0.37F;
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
                float band0 = band(along, seconds, windSpeed, ring);
                float alpha = ringAlpha * band0;
                if (alpha < 0.002F) {
                    continue;
                }
                float u0 = s * circumference / SEGMENTS / TILE_WIDTH + ring * 0.21F;
                float u1 = (s + 1) * circumference / SEGMENTS / TILE_WIDTH + ring * 0.21F;
                // Slant: the top of the curtain leans away from where the wind comes from.
                float lean0 = -Mth.sin(a0 - windAngle) * slant / TILE_WIDTH;
                float lean1 = -Mth.sin(a1 - windAngle) * slant / TILE_WIDTH;
                quad(out, x0, z0, x1, z1, bottom, middle, u0, u1, lean0, lean1, scroll, r, g, b, 0.0F, alpha, light);
                quad(out, x0, z0, x1, z1, middle, top, u0, u1, lean0, lean1, scroll, r, g, b, alpha, 0.0F, light);
            }
        }
    }

    private static float band(float along, float seconds, float windSpeed, int ring) {
        float speed = Math.max(windSpeed * 20.0F, 1.5F);
        float wave = Mth.sin((along - seconds * speed) / 18.0F + ring * 1.7F);
        float slow = Mth.sin(seconds * 0.07F + ring * 2.3F);
        return Math.clamp(0.55F + 0.35F * wave + 0.15F * slow, 0.0F, 1.2F);
    }

    private static void quad(VertexConsumer out, float x0, float z0, float x1, float z1, float y0, float y1, float u0, float u1,
            float lean0, float lean1, float scroll, float r, float g, float b, float alpha0, float alpha1, int light) {
        float v0 = -y0 / TILE_HEIGHT - scroll;
        float v1 = -y1 / TILE_HEIGHT - scroll;
        out.addVertex(x0, y0, z0).setUv(u0 + lean0 * y0, v0).setColor(r, g, b, alpha0).setLight(light);
        out.addVertex(x1, y0, z1).setUv(u1 + lean1 * y0, v0).setColor(r, g, b, alpha0).setLight(light);
        out.addVertex(x1, y1, z1).setUv(u1 + lean1 * y1, v1).setColor(r, g, b, alpha1).setLight(light);
        out.addVertex(x0, y1, z0).setUv(u0 + lean0 * y1, v1).setColor(r, g, b, alpha1).setLight(light);
    }
}
