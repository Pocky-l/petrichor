package com.pockyl.petrichor.client.sound;

import net.minecraft.sounds.SoundEvent;

import com.pockyl.petrichor.Petrichor;

/**
 * The mod's sound events. They are not registered, only listed in sounds.json, so the client works on servers without
 * the mod; the client plays them directly.
 */
public final class PetrichorSounds {
    // The open ground around the listener, by intensity.
    public static final SoundEvent GROUND_LIGHT = event("ambient.rain.ground_light");
    public static final SoundEvent GROUND_MEDIUM = event("ambient.rain.ground_medium");
    public static final SoundEvent GROUND_HEAVY = event("ambient.rain.ground_heavy");
    public static final SoundEvent FAR = event("ambient.rain.far");
    public static final SoundEvent WIND = event("ambient.wind");

    // Surfaces near the listener.
    public static final SoundEvent LEAVES = event("surface.rain.leaves");
    public static final SoundEvent HARD_LIGHT = event("surface.rain.hard_light");
    public static final SoundEvent HARD_HEAVY = event("surface.rain.hard_heavy");
    public static final SoundEvent WOOD_LIGHT = event("surface.rain.wood_light");
    public static final SoundEvent WOOD_HEAVY = event("surface.rain.wood_heavy");
    public static final SoundEvent METAL_LIGHT = event("surface.rain.metal_light");
    public static final SoundEvent METAL_HEAVY = event("surface.rain.metal_heavy");
    public static final SoundEvent PUDDLE = event("surface.rain.puddle");
    public static final SoundEvent WATER_LIGHT = event("surface.rain.water_light");
    public static final SoundEvent WATER_HEAVY = event("surface.rain.water_heavy");
    public static final SoundEvent WINDOW = event("surface.rain.window");

    // Roofs heard from below.
    public static final SoundEvent ROOF_METAL_LIGHT = event("roof.rain.metal_light");
    public static final SoundEvent ROOF_METAL_HEAVY = event("roof.rain.metal_heavy");
    public static final SoundEvent ROOF_WOOD_LIGHT = event("roof.rain.wood_light");
    public static final SoundEvent ROOF_WOOD_HEAVY = event("roof.rain.wood_heavy");
    public static final SoundEvent ROOF_GLASS_LIGHT = event("roof.rain.glass_light");
    public static final SoundEvent ROOF_GLASS_HEAVY = event("roof.rain.glass_heavy");
    public static final SoundEvent ROOF_FABRIC_LIGHT = event("roof.rain.fabric_light");
    public static final SoundEvent ROOF_FABRIC_HEAVY = event("roof.rain.fabric_heavy");
    public static final SoundEvent ROOF_THICK = event("roof.rain.thick");

    // Running water.
    public static final SoundEvent TRICKLE = event("water.trickle");
    public static final SoundEvent POUR = event("water.pour");

    // One-shots.
    public static final SoundEvent DROP_PUDDLE = event("drop.puddle");
    public static final SoundEvent DROP_HARD = event("drop.hard");
    public static final SoundEvent DROP_WOOD = event("drop.wood");
    public static final SoundEvent DROP_METAL = event("drop.metal");
    public static final SoundEvent DROP_LEAVES = event("drop.leaves");
    public static final SoundEvent PUDDLE_STEP = event("step.puddle");
    public static final SoundEvent THUNDER_CLOSE = event("weather.thunder.close");
    public static final SoundEvent THUNDER_MID = event("weather.thunder.mid");
    public static final SoundEvent THUNDER_FAR = event("weather.thunder.far");

    private PetrichorSounds() {
    }

    private static SoundEvent event(String path) {
        return SoundEvent.createVariableRangeEvent(Petrichor.id(path));
    }
}
