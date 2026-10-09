package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.WeatherClient;
import com.pockyl.petrichor.weather.Noise;

/**
 * A rainbow in the sky, opposite the sun, where real ones are: the primary bow 42 degrees around the point opposite the
 * sun (red outside), a fainter secondary bow at 51 degrees with the colours reversed, and a brighter sky inside the
 * primary. Like a real one it needs sunlight on falling rain - a sun shower, or the last of a rain clearing up in daylight
 * - and a sun low enough (below 42 degrees) for the bow to stand above the horizon, so it is seen in the mornings and
 * afternoons. Drawn right after the sky: the land and the clouds stand in front of it.
 */
public final class Rainbow {
    private static final int SEED = 0x5EED_0101;
    /** How long after a rain in daylight its rainbow may hang in the sky. */
    private static final int AFTER_RAIN_TICKS = 20 * 120;
    /** The rainbow comes and goes over about 12 seconds. */
    private static final float FADE = 1.0F / 240.0F;
    private static final float DISTANCE = 100.0F;
    private static final int SEGMENTS = 96;
    private static final int RINGS = 10;
    /** Colours from the inner edge (violet) to the outer one (red). */
    private static final float[][] SPECTRUM = {
            {0.00F, 0.45F, 0.25F, 0.85F}, {0.18F, 0.20F, 0.35F, 1.00F}, {0.38F, 0.20F, 0.85F, 0.45F},
            {0.58F, 1.00F, 0.95F, 0.30F}, {0.78F, 1.00F, 0.55F, 0.15F}, {1.00F, 0.95F, 0.15F, 0.12F}
    };

    private static float strength;
    private static float previous;
    private static int afterRain;
    private static boolean wasRaining;

    private Rainbow() {
    }

    public static void clear() {
        strength = previous = 0.0F;
        afterRain = 0;
        wasRaining = false;
    }

    /** How strongly the rainbow shows, 0..1. */
    public static float strength() {
        return strength;
    }

    public static void tick(ClientLevel level) {
        previous = strength;
        float rain = ClientWeather.rain();
        float sunHeight = ClientWeather.sunHeight(level, 1.0F);
        if (rain > 0.5F) {
            wasRaining = true;
        } else if (wasRaining && rain <= 0.05F) {
            // The rain has passed: the sun may light its last drops for a while (the same for every player).
            wasRaining = false;
            long minute = level.getGameTime() / 1200L;
            if (sunHeight > 0.0F && Noise.unit(minute, SEED) * 100.0F < ClientConfig.RAINBOW_CHANCE.get()) {
                afterRain = AFTER_RAIN_TICKS;
            }
        }
        if (afterRain > 0) {
            afterRain--;
        }
        float target = Math.max(ClientWeather.sunshine() * Math.min(1.0F, rain * 2.0F), Math.min(1.0F, afterRain / 600.0F));
        target *= sunElevation(sunHeight);
        if (!ClientConfig.RAINBOWS.get()) {
            target = 0.0F;
        }
        strength += Math.clamp(target - strength, -FADE, FADE);
    }

    /** How well the sun stands for a rainbow: up, but below the 42 degrees at which the bow sinks under the horizon. */
    private static float sunElevation(float sunHeight) {
        float degrees = (float) Math.toDegrees(Math.asin(Mth.clamp(sunHeight, -1.0F, 1.0F)));
        return smoothstep(0.0F, 5.0F, degrees) * (1.0F - smoothstep(34.0F, 42.0F, degrees));
    }

    public static void render(ClientLevel level, Matrix4f modelView, float partialTick) {
        float shown = Mth.lerp(partialTick, previous, strength) * WeatherClient.skyView(partialTick);
        if (shown < 0.003F) {
            return;
        }
        // The point opposite the sun (the sun goes round the z axis, rising in the east) and two directions across.
        float sunAngle = level.getSunAngle(partialTick);
        Vector3f anti = new Vector3f(Mth.sin(sunAngle), -Mth.cos(sunAngle), 0.0F);
        Vector3f across = new Vector3f(0.0F, 0.0F, 1.0F);
        Vector3f up = new Vector3f(anti.y, -anti.x, 0.0F);

        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.set(modelView);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        // Light added to the sky, as a real rainbow is.
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float alpha = 0.42F * shown;
        // The sky inside the primary bow is a little brighter.
        bow(builder, anti, across, up, 30.0F, 40.0F, alpha * 0.16F, Band.GLOW);
        bow(builder, anti, across, up, 39.8F, 42.8F, alpha, Band.PRIMARY);
        bow(builder, anti, across, up, 50.2F, 53.8F, alpha * 0.38F, Band.SECONDARY);
        MeshData mesh = builder.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    private enum Band {
        GLOW, PRIMARY, SECONDARY
    }

    /** A ring of the sky between two angles from the point opposite the sun. */
    private static void bow(BufferBuilder builder, Vector3f anti, Vector3f across, Vector3f up, float inner, float outer, float alpha,
            Band band) {
        float[] color = new float[4];
        for (int k = 0; k < RINGS; k++) {
            float t0 = (float) k / RINGS;
            float t1 = (float) (k + 1) / RINGS;
            float r0 = (float) Math.toRadians(Mth.lerp(t0, inner, outer));
            float r1 = (float) Math.toRadians(Mth.lerp(t1, inner, outer));
            for (int j = 0; j < SEGMENTS; j++) {
                float a0 = Mth.TWO_PI * j / SEGMENTS;
                float a1 = Mth.TWO_PI * (j + 1) / SEGMENTS;
                // Only the part over the horizon (below it the rain lies on the land; the void must stay dark).
                if (direction(anti, across, up, r1, a0).y < -0.1F && direction(anti, across, up, r1, a1).y < -0.1F) {
                    continue;
                }
                vertex(builder, anti, across, up, r0, a0, t0, alpha, band, color);
                vertex(builder, anti, across, up, r1, a0, t1, alpha, band, color);
                vertex(builder, anti, across, up, r1, a1, t1, alpha, band, color);
                vertex(builder, anti, across, up, r0, a1, t0, alpha, band, color);
            }
        }
    }

    private static Vector3f direction(Vector3f anti, Vector3f across, Vector3f up, float radius, float around) {
        float sin = Mth.sin(radius);
        float cos = Mth.cos(radius);
        float x = Mth.cos(around);
        float y = Mth.sin(around);
        return new Vector3f(
                anti.x * cos + (across.x * x + up.x * y) * sin,
                anti.y * cos + (across.y * x + up.y * y) * sin,
                anti.z * cos + (across.z * x + up.z * y) * sin);
    }

    private static void vertex(BufferBuilder builder, Vector3f anti, Vector3f across, Vector3f up, float radius, float around, float t,
            float alpha, Band band, float[] color) {
        Vector3f dir = direction(anti, across, up, radius, around);
        float a = alpha * Mth.clamp((dir.y + 0.03F) / 0.1F, 0.0F, 1.0F);
        switch (band) {
            case GLOW -> {
                color[0] = color[1] = color[2] = 1.0F;
                a *= t * t;
            }
            case PRIMARY -> {
                spectrum(t, color);
                a *= edges(t);
            }
            case SECONDARY -> {
                spectrum(1.0F - t, color);
                a *= edges(t);
            }
        }
        builder.addVertex(dir.x * DISTANCE, dir.y * DISTANCE, dir.z * DISTANCE).setColor(color[0], color[1], color[2], a);
    }

    /** Soft edges across the band. */
    private static float edges(float t) {
        return (float) Math.pow(Math.sin(Math.PI * t), 0.7);
    }

    private static void spectrum(float t, float[] out) {
        for (int i = 1; i < SPECTRUM.length; i++) {
            float[] hi = SPECTRUM[i];
            if (t <= hi[0] || i == SPECTRUM.length - 1) {
                float[] lo = SPECTRUM[i - 1];
                float f = Mth.clamp((t - lo[0]) / (hi[0] - lo[0]), 0.0F, 1.0F);
                out[0] = Mth.lerp(f, lo[1], hi[1]);
                out[1] = Mth.lerp(f, lo[2], hi[2]);
                out[2] = Mth.lerp(f, lo[3], hi[3]);
                return;
            }
        }
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }
}
