package com.pockyl.petrichor.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;
import com.pockyl.petrichor.client.fx.RainFx;

/**
 * The sound of rain, mixed from looping layers instead of vanilla's scattered one-shots:
 * <ul>
 *   <li>light, medium and heavy rain loops, blended by the rain type and scaled by how hard it rains;</li>
 *   <li>how much of it you hear depends on how open your surroundings are (columns around you where rain reaches the
 *   ground near your level) and on the sky light where you stand - a closed room is quiet, a doorway is not;</li>
 *   <li>under a roof the muffled drumming of rain on the roof takes over, louder the closer the roof is;</li>
 *   <li>one-shots for drips landing and steps in puddles.</li>
 * </ul>
 * The sound events are not registered (only listed in sounds.json), so the client works on servers without the mod.
 */
public final class RainSounds {
    public static final SoundEvent RAIN_LIGHT = event("ambient.rain.light");
    public static final SoundEvent RAIN_MEDIUM = event("ambient.rain.medium");
    public static final SoundEvent RAIN_HEAVY = event("ambient.rain.heavy");
    public static final SoundEvent RAIN_ROOF = event("ambient.rain.roof");
    public static final SoundEvent PUDDLE_STEP = event("step.puddle");
    private static final int[] RINGS = {3, 6, 10};
    private static final int DIRECTIONS = 8;

    private static final RandomSource RANDOM = RandomSource.create();
    private static LoopSound light;
    private static LoopSound medium;
    private static LoopSound heavy;
    private static LoopSound roof;
    private static float open;
    private static float roofAmount;

    private RainSounds() {
    }

    private static SoundEvent event(String path) {
        return SoundEvent.createVariableRangeEvent(Petrichor.id(path));
    }

    public static void tick(ClientLevel level, Columns columns, Vec3 eye) {
        measure(level, columns, eye);
        float loudness = Math.min(1.0F, ClientWeather.intensity()) * (float) (double) ClientConfig.RAIN_VOLUME.get();
        float outdoor = open;
        light = drive(light, RAIN_LIGHT, ClientWeather.soundLight * loudness * outdoor * 0.8F);
        medium = drive(medium, RAIN_MEDIUM, ClientWeather.soundMedium * loudness * outdoor * 0.85F);
        heavy = drive(heavy, RAIN_HEAVY, ClientWeather.soundHeavy * loudness * outdoor);
        float roofTarget = ClientConfig.ROOF.get() ? roofAmount * loudness * (0.45F + 0.25F * ClientWeather.density) : 0.0F;
        roof = drive(roof, RAIN_ROOF, roofTarget);
    }

    /** How open the surroundings are to the rain, and how much roof is overhead. */
    private static void measure(ClientLevel level, Columns columns, Vec3 eye) {
        int ex = Mth.floor(eye.x);
        int ez = Mth.floor(eye.z);
        double ey = eye.y;
        int exposed = 0;
        int samples = 0;
        for (int radius : RINGS) {
            for (int d = 0; d < DIRECTIONS; d++) {
                float angle = (d + radius * 0.37F) * Mth.TWO_PI / DIRECTIONS;
                int x = ex + Math.round(Mth.cos(angle) * radius);
                int z = ez + Math.round(Mth.sin(angle) * radius);
                byte kind = columns.precipitation(x, z);
                if (kind == Columns.NONE) {
                    continue;
                }
                samples++;
                if (kind == Columns.RAIN && columns.height(x, z) <= ey + 3.0) {
                    exposed++;
                }
            }
        }
        float sky = level.getBrightness(LightLayer.SKY, BlockPos.containing(eye)) / 15.0F;
        float openness = samples == 0 ? 0.0F : (float) exposed / (RINGS.length * DIRECTIONS);
        open = openness * (0.3F + 0.7F * sky);
        int above = columns.height(ex, ez);
        double roofDistance = above - ey;
        boolean rainsHere = columns.precipitation(ex, ez) == Columns.RAIN;
        if (rainsHere && roofDistance > 0.5 && roofDistance < 20.0) {
            roofAmount = (1.0F - 0.6F * openness) * Math.clamp(1.2F - (float) roofDistance / 16.0F, 0.25F, 1.0F);
        } else {
            roofAmount = 0.0F;
        }
    }

    private static LoopSound drive(LoopSound loop, SoundEvent event, float target) {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        if (loop != null && (loop.isStopped() || !sounds.isActive(loop))) {
            loop = null;
        }
        if (loop == null) {
            if (target < 0.005F) {
                return null;
            }
            loop = new LoopSound(event);
            sounds.play(loop);
        }
        loop.setTarget(target);
        return loop;
    }

    public static void stopAll() {
        for (LoopSound loop : new LoopSound[] {light, medium, heavy, roof}) {
            if (loop != null) {
                Minecraft.getInstance().getSoundManager().stop(loop);
            }
        }
        light = null;
        medium = null;
        heavy = null;
        roof = null;
    }

    /** A falling drip landed; plays now and then within earshot so a dripping eave is heard but not a drum roll. */
    public static void drip(double x, double y, double z, double distanceSq, byte surface) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || distanceSq > 14 * 14 || RANDOM.nextFloat() > 0.12F) {
            return;
        }
        SoundEvent sound = surface == RainFx.LAND_GROUND ? SoundEvents.POINTED_DRIPSTONE_DRIP_WATER
                : SoundEvents.POINTED_DRIPSTONE_DRIP_WATER_INTO_CAULDRON;
        level.playLocalSound(x, y, z, sound, SoundSource.WEATHER, 0.25F + RANDOM.nextFloat() * 0.2F, 0.8F + RANDOM.nextFloat() * 0.5F,
                false);
    }

    public static void puddleStep(Entity entity, float strength) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), PUDDLE_STEP, entity.getSoundSource(),
                0.2F + 0.35F * Math.min(1.0F, strength), 0.9F + RANDOM.nextFloat() * 0.25F, false);
    }

    public static float open() {
        return open;
    }

    public static float roof() {
        return roofAmount;
    }
}
