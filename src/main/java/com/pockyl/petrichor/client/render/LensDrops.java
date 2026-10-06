package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;

/**
 * Rain on the lens, in first person: drops land on the view when you look up into the rain or face the wind, bead up,
 * and the bigger ones run down in jerks, swallowing the small ones and leaving a few behind. Each shows the picture
 * behind it upside down. Under cover they stop coming and dry up.
 *
 * <p>Positions are in screen heights: y from 0 at the top to 1 at the bottom, x from 0 to the aspect ratio.
 */
final class LensDrops {
    private static final int MAX = 72;
    private static final int FULL_BRIGHT = 0xF000F0;

    private final RandomSource random = RandomSource.create();
    private final float[] x = new float[MAX];
    private final float[] y = new float[MAX];
    private final float[] radius = new float[MAX];
    private final float[] fall = new float[MAX];
    private final float[] fade = new float[MAX];
    private final float[] slid = new float[MAX];
    private final float[] streak = new float[MAX];
    private final int[] pinned = new int[MAX];
    private final boolean[] running = new boolean[MAX];
    private int count;
    private float aspect = 16.0F / 9.0F;

    void clear() {
        count = 0;
    }

    void tick(ClientLevel level, Vec3 cam) {
        Minecraft minecraft = Minecraft.getInstance();
        boolean enabled = ClientConfig.LENS_DROPS.get() && minecraft.options.getCameraType().isFirstPerson();
        if (!enabled) {
            count = 0;
            return;
        }
        int width = minecraft.getWindow().getWidth();
        int height = minecraft.getWindow().getHeight();
        if (height > 0) {
            aspect = (float) width / height;
        }
        boolean exposed = level.isRainingAt(BlockPos.containing(cam.x, cam.y + 0.5, cam.z)) && ClientWeather.localRain() > 0.0F;
        if (exposed) {
            spawn(minecraft);
        }
        for (int i = 0; i < count; i++) {
            // Out of the rain the lens dries in a few seconds; in the rain drops last until they run off.
            fade[i] -= exposed ? 0.0025F : 0.012F;
            if (running[i]) {
                run(i);
            } else if (radius[i] > 0.016F) {
                running[i] = true;
            }
        }
        merge();
        int w = 0;
        for (int i = 0; i < count; i++) {
            if (fade[i] <= 0.0F || y[i] - radius[i] > 1.02F || radius[i] < 0.0015F) {
                continue;
            }
            copy(i, w++);
        }
        count = w;
    }

    private void spawn(Minecraft minecraft) {
        Vector3f look = minecraft.gameRenderer.getMainCamera().getLookVector();
        // Looking up catches the falling rain; facing the wind catches what it drives.
        float up = Math.max(0.0F, look.y() + 0.15F);
        float windX = ClientWeather.windX();
        float windZ = ClientWeather.windZ();
        float wind = Mth.sqrt(windX * windX + windZ * windZ);
        float facing = 0.0F;
        if (wind > 1.0E-4F) {
            float horizontal = Mth.sqrt(look.x() * look.x() + look.z() * look.z());
            if (horizontal > 1.0E-4F) {
                facing = Math.max(0.0F, -(look.x() * windX + look.z() * windZ) / (horizontal * wind)) * Math.min(1.0F, wind * 3.0F);
            }
        }
        float rate = ClientWeather.localIntensity() * ClientWeather.splash * (0.03F + 0.9F * up + 0.45F * facing) * 0.35F;
        int n = (int) rate;
        if (random.nextFloat() < rate - n) {
            n++;
        }
        for (int k = 0; k < n && count < MAX; k++) {
            int i = count++;
            x[i] = random.nextFloat() * aspect;
            y[i] = random.nextFloat() * 0.95F;
            // Mostly small beads, now and then a fat drop.
            float size = random.nextFloat();
            radius[i] = 0.004F + size * size * size * 0.02F;
            fall[i] = 0.0F;
            fade[i] = 1.0F;
            slid[i] = 0.0F;
            streak[i] = 0.0F;
            pinned[i] = 0;
            running[i] = false;
        }
    }

    private void run(int i) {
        if (pinned[i] > 0) {
            pinned[i]--;
            fall[i] *= 0.25F;
        } else {
            float target = Math.min(0.014F, 0.0011F * (radius[i] / 0.01F) * (radius[i] / 0.01F));
            fall[i] += (target - fall[i]) * 0.25F;
            if (random.nextFloat() < 0.04F) {
                pinned[i] = 2 + random.nextInt(10);
            }
        }
        y[i] += fall[i];
        x[i] += (random.nextFloat() - 0.5F) * fall[i] * 0.25F;
        streak[i] = Math.min(0.08F, streak[i] + fall[i]);
        slid[i] += fall[i];
        if (slid[i] > 0.02F + random.nextFloat() * 0.02F && count < MAX) {
            slid[i] = 0.0F;
            // A small bead stays behind on the way.
            int j = count++;
            x[j] = x[i];
            y[j] = y[i] - radius[i] * 1.2F;
            radius[j] = Math.min(radius[i] * 0.35F, 0.0035F + random.nextFloat() * 0.002F);
            fall[j] = 0.0F;
            fade[j] = fade[i];
            slid[j] = 0.0F;
            streak[j] = 0.0F;
            pinned[j] = 0;
            running[j] = false;
            radius[i] = (float) Math.cbrt(Math.max(0.0F, radius[i] * radius[i] * radius[i] - radius[j] * radius[j] * radius[j]));
        }
        if (radius[i] < 0.009F) {
            running[i] = false;
            fall[i] = 0.0F;
        }
    }

    /** Drops that touch become one, with the water of both. */
    private void merge() {
        for (int i = 0; i < count; i++) {
            if (fade[i] <= 0.0F) {
                continue;
            }
            for (int j = i + 1; j < count; j++) {
                if (fade[j] <= 0.0F) {
                    continue;
                }
                float dx = x[j] - x[i];
                float dy = y[j] - y[i];
                float touch = radius[i] + radius[j];
                if (dx * dx + dy * dy >= touch * touch * 0.8F) {
                    continue;
                }
                int big = radius[i] >= radius[j] ? i : j;
                int small = big == i ? j : i;
                float volume = radius[big] * radius[big] * radius[big] + radius[small] * radius[small] * radius[small];
                radius[big] = (float) Math.cbrt(volume);
                fade[big] = Math.max(fade[big], fade[small]);
                pinned[big] = 0;
                fade[small] = 0.0F;
            }
        }
    }

    private void copy(int from, int to) {
        if (from == to) {
            return;
        }
        x[to] = x[from];
        y[to] = y[from];
        radius[to] = radius[from];
        fall[to] = fall[from];
        fade[to] = fade[from];
        slid[to] = slid[from];
        streak[to] = streak[from];
        pinned[to] = pinned[from];
        running[to] = running[from];
    }

    void render(GuiGraphics graphics, float partialTick) {
        ShaderInstance shader = PetrichorShaders.bead();
        if (count == 0 || shader == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        TextureTarget screen = SceneCopy.screen();
        float scale = graphics.guiHeight();
        Matrix4f pose = graphics.pose().last().pose();
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        for (int i = 0; i < count; i++) {
            float a = Math.min(1.0F, fade[i] * 3.0F);
            float cx = x[i] * scale;
            float cy = (y[i] + fall[i] * (partialTick - 1.0F)) * scale;
            float r = radius[i] * scale;
            float half = r * (running[i] ? 1.0F + Math.min(0.8F, fall[i] * 90.0F) : 1.0F);
            // GUI y grows downwards; V = +1 is the top of the drop.
            quad(builder, pose, cx, cy - half + r, r, half, -1.0F, 1.0F, 1.0F, -1.0F, a);
            if (running[i] && streak[i] > 0.004F) {
                float tail = streak[i] * scale;
                quad(builder, pose, cx, cy - r * 0.5F - tail * 0.5F, r * 0.4F, tail * 0.5F, -1.0F, 1.0F, 2.0F, 3.0F, a * 0.5F);
            }
        }
        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
        LightTexture lightTexture = minecraft.gameRenderer.lightTexture();
        lightTexture.turnOnLightLayer();
        float fogStart = RenderSystem.getShaderFogStart();
        RenderSystem.setShaderFogStart(Float.MAX_VALUE);
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, screen.getColorTextureId());
        shader.safeGetUniform("Refraction").set(1.0F);
        shader.safeGetUniform("LensPower").set(2.6F);
        shader.safeGetUniform("SkyColor").set(0.75F, 0.8F, 0.88F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        BufferUploader.drawWithShader(mesh);
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.setShaderFogStart(fogStart);
        lightTexture.turnOffLightLayer();
    }

    private static void quad(BufferBuilder out, Matrix4f pose, float cx, float cy, float halfU, float halfV, float uMin, float uMax,
            float vTop, float vBottom, float a) {
        out.addVertex(pose, cx - halfU, cy + halfV, 0.0F).setUv(uMin, vBottom).setColor(1.0F, 1.0F, 1.0F, a).setLight(FULL_BRIGHT);
        out.addVertex(pose, cx + halfU, cy + halfV, 0.0F).setUv(uMax, vBottom).setColor(1.0F, 1.0F, 1.0F, a).setLight(FULL_BRIGHT);
        out.addVertex(pose, cx + halfU, cy - halfV, 0.0F).setUv(uMax, vTop).setColor(1.0F, 1.0F, 1.0F, a).setLight(FULL_BRIGHT);
        out.addVertex(pose, cx - halfU, cy - halfV, 0.0F).setUv(uMin, vTop).setColor(1.0F, 1.0F, 1.0F, a).setLight(FULL_BRIGHT);
    }
}
