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

import com.pockyl.petrichor.client.WeatherClient;

/**
 * The storm seen through a camera: the world holds its breath and darkens before a strike, a close flash overexposes
 * the view and leaves the eyes in the dark for a moment, and in first person a rare drop of rain lands on the lens.
 */
public final class Cinematics {
    private static final LensDrops LENS = new LensDrops();
    /** How bright the last close flash was, remembered by the eyes: it fades slowly. */
    private static float adaptation;
    private static float previousAdaptation;

    private Cinematics() {
    }

    public static void clear() {
        adaptation = previousAdaptation = 0.0F;
        LENS.clear();
    }

    public static void tick(ClientLevel level, Vec3 cam) {
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
    // Screen
    // ------------------------------------------------------------------------------------------------------------

    /** Drawn over the finished picture, under the HUD; also with the HUD hidden (F1), for screenshots. */
    public static void renderScreen(GuiGraphics graphics, float partialTick) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float darken = darkness(partialTick);
        float glare = WeatherClient.glare(partialTick);
        float exposure = (float) Math.pow(glare, 1.4) * 0.6F;
        ShaderInstance grade = PetrichorShaders.grade();
        if (grade != null && (darken > 0.002F || exposure > 0.002F)) {
            RenderSystem.enableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShader(() -> grade);
            grade.safeGetUniform("Darken").set(darken);
            grade.safeGetUniform("Exposure").set(exposure);
            grade.safeGetUniform("FlashColor").set(0.82F, 0.88F, 1.0F);
            if (darken > 0.002F) {
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
