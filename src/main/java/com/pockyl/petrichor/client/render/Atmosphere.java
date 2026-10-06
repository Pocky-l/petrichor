package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.WeatherClient;

/**
 * The air of a rainy day, drawn over the world from its depth buffer before the rain itself (shader
 * {@code petrichor_atmosphere}). Replaces the flat wall of fog: the haze deepens with distance and in low ground, so
 * hills behind hills fade in layers; heavier showers drift through it with the wind; the sky becomes a cloud deck with
 * rain shafts at the horizon, lit by lightning.
 */
public final class Atmosphere {
    /** Optical depth that counts as "can no longer see": 95% haze. */
    private static final float OPAQUE = 3.0F;
    private static TextureTarget depth;

    private Atmosphere() {
    }

    /** Whether the atmosphere draws the rain's haze this frame (otherwise the vanilla fog is pulled in). */
    public static boolean active() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!ClientConfig.ATMOSPHERE.get() || PetrichorShaders.atmosphere() == null || ClientWeather.rain() <= 0.001F
                || ClientConfig.FOG.get() <= 0.0) {
            return false;
        }
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera.getFluidInCamera() != FogType.NONE) {
            return false;
        }
        return !(camera.getEntity() instanceof LivingEntity living)
                || !living.hasEffect(MobEffects.BLINDNESS) && !living.hasEffect(MobEffects.DARKNESS);
    }

    /** Haze per block at the ground for the current rain. */
    public static float haze() {
        float rain = ClientWeather.rain();
        float gust = rain > 0.001F ? ClientWeather.intensity() / rain : 1.0F;
        return OPAQUE / ClientWeather.visibility * (float) (double) ClientConfig.FOG.get() * Math.clamp(0.75F + 0.25F * gust, 0.7F, 1.3F);
    }

    public static void render(ClientLevel level, float partialTick, double camX, double camY, double camZ) {
        ShaderInstance shader = PetrichorShaders.atmosphere();
        if (shader == null || !active()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        captureDepth(minecraft);

        float rain = ClientWeather.rain();
        float heaviness = ClientWeather.heaviness;
        float fog = (float) Math.min(1.0, ClientConfig.FOG.get());
        float sunAngle = level.getSunAngle(partialTick);
        float sunX = -Mth.sin(sunAngle);
        float sunY = Mth.cos(sunAngle);
        double seconds = (level.getGameTime() + (double) partialTick) / 20.0 % 7200.0;

        Matrix4f inverse = new Matrix4f(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix()).invert();
        shader.safeGetUniform("InvViewProj").set(inverse);
        shader.safeGetUniform("CameraPos").set((float) (camX % 16384.0), (float) camY, (float) (camZ % 16384.0));
        shader.safeGetUniform("PetrichorTime").set((float) seconds);
        shader.safeGetUniform("Wind").set(ClientWeather.windX() * 20.0F, ClientWeather.windZ() * 20.0F);
        shader.safeGetUniform("Haze").set(haze());
        shader.safeGetUniform("Falloff").set(1.0F / 40.0F);
        shader.safeGetUniform("BaseY").set((float) level.getSeaLevel());
        shader.safeGetUniform("Strength").set(rain);
        // A drizzle only greys the sky; a downpour hides it behind a heavy deck.
        shader.safeGetUniform("Overcast").set(Math.min(1.0F, 0.55F + 0.45F * heaviness) * fog);
        shader.safeGetUniform("Gloom").set(Math.max(Math.clamp((heaviness - 0.4F) / 0.6F, 0.0F, 1.0F), ClientWeather.thunder() * 0.9F));
        shader.safeGetUniform("Shafts").set(Mth.clamp((heaviness - 0.3F) / 0.7F, 0.0F, 1.0F));
        shader.safeGetUniform("Glow").set(Math.max(0.0F, sunY + 0.2F) * (1.0F - 0.6F * heaviness) * 0.6F);
        shader.safeGetUniform("Flash").set(WeatherClient.flash(partialTick));
        shader.safeGetUniform("SunDir").set(sunX, sunY, 0.0F);

        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, depth.getDepthTextureId());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
        quad.addVertex(-1.0F, -1.0F, 0.0F);
        quad.addVertex(1.0F, -1.0F, 0.0F);
        quad.addVertex(1.0F, 1.0F, 0.0F);
        quad.addVertex(-1.0F, 1.0F, 0.0F);
        BufferUploader.drawWithShader(quad.buildOrThrow());
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /** Copies the world's depth so the shader can read it while drawing over the same target. */
    private static void captureDepth(Minecraft minecraft) {
        RenderTarget main = minecraft.getMainRenderTarget();
        if (depth == null) {
            depth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        } else if (depth.width != main.width || depth.height != main.height) {
            depth.resize(main.width, main.height, Minecraft.ON_OSX);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, depth.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, depth.width, depth.height, GL11.GL_DEPTH_BUFFER_BIT,
                GL11.GL_NEAREST);
        // Back to where the weather is drawn: its own target with Fabulous graphics, the main one otherwise.
        RenderTarget weather = minecraft.levelRenderer.getWeatherTarget();
        (Minecraft.useShaderTransparency() && weather != null ? weather : main).bindWrite(false);
    }
}
