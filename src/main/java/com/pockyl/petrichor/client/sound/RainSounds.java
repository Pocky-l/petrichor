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
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;
import com.pockyl.petrichor.client.fx.RainFx;
import com.pockyl.petrichor.world.SurfaceKind;

import java.util.Arrays;

/**
 * The sound of rain as a soundscape placed in the world instead of a recording played into the ears.
 *
 * <ul>
 *   <li>around the listener, one source per direction sits on the nearest ground where rain actually lands - so rain
 *   is heard from the open side of a doorway, from the field to the left and not from the wall to the right;</li>
 *   <li>a source hidden behind blocks is turned down: rain outside a closed room is a faint murmur;</li>
 *   <li>tree crowns in the rain get sources of their own, up in the leaves;</li>
 *   <li>a roof over the listener drums from above, louder the closer it is;</li>
 *   <li>every ground source blends light, medium and heavy rain by the rain type.</li>
 * </ul>
 * The loops are mono CC0 field recordings (see tools/prepare_sounds.py). The sound events are not registered, only
 * listed in sounds.json, so the client works on servers without the mod.
 */
public final class RainSounds {
    public static final SoundEvent GROUND_LIGHT = event("ambient.rain.ground_light");
    public static final SoundEvent GROUND_MEDIUM = event("ambient.rain.ground_medium");
    public static final SoundEvent GROUND_HEAVY = event("ambient.rain.ground_heavy");
    public static final SoundEvent LEAVES = event("ambient.rain.leaves");
    public static final SoundEvent ROOF = event("ambient.rain.roof");
    public static final SoundEvent PUDDLE_STEP = event("step.puddle");

    private static final int SECTORS = 6;
    private static final int[] DISTANCES = {2, 4, 6, 9, 12, 16};
    private static final int LEAF_SOURCES = 3;
    private static final int LEAF_RANGE = 10;
    private static final float GROUND_GAIN = 0.3F;
    private static final float LEAF_GAIN = 0.32F;
    private static final float ROOF_GAIN = 0.5F;
    /** A source hidden behind blocks keeps this much of its volume. */
    private static final float OCCLUDED = 0.28F;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final Spot[] GROUND = new Spot[SECTORS];
    private static final Spot[] CANOPY = new Spot[LEAF_SOURCES];
    private static final Spot OVERHEAD = new Spot();
    private static final LoopSound[][] GROUND_LOOPS = new LoopSound[SECTORS][3];
    private static final LoopSound[] LEAF_LOOPS = new LoopSound[LEAF_SOURCES];
    private static LoopSound roofLoop;
    private static int ticks;

    static {
        for (int i = 0; i < SECTORS; i++) {
            GROUND[i] = new Spot();
        }
        for (int i = 0; i < LEAF_SOURCES; i++) {
            CANOPY[i] = new Spot();
        }
    }

    /** Where a source should be and how much rain it stands for (0..1, with occlusion applied). */
    private static final class Spot {
        double x;
        double y;
        double z;
        float amount;
    }

    private RainSounds() {
    }

    private static SoundEvent event(String path) {
        return SoundEvent.createVariableRangeEvent(Petrichor.id(path));
    }

    public static void tick(ClientLevel level, Columns columns, Vec3 eye) {
        if (ticks++ % 4 == 0) {
            measureGround(level, columns, eye);
            measureCanopy(level, columns, eye);
            measureRoof(level, columns, eye);
        }
        float loudness = Math.min(1.0F, ClientWeather.intensity()) * (float) (double) ClientConfig.RAIN_VOLUME.get();
        for (int s = 0; s < SECTORS; s++) {
            Spot spot = GROUND[s];
            float base = spot.amount * loudness * GROUND_GAIN;
            LoopSound[] loops = GROUND_LOOPS[s];
            loops[0] = drive(loops[0], GROUND_LIGHT, base * ClientWeather.soundLight, spot);
            loops[1] = drive(loops[1], GROUND_MEDIUM, base * ClientWeather.soundMedium, spot);
            loops[2] = drive(loops[2], GROUND_HEAVY, base * ClientWeather.soundHeavy, spot);
        }
        for (int c = 0; c < LEAF_SOURCES; c++) {
            LEAF_LOOPS[c] = drive(LEAF_LOOPS[c], LEAVES, CANOPY[c].amount * loudness * LEAF_GAIN, CANOPY[c]);
        }
        float roofVolume = ClientConfig.ROOF.get() ? OVERHEAD.amount * loudness * ROOF_GAIN * (0.6F + 0.2F * ClientWeather.density) : 0.0F;
        roofLoop = drive(roofLoop, ROOF, roofVolume, OVERHEAD);
    }

    /** For every direction, the nearest rain-hit ground at about the listener's level, and how much of it there is. */
    private static void measureGround(ClientLevel level, Columns columns, Vec3 eye) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int s = 0; s < SECTORS; s++) {
            float angle = (s + 0.5F) * Mth.TWO_PI / SECTORS;
            float dx = Mth.cos(angle);
            float dz = Mth.sin(angle);
            float weight = 0.0F;
            float total = 0.0F;
            boolean placed = false;
            Spot spot = GROUND[s];
            for (int d : DISTANCES) {
                float w = 1.0F / (1.0F + d / 6.0F);
                total += w;
                int x = Mth.floor(eye.x + dx * d);
                int z = Mth.floor(eye.z + dz * d);
                if (columns.precipitation(x, z) != Columns.RAIN) {
                    continue;
                }
                int h = columns.height(x, z);
                if (h > eye.y + 4.0 || h < eye.y - 12.0) {
                    continue;
                }
                pos.set(x, h - 1, z);
                if (SurfaceKind.classify(level.getBlockState(pos)).kind() == SurfaceKind.LEAVES) {
                    continue;
                }
                weight += w;
                if (!placed) {
                    placed = true;
                    spot.x = x + 0.5;
                    spot.y = h + 0.3;
                    spot.z = z + 0.5;
                }
            }
            if (!placed) {
                spot.amount = 0.0F;
                continue;
            }
            float amount = weight / total;
            spot.amount = amount * (visible(level, eye, spot.x, spot.y + 0.4, spot.z) ? 1.0F : OCCLUDED);
        }
    }

    /** Up to three rain-hit tree crowns nearby, the nearest one in each third of the circle. */
    private static void measureCanopy(ClientLevel level, Columns columns, Vec3 eye) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        double[] best = new double[LEAF_SOURCES];
        int[] count = new int[LEAF_SOURCES];
        Arrays.fill(best, Double.MAX_VALUE);
        int ex = Mth.floor(eye.x);
        int ez = Mth.floor(eye.z);
        for (int oz = -LEAF_RANGE; oz <= LEAF_RANGE; oz += 2) {
            for (int ox = -LEAF_RANGE; ox <= LEAF_RANGE; ox += 2) {
                int x = ex + ox;
                int z = ez + oz;
                if (columns.precipitation(x, z) != Columns.RAIN) {
                    continue;
                }
                int h = columns.height(x, z);
                if (h < eye.y - 6.0 || h > eye.y + 24.0) {
                    continue;
                }
                pos.set(x, h - 1, z);
                if (SurfaceKind.classify(level.getBlockState(pos)).kind() != SurfaceKind.LEAVES) {
                    continue;
                }
                double angle = Math.atan2(oz, ox) + Math.PI;
                int sector = Math.min(LEAF_SOURCES - 1, (int) (angle / (Math.PI * 2.0) * LEAF_SOURCES));
                count[sector]++;
                double dist = ox * ox + oz * oz + (h - eye.y) * (h - eye.y) * 0.5;
                if (dist < best[sector]) {
                    best[sector] = dist;
                    CANOPY[sector].x = x + 0.5;
                    CANOPY[sector].y = h - 0.5;
                    CANOPY[sector].z = z + 0.5;
                }
            }
        }
        for (int c = 0; c < LEAF_SOURCES; c++) {
            Spot spot = CANOPY[c];
            if (count[c] == 0) {
                spot.amount = 0.0F;
                continue;
            }
            float amount = Math.min(1.0F, count[c] / 10.0F);
            spot.amount = amount * (visible(level, eye, spot.x, spot.y, spot.z) ? 1.0F : OCCLUDED);
        }
    }

    /** A roof (not a tree) close above the listener drums on it from above. */
    private static void measureRoof(ClientLevel level, Columns columns, Vec3 eye) {
        int ex = Mth.floor(eye.x);
        int ez = Mth.floor(eye.z);
        int h = columns.height(ex, ez);
        double distance = h - eye.y;
        OVERHEAD.x = eye.x;
        OVERHEAD.y = h - 0.5;
        OVERHEAD.z = eye.z;
        if (columns.precipitation(ex, ez) != Columns.RAIN || distance < 0.5 || distance > 16.0) {
            OVERHEAD.amount = 0.0F;
            return;
        }
        BlockPos top = new BlockPos(ex, h - 1, ez);
        if (SurfaceKind.classify(level.getBlockState(top)).kind() == SurfaceKind.LEAVES) {
            OVERHEAD.amount = 0.0F;
            return;
        }
        OVERHEAD.amount = Math.clamp(1.25F - (float) distance / 10.0F, 0.2F, 1.0F);
    }

    /** Whether the line from the ear to the spot is free of blocks (the spot's own block does not count). */
    private static boolean visible(ClientLevel level, Vec3 eye, double x, double y, double z) {
        Vec3 target = new Vec3(x, y, z);
        BlockHitResult hit = level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE,
                CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(target) < 2.25;
    }

    private static LoopSound drive(LoopSound loop, SoundEvent event, float volume, Spot spot) {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        if (loop != null && (loop.isStopped() || !sounds.isActive(loop))) {
            loop = null;
        }
        if (loop == null) {
            if (volume < 0.004F) {
                return null;
            }
            loop = new LoopSound(event, spot.x, spot.y, spot.z, RANDOM);
            sounds.play(loop);
        }
        loop.setTarget(volume, spot.x, spot.y, spot.z);
        return loop;
    }

    public static void stopAll() {
        SoundManager sounds = Minecraft.getInstance().getSoundManager();
        for (LoopSound[] loops : GROUND_LOOPS) {
            for (int i = 0; i < loops.length; i++) {
                if (loops[i] != null) {
                    sounds.stop(loops[i]);
                    loops[i] = null;
                }
            }
        }
        for (int i = 0; i < LEAF_SOURCES; i++) {
            if (LEAF_LOOPS[i] != null) {
                sounds.stop(LEAF_LOOPS[i]);
                LEAF_LOOPS[i] = null;
            }
        }
        if (roofLoop != null) {
            sounds.stop(roofLoop);
            roofLoop = null;
        }
    }

    /** A falling drip landed; plays now and then within earshot so a dripping eave is heard but not a drum roll. */
    public static void drip(double x, double y, double z, double distanceSq, byte surface) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || distanceSq > 14 * 14 || RANDOM.nextFloat() > 0.1F) {
            return;
        }
        SoundEvent sound = surface == RainFx.LAND_GROUND ? SoundEvents.POINTED_DRIPSTONE_DRIP_WATER
                : SoundEvents.POINTED_DRIPSTONE_DRIP_WATER_INTO_CAULDRON;
        level.playLocalSound(x, y, z, sound, SoundSource.WEATHER, 0.18F + RANDOM.nextFloat() * 0.15F, 0.8F + RANDOM.nextFloat() * 0.5F,
                false);
    }

    public static void puddleStep(Entity entity, float strength) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        level.playLocalSound(entity.getX(), entity.getY(), entity.getZ(), PUDDLE_STEP, entity.getSoundSource(),
                0.15F + 0.25F * Math.min(1.0F, strength), 0.9F + RANDOM.nextFloat() * 0.25F, false);
    }

    /** Ground sources that can be heard, for the debug screen. */
    public static String debugSummary() {
        StringBuilder out = new StringBuilder();
        for (Spot spot : GROUND) {
            out.append(String.format("%.1f ", spot.amount));
        }
        out.append("| leaves ");
        for (Spot spot : CANOPY) {
            out.append(String.format("%.1f ", spot.amount));
        }
        out.append(String.format("| roof %.1f", OVERHEAD.amount));
        return out.toString();
    }
}
