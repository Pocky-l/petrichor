package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.fx.RainFx;
import com.pockyl.petrichor.weather.Wetness;
import com.pockyl.petrichor.world.PuddleField;
import com.pockyl.petrichor.world.RunoffSolver;
import com.pockyl.petrichor.world.SurfaceGrid;
import com.pockyl.petrichor.world.SurfaceKind;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Puddles, wet ground, running water and the places where water pours off edges, per chunk.
 *
 * <p>For every chunk near the camera the surface of the chunk plus a 16-block margin is sampled, the runoff solved and
 * the puddle field computed (all in {@code world}). From that a static GPU mesh is built: one quad over every flat,
 * rain-exposed top (the shader turns it into wet ground, a puddle or nothing depending on the wetness, so puddles grow
 * and dry without rebuilding; the direction and amount of runoff ride along in the vertex normals) and sheets of water
 * spilling over steps. Puddles reflect the scene by marching through a copy of the depth buffer. Meshes are rebuilt when the chunk's surface changes.
 */
public final class Puddles implements AutoCloseable {
    private static final int MARGIN = 16;
    private static final int GRID = 16 + MARGIN * 2;
    /** Edges collecting the rain of fewer cells than this do not visibly spill over. */
    private static final int MIN_FLOW = 4;
    /** Water visibly runs only this close to the next step down; on wide flats it just soaks in and pools. */
    private static final int MAX_EDGE_DISTANCE = 4;
    /** Seconds a new surface takes to soak in heavy rain (longer in light rain). */
    private static final float SOAK_SECONDS = 30.0F;
    /** Sheltered ground fainter than this is not drawn. */
    private static final float MIN_EXPOSURE = 0.02F;
    /** How much openness a sheltered block keeps from its more open neighbour. */
    private static final float SHELTER_FALLOFF = 0.66F;
    /** Blocks the wet ground reaches in under a shelter. */
    private static final int SHELTER_REACH = 6;
    /** How far below a roof or crown the sheltered ground is looked for. */
    private static final int SHELTER_DEPTH = 14;
    /** Extra puddle field under the edge water pours off. */
    private static final float DRIP_LINE_BONUS = 0.2F;
    private static final int[] CORNER_X = {0, 0, 1, 1};
    private static final int[] CORNER_Z = {0, 1, 1, 0};
    private static final int BUILDS_PER_TICK = 2;
    private static final int CHECKS_PER_TICK = 3;
    private static final float LIFT = 0.002F;

    private final Long2ObjectOpenHashMap<ChunkPuddles> chunks = new Long2ObjectOpenHashMap<>();
    private final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 18);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private ClientLevel level;
    private RenderTarget scene;
    private int idleTicks;
    private int checkCursor;
    private int lastQuads;

    /**
     * One edge water falls from: the lip of a roof or a cliff. Drops hang at ({@code x}, {@code hangY}, {@code z}) - under
     * the lip of an overhang, or at the top edge of a wall the water runs down - and land at {@code groundY}.
     */
    public record Emitter(float x, float hangY, float z, float dirX, float dirZ, float groundY, int flow, byte surface) {
    }

    private static final class ChunkPuddles {
        final int chunkX;
        final int chunkZ;
        VertexBuffer puddles;
        VertexBuffer sheets;
        int puddleQuads;
        int sheetQuads;
        AABB bounds;
        final float[] field = new float[256];
        final float[] top = new float[256];
        final boolean[] ground = new boolean[256];
        /** Surface height and openness of each column at the last build, to notice new and uncovered surfaces. */
        final float[] lastTop = new float[256];
        final float[] lastExposure = new float[256];
        /** 1 for a surface that just appeared and is still dry, falling to 0 as it soaks. */
        final float[] fresh = new float[256];
        boolean builtOnce;
        boolean soaking;
        List<Emitter> emitters = List.of();
        int signature;

        ChunkPuddles(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            Arrays.fill(lastTop, Float.NaN);
        }

        void close() {
            if (puddles != null) {
                puddles.close();
                puddles = null;
            }
            if (sheets != null) {
                sheets.close();
                sheets = null;
            }
        }
    }

    public void clear() {
        for (ChunkPuddles chunk : chunks.values()) {
            chunk.close();
        }
        chunks.clear();
    }

    @Override
    public void close() {
        clear();
        bytes.close();
        if (scene != null) {
            scene.destroyBuffers();
            scene = null;
        }
    }

    public int chunkCount() {
        return chunks.size();
    }

    public int lastQuads() {
        return lastQuads;
    }

    public int emitterCount() {
        int count = 0;
        for (ChunkPuddles chunk : chunks.values()) {
            count += chunk.emitters.size();
        }
        return count;
    }

    private static boolean enabled() {
        return ClientConfig.PUDDLES.get() || ClientConfig.RIVULETS.get() || ClientConfig.DRIPS.get();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------------------------------------------------

    public void tick(ClientLevel level, double camX, double camZ) {
        if (this.level != level) {
            clear();
            this.level = level;
        }
        boolean active = enabled() && (ClientWeather.wetness() > 0.002F || ClientWeather.rain() > 0.0F);
        if (!active) {
            if (++idleTicks > 100 && !chunks.isEmpty()) {
                clear();
            }
            return;
        }
        idleTicks = 0;
        int radius = (ClientConfig.quality().puddleRadius + 15) >> 4;
        int centerX = Mth.floor(camX) >> 4;
        int centerZ = Mth.floor(camZ) >> 4;
        chunks.long2ObjectEntrySet().removeIf(entry -> {
            ChunkPuddles chunk = entry.getValue();
            boolean far = Math.abs(chunk.chunkX - centerX) > radius + 1 || Math.abs(chunk.chunkZ - centerZ) > radius + 1
                    || !level.hasChunk(chunk.chunkX, chunk.chunkZ);
            if (far) {
                chunk.close();
            }
            return far;
        });

        int built = 0;
        // Nearest missing chunks first, ring by ring.
        for (int ring = 0; ring <= radius && built < BUILDS_PER_TICK; ring++) {
            for (int dz = -ring; dz <= ring && built < BUILDS_PER_TICK; dz++) {
                for (int dx = -ring; dx <= ring && built < BUILDS_PER_TICK; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int cx = centerX + dx;
                    int cz = centerZ + dz;
                    long key = ChunkPos.asLong(cx, cz);
                    if (!chunks.containsKey(key) && neighbourhoodLoaded(level, cx, cz)) {
                        ChunkPuddles chunk = new ChunkPuddles(cx, cz);
                        build(level, chunk);
                        chunks.put(key, chunk);
                        built++;
                    }
                }
            }
        }
        if (built == 0 && !chunks.isEmpty()) {
            checkChanged(level);
        }
        soak(level, ClientWeather.intensity());
    }

    private static boolean neighbourhoodLoaded(ClientLevel level, int cx, int cz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!level.hasChunk(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Round robin over the built chunks: rebuild those whose surface heights changed. */
    private void checkChanged(ClientLevel level) {
        Long2ObjectMap.Entry<ChunkPuddles>[] entries = chunks.long2ObjectEntrySet().toArray(new Long2ObjectMap.Entry[0]);
        for (int c = 0; c < CHECKS_PER_TICK && c < entries.length; c++) {
            checkCursor = (checkCursor + 1) % entries.length;
            ChunkPuddles chunk = entries[checkCursor].getValue();
            if (signature(level, chunk.chunkX, chunk.chunkZ) != chunk.signature) {
                build(level, chunk);
                return;
            }
        }
    }

    private static int signature(ClientLevel level, int cx, int cz) {
        int hash = 1;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                hash = hash * 31 + level.getHeight(Heightmap.Types.MOTION_BLOCKING, (cx << 4) + x, (cz << 4) + z);
            }
        }
        return hash;
    }

    private void build(ClientLevel level, ChunkPuddles chunk) {
        chunk.signature = signature(level, chunk.chunkX, chunk.chunkZ);
        int originX = chunk.chunkX << 4;
        int originZ = chunk.chunkZ << 4;
        SurfaceGrid grid = SurfaceGrid.sample(level, originX - MARGIN, originZ - MARGIN, GRID);
        RunoffSolver runoff = RunoffSolver.solve(grid);
        float[] field = PuddleField.compute(grid, runoff);
        wetDripLines(grid, runoff, field);

        boolean[] rains = new boolean[256];
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int i = grid.index(lx + MARGIN, lz + MARGIN);
                int c = lz * 16 + lx;
                chunk.ground[c] = false;
                if (!grid.known(i)) {
                    continue;
                }
                pos.set(originX + lx, grid.height[i], originZ + lz);
                Biome biome = level.getBiome(pos).value();
                rains[c] = biome.hasPrecipitation() && biome.getPrecipitationAt(pos) == Biome.Precipitation.RAIN;
                chunk.ground[c] = rains[c] && grid.kind[i] == SurfaceKind.GROUND;
                chunk.field[c] = field[i];
                chunk.top[c] = grid.top[i];
                minY = Math.min(minY, grid.top[i]);
                maxY = Math.max(maxY, grid.top[i]);
            }
        }
        if (minY > maxY) {
            chunk.close();
            chunk.emitters = List.of();
            chunk.bounds = null;
            return;
        }
        chunk.bounds = new AABB(originX, minY - 1.0, originZ, originX + 16, maxY + 1.0, originZ + 16);
        buildPuddles(level, chunk, grid, runoff, field, rains);
        buildSheets(level, chunk, grid, runoff, rains);
        chunk.emitters = findEmitters(level, chunk, grid, runoff, rains);
    }

    /** Where water pours off roofs and cliffs the ground below gets soaked: a drip line of puddles along the eaves. */
    private static void wetDripLines(SurfaceGrid grid, RunoffSolver runoff, float[] field) {
        for (int i = 0; i < field.length; i++) {
            int j = runoff.downstream[i];
            if (j < 0 || runoff.drop[i] < 2 || runoff.accumulation[i] < 3 || grid.kind[j] != SurfaceKind.GROUND) {
                continue;
            }
            SurfaceKind kind = grid.kind[i];
            if (kind == SurfaceKind.LEAVES || kind == SurfaceKind.WATER || kind == SurfaceKind.HOT) {
                continue;
            }
            field[j] = Math.min(1.0F, field[j] + DRIP_LINE_BONUS);
        }
    }

    private void buildPuddles(ClientLevel level, ChunkPuddles chunk, SurfaceGrid grid, RunoffSolver runoff, float[] field, boolean[] rains) {
        int originX = chunk.chunkX << 4;
        int originZ = chunk.chunkZ << 4;
        int n = grid.size * grid.size;
        float[] flowX = new float[n];
        float[] flowZ = new float[n];
        for (int i = 0; i < n; i++) {
            float strength = flowStrength(grid, runoff, i);
            if (strength > 0.0F) {
                int j = runoff.downstream[i];
                flowX[i] = ((j % grid.size) - (i % grid.size)) * strength;
                flowZ[i] = ((j / grid.size) - (i / grid.size)) * strength;
            }
        }
        Surfaces surfaces = surfaces(level, grid, field);
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        int quads = 0;
        float[] cornerFlow = new float[3];
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if (!rains[lz * 16 + lx]) {
                    continue;
                }
                int gx = lx + MARGIN;
                int gz = lz + MARGIN;
                int i = grid.index(gx, gz);
                float top = surfaces.top[i];
                if (Float.isNaN(top) || surfaces.exposure[i] < MIN_EXPOSURE) {
                    continue;
                }
                minY = Math.min(minY, top);
                maxY = Math.max(maxY, top);
                int c = lz * 16 + lx;
                noteChange(chunk, c, top, surfaces.exposure[i]);
                pos.set(originX + lx, Mth.floor(top) + (top % 1.0F == 0.0F ? 0 : 1), originZ + lz);
                int light = LevelRenderer.getLightColor(level, pos);
                float y = top + LIFT;
                int wx = Math.floorMod(originX + lx, 256);
                int wz = Math.floorMod(originZ + lz, 256);
                boolean open = grid.kind[i] == SurfaceKind.GROUND;
                for (int k = 0; k < 4; k++) {
                    int cornerX = gx + CORNER_X[k];
                    int cornerZ = gz + CORNER_Z[k];
                    float cornerField = surfaces.corner(surfaces.field, cornerX, cornerZ, top);
                    float cornerExposure = surfaces.corner(surfaces.exposure, cornerX, cornerZ, top);
                    if (open) {
                        cornerFlow(grid, flowX, flowZ, cornerX, cornerZ, top, cornerFlow);
                    } else {
                        cornerFlow[0] = 0.0F;
                        cornerFlow[1] = 0.0F;
                        cornerFlow[2] = 0.0F;
                    }
                    // Colour: r = how muddy (soil) the water is, g = how open the spot is to the rain, b = still dry (new).
                    builder.addVertex(lx + CORNER_X[k], y, lz + CORNER_Z[k])
                            .setColor(surfaces.soil[i] ? 1.0F : 0.0F, cornerExposure, chunk.fresh[c], cornerField)
                            .setUv(wx + CORNER_X[k], wz + CORNER_Z[k]).setLight(light)
                            .setNormal(cornerFlow[0], cornerFlow[1], cornerFlow[2]);
                }
                quads++;
            }
        }
        if (minY <= maxY && chunk.bounds != null) {
            chunk.bounds = chunk.bounds.minmax(new AABB(originX, minY - 1.0, originZ, originX + 16, maxY + 1.0, originZ + 16));
        }
        chunk.builtOnce = true;
        chunk.puddleQuads = quads;
        chunk.puddles = upload(chunk.puddles, builder.build());
    }

    /**
     * A surface that was not there at the last build (a block placed, a roof broken) starts dry and soaks over the next
     * half minute instead of being wet at once.
     */
    private static void noteChange(ChunkPuddles chunk, int c, float top, float exposure) {
        if (chunk.builtOnce) {
            float last = chunk.lastTop[c];
            if (Float.isNaN(last) || Math.abs(last - top) > 0.01F) {
                chunk.fresh[c] = 1.0F;
            } else if (exposure > chunk.lastExposure[c] + 0.2F) {
                chunk.fresh[c] = Math.max(chunk.fresh[c], exposure - chunk.lastExposure[c]);
            }
            if (chunk.fresh[c] > 0.0F) {
                chunk.soaking = true;
            }
        }
        chunk.lastTop[c] = top;
        chunk.lastExposure[c] = exposure;
    }

    /** New surfaces soak up; their meshes are rebuilt once a second while they do. */
    private void soak(ClientLevel level, float intensity) {
        float rate = intensity > 0.05F ? (0.4F + intensity) / (20.0F * SOAK_SECONDS) : 0.0F;
        int rebuilt = 0;
        for (ChunkPuddles chunk : chunks.values()) {
            if (!chunk.soaking) {
                continue;
            }
            boolean any = false;
            for (int c = 0; c < 256; c++) {
                if (chunk.fresh[c] > 0.0F) {
                    chunk.fresh[c] = Math.max(0.0F, chunk.fresh[c] - rate);
                    any |= chunk.fresh[c] > 0.0F;
                }
            }
            chunk.soaking = any;
            if (rate > 0.0F && rebuilt < BUILDS_PER_TICK && (level.getGameTime() + chunk.chunkX * 7L + chunk.chunkZ * 13L) % 20 == 0) {
                build(level, chunk);
                rebuilt++;
            }
        }
    }

    /**
     * The surfaces that can get wet: open ground in the rain, and ground sheltered under a roof or a tree crown. How open
     * a sheltered spot is falls off with its distance from the open ground beside it, so the wet ground fades out a block or
     * two under the eaves instead of ending in a straight line.
     */
    private Surfaces surfaces(ClientLevel level, SurfaceGrid grid, float[] field) {
        int n = grid.size * grid.size;
        Surfaces surfaces = new Surfaces(grid.size);
        for (int i = 0; i < n; i++) {
            SurfaceKind kind = grid.kind[i];
            if (kind == SurfaceKind.GROUND) {
                surfaces.top[i] = grid.top[i];
                surfaces.soil[i] = grid.soil[i];
                surfaces.field[i] = field[i];
                surfaces.exposure[i] = 1.0F;
            } else if (kind == SurfaceKind.OTHER || kind == SurfaceKind.LEAVES) {
                shelteredFloor(level, grid, i, surfaces);
            }
        }
        // Spread openness from the open ground in under the shelter, one block per pass.
        float[] next = new float[n];
        for (int pass = 0; pass < SHELTER_REACH; pass++) {
            System.arraycopy(surfaces.exposure, 0, next, 0, n);
            for (int i = 0; i < n; i++) {
                if (Float.isNaN(surfaces.top[i]) || surfaces.exposure[i] >= 1.0F) {
                    continue;
                }
                int lx = i % grid.size;
                int lz = i / grid.size;
                for (int d = 0; d < 4; d++) {
                    int nx = lx + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int nz = lz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (nx < 0 || nz < 0 || nx >= grid.size || nz >= grid.size) {
                        continue;
                    }
                    int j = grid.index(nx, nz);
                    if (!Float.isNaN(surfaces.top[j]) && Math.abs(surfaces.top[j] - surfaces.top[i]) <= 1.1F) {
                        next[i] = Math.max(next[i], surfaces.exposure[j] * SHELTER_FALLOFF);
                    }
                }
            }
            System.arraycopy(next, 0, surfaces.exposure, 0, n);
        }
        // The fade also starts a little outside: open ground right next to a shelter is a touch less wet, so the change
        // is spread over a few blocks instead of happening at the eave.
        for (int i = 0; i < n; i++) {
            if (Float.isNaN(surfaces.top[i]) || surfaces.exposure[i] < 1.0F) {
                continue;
            }
            int lx = i % grid.size;
            int lz = i / grid.size;
            float nearest = 3.0F;
            for (int dz = -2; dz <= 2; dz++) {
                for (int dx = -2; dx <= 2; dx++) {
                    int nx = lx + dx;
                    int nz = lz + dz;
                    if (nx < 0 || nz < 0 || nx >= grid.size || nz >= grid.size) {
                        continue;
                    }
                    int j = grid.index(nx, nz);
                    if (!Float.isNaN(surfaces.top[j]) && surfaces.exposure[j] < 1.0F && Math.abs(surfaces.top[j] - surfaces.top[i]) <= 1.1F) {
                        nearest = Math.min(nearest, Math.max(Math.abs(dx), Math.abs(dz)));
                    }
                }
            }
            if (nearest < 3.0F) {
                next[i] = 0.78F + 0.07F * nearest;
            } else {
                next[i] = 1.0F;
            }
        }
        for (int i = 0; i < n; i++) {
            if (!Float.isNaN(surfaces.top[i]) && surfaces.exposure[i] >= 1.0F) {
                surfaces.exposure[i] = next[i];
            }
        }
        return surfaces;
    }

    /** The ground under the roof or crown at the top of column {@code i}, if there is some within reach. */
    private void shelteredFloor(ClientLevel level, SurfaceGrid grid, int i, Surfaces surfaces) {
        int x = grid.x0 + i % grid.size;
        int z = grid.z0 + i / grid.size;
        int top = grid.height[i] - 1;
        boolean below = false;
        for (int y = top - 1; y > top - SHELTER_DEPTH; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()) {
                below = true;
                continue;
            }
            SurfaceKind.Shape shape = SurfaceKind.classify(state);
            if (below && shape.kind() == SurfaceKind.GROUND) {
                surfaces.top[i] = y + shape.top();
                surfaces.soil[i] = shape.soil();
            }
            return;
        }
    }

    /** Wettable surfaces of a grid: height of the surface (NaN for none), soil, puddle field and openness to the rain. */
    private static final class Surfaces {
        final int size;
        final float[] top;
        final boolean[] soil;
        final float[] field;
        final float[] exposure;

        Surfaces(int size) {
            this.size = size;
            int n = size * size;
            top = new float[n];
            soil = new boolean[n];
            field = new float[n];
            exposure = new float[n];
            Arrays.fill(top, Float.NaN);
        }

        /**
         * The mean of a value over the four cells sharing a block corner; cells without a surface on the same level count
         * as zero, so values fade out towards steps and walls.
         */
        float corner(float[] values, int cornerX, int cornerZ, float level) {
            float sum = 0.0F;
            for (int dz = -1; dz <= 0; dz++) {
                for (int dx = -1; dx <= 0; dx++) {
                    int x = cornerX + dx;
                    int z = cornerZ + dz;
                    if (x < 0 || z < 0 || x >= size || z >= size) {
                        continue;
                    }
                    int j = z * size + x;
                    if (!Float.isNaN(top[j]) && Math.abs(top[j] - level) < 0.01F) {
                        sum += values[j];
                    }
                }
            }
            return sum * 0.25F;
        }
    }

    /** How much water runs over this cell, 0..1: visible runoff only near the next step down. */
    private static float flowStrength(SurfaceGrid grid, RunoffSolver runoff, int i) {
        if (grid.kind[i] != SurfaceKind.GROUND || runoff.downstream[i] < 0 || runoff.accumulation[i] < 3
                || runoff.distanceToEdge[i] > MAX_EDGE_DISTANCE) {
            return 0.0F;
        }
        float amount = Math.clamp(log2(runoff.accumulation[i]) / 5.0F, 0.0F, 1.0F);
        return amount * (1.0F - runoff.distanceToEdge[i] * 0.18F);
    }

    /** Mean flow of the cells sharing a corner on the same level: direction (x, z) and strength (y). */
    private static void cornerFlow(SurfaceGrid grid, float[] flowX, float[] flowZ, int cornerX, int cornerZ, float top, float[] out) {
        float sx = 0.0F;
        float sz = 0.0F;
        for (int dz = -1; dz <= 0; dz++) {
            for (int dx = -1; dx <= 0; dx++) {
                int x = cornerX + dx;
                int z = cornerZ + dz;
                if (x < 0 || z < 0 || x >= grid.size || z >= grid.size) {
                    continue;
                }
                int j = grid.index(x, z);
                if (grid.kind[j] == SurfaceKind.GROUND && Math.abs(grid.top[j] - top) < 0.01F) {
                    sx += flowX[j];
                    sz += flowZ[j];
                }
            }
        }
        sx *= 0.25F;
        sz *= 0.25F;
        float strength = Mth.sqrt(sx * sx + sz * sz);
        if (strength < 1.0E-4F) {
            out[0] = 0.0F;
            out[1] = 0.0F;
            out[2] = 0.0F;
            return;
        }
        out[0] = sx / strength;
        out[1] = Math.min(1.0F, strength);
        out[2] = sz / strength;
    }

    private static float log2(int value) {
        return (float) (Math.log(value) / Math.log(2.0));
    }

    /**
     * Water spilling over the lip of a one- or two-block step: a sheet across the whole width of the edge, on the face
     * of the step, from the upper surface down to the lower one.
     */
    private void buildSheets(ClientLevel level, ChunkPuddles chunk, SurfaceGrid grid, RunoffSolver runoff, boolean[] rains) {
        if (!ClientConfig.RIVULETS.get() || !ClientConfig.quality().rivulets) {
            chunk.sheetQuads = 0;
            chunk.sheets = upload(chunk.sheets, null);
            return;
        }
        int originX = chunk.chunkX << 4;
        int originZ = chunk.chunkZ << 4;
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        int quads = 0;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if (!rains[lz * 16 + lx]) {
                    continue;
                }
                int gx = lx + MARGIN;
                int gz = lz + MARGIN;
                int i = grid.index(gx, gz);
                int j = runoff.downstream[i];
                SurfaceKind kind = grid.kind[i];
                if (j < 0 || runoff.drop[i] < 1 || runoff.drop[i] > 2 || runoff.accumulation[i] < MIN_FLOW
                        || kind == SurfaceKind.LEAVES || kind == SurfaceKind.WATER || kind == SurfaceKind.HOT) {
                    continue;
                }
                int dirX = (j % grid.size) - gx;
                int dirZ = (j / grid.size) - gz;
                float topY = grid.top[i];
                float bottomY = grid.known(j) ? grid.top[j] : topY - runoff.drop[i];
                pos.set(originX + lx, grid.height[i], originZ + lz);
                int light = LevelRenderer.getLightColor(level, pos);
                float strength = Math.clamp(0.3F + 0.12F * log2(runoff.accumulation[i]), 0.3F, 1.0F);
                // The face between the two cells, pushed a hair out towards the lower one.
                float fx = lx + 0.5F + dirX * 0.506F;
                float fz = lz + 0.5F + dirZ * 0.506F;
                float ax = fx - dirZ * 0.5F;
                float az = fz + dirX * 0.5F;
                float bx = fx + dirZ * 0.5F;
                float bz = fz - dirX * 0.5F;
                // Along-edge texture coordinate in world blocks, so neighbouring sheets continue each other.
                float ua = dirX != 0 ? Math.floorMod(originZ, 64) + az : Math.floorMod(originX, 64) + ax;
                float ub = dirX != 0 ? Math.floorMod(originZ, 64) + bz : Math.floorMod(originX, 64) + bx;
                sheetVertex(builder, ax, topY, az, ua, 0.0F, strength, light, dirX, dirZ);
                sheetVertex(builder, bx, topY, bz, ub, 0.0F, strength, light, dirX, dirZ);
                sheetVertex(builder, bx, bottomY, bz, ub, 1.0F, strength, light, dirX, dirZ);
                sheetVertex(builder, ax, bottomY, az, ua, 1.0F, strength, light, dirX, dirZ);
                quads++;
            }
        }
        chunk.sheetQuads = quads;
        chunk.sheets = upload(chunk.sheets, builder.build());
    }

    private static void sheetVertex(BufferBuilder out, float x, float y, float z, float u, float v, float strength, int light, int dirX,
            int dirZ) {
        out.addVertex(x, y, z).setColor(0.5F, 0.55F, 0.6F, strength).setUv(u, v).setLight(light).setNormal(dirX, 0.0F, dirZ);
    }

    private List<Emitter> findEmitters(ClientLevel level, ChunkPuddles chunk, SurfaceGrid grid, RunoffSolver runoff, boolean[] rains) {
        List<Emitter> emitters = new ArrayList<>();
        int originX = chunk.chunkX << 4;
        int originZ = chunk.chunkZ << 4;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                if (!rains[lz * 16 + lx]) {
                    continue;
                }
                int gx = lx + MARGIN;
                int gz = lz + MARGIN;
                int i = grid.index(gx, gz);
                int j = runoff.downstream[i];
                // Canopies drip through their leaves (FxSpawner.leafDrips), not off their rims.
                if (j < 0 || runoff.drop[i] < 2 || grid.kind[i] == SurfaceKind.WATER || grid.kind[i] == SurfaceKind.HOT
                        || grid.kind[i] == SurfaceKind.LEAVES) {
                    continue;
                }
                float dirX = (j % grid.size) - gx;
                float dirZ = (j / grid.size) - gz;
                byte surface = grid.kind[j] == SurfaceKind.WATER ? RainFx.LAND_WATER : RainFx.LAND_GROUND;
                // Under an overhang water creeps round the lip and drips from its underside; off a wall it falls from the top.
                pos.set(originX + lx + (int) dirX, grid.height[i] - 2, originZ + lz + (int) dirZ);
                boolean overhang = level.getBlockState(pos).isAir();
                float out = overhang ? 0.47F : 0.53F;
                float hangY = overhang ? grid.height[i] - 1.0F : grid.top[i];
                emitters.add(new Emitter(originX + lx + 0.5F + dirX * out, hangY, originZ + lz + 0.5F + dirZ * out, dirX, dirZ,
                        grid.top[j], runoff.accumulation[i], surface));
            }
        }
        return emitters;
    }

    private static VertexBuffer upload(VertexBuffer buffer, MeshData data) {
        if (data == null) {
            if (buffer != null) {
                buffer.close();
            }
            return null;
        }
        if (buffer == null) {
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        }
        buffer.bind();
        buffer.upload(data);
        VertexBuffer.unbind();
        return buffer;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------------------------------------------------

    /**
     * How much of the block column at this spot is under a puddle, 0..1, if the surface there is at about
     * {@code y}; 0 elsewhere. Ignores the fine edge detail the shader adds.
     */
    public float coverAt(double x, double y, double z) {
        if (!ClientConfig.PUDDLES.get()) {
            return 0.0F;
        }
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        ChunkPuddles chunk = chunks.get(ChunkPos.asLong(bx >> 4, bz >> 4));
        if (chunk == null) {
            return 0.0F;
        }
        int c = (bz & 15) * 16 + (bx & 15);
        if (!chunk.ground[c] || Math.abs(chunk.top[c] - y) > 0.3) {
            return 0.0F;
        }
        return PuddleField.cover(chunk.field[c], ClientWeather.wetness(), (float) (double) ClientConfig.PUDDLE_COVERAGE.get());
    }

    /** What the puddle system knows about one column, for the debug screen. */
    public String describe(int x, int z) {
        ChunkPuddles chunk = chunks.get(ChunkPos.asLong(x >> 4, z >> 4));
        if (chunk == null) {
            return "no puddle data";
        }
        int c = (z & 15) * 16 + (x & 15);
        return String.format("ground %s, field %.2f, top %.2f, fresh %.2f, soaking %s", chunk.ground[c], chunk.field[c], chunk.top[c],
                chunk.fresh[c], chunk.soaking);
    }

    /** Emitters of the chunks within {@code radius} blocks. */
    public void emittersNear(double x, double z, int radius, List<Emitter> out) {
        int cx0 = Mth.floor(x - radius) >> 4;
        int cx1 = Mth.floor(x + radius) >> 4;
        int cz0 = Mth.floor(z - radius) >> 4;
        int cz1 = Mth.floor(z + radius) >> 4;
        for (int cz = cz0; cz <= cz1; cz++) {
            for (int cx = cx0; cx <= cx1; cx++) {
                ChunkPuddles chunk = chunks.get(ChunkPos.asLong(cx, cz));
                if (chunk != null) {
                    out.addAll(chunk.emitters);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------------------------

    public void render(Matrix4f modelView, Matrix4f projection, Vec3 cam, Frustum frustum, float partialTick) {
        lastQuads = 0;
        if (chunks.isEmpty() || level == null) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        LightTexture lightTexture = minecraft.gameRenderer.lightTexture();
        Vec3 sky = level.getSkyColor(cam, partialTick);
        float time = (float) ((level.getGameTime() % 72000L + partialTick) / 20.0);
        float wetness = ClientWeather.wetness();
        float intensity = ClientWeather.intensity();
        float flow = Wetness.runoff(wetness, intensity);
        int steps = ClientConfig.quality().reflectionSteps;

        ShaderInstance puddle = PetrichorShaders.puddle();
        boolean drawPuddles = puddle != null && ClientConfig.PUDDLES.get() && wetness > 0.002F;
        if (drawPuddles && steps > 0) {
            captureScene(minecraft.getMainRenderTarget());
        }

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0F, -10.0F);
        lightTexture.turnOnLightLayer();

        if (drawPuddles) {
            puddle.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
            if (scene != null) {
                puddle.setSampler("SceneColor", scene.getColorTextureId());
                puddle.setSampler("SceneDepth", scene.getDepthTextureId());
            }
            puddle.safeGetUniform("InvProjMat").set(new Matrix4f(projection).invert());
            puddle.safeGetUniform("SsrSteps").set(scene != null ? (float) steps : 0.0F);
            puddle.safeGetUniform("Wetness").set(wetness);
            puddle.safeGetUniform("Coverage").set((float) (double) ClientConfig.PUDDLE_COVERAGE.get());
            puddle.safeGetUniform("RainAmount").set(Math.min(1.0F, intensity * (0.2F + ClientWeather.density * 0.4F)));
            puddle.safeGetUniform("Flow").set(ClientConfig.RIVULETS.get() ? flow : 0.0F);
            puddle.safeGetUniform("PetrichorTime").set(time);
            puddle.safeGetUniform("SkyColor").set((float) sky.x, (float) sky.y, (float) sky.z);
            puddle.apply();
            for (ChunkPuddles chunk : chunks.values()) {
                lastQuads += draw(chunk, chunk.puddles, chunk.puddleQuads, puddle.CHUNK_OFFSET, cam, frustum);
            }
            puddle.clear();
        }
        ShaderInstance sheet = PetrichorShaders.sheet();
        if (sheet != null && flow > 0.01F) {
            RenderSystem.polygonOffset(-1.0F, -4.0F);
            sheet.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
            sheet.safeGetUniform("Flow").set(flow);
            sheet.safeGetUniform("PetrichorTime").set(time);
            sheet.safeGetUniform("SkyColor").set((float) sky.x, (float) sky.y, (float) sky.z);
            sheet.apply();
            for (ChunkPuddles chunk : chunks.values()) {
                lastQuads += draw(chunk, chunk.sheets, chunk.sheetQuads, sheet.CHUNK_OFFSET, cam, frustum);
            }
            sheet.clear();
        }
        VertexBuffer.unbind();

        lightTexture.turnOffLightLayer();
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** Copies colour and depth of what is drawn so far, for the reflections in the puddles. */
    private void captureScene(RenderTarget main) {
        if (scene == null) {
            scene = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            scene.setFilterMode(GL11.GL_LINEAR);
        } else if (scene.width != main.width || scene.height != main.height) {
            scene.resize(main.width, main.height, Minecraft.ON_OSX);
            scene.setFilterMode(GL11.GL_LINEAR);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, scene.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, scene.width, scene.height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(false);
    }

    private static int draw(ChunkPuddles chunk, VertexBuffer buffer, int quads, Uniform offset, Vec3 cam, Frustum frustum) {
        if (buffer == null || chunk.bounds == null || !frustum.isVisible(chunk.bounds)) {
            return 0;
        }
        if (offset != null) {
            offset.set((float) ((chunk.chunkX << 4) - cam.x), (float) -cam.y, (float) ((chunk.chunkZ << 4) - cam.z));
            offset.upload();
        }
        buffer.bind();
        buffer.draw();
        return quads;
    }
}
