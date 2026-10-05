package com.pockyl.petrichor.client.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * A looping rain sound placed in the world. Volume and position glide towards their targets, so a source that moves
 * to another spot (the player walked on) slides there instead of jumping, and it stops itself once silent.
 */
final class LoopSound extends AbstractTickableSoundInstance {
    private static final float VOLUME_STEP = 0.02F;
    private static final double MOVE = 0.15;
    private float target;
    private double targetX;
    private double targetY;
    private double targetZ;
    private int silentTicks;

    LoopSound(SoundEvent event, double x, double y, double z, RandomSource random) {
        super(event, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
        looping = true;
        delay = 0;
        volume = 0.0001F;
        // A slightly different pitch per source keeps sources of the same loop from phasing.
        pitch = 0.95F + random.nextFloat() * 0.1F;
        attenuation = Attenuation.LINEAR;
        this.x = targetX = x;
        this.y = targetY = y;
        this.z = targetZ = z;
    }

    void setTarget(float volume, double x, double y, double z) {
        target = Math.clamp(volume, 0.0F, 1.0F);
        targetX = x;
        targetY = y;
        targetZ = z;
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        volume += Math.clamp(target - volume, -VOLUME_STEP, VOLUME_STEP);
        x += (targetX - x) * MOVE;
        y += (targetY - y) * MOVE;
        z += (targetZ - z) * MOVE;
        if (target <= 0.0F && volume <= 0.001F) {
            if (++silentTicks > 40) {
                stop();
            }
        } else {
            silentTicks = 0;
        }
    }
}
