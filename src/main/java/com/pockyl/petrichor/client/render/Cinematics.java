package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.WeatherClient;
import com.pockyl.petrichor.weather.Noise;

/**
 * The storm seen through a camera: the world holds its breath and darkens before a strike, a close flash overexposes
 * the view and leaves the eyes in the dark for a moment, close thunder shakes the camera, heavy rain and storms close in
 * the edges of the view with a cold vignette, and in first person rain lands on the lens.
 */
public final class Cinematics {
    private static final int SEED_PITCH = 0x0D23_0001;
    private static final int SEED_YAW = 0x0D23_0002;
    private static final int SEED_ROLL = 0x0D23_0003;
    /** Degrees of camera shake at full strength. */
    private static final float SHAKE_DEGREES = 1.4F;

    private static final LensDrops LENS = new LensDrops();
    private static float shake;
    private static float previousShake;
    /** How bright the last close flash was, remembered by the eyes: it fades slowly. */
    private static float adaptation;
    private static float previousAdaptation;
    private static long ticks;

    private Cinematics() {
    }

    /** Close thunder arrived: the camera shakes, harder the closer it was. */
    public static void thunderShake(float strength) {
        shake = Math.max(shake, Math.clamp(strength, 0.0F, 1.0F));
    }

    public static void clear() {
        shake = previousShake = 0.0F;
        adaptation = previousAdaptation = 0.0F;
        LENS.clear();
    }

    public static void tick(ClientLevel level, Vec3 cam) {
        ticks++;
        previousShake = shake;
        shake *= 0.9F;
        if (shake < 0.002F) {
            shake = 0.0F;
        }
        previousAdaptation = adaptation;
        float glare = WeatherClient.glare(1.0F);
        adaptation = Math.max(glare, adaptation * 0.955F);
        LENS.tick(level, cam);
    }

    /** Darkening of the view: the hush before a strike, and the moment the eyes need after a close flash. */
    private static float darkness(float partialTick) {
        float hush = WeatherClient.hush(partialTick);
        float glare = WeatherClient.glare(partialTick);
        float memory = Mth.lerp(partialTick, previousAdaptation, adaptation);
        float blind = Math.max(0.0F, memory - glare * 1.5F) * 0.55F;
        return Math.min(0.75F, hush + blind);
    }

    // ------------------------------------------------------------------------------------------------------------
    // Camera
    // ------------------------------------------------------------------------------------------------------------

    /** @return pitch, yaw and roll to add, in degrees */
    public static float[] shakeAngles(float partialTick) {
        float amount = Mth.lerp(partialTick, previousShake, shake) * (float) (double) ClientConfig.SHAKE.get();
        if (amount <= 0.0F) {
            return null;
        }
        // A rumble: quick, irregular, settling down - not a regular wobble.
        double t = (ticks + partialTick) * 0.85;
        float degrees = amount * amount * SHAKE_DEGREES;
        return new float[] {
                (Noise.value(t, SEED_PITCH) - 0.5F) * 2.0F * degrees,
                (Noise.value(t, SEED_YAW) - 0.5F) * 1.4F * degrees,
                (Noise.value(t * 0.7, SEED_ROLL) - 0.5F) * 1.2F * degrees
        };
    }

    // ------------------------------------------------------------------------------------------------------------
    // Screen
    // ------------------------------------------------------------------------------------------------------------

    /** Drawn over the finished picture, under the HUD; also with the HUD hidden (F1), for screenshots. */
    public static void renderScreen(GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float darken = darkness(partialTick);
        float storm = ClientWeather.rain() * Math.clamp((ClientWeather.heaviness - 0.3F) / 0.7F, 0.0F, 1.0F) * 0.55F
                + ClientWeather.thunder() * 0.45F;
        float vignette = Math.min(1.0F, storm * (float) (double) ClientConfig.VIGNETTE.get());
        float glare = WeatherClient.glare(partialTick);
        float exposure = (float) Math.pow(glare, 1.4) * 0.6F;
        ShaderInstance grade = PetrichorShaders.grade();
        if (grade != null && (darken > 0.002F || vignette > 0.002F || exposure > 0.002F)) {
            RenderSystem.enableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(() -> grade);
            grade.safeGetUniform("Darken").set(darken);
            grade.safeGetUniform("Vignette").set(vignette);
            grade.safeGetUniform("Tint").set(0.86F, 0.92F, 1.0F);
            grade.safeGetUniform("Exposure").set(exposure);
            grade.safeGetUniform("FlashColor").set(0.82F, 0.88F, 1.0F);
            if (darken > 0.002F || vignette > 0.002F) {
                grade.safeGetUniform("Pass").set(0.0F);
                RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
                fullScreen();
            }
            if (exposure > 0.002F) {
                grade.safeGetUniform("Pass").set(1.0F);
                RenderSystem.blendFunc(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
                fullScreen();
            }
            RenderSystem.defaultBlendFunc();
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
        }
        LENS.render(graphics, partialTick);
    }

    private static void fullScreen() {
        BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        quad.addVertex(-1.0F, -1.0F, 0.0F);
        quad.addVertex(1.0F, -1.0F, 0.0F);
        quad.addVertex(1.0F, 1.0F, 0.0F);
        quad.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(quad.buildOrThrow());
    }
}
