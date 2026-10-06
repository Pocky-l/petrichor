package com.pockyl.petrichor.gametest;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.network.WeatherSyncPayload;
import com.pockyl.petrichor.weather.RainSchedule;
import com.pockyl.petrichor.weather.RainType;
import com.pockyl.petrichor.weather.StormData;
import com.pockyl.petrichor.weather.Wetness;
import com.pockyl.petrichor.world.DropPath;
import com.pockyl.petrichor.world.PuddleField;
import com.pockyl.petrichor.world.RunoffSolver;
import com.pockyl.petrichor.world.SoundMaterial;
import com.pockyl.petrichor.world.StrikeTargeting;
import com.pockyl.petrichor.world.SurfaceGrid;
import com.pockyl.petrichor.world.SurfaceKind;

import java.util.List;

/**
 * In-game tests, run headless by {@code gradlew runGameTestServer}.
 * Tests use the 1x1x1 {@code empty} structure unless they need a prepared one.
 */
@GameTestHolder(Petrichor.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ModGameTests {
    private ModGameTests() {
    }

    @GameTest(template = "empty")
    public static void modLoads(GameTestHelper helper) {
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scheduleIsDeterministic(GameTestHelper helper) {
        for (long t = 0; t < 200_000; t += 1234) {
            RainType a = RainSchedule.naturalType(t, false, 30, 45, 25);
            RainType b = RainSchedule.naturalType(t, false, 30, 45, 25);
            helper.assertTrue(a == b, "Same time must give the same rain type");
            helper.assertTrue(a != RainType.THUNDERSTORM, "Thunderstorms only come with thunder");
        }
        helper.assertTrue(RainSchedule.naturalType(5000, true, 30, 45, 25) == RainType.THUNDERSTORM, "Thunder forces a thunderstorm");
        // The type holds for a whole window.
        long start = RainSchedule.WINDOW * 7L;
        RainType first = RainSchedule.naturalType(start, false, 30, 45, 25);
        helper.assertTrue(RainSchedule.naturalType(start + RainSchedule.WINDOW - 1, false, 30, 45, 25) == first,
                "Type changes only between windows");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void scheduleFollowsWeights(GameTestHelper helper) {
        int[] counts = new int[RainType.values().length];
        int windows = 20_000;
        for (int w = 0; w < windows; w++) {
            counts[RainSchedule.naturalType((long) w * RainSchedule.WINDOW, false, 30, 45, 25).ordinal()]++;
        }
        assertShare(helper, counts[RainType.DRIZZLE.ordinal()], windows, 0.30, "drizzle");
        assertShare(helper, counts[RainType.RAIN.ordinal()], windows, 0.45, "rain");
        assertShare(helper, counts[RainType.DOWNPOUR.ordinal()], windows, 0.25, "downpour");
        int[] onlyRain = new int[RainType.values().length];
        for (int w = 0; w < 500; w++) {
            onlyRain[RainSchedule.naturalType((long) w * RainSchedule.WINDOW, false, 0, 1, 0).ordinal()]++;
        }
        helper.assertTrue(onlyRain[RainType.RAIN.ordinal()] == 500, "Zero weights are never picked");
        helper.succeed();
    }

    private static void assertShare(GameTestHelper helper, int count, int total, double expected, String name) {
        double share = (double) count / total;
        helper.assertTrue(Math.abs(share - expected) < 0.03, "Share of " + name + " was " + share + ", expected about " + expected);
    }

    @GameTest(template = "empty")
    public static void gustsAndWindStayInRange(GameTestHelper helper) {
        for (int t = 0; t < 100_000; t += 97) {
            float gust = RainSchedule.gust(t, 0.9F);
            helper.assertTrue(gust >= 0.25F && gust <= 2.0F, "Gust out of range: " + gust);
            float wind = RainSchedule.windSpeed(t, 0.32F, 0.9F);
            helper.assertTrue(wind >= 0.0F && wind <= 0.32F * 2.6F, "Wind out of range: " + wind);
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wetnessSoaksAndDries(GameTestHelper helper) {
        float wet = 0.0F;
        for (int t = 0; t < 20 * 60; t++) {
            wet = Wetness.step(wet, 1.0F, RainType.DOWNPOUR, true, 1.0, 1.0);
        }
        helper.assertTrue(wet > 0.6F, "A minute of downpour soaks the ground, got " + wet);
        float drizzle = 0.0F;
        for (int t = 0; t < 20 * 600; t++) {
            drizzle = Wetness.step(drizzle, 1.0F, RainType.DRIZZLE, true, 1.0, 1.0);
        }
        helper.assertTrue(drizzle <= RainType.DRIZZLE.wetnessCap + 1.0E-4F, "Drizzle stays below its cap, got " + drizzle);
        float dry = 1.0F;
        for (int t = 0; t < 20 * 60; t++) {
            dry = Wetness.step(dry, 0.0F, null, true, 1.0, 1.0);
        }
        helper.assertTrue(dry < 1.0F && dry > 0.6F, "The ground dries slowly, got " + dry);
        helper.assertTrue(Wetness.runoff(0.2F, 1.0F) == 0.0F, "No runoff on dry ground");
        helper.assertTrue(Wetness.runoff(1.0F, 1.0F) > 0.9F, "Full runoff on soaked ground in heavy rain");
        helper.succeed();
    }

    /** A square of flat soil whose surface is at {@code height}. */
    private static SurfaceGrid flatGrid(int size, int height) {
        SurfaceGrid grid = new SurfaceGrid(1000, 1000, size);
        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                grid.set(x, z, height, height, SurfaceKind.GROUND, true);
            }
        }
        return grid;
    }

    @GameTest(template = "empty")
    public static void puddlesFormInHollows(GameTestHelper helper) {
        SurfaceGrid grid = flatGrid(9, 64);
        grid.set(4, 4, 63, 63.0F, SurfaceKind.GROUND, true);
        // A one-block step down on the east edge.
        for (int z = 0; z < 9; z++) {
            grid.set(8, z, 63, 63.0F, SurfaceKind.GROUND, true);
        }
        RunoffSolver runoff = RunoffSolver.solve(grid);
        float[] field = PuddleField.compute(grid, runoff);
        float hollow = field[grid.index(4, 4)];
        float edge = field[grid.index(7, 4)];
        SurfaceGrid plain = flatGrid(9, 64);
        float plainEdge = PuddleField.compute(plain, RunoffSolver.solve(plain))[plain.index(7, 4)];
        helper.assertTrue(runoff.closed[grid.index(4, 4)], "A pit with no way out is a closed hollow");
        helper.assertTrue(hollow > 0.75F, "The hollow fills first, field " + hollow);
        helper.assertTrue(edge < plainEdge, "Ground at a drop stays drier, " + edge + " vs " + plainEdge);
        helper.assertTrue(PuddleField.cover(hollow, 0.8F, 1.0F) > 0.9F, "Wet ground puts the hollow under water");
        helper.assertTrue(PuddleField.cover(hollow, 0.0F, 1.0F) == 0.0F, "Dry ground has no puddles");
        // Coverage grows monotonically with wetness.
        float last = -1.0F;
        for (float w = 0.0F; w <= 1.0F; w += 0.1F) {
            float covered = 0.0F;
            for (float f : field) {
                covered += PuddleField.cover(f, w, 1.0F);
            }
            helper.assertTrue(covered >= last, "Puddles never shrink while the ground gets wetter");
            last = covered;
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void wideHollowFills(GameTestHelper helper) {
        SurfaceGrid grid = flatGrid(13, 64);
        for (int z = 4; z <= 8; z++) {
            for (int x = 4; x <= 8; x++) {
                grid.set(x, z, 63, 63.0F, SurfaceKind.GROUND, true);
            }
        }
        RunoffSolver runoff = RunoffSolver.solve(grid);
        float[] field = PuddleField.compute(grid, runoff);
        for (int z = 4; z <= 8; z++) {
            for (int x = 4; x <= 8; x++) {
                int i = grid.index(x, z);
                helper.assertTrue(runoff.closed[i], "Pit cell " + x + "," + z + " is closed");
                helper.assertTrue(field[i] > 0.5F, "Pit cell " + x + "," + z + " field " + field[i]);
            }
        }
        // A deeper spot inside the pit must not drain the rest of it: the pit still fills to its brim.
        grid.set(6, 6, 62, 62.0F, SurfaceKind.GROUND, true);
        runoff = RunoffSolver.solve(grid);
        field = PuddleField.compute(grid, runoff);
        helper.assertTrue(runoff.depth[grid.index(4, 4)] == 1, "Pit corner lies a block under the brim");
        helper.assertTrue(runoff.depth[grid.index(6, 6)] == 2, "The deeper spot two blocks");
        helper.assertTrue(runoff.depth[grid.index(1, 1)] == 0, "Open ground is not under water");
        helper.assertTrue(field[grid.index(5, 4)] > 0.5F, "The pit around the deeper spot still fills, " + field[grid.index(5, 4)]);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void runoffFlowsDownhill(GameTestHelper helper) {
        // A staircase falling to the east: columns x=0..2 at 70, 3..5 at 69, 6..8 at 68.
        SurfaceGrid grid = new SurfaceGrid(0, 0, 9);
        for (int z = 0; z < 9; z++) {
            for (int x = 0; x < 9; x++) {
                int h = 70 - x / 3;
                grid.set(x, z, h, h, SurfaceKind.GROUND, false);
            }
        }
        RunoffSolver runoff = RunoffSolver.solve(grid);
        int top = grid.index(0, 4);
        int step = grid.index(2, 4);
        int foot = grid.index(5, 4);
        helper.assertTrue(runoff.downstream[step] == grid.index(3, 4) && runoff.drop[step] == 1, "Water steps down to the east");
        helper.assertTrue(runoff.distanceToEdge[top] == 2, "Flat cells drain towards the nearest step");
        helper.assertTrue(runoff.accumulation[foot] > runoff.accumulation[top], "Flow accumulates downstream");
        helper.assertTrue(runoff.accumulation[grid.index(2, 4)] >= 3, "The step edge collects the terrace behind it");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void roofEdgesPourWater(GameTestHelper helper) {
        // A 3x3 flat roof at 75 over ground at 64.
        SurfaceGrid grid = flatGrid(9, 64);
        for (int z = 3; z <= 5; z++) {
            for (int x = 3; x <= 5; x++) {
                grid.set(x, z, 75, 75.0F, SurfaceKind.OTHER, false);
            }
        }
        RunoffSolver runoff = RunoffSolver.solve(grid);
        int corner = grid.index(3, 3);
        int middle = grid.index(4, 4);
        helper.assertTrue(runoff.drop[corner] >= 2, "Roof rim cells pour over the edge");
        helper.assertTrue(runoff.downstream[middle] >= 0 && runoff.drop[middle] == 0, "The roof middle drains to the rim");
        int roofTotal = 0;
        for (int z = 3; z <= 5; z++) {
            for (int x = 3; x <= 5; x++) {
                int i = grid.index(x, z);
                if (runoff.drop[i] >= 2) {
                    roofTotal += runoff.accumulation[i];
                }
            }
        }
        helper.assertTrue(roofTotal == 9, "All rain on the roof leaves over its edges, got " + roofTotal);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void dropsMeetWallsAndTops(GameTestHelper helper) {
        // Flat ground at y 0 with a wall in the column x = 5 rising to y 10.
        DropPath.Tops tops = (x, z) -> x == 5 ? 10.0F : 0.0F;
        DropPath.Hit side = DropPath.hit(tops, 3.0, 8.0, 0.5, 5.25, 3.5, 0.5);
        helper.assertTrue(side != null && side.face() == Direction.WEST, "A slanted drop hits the side of the wall");
        helper.assertTrue(Math.abs(side.x() - 5.0) < 1.0E-6 && Math.abs(side.y() - 4.0) < 0.01, "It hits where its path crosses the face");
        DropPath.Hit ground = DropPath.hit(tops, 1.5, 3.0, 0.5, 2.0, -1.0, 0.5);
        helper.assertTrue(ground != null && ground.face() == Direction.UP && Math.abs(ground.y()) < 1.0E-6, "A drop lands on the ground");
        DropPath.Hit top = DropPath.hit(tops, 4.6, 11.3, 0.5, 5.4, 9.7, 0.5);
        helper.assertTrue(top != null && top.face() == Direction.UP && Math.abs(top.y() - 10.0) < 1.0E-6,
                "Crossing above the wall's top, the drop lands on it");
        helper.assertTrue(DropPath.hit(tops, 1.5, 5.0, 0.5, 1.6, 4.0, 0.5) == null, "A free path meets nothing");
        helper.assertTrue(DropPath.blocked(tops, 6.5, 5.0, 0.5, 0.5F, 0.0F, 40.0), "No drop comes out on the far side of a wall");
        helper.assertTrue(!DropPath.blocked(tops, 2.5, 5.0, 0.5, 0.5F, 0.0F, 40.0), "In front of the wall the rain falls freely");
        helper.assertTrue(DropPath.blocked(tops, 5.5, 9.0, 0.5, 0.0F, 0.0F, 40.0), "Inside the wall a drop is gone");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void surfaceKinds(GameTestHelper helper) {
        helper.assertTrue(SurfaceKind.classify(Blocks.GRASS_BLOCK.defaultBlockState()).kind() == SurfaceKind.GROUND, "Grass holds puddles");
        helper.assertTrue(SurfaceKind.classify(Blocks.GRASS_BLOCK.defaultBlockState()).soil(), "Grass is soil");
        helper.assertTrue(SurfaceKind.classify(Blocks.STONE_BRICKS.defaultBlockState()).kind() == SurfaceKind.GROUND, "Stone holds puddles");
        helper.assertTrue(SurfaceKind.classify(Blocks.SAND.defaultBlockState()).kind() == SurfaceKind.OTHER, "Sand soaks rain up");
        helper.assertTrue(SurfaceKind.classify(Blocks.OAK_STAIRS.defaultBlockState()).kind() == SurfaceKind.OTHER, "Stairs are not flat");
        helper.assertTrue(SurfaceKind.classify(Blocks.OAK_LEAVES.defaultBlockState()).kind() == SurfaceKind.LEAVES, "Leaves");
        helper.assertTrue(SurfaceKind.classify(Blocks.WATER.defaultBlockState()).kind() == SurfaceKind.WATER, "Water");
        helper.assertTrue(SurfaceKind.classify(Blocks.MAGMA_BLOCK.defaultBlockState()).kind() == SurfaceKind.HOT, "Magma sizzles");
        SurfaceKind.Shape slab = SurfaceKind.classify(Blocks.STONE_SLAB.defaultBlockState());
        helper.assertTrue(slab.kind() == SurfaceKind.GROUND && Math.abs(slab.top() - 0.5F) < 1.0E-4F, "A bottom slab is flat at half height");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void sampledGridSeesBlocks(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(BlockPos.ZERO);
        helper.setBlock(BlockPos.ZERO, Blocks.STONE);
        ServerLevel level = helper.getLevel();
        SurfaceGrid grid = SurfaceGrid.sample(level, base.getX(), base.getZ(), 1);
        helper.assertTrue(grid.known(0), "Loaded column is known");
        helper.assertTrue(grid.height[0] > base.getY(), "Height is above the placed block");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void strikesSeekTallestPoint(GameTestHelper helper) {
        for (int y = 0; y < 6; y++) {
            helper.setBlock(new BlockPos(0, y, 0), Blocks.OAK_LOG);
        }
        BlockPos pillar = helper.absolutePos(new BlockPos(0, 0, 0));
        BlockPos beside = pillar.offset(3, 0, 3);
        BlockPos target = StrikeTargeting.tallest(helper.getLevel(), List.of(beside, pillar));
        helper.assertTrue(target.getX() == pillar.getX() && target.getZ() == pillar.getZ(), "The pillar is struck");
        helper.assertTrue(target.getY() == pillar.getY() + 6, "On top of the pillar, got " + target);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void commandForcesRainType(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var source = server.createCommandSourceStack().withLevel(level).withPermission(4).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, "petrichor weather downpour 60");
        StormData data = StormData.get(level);
        helper.assertTrue(level.getLevelData().isRaining(), "The command starts the rain");
        helper.assertTrue(data.currentType(level) == RainType.DOWNPOUR, "The forced type is used");
        server.getCommands().performPrefixedCommand(source, "petrichor wetness 0.7");
        helper.assertTrue(Math.abs(data.wetness() - 0.7F) < 1.0E-4F, "Wetness is set");
        server.getCommands().performPrefixedCommand(source, "petrichor weather clear");
        helper.assertTrue(!level.getLevelData().isRaining(), "Clear stops the rain");
        helper.assertTrue(data.currentType(level) == null, "No rain type without rain");
        data.setWetness(0.0F);
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rainSoundsMatchMaterials(GameTestHelper helper) {
        helper.assertTrue(SoundMaterial.of(Blocks.STONE_BRICKS.defaultBlockState()) == SoundMaterial.HARD, "Stone bricks are hard");
        helper.assertTrue(SoundMaterial.of(Blocks.COBBLESTONE.defaultBlockState()) == SoundMaterial.HARD, "Cobblestone is hard");
        helper.assertTrue(SoundMaterial.of(Blocks.GRASS_BLOCK.defaultBlockState()) == SoundMaterial.SOFT, "Grass is soft");
        helper.assertTrue(SoundMaterial.of(Blocks.SAND.defaultBlockState()) == SoundMaterial.SOFT, "Sand is soft");
        helper.assertTrue(SoundMaterial.of(Blocks.SPRUCE_PLANKS.defaultBlockState()) == SoundMaterial.WOOD, "Planks are wood");
        helper.assertTrue(SoundMaterial.of(Blocks.OAK_STAIRS.defaultBlockState()) == SoundMaterial.WOOD, "Wooden stairs are wood");
        helper.assertTrue(SoundMaterial.of(Blocks.IRON_BLOCK.defaultBlockState()) == SoundMaterial.METAL, "Iron is metal");
        helper.assertTrue(SoundMaterial.of(Blocks.CUT_COPPER_SLAB.defaultBlockState()) == SoundMaterial.METAL, "Copper is metal");
        helper.assertTrue(SoundMaterial.of(Blocks.GLASS.defaultBlockState()) == SoundMaterial.GLASS, "Glass is glass");
        helper.assertTrue(SoundMaterial.of(Blocks.LIGHT_BLUE_STAINED_GLASS_PANE.defaultBlockState()) == SoundMaterial.GLASS, "Panes are glass");
        helper.assertTrue(SoundMaterial.of(Blocks.WHITE_WOOL.defaultBlockState()) == SoundMaterial.FABRIC, "Wool is fabric");
        helper.assertTrue(SoundMaterial.of(Blocks.RED_CARPET.defaultBlockState()) == SoundMaterial.FABRIC, "Carpet is fabric");
        helper.assertTrue(SoundMaterial.of(Blocks.OAK_LEAVES.defaultBlockState()) == SoundMaterial.LEAVES, "Leaves are leaves");
        helper.assertTrue(SoundMaterial.of(Blocks.WATER.defaultBlockState()) == SoundMaterial.WATER, "Water is water");
        helper.assertTrue(SoundMaterial.of(Blocks.SNOW_BLOCK.defaultBlockState()) == SoundMaterial.SILENT, "Snow swallows rain");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void rainTypesGrowHeavier(GameTestHelper helper) {
        helper.assertTrue(RainType.DRIZZLE.heaviness < RainType.RAIN.heaviness, "A drizzle is lighter than rain");
        helper.assertTrue(RainType.RAIN.heaviness < RainType.DOWNPOUR.heaviness, "Rain is lighter than a downpour");
        helper.assertTrue(RainType.THUNDERSTORM.heaviness > RainType.RAIN.heaviness, "A thunderstorm is heavy");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void syncPayloadRoundTrip(GameTestHelper helper) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        WeatherSyncPayload payload = new WeatherSyncPayload(RainType.THUNDERSTORM.ordinal(), 0.625F);
        WeatherSyncPayload.STREAM_CODEC.encode(buffer, payload);
        WeatherSyncPayload decoded = WeatherSyncPayload.STREAM_CODEC.decode(buffer);
        helper.assertTrue(decoded.equals(payload), "Payload survives encoding");
        helper.assertTrue(RainType.byOrdinal(decoded.rainType()) == RainType.THUNDERSTORM, "Type decodes");
        helper.assertTrue(RainType.byOrdinal(-1) == null, "-1 means no rain");
        helper.succeed();
    }
}
