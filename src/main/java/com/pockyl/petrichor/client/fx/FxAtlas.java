package com.pockyl.petrichor.client.fx;

import net.minecraft.resources.ResourceLocation;

import com.pockyl.petrichor.Petrichor;

/**
 * The effects texture: a 4x4 grid of 32px tiles, drawn with linear filtering.
 */
public final class FxAtlas {
    public static final ResourceLocation TEXTURE = Petrichor.id("textures/fx/rain_fx.png");
    public static final int SPLASH = 0;
    public static final int SPLASH_FRAMES = 4;
    public static final int RIPPLE = 4;
    public static final int DROPLET = 5;
    public static final int MIST = 6;
    public static final int GLOW = 7;
    public static final int FLAKE = 8;
    public static final int STREAK = 9;
    public static final int DRIP = 10;
    public static final int SPARK = 11;
    /** Half a texel of inset so linear filtering never samples the neighbouring tile. */
    private static final float INSET = 0.5F / 128.0F;

    private FxAtlas() {
    }

    public static float u0(int tile) {
        return (tile & 3) * 0.25F + INSET;
    }

    public static float v0(int tile) {
        return (tile >> 2) * 0.25F + INSET;
    }

    public static float u1(int tile) {
        return (tile & 3) * 0.25F + 0.25F - INSET;
    }

    public static float v1(int tile) {
        return (tile >> 2) * 0.25F + 0.25F - INSET;
    }
}
