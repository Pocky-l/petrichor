package com.pockyl.petrichor.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.weather.MistModel;

/**
 * The low mist around the listener (see {@link MistModel}): how much the land around holds it (forest canopy, water,
 * a hollow), where its base lies (the forest floor, not the treetops) and how high it reaches. Surveyed every 10 ticks,
 * everything glides.
 */
public final class Mist {
    /** Columns surveyed around the listener: 7 x 7, 6 blocks apart. */
    private static final int GRID = 3;
    private static final int STEP = 6;
    /** The ring that tells a hollow from a hilltop. */
    private static final int RING = 40;
    /** How far down through crowns and trunks the forest floor is searched for. */
    private static final int MAX_SCAN = 40;

    private static float density;
    private static float baseY = Float.NaN;
    private static float height = 5.0F;
    private static float cover;
    private static float water;
    private static float hollow;
    private static float canopy;
    private static boolean outdoors;

    private Mist() {
    }

    public static void reset() {
        density = 0.0F;
        baseY = Float.NaN;
        cover = water = hollow = canopy = 0.0F;
        outdoors = false;
    }

    public static void tick(ClientLevel level, Vec3 cam, long ticks) {
        if (ticks % 10 == 0 || Float.isNaN(baseY)) {
            survey(level, cam);
        }
        outdoors = openThroughCrowns(level, cam);
        float place = Math.max(cover, Math.max(0.7F * water, hollow));
        float target = MistModel.density(ClientWeather.rain(), ClientWeather.level(), ClientWeather.wetness() * ClientWeather.rainyLand(),
                ClientWeather.sunHeight(level, 1.0F), place, (float) (double) ClientConfig.MIST.get());
        density += (target - density) * 0.03F;
        height += (MistModel.height(cover, canopy) - height) * 0.05F;
    }

    /** Mist per block at its base. */
    public static float density() {
        return density;
    }

    /** Height of the mist's base: the ground around the listener, under the trees. */
    public static float baseY() {
        return baseY;
    }

    /** Scale height of the mist above its base, in blocks. */
    public static float height() {
        return height;
    }

    /** Whether only leaves and trunks (or nothing) are between the listener and the sky: under trees is still outdoors. */
    public static boolean outdoors() {
        return outdoors;
    }

    public static String describe() {
        return String.format("Mist %.4f/block (base %.0f, height %.1f), forest %.2f, water %.2f, hollow %.2f%s", density, baseY, height,
                cover, water, hollow, outdoors ? ", outdoors" : "");
    }

    private static void survey(ClientLevel level, Vec3 cam) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cx = Mth.floor(cam.x);
        int cz = Mth.floor(cam.z);
        int columns = 0;
        int covered = 0;
        int wet = 0;
        float groundSum = 0.0F;
        float canopySum = 0.0F;
        for (int i = -GRID; i <= GRID; i++) {
            for (int j = -GRID; j <= GRID; j++) {
                int x = cx + i * STEP;
                int z = cz + j * STEP;
                if (!level.hasChunkAt(pos.set(x, 0, z))) {
                    continue;
                }
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
                int ground = ground(level, pos, x, z, top);
                columns++;
                groundSum += ground;
                if (top - ground >= 3 && level.getBlockState(pos.set(x, top - 1, z)).is(BlockTags.LEAVES)) {
                    covered++;
                    canopySum += top - ground;
                }
                if (level.getFluidState(pos.set(x, top - 1, z)).is(FluidTags.WATER)) {
                    wet++;
                }
            }
        }
        if (columns == 0) {
            return;
        }
        float local = groundSum / columns;
        float ringSum = 0.0F;
        int ringColumns = 0;
        for (int k = 0; k < 8; k++) {
            double angle = k * Math.PI / 4.0;
            int x = cx + Mth.floor(Math.cos(angle) * RING);
            int z = cz + Mth.floor(Math.sin(angle) * RING);
            if (level.hasChunkAt(pos.set(x, 0, z))) {
                ringSum += ground(level, pos, x, z, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
                ringColumns++;
            }
        }
        cover = covered / (float) columns;
        water = wet / (float) columns;
        canopy = covered > 0 ? canopySum / covered : 0.0F;
        // Land around that rises above this spot: a hollow or a valley, where the cold damp air settles.
        hollow = ringColumns > 0 ? Math.clamp((ringSum / ringColumns - local - 2.0F) / 10.0F, 0.0F, 1.0F) : 0.0F;
        baseY = Float.isNaN(baseY) ? local : baseY + (local - baseY) * 0.3F;
    }

    /** The forest floor under a column: down from its top through crowns, trunks and air. */
    private static int ground(ClientLevel level, BlockPos.MutableBlockPos pos, int x, int z, int top) {
        int y = top - 1;
        int bottom = Math.max(level.getMinBuildHeight(), top - MAX_SCAN);
        while (y > bottom && passable(level.getBlockState(pos.set(x, y, z)))) {
            y--;
        }
        return y + 1;
    }

    private static boolean passable(BlockState state) {
        return state.is(BlockTags.LEAVES) || state.is(BlockTags.LOGS) || state.isAir() || !state.blocksMotion() && state.getFluidState().isEmpty();
    }

    private static boolean openThroughCrowns(ClientLevel level, Vec3 cam) {
        int x = Mth.floor(cam.x);
        int z = Mth.floor(cam.z);
        int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int y = Mth.floor(cam.y) + 1;
        if (y >= top) {
            return true;
        }
        if (top - y > MAX_SCAN) {
            return false;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (; y < top; y++) {
            BlockState state = level.getBlockState(pos.set(x, y, z));
            if (!state.is(BlockTags.LEAVES) && !state.is(BlockTags.LOGS) && state.blocksMotion()) {
                return false;
            }
        }
        return true;
    }
}
