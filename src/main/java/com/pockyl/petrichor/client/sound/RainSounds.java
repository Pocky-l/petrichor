package com.pockyl.petrichor.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;
import com.pockyl.petrichor.client.fx.RainFx;
import com.pockyl.petrichor.client.render.Puddles;
import com.pockyl.petrichor.world.SoundMaterial;

/**
 * The sound of rain: the {@link Soundscape} of loops placed around the listener, plus single drops - water falling
 * off eaves and leaves onto stone, planks, metal or a puddle, and the odd nearby drop that lets a light rain be heard
 * drop by drop.
 *
 * <p>Intensity drives everything: a drizzle is a soft hush with single drops, a downpour a dense roar; gusts make the
 * rain swell and ebb.
 */
@EventBusSubscriber(modid = Petrichor.MOD_ID, value = Dist.CLIENT)
public final class RainSounds {
    private static final RandomSource RANDOM = RandomSource.create();
    private static final Soundscape SOUNDSCAPE = new Soundscape();
    /** Falling drops heard at most this far. */
    private static final double DRIP_RANGE = 16.0;
    /** At most this many drop sounds start per second (a dripping eave, not a drum roll). */
    private static final float DRIPS_PER_TICK = 0.35F;
    private static final float DRIP_BURST = 3.0F;
    private static final float DRIP_GAIN = 0.4F;
    private static final float CLOSE_GAIN = 0.16F;
    private static float dripTokens;

    private RainSounds() {
    }

    /** A drop or other one-shot of the mod, muffled by what is between it and the listener. */
    private static final class ShotSound extends SimpleSoundInstance implements Muffler.Muffled {
        private final float highs;

        ShotSound(SoundEvent sound, float volume, float pitch, double x, double y, double z, float highs) {
            super(sound.getLocation(), SoundSource.WEATHER, volume, pitch, SoundInstance.createUnseededRandom(), false, 0,
                    SoundInstance.Attenuation.LINEAR, x, y, z, false);
            this.highs = highs;
        }

        @Override
        public float highs() {
            return highs;
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Intensity
    // ------------------------------------------------------------------------------------------------------------

    /** How hard it rains for the ear, ~0.15 for a drizzle .. ~1 for a downpour, swelling with gusts. */
    public static float intensity() {
        return ClientWeather.heaviness * (float) Math.pow(Mth.clamp(gust(), 0.4F, 1.6F), 0.6);
    }

    /** Overall loudness at an intensity: a drizzle is quiet, a downpour loud. */
    static float loudness(float intensity) {
        return 0.2F + 0.8F * (float) Math.pow(Math.min(intensity, 1.2F), 0.8);
    }

    static float gust() {
        float rain = ClientWeather.rain();
        return rain > 0.001F ? ClientWeather.intensity() / rain : 1.0F;
    }

    /** Water still dripping and running after the rain, 0..0.25, while the ground is wet. */
    static float afterRain() {
        return Math.clamp((ClientWeather.wetness() - 0.1F) * 0.4F, 0.0F, 0.25F) * (1.0F - Math.min(1.0F, ClientWeather.rain() * 2.0F));
    }

    /** 0 out in the open .. 1 in a closed room under a roof. */
    public static float enclosure() {
        return SOUNDSCAPE.enclosure();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------------------------------------------------

    public static void tick(ClientLevel level, Columns columns, Puddles puddles, Vec3 eye) {
        SOUNDSCAPE.tick(level, columns, puddles, eye);
        dripTokens = Math.min(DRIP_BURST, dripTokens + DRIPS_PER_TICK);
        closeDrops(level, columns, puddles, eye);
    }

    public static void stopAll() {
        SOUNDSCAPE.stop();
    }

    /**
     * Now and then a single drop lands close to the listener and is heard on its own: on a stone, a plank, a puddle.
     * In a drizzle these drops are most of what is heard; in a downpour they get lost in the roar.
     */
    private static void closeDrops(ClientLevel level, Columns columns, Puddles puddles, Vec3 eye) {
        float rain = ClientWeather.rain();
        if (rain <= 0.0F) {
            return;
        }
        float s = intensity();
        float expected = rain * (0.08F + 0.1F * Math.min(1.0F, s));
        if (RANDOM.nextFloat() >= expected) {
            return;
        }
        float angle = RANDOM.nextFloat() * Mth.TWO_PI;
        float r = 1.2F + RANDOM.nextFloat() * 4.0F;
        double x = eye.x + Mth.cos(angle) * r;
        double z = eye.z + Mth.sin(angle) * r;
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        if (columns.precipitation(bx, bz) != Columns.RAIN) {
            return;
        }
        int h = columns.height(bx, bz);
        if (h > eye.y + 5.0 || h < eye.y - 5.0) {
            return;
        }
        BlockPos top = new BlockPos(bx, h - 1, bz);
        SoundMaterial material = SoundMaterial.of(level.getBlockState(top));
        if ((material == SoundMaterial.SOFT || material == SoundMaterial.HARD) && puddles.coverAt(x, h, z) > 0.5F) {
            material = SoundMaterial.PUDDLE;
        }
        float volume = CLOSE_GAIN * (float) (double) ClientConfig.RAIN_VOLUME.get() * rain * (0.6F + RANDOM.nextFloat() * 0.6F);
        // Single drops stand out in light rain and drown in heavy rain.
        volume *= 1.2F - 0.6F * Math.min(1.0F, s);
        playDrop(level, eye, material, x, h + 0.05, z, volume, true);
    }

    /** A falling drip landed. Every drip near the listener can be heard, within a budget of sounds per second. */
    public static void drip(double x, double y, double z, double distanceSq, byte surface) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || distanceSq > DRIP_RANGE * DRIP_RANGE || dripTokens < 1.0F) {
            return;
        }
        double distance = Math.sqrt(distanceSq);
        // Near drips always, far ones only now and then: the budget goes to what is close.
        if (RANDOM.nextFloat() > Mth.clamp(1.3F - (float) distance / 10.0F, 0.12F, 1.0F)) {
            return;
        }
        SoundMaterial material = switch (surface) {
            case RainFx.LAND_WATER -> SoundMaterial.WATER;
            case RainFx.LAND_PUDDLE -> SoundMaterial.PUDDLE;
            default -> SoundMaterial.of(level.getBlockState(BlockPos.containing(x, y - 0.05, z)));
        };
        float volume = DRIP_GAIN * (float) (double) ClientConfig.DRIP_VOLUME.get() * (0.7F + RANDOM.nextFloat() * 0.5F);
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (playDrop(level, eye, material, x, y, z, volume, false)) {
            dripTokens -= 1.0F;
        }
    }

    /**
     * A drop landing on a material.
     *
     * @param close the near-field drops of the rain itself: smaller and only where the listener can see them
     * @return whether a sound was started
     */
    private static boolean playDrop(ClientLevel level, Vec3 eye, SoundMaterial material, double x, double y, double z, float volume,
            boolean close) {
        SoundEvent sound;
        float pitch = 0.9F + RANDOM.nextFloat() * 0.25F;
        switch (material) {
            case HARD -> sound = PetrichorSounds.DROP_HARD;
            case WOOD -> sound = PetrichorSounds.DROP_WOOD;
            case METAL -> sound = PetrichorSounds.DROP_METAL;
            case GLASS -> {
                // A glassy tick: the metal drop, higher and softer.
                sound = PetrichorSounds.DROP_METAL;
                pitch += 0.35F;
                volume *= 0.6F;
            }
            case PUDDLE -> sound = PetrichorSounds.DROP_PUDDLE;
            case WATER -> {
                // Deeper water: a rounder plop.
                sound = PetrichorSounds.DROP_PUDDLE;
                pitch -= 0.2F;
            }
            case LEAVES -> sound = PetrichorSounds.DROP_LEAVES;
            case FABRIC -> {
                sound = PetrichorSounds.DROP_LEAVES;
                pitch -= 0.3F;
                volume *= 0.7F;
            }
            case SOFT -> {
                // Grass and earth swallow a drop: only a soft tap, and only some of them.
                if (close || RANDOM.nextFloat() < 0.5F) {
                    return false;
                }
                sound = PetrichorSounds.DROP_LEAVES;
                pitch -= 0.35F;
                volume *= 0.35F;
            }
            default -> {
                return false;
            }
        }
        float highs = 1.0F;
        BlockHitResult hit = level.clip(new ClipContext(eye, new Vec3(x, y + 0.1, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.MISS && hit.getLocation().distanceToSqr(x, y + 0.1, z) > 1.0) {
            if (close) {
                return false;
            }
            highs = 0.12F;
            volume *= 0.45F;
        }
        if (volume < 0.01F) {
            return false;
        }
        Minecraft.getInstance().getSoundManager().play(new ShotSound(sound, volume, pitch, x, y, z, highs));
        return true;
    }

    public static void puddleStep(Entity entity, float strength) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), PetrichorSounds.PUDDLE_STEP, entity.getSoundSource(),
                0.15F + 0.25F * Math.min(1.0F, strength), 0.9F + RANDOM.nextFloat() * 0.25F, false);
    }

    /** What the listener hears, for the debug screen. */
    public static String debugSummary() {
        return String.format("intensity %.2f, ", intensity()) + SOUNDSCAPE.debugSummary();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Muffling hooks
    // ------------------------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onSourceReady(PlaySoundSourceEvent event) {
        Muffler.onSourceReady(event.getSound(), event.getChannel());
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        SoundMaterial.clearCache();
    }
}
