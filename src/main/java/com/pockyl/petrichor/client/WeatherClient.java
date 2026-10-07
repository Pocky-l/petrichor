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
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
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
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.joml.Vector3f;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.compat.ShaderPacks;
import com.pockyl.petrichor.client.fx.FxAtlas;
import com.pockyl.petrichor.client.fx.FxSpawner;
import com.pockyl.petrichor.client.fx.Precipitation;
import com.pockyl.petrichor.client.fx.RainFx;
import com.pockyl.petrichor.client.fx.RainVeils;
import com.pockyl.petrichor.client.lightning.Lightning;
import com.pockyl.petrichor.client.render.Atmosphere;
import com.pockyl.petrichor.client.render.Cinematics;
import com.pockyl.petrichor.client.render.PetrichorEffects;
import com.pockyl.petrichor.client.render.PetrichorShaders;
import com.pockyl.petrichor.client.render.Puddles;
import com.pockyl.petrichor.client.render.SceneCopy;
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
    /** How much of the sky the camera sees, 0 (deep indoors, underground) .. 1 (outdoors), eased. */
    private static float skyView = 1.0F;
    private static float previousSkyView = 1.0F;

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

    /** Whether the mod draws the rain (and vanilla only the snow). */
    public static boolean ownsRain() {
        return ClientConfig.RAIN.get() && PetrichorShaders.rain() != null && ourSky(Minecraft.getInstance().level);
    }

    public static float flash(float partialTick) {
        return LIGHTNING.flash(partialTick);
    }

    /** How much the world darkens in the moment before a strike. */
    public static float hush(float partialTick) {
        return LIGHTNING.hush(partialTick);
    }

    /** Light of close strikes, which overexposes the view. */
    public static float glare(float partialTick) {
        return LIGHTNING.glare(partialTick);
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

    /**
     * How much of the sky the camera sees, from the sky light where it stands: 0 deep indoors or underground, 1
     * outdoors. What lights or hazes the whole view (lightning flashes, the rain's haze and fog) fades with it, so a
     * storm overhead does not flicker in a cave.
     */
    public static float skyView(float partialTick) {
        return Mth.lerp(partialTick, previousSkyView, skyView);
    }

    private static void tickSkyView(ClientLevel level, Camera camera) {
        previousSkyView = skyView;
        int light = level.getBrightness(LightLayer.SKY, camera.getBlockPosition());
        // Sky light drops by one per block into a cave: the sky's light fades in over the last 14 blocks to the exit.
        float target = Mth.clamp((light - 1) / 14.0F, 0.0F, 1.0F);
        target *= target;
        skyView += (target - skyView) * 0.15F;
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
        tickSkyView(level, camera);
        COLUMNS.begin(level, ticks);
        FX.setCapacity(ClientConfig.quality().maxEffects);
        FX.recenter(cam.x, cam.z);
        FX.tick(ClientWeather.windX(), ClientWeather.windZ(), cam.x, cam.y, cam.z);
        Puddles puddles = puddles();
        puddles.tick(level, cam.x, cam.z);
        boolean rain = ClientConfig.RAIN.get();
        if (rain) {
            SPAWNER.tick(level, COLUMNS, puddles, FX, cam);
            RainSounds.tick(level, COLUMNS, puddles, cam);
        } else {
            RainSounds.stopAll();
        }
        LIGHTNING.tick(level, cam, FX, ClientWeather.thunder(), skyView);
        Cinematics.tick();
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
        Cinematics.clear();
        RainSounds.stopAll();
        if (puddles != null) {
            puddles.clear();
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------------------------

    /** Draws the rain and its effects in place of the vanilla rain. */
    public static boolean renderWeather(ClientLevel level, float partialTick, LightTexture lightTexture, double camX, double camY,
            double camZ) {
        // With a shader pack the vanilla particle shader is used: Iris swaps it for the pack's weather program, while
        // the mod's own shaders would not be drawn at all.
        boolean shaderPack = ShaderPacks.inUse();
        ShaderInstance shader = shaderPack ? GameRenderer.getParticleShader() : PetrichorShaders.rain();
        if (shader == null) {
            return false;
        }
        // The air first: haze over the distance, the overcast sky. The rain falls in front of it.
        Atmosphere.render(level, partialTick, camX, camY, camZ);
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
        // Two passes: drops add light (rain glints, it never darkens), effects blend normally.
        ByteBufferBuilder rainBytes = rainBuffer();
        BufferBuilder drops = new BufferBuilder(rainBytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        PRECIPITATION.render(drops, COLUMNS, camX, camY, camZ, time, fog, LIGHTNING.flash(partialTick));
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

        ShaderInstance veil = shaderPack ? GameRenderer.getParticleShader() : PetrichorShaders.veil();
        if (veil != null) {
            RenderSystem.setShader(() -> veil);
            RenderSystem.setShaderTexture(0, RainVeils.TEXTURE);
            Minecraft.getInstance().getTextureManager().getTexture(RainVeils.TEXTURE).setFilter(true, false);
            builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
            RainVeils.render(builder, COLUMNS, camX, camY, camZ, time, fog, RenderSystem.getShaderFogEnd(),
                    Atmosphere.active() ? Atmosphere.haze() : 0.0F);
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
            SceneCopy.newFrame();
            LIGHTNING.renderSky(event.getModelViewMatrix(), event.getCamera(), partialTick);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            if (puddles != null) {
                puddles.render(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera().getPosition(), event.getFrustum(),
                        partialTick);
            }
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            if (puddles != null) {
                puddles.renderWater(event.getModelViewMatrix(), event.getProjectionMatrix(), event.getCamera().getPosition(),
                        event.getFrustum());
            }
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            LIGHTNING.renderBolts(event.getModelViewMatrix(), event.getCamera(), partialTick);
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Cinematic
    // ------------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Pre event) {
        if (ourSky(Minecraft.getInstance().level)) {
            Cinematics.renderScreen(event.getGuiGraphics(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
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
        float rain = ClientWeather.rain() * skyView((float) event.getPartialTick());
        double strength = ClientConfig.FOG.get();
        // The atmosphere draws the haze itself; the vanilla fog only hides the edge of the world as usual.
        if (rain <= 0.0F || strength <= 0.0 || Atmosphere.active()) {
            return;
        }
        float far = event.getFarPlaneDistance();
        // Without the atmosphere a plain fog stands in for the haze, about half as far as one can see.
        float target = ClientWeather.visibility * 0.5F;
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
        float rain = ClientWeather.rain() * skyView((float) event.getPartialTick()) * (float) Math.min(1.0, ClientConfig.FOG.get());
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
