package com.pockyl.petrichor.client.sound;

import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * A looping ambience whose volume glides towards a target and which stops itself once silent.
 */
final class LoopSound extends AbstractTickableSoundInstance {
    private static final float STEP = 0.025F;
    private float target;
    private int silentTicks;

    LoopSound(SoundEvent event) {
        super(event, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
        looping = true;
        delay = 0;
        volume = 0.0001F;
        relative = true;
        attenuation = Attenuation.NONE;
    }

    void setTarget(float target) {
        this.target = Math.clamp(target, 0.0F, 1.0F);
    }

    @Override
    public boolean canStartSilent() {
        return true;
    }

    @Override
    public void tick() {
        volume += Math.clamp(target - volume, -STEP, STEP);
        if (target <= 0.0F && volume <= 0.001F) {
            if (++silentTicks > 40) {
                stop();
            }
        } else {
            silentTicks = 0;
        }
    }

    void fadeOut() {
        target = 0.0F;
    }
}
