package com.pockyl.petrichor.client.lightning;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

/**
 * One lightning event and its timeline (in ticks):
 * <ol>
 *   <li>a hush: for a moment before a strike the world darkens (negative ages, only with the cinematic option);</li>
 *   <li>the stepped leader grows from the cloud in a few ticks, faint, with all its branches;</li>
 *   <li>the return stroke: the channel flares up, the branches light once and fade;</li>
 *   <li>one to three restrokes down the same channel at random intervals - the flicker of real lightning;</li>
 *   <li>a short afterglow - after a close strike a long, fading afterimage of the channel.</li>
 * </ol>
 * Cloud flashes have no visible channel; they only light the clouds and the sky.
 */
final class Strike {
    enum Kind {
        GROUND,
        CLOUD,
        SHEET
    }

    final Kind kind;
    final double x;
    final double y;
    final double z;
    final BoltShape shape;
    /** Centre of the glow in the clouds. */
    final double glowX;
    final double glowY;
    final double glowZ;
    final float glowRadius;
    final float leaderTicks;
    final float[] strokes;
    final float end;
    final float distance;
    /** Whether the bolt exists in the world (a vanilla bolt) rather than as distant scenery. */
    final boolean real;
    /** Ticks of hush before the strike begins. */
    final float lead;
    /** The channel stays visible as an afterimage for this many ticks after the strokes. */
    final float afterimage;
    float age;
    boolean impactDone;

    Strike(Kind kind, double x, double y, double z, BoltShape shape, double glowX, double glowY, double glowZ, float glowRadius,
            float distance, boolean real, float lead, float afterimage, RandomSource random) {
        this.kind = kind;
        this.x = x;
        this.y = y;
        this.z = z;
        this.shape = shape;
        this.glowX = glowX;
        this.glowY = glowY;
        this.glowZ = glowZ;
        this.glowRadius = glowRadius;
        this.distance = distance;
        this.real = real;
        this.lead = lead;
        this.afterimage = afterimage;
        age = -lead;
        leaderTicks = kind == Kind.SHEET ? 0.0F : 2.5F + random.nextFloat() * 2.5F;
        int restrokes = kind == Kind.SHEET ? 1 + random.nextInt(3) : random.nextInt(4);
        strokes = new float[1 + restrokes];
        float t = leaderTicks;
        for (int s = 0; s < strokes.length; s++) {
            strokes[s] = t;
            t += 1.5F + random.nextFloat() * 5.5F;
        }
        end = strokes[strokes.length - 1] + 8.0F + afterimage;
    }

    /** How deep the hush before the strike is, 0..1: it deepens until the leader starts, then lifts at once. */
    float hush(float t) {
        if (lead <= 0.0F || t >= 0.0F) {
            return 0.0F;
        }
        float k = Mth.clamp(1.0F + t / lead, 0.0F, 1.0F);
        return k * k * (3.0F - 2.0F * k);
    }

    boolean done() {
        return age > end;
    }

    private static float pulse(float t) {
        if (t < 0.0F) {
            return 0.0F;
        }
        if (t < 0.3F) {
            return t / 0.3F;
        }
        // A bright moment, then a fade over a few tenths of a second, as the eye perceives a stroke.
        return (float) Math.exp(-(t - 0.3F) / 2.2F);
    }

    /** Light the event gives off at time {@code t}, 0..~1.2. */
    float flash(float t) {
        if (t < 0.0F) {
            return 0.0F;
        }
        float sum = 0.0F;
        for (int s = 0; s < strokes.length; s++) {
            sum += pulse(t - strokes[s]) * (s == 0 ? 1.0F : 0.75F);
        }
        if (t < leaderTicks) {
            sum += 0.08F * t / Math.max(leaderTicks, 0.01F);
        }
        return Math.min(sum, 1.2F);
    }

    /** Brightness of the main channel: the strokes plus an afterglow. */
    float mainBrightness(float t) {
        if (t < leaderTicks) {
            return 0.0F;
        }
        float strokesLight = 0.0F;
        for (float stroke : strokes) {
            strokesLight = Math.max(strokesLight, pulse(t - stroke));
        }
        float strokesEnd = end - afterimage;
        float glow = 0.22F * (1.0F - Mth.clamp((t - leaderTicks) / (strokesEnd - leaderTicks), 0.0F, 1.0F));
        if (afterimage > 0.0F && t > strokesEnd - 8.0F) {
            // The afterimage: the shape burnt into the eye, fading slowly.
            float k = 1.0F - Mth.clamp((t - (strokesEnd - 8.0F)) / (afterimage + 8.0F), 0.0F, 1.0F);
            glow = Math.max(glow, 0.16F * k * k);
        }
        return Math.max(strokesLight, glow);
    }

    /** Branches show while the leader grows and light up once with the first return stroke. */
    float branchBrightness(float t) {
        if (t < leaderTicks) {
            return 0.3F;
        }
        return pulse(t - leaderTicks) * 0.85F;
    }

    float leaderProgress(float t) {
        return leaderTicks <= 0.0F ? 1.0F : Mth.clamp(t / leaderTicks, 0.0F, 1.0F);
    }
}
