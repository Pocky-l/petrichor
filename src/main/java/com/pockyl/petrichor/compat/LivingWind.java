package com.pockyl.petrichor.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.util.Mth;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import com.pockyl.petrichor.ClientConfig;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * The world wind of <a href="https://www.curseforge.com/minecraft/mc-mods/living-wind">Living Wind</a> (mod id
 * {@code livingwind}): its server simulates one wind per level and syncs it to the clients, and while it blows the rain
 * slants the way its wind vanes point. The speed stays this mod's (a drizzle drifts, a storm drives the rain sideways),
 * scaled by how much harder or softer Living Wind blows than it usually does in this weather.
 *
 * <p>Living Wind is never compiled against: its public {@code ClientWindApi} is called through method handles, found on
 * first use. {@code directionDegrees()} blows towards (-sin, cos) like an entity's yaw; {@code globalStrength()} is
 * unitless and its weather bonuses are known (see {@link #usualStrength}). If its API changes, the integration switches
 * itself off and the rain keeps its own wind.
 */
public final class LivingWind {
    public static final String MOD_ID = "livingwind";
    private static final String CLIENT_API = "com.macatmovie.livingwind.api.ClientWindApi";

    // Living Wind's default strength: its clear-weather base wanders between 0.04 and 0.48, rain adds 0.24, thunder 0.44,
    // all times its global multiplier 1.3.
    private static final float CLEAR = 0.34F;
    private static final float RAIN = 0.31F;
    private static final float THUNDER = 0.57F;
    private static final float MIN_SCALE = 0.3F;
    private static final float MAX_SCALE = 2.0F;

    private static final Logger LOGGER = LogUtils.getLogger();

    private static Boolean loaded;
    private static volatile boolean broken;
    private static MethodHandle hasWindState;
    private static MethodHandle strength;
    private static MethodHandle direction;

    private LivingWind() {
    }

    /** Whether Living Wind is installed. */
    public static boolean loaded() {
        if (loaded == null) {
            ModList mods = ModList.get();
            loaded = mods != null && mods.isLoaded(MOD_ID);
        }
        return loaded;
    }

    /** Whether the rain follows Living Wind now: it is installed and the integration is on. */
    public static boolean active() {
        return !broken && loaded() && ClientConfig.LIVING_WIND.get();
    }

    /**
     * Reads Living Wind's wind on the client into {@code out}: the heading it blows towards as a unit vector
     * ({@code out[0]} x, {@code out[1]} z) and how hard it blows against its usual wind in this weather ({@code out[2]},
     * 1 = usual). Returns false when it has no wind to give (not active, or the server does not run it).
     *
     * @param rain    the world's rain level, 0..1
     * @param thunder the world's thunder level, 0..1
     */
    public static boolean read(float rain, float thunder, float[] out) {
        if (!active() || !findApi()) {
            return false;
        }
        try {
            if (!(boolean) hasWindState.invokeExact()) {
                return false;
            }
            float now = (float) strength.invokeExact();
            float degrees = (float) direction.invokeExact();
            if (!Float.isFinite(now) || !Float.isFinite(degrees)) {
                return false;
            }
            float radians = degrees * Mth.DEG_TO_RAD;
            out[0] = -Mth.sin(radians);
            out[1] = Mth.cos(radians);
            out[2] = Mth.clamp(now / usualStrength(rain, thunder), MIN_SCALE, MAX_SCALE);
            return true;
        } catch (Throwable e) {
            disable("reading its wind failed", e);
            return false;
        }
    }

    /** Living Wind's strength on an average day in this weather (with its default settings). */
    static float usualStrength(float rain, float thunder) {
        return CLEAR + RAIN * rain + THUNDER * thunder;
    }

    private static boolean findApi() {
        if (direction != null) {
            return true;
        }
        try {
            Class<?> api = Class.forName(CLIENT_API);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            hasWindState = lookup.findStatic(api, "hasWindState", MethodType.methodType(boolean.class));
            strength = lookup.findStatic(api, "globalStrength", MethodType.methodType(float.class));
            direction = lookup.findStatic(api, "directionDegrees", MethodType.methodType(float.class));
            LOGGER.info("The rain blows with the wind of Living Wind");
            return true;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            disable("its wind could not be found", e);
            return false;
        }
    }

    private static void disable(String reason, Throwable e) {
        broken = true;
        LOGGER.warn("Living Wind is installed, but {}; the rain keeps its own wind", reason, e);
    }
}
