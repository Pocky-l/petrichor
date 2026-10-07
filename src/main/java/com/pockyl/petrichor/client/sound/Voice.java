package com.pockyl.petrichor.client.sound;

import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.RandomSource;

/**
 * One place in the soundscape - the rain on the field to the north, on the tin roof above, on the window - played by a
 * few loops of the same surface (light and heavy recordings) that crossfade with the rain's intensity. Each recording
 * keeps its slot, so a crossfade never restarts a loop; loops start when they become audible and stop themselves when
 * they fall silent.
 */
final class Voice {
    private static final float AUDIBLE = 0.004F;
    private static final RandomSource RANDOM = RandomSource.create();
    /** How far the listener's head is under water, 0..1: the air above reaches the ear dull and faint. */
    static float submerged;

    private final boolean distant;
    private final LoopSound[] loops;
    private final SoundEvent[] playing;
    double x;
    double y;
    double z;
    /** How much of this surface there is, 0..1, before intensity and occlusion. */
    float amount;
    /** Volume factor and highs from what stands between the listener and the place. */
    float occlusion = 1.0F;
    float highs = 1.0F;
    /** A sound in the water itself, heard as it is under water. */
    boolean underwater;

    Voice(int slots, boolean distant) {
        this.distant = distant;
        loops = new LoopSound[slots];
        playing = new SoundEvent[slots];
    }

    void place(double x, double y, double z, float amount) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.amount = amount;
    }

    /** Plays {@code sound} in {@code slot} at this volume; null or a different sound fades the slot's loop out. */
    void drive(int slot, SoundEvent sound, float volume) {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        float highs = this.highs;
        if (!underwater) {
            volume *= 1.0F - 0.7F * submerged;
            highs *= 1.0F - 0.95F * submerged;
        }
        LoopSound loop = loops[slot];
        if (loop != null && (loop.isStopped() || !sounds.isActive(loop))) {
            loop = null;
        }
        if (loop != null && playing[slot] != sound) {
            // The surface changed (a different roof above): fade the old loop out, it stops itself.
            loop.setTarget(0.0F, x, y, z, highs);
            loop = null;
        }
        if (loop == null) {
            if (sound == null || volume < AUDIBLE) {
                loops[slot] = null;
                return;
            }
            loop = new LoopSound(sound, x, y, z, highs, distant, RANDOM);
            sounds.play(loop);
            playing[slot] = sound;
        }
        loop.setTarget(volume, x, y, z, highs);
        loops[slot] = loop;
    }

    void silence() {
        for (int i = 0; i < loops.length; i++) {
            drive(i, null, 0.0F);
        }
    }

    void stop() {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        for (int i = 0; i < loops.length; i++) {
            if (loops[i] != null) {
                sounds.stop(loops[i]);
                loops[i] = null;
            }
        }
    }
}
