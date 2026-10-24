package com.pockyl.petrichor.client.sound;

/**
 * The ears after a strike next to the listener: for a moment the rain around goes faint and dull, as if half deaf,
 * and comes back over a few seconds. Thunder itself is not affected.
 */
public final class Deafness {
    /** Ticks the ears stay stunned before they start to recover. */
    private static final int HOLD = 8;
    /** Recovery per tick: from full deafness in about three and a half seconds. */
    private static final float RECOVERY = 0.014F;

    private static float amount;
    private static int hold;

    private Deafness() {
    }

    /** A strike was heard: {@code strength} 1 = right next to it. Never weakens a stronger stun still going on. */
    public static void stun(float strength) {
        if (strength > amount) {
            amount = Math.min(1.0F, strength);
            hold = HOLD;
        }
    }

    public static void tick() {
        if (hold > 0) {
            hold--;
        } else {
            amount = Math.max(0.0F, amount - RECOVERY);
        }
    }

    public static void clear() {
        amount = 0.0F;
        hold = 0;
    }

    /** Volume factor for the rain; the recovery eases out so the sound swells back softly. */
    public static float volume() {
        float a = amount * amount * (3.0F - 2.0F * amount);
        return 1.0F - 0.8F * a;
    }

    /** How much of the highs reach the ear. */
    public static float highs() {
        float a = amount * amount * (3.0F - 2.0F * amount);
        return 1.0F - 0.9F * a;
    }
}
