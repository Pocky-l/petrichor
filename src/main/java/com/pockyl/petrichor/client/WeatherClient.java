package com.pockyl.petrichor.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.CustomizeGuiOverlayEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.joml.Vector3f;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.fx.FxAtlas;
import com.pockyl.petrichor.client.fx.FxSpawner;
import com.pockyl.petrichor.client.fx.Precipitation;
import com.pockyl.petrichor.client.fx.RainFx;
import com.pockyl.petrichor.client.fx.RainVeils;
import com.pockyl.petrichor.client.lightning.Lightning;
import com.pockyl.petrichor.client.render.PetrichorEffects;
import com.pockyl.petrichor.client.render.PetrichorShaders;
import com.pockyl.petrichor.client.render.Puddles;
import com.pockyl.petrichor.client.sound.RainSounds;

/**
 * The client's weather systems and the game events that drive them.
 */
@EventBusSubscriber(modid = Petrichor.MOD_ID, value = Dist.CLIENT)
public final class WeatherClient {
    private static final Columns COLUMNS = new Columns();
    private static final RainFx FX = new RainFx();
    private static final Precipitation PRECIPITATION = new Precipitation();
    private static final FxSpawner SPAWNER = new FxSpawner();
    private static final Lightning LIGHTNING = new Lightning();
    private static Puddles puddles;
    private static ByteBufferBuilder rainBytes;
    private static int ticks;

    private WeatherClient() {
    }

    private static boolean ourSky(ClientLevel level) {
        return level != null && level.effects() instanceof PetrichorEffects;
    }

    private static ByteBufferBuilder rainBuffer() {
        if (rainBytes == null) {
            rainBytes = new ByteBufferBuilder(1 << 20);
        }
        return rainBytes;
    }

    private static Puddles puddles() {
        if (puddles == null) {
            puddles = new Puddles();
        }
        return puddles;
    }

    public static float flash(float partialTick) {
        return LIGHTNING.flash(partialTick);
    }

    /**
     * Vanilla leaves in the rain drip water particles; with drips enabled the mod's own drop falls from there instead.
     *
     * @return whether the drop was handled (false: let vanilla spawn its particle)
     */
    public static boolean leafDrip(Level level, BlockPos pos, RandomSource random) {
        if (!(level instanceof ClientLevel clientLevel) || !ourSky(clientLevel) || !ClientConfig.DRIPS.get()) {
            return false;
        }
        SPAWNER.dropFrom(clientLevel, puddles(), FX, pos.getX() + random.nextDouble(), pos.getY(), pos.getZ() + random.nextDouble());
        return true;
    }

    /** How overcast the light is: follows the rain, heavier rain is gloomier. */
    public static float gloom() {
        return ClientWeather.rain() * Math.min(1.0F, 0.45F + ClientWeather.density * 0.25F) * (float) Math.min(1.0, ClientConfig.FOG.get());
    }

    /** Debug description of the puddle data at a column. */
    public static String describePuddle(int x, int z) {
        return puddles == null ? "no puddles" : puddles.describe(x, z);
    }

    /** Throws away the puddle meshes so they are built again from the current terrain. */
    public static void rebuildSurfaces() {
        if (puddles != null) {
            puddles.clear();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (!ourSky(level)) {
            RainSounds.stopAll();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        ticks++;
        ClientWeather.tick(level);
        Camera camera = minecraft.gameRenderer.getMainCamera();
        Vec3 cam = camera.getPosition();
        COLUMNS.begin(level, ticks);
        FX.setCapacity(ClientConfig.quality().maxEffects);
        FX.recenter(cam.x, cam.z);
        FX.tick(ClientWeather.windX(), ClientWeather.windZ(), cam.x, cam.y, cam.z);
        Puddles puddles = puddles();
        puddles.tick(level, cam.x, cam.z);
        boolean rain = ClientConfig.RAIN.get();
        if (rain) {
            SPAWNER.tick(level, COLUMNS, puddles, FX, cam);
            RainSounds.tick(level, COLUMNS, cam);
        } else {
            RainSounds.stopAll();
        }
        LIGHTNING.tick(level, cam, FX, ClientWeather.thunder());
        if (ClientConfig.BOLTS.get()) {
            // Vanilla bolts set a full-white sky flash; the graded flash of the lightning system replaces it.
            level.setSkyFlashTime(0);
        }
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ClientLevel level && event.getEntity() instanceof LightningBolt bolt && ourSky(level)
                && ClientConfig.BOLTS.get()) {
            LIGHTNING.onBolt(level, bolt, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        }
    }

    @SubscribeEvent
    public static void onPlaySound(PlaySoundEvent event) {
        if (event.getSound() instanceof Lightning.ThunderSound || !ClientConfig.DELAYED_THUNDER.get()
                || !ourSky(Minecraft.getInstance().level)) {
            return;
        }
        String name = event.getName();
        if (name.equals("entity.lightning_bolt.thunder") || name.equals("entity.lightning_bolt.impact")) {
            event.setSound(null);
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientWeather.reset();
        FX.clear();
        LIGHTNING.clear();
        RainSounds.stopAll();
        if (puddles != null) {
            puddles.clear();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------------------------

    /** Draws rain, snow and the rain effects in place of the vanilla weather. */
    public static boolean renderWeather(ClientLevel level, float partialTick, LightTexture lightTexture, double camX, double camY,
            double camZ) {
        ShaderInstance shader = PetrichorShaders.rain();
        if (shader == null) {
            return false;
        }
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        COLUMNS.begin(level, ticks);
        AbstractTexture texture = Minecraft.getInstance().getTextureManager().getTexture(FxAtlas.TEXTURE);
        texture.setFilter(true, false);

        lightTexture.turnOnLightLayer();
        RenderSystem.setShader(() -> shader);
        RenderSystem.setShaderTexture(0, FxAtlas.TEXTURE);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();

        float[] fog = RenderSystem.getShaderFogColor();
        double time = level.getGameTime() + (double) partialTick;
        // Two passes: drops add light (rain glints, it never darkens), flakes and effects blend normally.
        ByteBufferBuilder rainBytes = rainBuffer();
        BufferBuilder drops = new BufferBuilder(rainBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        PRECIPITATION.render(drops, builder, COLUMNS, camX, camY, camZ, time, left, up, fog);
        FX.render(builder, drops, camX, camY, camZ, partialTick, left, up);
        MeshData mesh = builder.build();
        if (mesh != null) {
            BufferUploader.drawWithShader(mesh);
        }
        MeshData dropMesh = drops.build();
        if (dropMesh != null) {
            RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
            BufferUploader.drawWithShader(dropMesh);
            RenderSystem.defaultBlendFunc();
        }

        ShaderInstance veil = PetrichorShaders.veil();
        if (veil != null) {
            RenderSystem.setShader(() -> veil);
            RenderSystem.setShaderTexture(0, RainVeils.TEXTURE);
            Minecraft.getInstance().getTextureManager().getTexture(RainVeils.TEXTURE).setFilter(true, false);
            builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            RainVeils.render(builder, COLUMNS, camX, camY, camZ, time, fog, RenderSystem.getShaderFogEnd());
            mesh = builder.build();
            if (mesh != null) {
                BufferUploader.drawWithShader(mesh);
            }
        }

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
        lightTexture.turnOffLightLayer();
        return true;
    }

    @SubscribeEvent
    public static void onRenderStage(RenderLevelStageEvent event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!ourSky(level)) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            LIGHTNING.renderSky(event.getModelViewMatrix(), event.getCamera(), partialTick);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            if (puddles != null) {
                puddles.render(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera().getPosition(), event.getFrustum(),
                        partialTick);
            }
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            LIGHTNING.renderBolts(event.getModelViewMatrix(), event.getCamera(), partialTick);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Fog
    // ------------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderFog(ViewportEvent.RenderFog event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!ourSky(level) || event.getMode() != FogRenderer.FogMode.FOG_TERRAIN || event.getType() != FogType.NONE) {
            return;
        }
        float rain = ClientWeather.rain();
        double strength = ClientConfig.FOG.get();
        if (rain <= 0.0F || strength <= 0.0) {
            return;
        }
        float far = event.getFarPlaneDistance();
        float target = ClientWeather.fogDistance;
        if (target >= far) {
            return;
        }
        float k = (float) Math.min(1.0, rain * Math.sqrt(Math.max(ClientWeather.intensity(), 0.0F)) * strength);
        float newFar = far + (target - far) * k;
        float near = event.getNearPlaneDistance();
        // The haze starts a little in front of the camera instead of a clear zone with a wall of fog.
        float newNear = Math.min(near, newFar * (0.4F - 0.25F * k));
        event.setFarPlaneDistance(newFar);
        event.setNearPlaneDistance(Math.max(0.0F, newNear));
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFogColor(ViewportEvent.ComputeFogColor event) {
        ClientLevel level = Minecraft.getInstance().level;
        if (!ourSky(level) || event.getCamera().getFluidInCamera() != FogType.NONE) {
            return;
        }
        float r = event.getRed();
        float g = event.getGreen();
        float b = event.getBlue();
        float rain = ClientWeather.rain() * (float) Math.min(1.0, ClientConfig.FOG.get());
        if (rain > 0.0F) {
            // Rain washes the colour out towards a deep, neutral grey.
            float luma = r * 0.3F + g * 0.59F + b * 0.11F;
            float k = rain * 0.55F;
            r += (luma * 0.84F - r) * k;
            g += (luma * 0.9F - g) * k;
            b += (luma * 0.97F - b) * k;
        }
        float flash = LIGHTNING.flash((float) event.getPartialTick());
        if (flash > 0.0F) {
            r += flash * 0.35F;
            g += flash * 0.37F;
            b += flash * 0.45F;
        }
        event.setRed(Math.min(1.0F, r));
        event.setGreen(Math.min(1.0F, g));
        event.setBlue(Math.min(1.0F, b));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Debug
    // ------------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onDebugText(CustomizeGuiOverlayEvent.DebugText event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.getDebugOverlay().showDebugScreen() || !ourSky(minecraft.level)) {
            return;
        }
        event.getRight().add("");
        event.getRight().add(String.format("Petrichor: %s%s, rain %.2f, intensity %.2f, wetness %.2f",
                ClientWeather.type().id(), ClientWeather.syncedWithServer() ? " (server)" : "", ClientWeather.rain(),
                ClientWeather.intensity(), ClientWeather.wetness()));
        event.getRight().add(String.format("Drops %d, effects %d, puddle chunks %d (%d quads), strikes %d",
                PRECIPITATION.lastDrops(), FX.count(), puddles == null ? 0 : puddles.chunkCount(),
                puddles == null ? 0 : puddles.lastQuads(), LIGHTNING.strikeCount()));
        event.getRight().add(String.format("Wind %.2f, %.2f, drip sources %d", ClientWeather.windX(), ClientWeather.windZ(),
                puddles == null ? 0 : puddles.emitterCount()));
        event.getRight().add("Rain sound: " + RainSounds.debugSummary());
        if (puddles != null && minecraft.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            event.getRight().add("Puddle: " + puddles.describe(hit.getBlockPos().getX(), hit.getBlockPos().getZ()));
        }
    }
}
