package com.pockyl.petrichor.client.lightning;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.fx.RainFx;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * All lightning on the client: bolts the server sends (vanilla entities, drawn our way), distant bolts and cloud
 * flashes that exist only as scenery during thunderstorms, the light they throw on the world and the sky, and thunder
 * that arrives late from far away.
 */
public final class Lightning {
    /** Blocks per tick of sound at 343 m/s. */
    private static final double TICKS_PER_SECOND = 20.0;
    private static final float DEFAULT_CLOUD_HEIGHT = 192.0F;

    private final List<Strike> strikes = new ArrayList<>();
    private final List<Thunder> thunder = new ArrayList<>();
    private final RandomSource random = RandomSource.create();
    private float flash;
    private float previousFlash;

    private record Thunder(long due, double x, double y, double z, SoundEvent sound, float volume, float pitch) {
    }

    /** Thunder played by this mod; vanilla thunder is replaced, ours must pass. */
    public static final class ThunderSound extends SimpleSoundInstance {
        ThunderSound(SoundEvent sound, float volume, float pitch, double x, double y, double z) {
            super(sound.getLocation(), SoundSource.WEATHER, volume, pitch, SoundInstance.createUnseededRandom(), false, 0,
                    SoundInstance.Attenuation.NONE, x, y, z, false);
        }
    }

    public void clear() {
        strikes.clear();
        thunder.clear();
        flash = 0.0F;
        previousFlash = 0.0F;
    }

    /** Flash brightness for lighting, with the partial tick. */
    public float flash(float partialTick) {
        return Mth.lerp(partialTick, previousFlash, flash);
    }

    public int strikeCount() {
        return strikes.size();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Events
    // ------------------------------------------------------------------------------------------------------------

    public void onBolt(ClientLevel level, LightningBolt bolt, Vec3 cam) {
        long seed = bolt.getUUID().getLeastSignificantBits() ^ bolt.getUUID().getMostSignificantBits();
        RandomSource shapeRandom = RandomSource.create(seed);
        double groundY = bolt.getY();
        float cloud = cloudHeight(level);
        float height = (float) Math.max(cloud - groundY, 70.0);
        float offX = (shapeRandom.nextFloat() - 0.5F) * height * 0.35F;
        float offZ = (shapeRandom.nextFloat() - 0.5F) * height * 0.35F;
        BoltShape shape = BoltShape.groundStrike(seed, offX, height, offZ);
        float distance = (float) cam.distanceTo(bolt.position());
        strikes.add(new Strike(Strike.Kind.GROUND, bolt.getX(), groundY, bolt.getZ(), shape, bolt.getX() + offX, groundY + height,
                bolt.getZ() + offZ, 40.0F, distance, true, random));
        scheduleThunder(level, bolt.getX(), groundY + 10.0, bolt.getZ(), distance, false);
    }

    private static float cloudHeight(ClientLevel level) {
        float cloud = level.effects().getCloudHeight();
        return Float.isNaN(cloud) ? DEFAULT_CLOUD_HEIGHT : cloud;
    }

    private void scheduleThunder(ClientLevel level, double x, double y, double z, float distance, boolean cloud) {
        if (!ClientConfig.DELAYED_THUNDER.get()) {
            return;
        }
        double blocksPerTick = ClientConfig.SOUND_SPEED.get() / TICKS_PER_SECOND;
        long now = level.getGameTime();
        float volume = (float) (double) ClientConfig.THUNDER_VOLUME.get();
        long due = now + Math.round(distance / blocksPerTick);
        if (distance < 40.0F && !cloud) {
            // Close: the crack of the discharge right away, the roll right behind it.
            thunder.add(new Thunder(due, x, y, z, SoundEvents.LIGHTNING_BOLT_IMPACT, volume, 0.6F + random.nextFloat() * 0.25F));
            thunder.add(new Thunder(due + 1, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, volume, 0.9F + random.nextFloat() * 0.1F));
            return;
        }
        float pitch;
        float loudness;
        if (distance < 120.0F) {
            pitch = 0.8F + random.nextFloat() * 0.15F;
            loudness = 0.9F;
        } else if (distance < 300.0F) {
            pitch = 0.65F + random.nextFloat() * 0.15F;
            loudness = 0.6F;
        } else {
            pitch = 0.5F + random.nextFloat() * 0.12F;
            loudness = 0.4F;
        }
        if (cloud) {
            loudness *= 0.6F;
            pitch -= 0.05F;
        }
        thunder.add(new Thunder(due, x, y, z, SoundEvents.LIGHTNING_BOLT_THUNDER, volume * loudness, pitch));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------------------------------------------------

    public void tick(ClientLevel level, Vec3 cam, RainFx fx, float thunderLevel) {
        long now = level.getGameTime();
        for (Iterator<Thunder> it = thunder.iterator(); it.hasNext(); ) {
            Thunder t = it.next();
            if (now >= t.due()) {
                Minecraft.getInstance().getSoundManager().play(new ThunderSound(t.sound(), t.volume(), t.pitch(), t.x(), t.y(), t.z()));
                it.remove();
            } else if (t.due() - now > 2000) {
                it.remove();
            }
        }

        previousFlash = flash;
        float sum = 0.0F;
        for (Iterator<Strike> it = strikes.iterator(); it.hasNext(); ) {
            Strike strike = it.next();
            strike.age += 1.0F;
            if (strike.done()) {
                it.remove();
                continue;
            }
            float scale = 1.0F / (1.0F + (strike.distance / 100.0F) * (strike.distance / 100.0F));
            float kind = strike.kind == Strike.Kind.GROUND ? 1.0F : strike.kind == Strike.Kind.CLOUD ? 0.6F : 0.35F;
            sum += strike.flash(strike.age) * Math.max(scale, 0.05F) * kind;
            if (strike.kind == Strike.Kind.GROUND && strike.real && !strike.impactDone && strike.age >= strike.leaderTicks) {
                strike.impactDone = true;
                if (strike.distance < 64.0F) {
                    fx.sparks(strike.x, strike.y, strike.z, 24);
                }
            }
        }
        boolean hidden = Minecraft.getInstance().options.hideLightningFlash().get();
        flash = hidden ? 0.0F : Math.min(1.0F, sum) * (float) (double) ClientConfig.FLASH.get();

        if (thunderLevel > 0.3F) {
            spawnScenery(level, cam, thunderLevel);
        }
    }

    /** Distant bolts and flashes in the clouds: a storm is never only the strikes next to you. */
    private void spawnScenery(ClientLevel level, Vec3 cam, float thunderLevel) {
        double rate = ClientConfig.DISTANT.get() * thunderLevel;
        if (rate <= 0.0) {
            return;
        }
        float view = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0F;
        float cloud = cloudHeight(level);
        if (random.nextDouble() < rate / (20.0 * 7.0)) {
            float distance = 80.0F + random.nextFloat() * Math.max(60.0F, Math.min(view * 1.5F, 420.0F) - 80.0F);
            float angle = random.nextFloat() * Mth.TWO_PI;
            double x = cam.x + Mth.cos(angle) * distance;
            double z = cam.z + Mth.sin(angle) * distance;
            double y = cloud + random.nextFloat() * 20.0F;
            strikes.add(new Strike(Strike.Kind.SHEET, x, y, z, null, x, y, z, 50.0F + random.nextFloat() * 40.0F, distance, false, random));
            scheduleThunder(level, x, y, z, distance, true);
        }
        if (random.nextDouble() < rate / (20.0 * 28.0)) {
            float distance = 90.0F + random.nextFloat() * Math.max(60.0F, Math.min(view, 380.0F) - 90.0F);
            float angle = random.nextFloat() * Mth.TWO_PI;
            double x = cam.x + Mth.cos(angle) * distance;
            double z = cam.z + Mth.sin(angle) * distance;
            double y = cloud - 4.0F - random.nextFloat() * 12.0F;
            float run = 50.0F + random.nextFloat() * 120.0F;
            float dir = random.nextFloat() * Mth.TWO_PI;
            BoltShape shape = BoltShape.cloudDischarge(random.nextLong(), Mth.cos(dir) * run, (random.nextFloat() - 0.5F) * 10.0F,
                    Mth.sin(dir) * run);
            strikes.add(new Strike(Strike.Kind.CLOUD, x, y, z, shape, x + Mth.cos(dir) * run * 0.5, y + 6.0, z + Mth.sin(dir) * run * 0.5,
                    60.0F, distance, false, random));
            scheduleThunder(level, x, y, z, distance, true);
        }
        if (random.nextDouble() < rate / (20.0 * 20.0)) {
            float min = 140.0F;
            float max = Math.max(min + 40.0F, view * 0.85F);
            float distance = min + random.nextFloat() * (max - min);
            float angle = random.nextFloat() * Mth.TWO_PI;
            double x = cam.x + Mth.cos(angle) * distance;
            double z = cam.z + Mth.sin(angle) * distance;
            int bx = Mth.floor(x);
            int bz = Mth.floor(z);
            double ground = level.hasChunk(bx >> 4, bz >> 4) ? level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz)
                    : level.getSeaLevel();
            float height = (float) Math.max(cloud - ground, 70.0);
            float offX = (random.nextFloat() - 0.5F) * height * 0.35F;
            float offZ = (random.nextFloat() - 0.5F) * height * 0.35F;
            BoltShape shape = BoltShape.groundStrike(random.nextLong(), offX, height, offZ);
            strikes.add(new Strike(Strike.Kind.GROUND, x, ground, z, shape, x + offX, ground + height, z + offZ, 45.0F, distance, false,
                    random));
            scheduleThunder(level, x, ground + 10.0, z, distance, false);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------------------------

    /** Bolts, after the weather so they shine through the rain. Their light reaches the world through the light map and fog. */
    public void renderBolts(Matrix4f modelView, Camera camera, float partialTick) {
        if (strikes.isEmpty() || !ClientConfig.BOLTS.get()) {
            return;
        }
        Vec3 cam = camera.getPosition();
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.set(modelView);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (Strike strike : strikes) {
            if (strike.shape != null) {
                channel(builder, strike, cam, strike.age + partialTick);
            }
        }
        draw(builder.build());


        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    private static void draw(MeshData mesh) {
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
    }

    private static void channel(BufferBuilder out, Strike strike, Vec3 cam, float t) {
        float[] d = strike.shape.data();
        float progress = strike.leaderProgress(t);
        float main = strike.mainBrightness(t);
        float branches = strike.branchBrightness(t);
        float ox = (float) (strike.x - cam.x);
        float oy = (float) (strike.y - cam.y);
        float oz = (float) (strike.z - cam.z);
        float widen = Math.max(1.0F, strike.distance / 110.0F);
        boolean leader = t < strike.leaderTicks;
        for (int s = 0; s < strike.shape.count(); s++) {
            int o = s * BoltShape.STRIDE;
            float arrive0 = d[o + 8];
            float arrive1 = d[o + 9];
            if (arrive0 > progress) {
                continue;
            }
            boolean isMain = d[o + 10] > 0.5F;
            float b;
            if (leader) {
                // The leader tip is the brightest part while it grows.
                float tip = 1.0F - Mth.clamp((progress - arrive1) * 8.0F, 0.0F, 1.0F);
                b = (0.25F + 0.5F * tip) * (isMain ? 1.0F : 0.8F) * d[o + 7];
            } else {
                b = isMain ? main : branches * d[o + 7];
            }
            if (b < 0.01F) {
                continue;
            }
            float x0 = ox + d[o];
            float y0 = oy + d[o + 1];
            float z0 = oz + d[o + 2];
            float x1 = ox + d[o + 3];
            float y1 = oy + d[o + 4];
            float z1 = oz + d[o + 5];
            if (arrive1 > progress && arrive1 > arrive0) {
                float k = (progress - arrive0) / (arrive1 - arrive0);
                x1 = x0 + (x1 - x0) * k;
                y1 = y0 + (y1 - y0) * k;
                z1 = z0 + (z1 - z0) * k;
            }
            float width = d[o + 6] * widen;
            ribbon(out, x0, y0, z0, x1, y1, z1, (isMain ? 2.4F : 1.4F) * width, 0.45F, 0.55F, 1.0F, Math.min(1.0F, b) * 0.28F);
            ribbon(out, x0, y0, z0, x1, y1, z1, (isMain ? 0.32F : 0.18F) * width + 0.03F, 0.92F, 0.94F, 1.0F, Math.min(1.0F, b));
        }
    }

    /** A camera-facing band with soft edges: two quads from transparent edges to the bright centre line. */
    private static void ribbon(BufferBuilder out, float x0, float y0, float z0, float x1, float y1, float z1, float width, float r, float g,
            float b, float a) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float mx = (x0 + x1) * 0.5F;
        float my = (y0 + y1) * 0.5F;
        float mz = (z0 + z1) * 0.5F;
        float sx = dy * mz - dz * my;
        float sy = dz * mx - dx * mz;
        float sz = dx * my - dy * mx;
        float sl = Mth.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0E-5F) {
            return;
        }
        float half = width * 0.5F / sl;
        sx *= half;
        sy *= half;
        sz *= half;
        out.addVertex(x0 - sx, y0 - sy, z0 - sz).setColor(r, g, b, 0.0F);
        out.addVertex(x0, y0, z0).setColor(r, g, b, a);
        out.addVertex(x1, y1, z1).setColor(r, g, b, a);
        out.addVertex(x1 - sx, y1 - sy, z1 - sz).setColor(r, g, b, 0.0F);
        out.addVertex(x0, y0, z0).setColor(r, g, b, a);
        out.addVertex(x0 + sx, y0 + sy, z0 + sz).setColor(r, g, b, 0.0F);
        out.addVertex(x1 + sx, y1 + sy, z1 + sz).setColor(r, g, b, 0.0F);
        out.addVertex(x1, y1, z1).setColor(r, g, b, a);
    }

    /** The whole sky brightens evenly with a flash; drawn right after the sky so terrain covers it. */
    public void renderSky(Matrix4f modelView, Camera camera, float partialTick) {
        float total = flash(partialTick);
        if (total < 0.004F) {
            return;
        }
        Vec3 cam = camera.getPosition();
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.set(modelView);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        float a = Math.min(1.0F, total * 0.45F);
        float s = 50.0F;
        // Inside of a cube around the camera.
        float[][] faces = {
                {-s, -s, -s, s, -s, -s, s, s, -s, -s, s, -s}, {-s, -s, s, -s, s, s, s, s, s, s, -s, s},
                {-s, -s, -s, -s, s, -s, -s, s, s, -s, -s, s}, {s, -s, -s, s, -s, s, s, s, s, s, s, -s},
                {-s, s, -s, s, s, -s, s, s, s, -s, s, s}
        };
        for (float[] f : faces) {
            for (int v = 0; v < 4; v++) {
                builder.addVertex(f[v * 3], f[v * 3 + 1], f[v * 3 + 2]).setColor(0.7F, 0.75F, 1.0F, a);
            }
        }
        draw(builder.build());


        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }
}
