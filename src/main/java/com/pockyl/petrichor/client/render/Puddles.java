package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
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
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.compat.ShaderPacks;
import com.pockyl.petrichor.client.fx.RainFx;
import com.pockyl.petrichor.compat.Seasons;
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
    /** How much wetness a sheltered block keeps from its wetter neighbour, side by side and diagonally. */
    private static final float SHELTER_FALLOFF = 0.72F;
    private static final float SHELTER_FALLOFF_DIAGONAL = 0.63F;
    /** Blocks the wet ground reaches in under a shelter. */
    private static final int SHELTER_REACH = 8;
    /** Seconds newly sheltered ground takes to dry. */
    private static final float DRY_SECONDS = 120.0F;
    /** Wetness states closer than this to their target count as settled. */
    private static final float SETTLED = 0.004F;
    /** How far below a roof or crown the sheltered ground is looked for. */
    private static final int SHELTER_DEPTH = 14;
    /** Extra puddle field under the edge water pours off. */
    private static final float DRIP_LINE_BONUS = 0.2F;
    private static final int[] CORNER_X = {0, 0, 1, 1};
    private static final int[] CORNER_Z = {0, 1, 1, 0};
    private static final int BUILDS_PER_TICK = 2;
    private static final int CHECKS_PER_TICK = 3;
    private static final float LIFT = 0.002F;
    /** Shader-pack puddles are made of cells this many to a block side (4 = cells of 4x4 texture pixels). */
    private static final int WATER_CELLS = 4;
    private static final int WATER_BUILDS_PER_TICK = 4;
    private static final ResourceLocation WATER_SPRITE = ResourceLocation.withDefaultNamespace("block/water_still");

    private final Long2ObjectOpenHashMap<ChunkPuddles> chunks = new Long2ObjectOpenHashMap<>();
    private final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 18);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private ClientLevel level;
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
        /** Puddles as water cells for a shader pack (see {@link #buildWater}). */
        VertexBuffer water;
        int puddleQuads;
        int sheetQuads;
        int waterQuads;
        /** {@link #waterKey()} the water mesh was built for, 0 when it has to be built. */
        int waterKey;
        /**
         * Per surface (top and sheltered layer per column) as last meshed: height (NaN = none), light, and per corner the
         * puddle field and wetness. The shader-pack water mesh is rebuilt from these when the wetness changes.
         */
        final float[] surfaceY = new float[512];
        final int[] surfaceLight = new int[512];
        final float[] cornerField = new float[512 * 4];
        final float[] cornerWet = new float[512 * 4];
        AABB bounds;
        final float[] field = new float[256];
        final float[] top = new float[256];
        final boolean[] ground = new boolean[256];
        /** Height of each surface (top and sheltered layer per column) at the last build, to notice new surfaces. */
        final float[] lastTop = new float[512];
        /** How wet each column is relative to open ground, and how wet it is heading to be. */
        final float[] state = new float[512];
        final float[] target = new float[512];
        boolean builtOnce;
        boolean settling;
        List<Emitter> emitters = List.of();
        int signature;

        ChunkPuddles(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            Arrays.fill(lastTop, Float.NaN);
            Arrays.fill(surfaceY, Float.NaN);
        }

        void closeWater() {
            if (water != null) {
                water.close();
                water = null;
            }
            waterQuads = 0;
            waterKey = 0;
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
            closeWater();
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
        settle(level, ClientWeather.intensity());
        updateWater(level);
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
                rains[c] = Seasons.precipitationAt(level, pos) == Biome.Precipitation.RAIN;
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

        // First the wetness state of every surface: what it should be now, and where it is on its way there.
        boolean moving = false;
        for (int layer = 0; layer < 2; layer++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int c = layer * 256 + lz * 16 + lx;
                    int id = layer * n + grid.index(lx + MARGIN, lz + MARGIN);
                    float top = surfaces.top[id];
                    if (Float.isNaN(top) || !rains[lz * 16 + lx]) {
                        chunk.lastTop[c] = Float.NaN;
                        chunk.state[c] = 0.0F;
                        chunk.target[c] = 0.0F;
                        continue;
                    }
                    float target = surfaces.exposure[id];
                    if (!chunk.builtOnce) {
                        chunk.state[c] = target;
                    } else if (Float.isNaN(chunk.lastTop[c]) || Math.abs(chunk.lastTop[c] - top) > 0.01F) {
                        // A surface that was not there before (a placed block, ground under a broken block) starts dry.
                        chunk.state[c] = 0.0F;
                    }
                    chunk.target[c] = target;
                    chunk.lastTop[c] = top;
                    moving |= Math.abs(chunk.state[c] - target) > SETTLED;
                }
            }
        }
        chunk.builtOnce = true;
        chunk.settling = moving;

        Arrays.fill(chunk.surfaceY, Float.NaN);
        chunk.waterKey = 0;
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        int quads = 0;
        float[] cornerFlow = new float[3];
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int layer = 0; layer < 2; layer++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    int c = layer * 256 + lz * 16 + lx;
                    int gx = lx + MARGIN;
                    int gz = lz + MARGIN;
                    int i = grid.index(gx, gz);
                    int id = layer * n + i;
                    float top = surfaces.top[id];
                    if (Float.isNaN(chunk.lastTop[c]) || Math.max(chunk.state[c], chunk.target[c]) < MIN_EXPOSURE) {
                        continue;
                    }
                    minY = Math.min(minY, top);
                    maxY = Math.max(maxY, top);
                    pos.set(originX + lx, Mth.floor(top) + (top % 1.0F == 0.0F ? 0 : 1), originZ + lz);
                    int light = LevelRenderer.getLightColor(level, pos);
                    float y = top + LIFT;
                    int wx = Math.floorMod(originX + lx, 256);
                    int wz = Math.floorMod(originZ + lz, 256);
                    boolean flowing = layer == 0;
                    chunk.surfaceY[c] = y;
                    chunk.surfaceLight[c] = light;
                    for (int k = 0; k < 4; k++) {
                        int cornerX = gx + CORNER_X[k];
                        int cornerZ = gz + CORNER_Z[k];
                        float cornerField = surfaces.corner(surfaces.field, cornerX, cornerZ, top);
                        float cornerWet = cornerState(chunk, grid, surfaces, cornerX, cornerZ, top);
                        chunk.cornerField[c * 4 + k] = cornerField;
                        chunk.cornerWet[c * 4 + k] = cornerWet;
                        if (flowing) {
                            cornerFlow(grid, flowX, flowZ, cornerX, cornerZ, top, cornerFlow);
                        } else {
                            cornerFlow[0] = 0.0F;
                            cornerFlow[1] = 0.0F;
                            cornerFlow[2] = 0.0F;
                        }
                        // Colour: r = how muddy (soil) the water is, g = how wet this spot is relative to the open ground.
                        builder.addVertex(lx + CORNER_X[k], y, lz + CORNER_Z[k])
                                .setColor(surfaces.soil[id] ? 1.0F : 0.0F, cornerWet, 0.0F, cornerField)
                                .setUv(wx + CORNER_X[k], wz + CORNER_Z[k]).setLight(light)
                                .setNormal(cornerFlow[0], cornerFlow[1], cornerFlow[2]);
                    }
                    quads++;
                }
            }
        }
        if (minY <= maxY && chunk.bounds != null) {
            chunk.bounds = chunk.bounds.minmax(new AABB(originX, minY - 1.0, originZ, originX + 16, maxY + 1.0, originZ + 16));
        }
        chunk.puddleQuads = quads;
        chunk.puddles = upload(chunk.puddles, builder.build());
    }

    // ------------------------------------------------------------------------------------------------------------
    // Shader packs
    // ------------------------------------------------------------------------------------------------------------

    /** What a water mesh depends on: the wetness (in steps), the coverage setting and the pack's id of water. Never 0. */
    private static int waterKey() {
        int wetness = Math.round(ClientWeather.wetness() * 40.0F);
        int coverage = Math.round((float) (double) ClientConfig.PUDDLE_COVERAGE.get() * 20.0F);
        return ((wetness * 64 + coverage) * 4099 + ShaderPacks.blockId(Blocks.WATER.defaultBlockState()) + 2) | 1 << 30;
    }

    /** With a shader pack, keeps the water meshes in step with the wetness; without one, frees them. */
    private void updateWater(ClientLevel level) {
        if (!ShaderPacks.inUse() || !ClientConfig.PUDDLES.get()) {
            for (ChunkPuddles chunk : chunks.values()) {
                if (chunk.waterKey != 0) {
                    chunk.closeWater();
                }
            }
            return;
        }
        int key = waterKey();
        int built = 0;
        for (ChunkPuddles chunk : chunks.values()) {
            if (chunk.waterKey != key && built < WATER_BUILDS_PER_TICK) {
                buildWater(level, chunk, key);
                built++;
            }
        }
    }

    /**
     * Puddles for a shader pack, which cannot run the puddle shader: water cells of a quarter block, cut out with the same
     * field, threshold and ragged edges the shader uses, tagged as water so the pack draws them with its own water
     * (reflections, ripples). Built while the pack is active, so Iris lays the vertices out in its terrain format.
     */
    private void buildWater(ClientLevel level, ChunkPuddles chunk, int key) {
        chunk.waterKey = key;
        float threshold = PuddleField.threshold(ClientWeather.wetness(), (float) (double) ClientConfig.PUDDLE_COVERAGE.get());
        int waterId = ShaderPacks.blockId(Blocks.WATER.defaultBlockState());
        TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS).getSprite(WATER_SPRITE);
        int originX = chunk.chunkX << 4;
        int originZ = chunk.chunkZ << 4;
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        float step = 1.0F / WATER_CELLS;
        int quads = 0;
        for (int c = 0; c < 512; c++) {
            float y = chunk.surfaceY[c];
            if (Float.isNaN(y)) {
                continue;
            }
            int k = c * 4;
            // Corners in CORNER_X/Z order: (0,0), (0,1), (1,1), (1,0).
            float f00 = chunk.cornerField[k];
            float f01 = chunk.cornerField[k + 1];
            float f11 = chunk.cornerField[k + 2];
            float f10 = chunk.cornerField[k + 3];
            float w00 = chunk.cornerWet[k];
            float w01 = chunk.cornerWet[k + 1];
            float w11 = chunk.cornerWet[k + 2];
            float w10 = chunk.cornerWet[k + 3];
            // The noise moves the field by at most 0.17 and the wetness edge by 0.35.
            if (Math.max(Math.max(f00, f01), Math.max(f11, f10)) + 0.17F < threshold
                    || Math.max(Math.max(w00, w01), Math.max(w11, w10)) + 0.35F < 0.75F) {
                continue;
            }
            int lx = c & 15;
            int lz = (c >> 4) & 15;
            pos.set(originX + lx, Mth.floor(y), originZ + lz);
            int color = 0xFF000000 | BiomeColors.getAverageWaterColor(level, pos);
            int light = chunk.surfaceLight[c];
            boolean tagged = false;
            for (int sz = 0; sz < WATER_CELLS; sz++) {
                for (int sx = 0; sx < WATER_CELLS; sx++) {
                    float u = (sx + 0.5F) * step;
                    float v = (sz + 0.5F) * step;
                    float wx = originX + lx + u;
                    float wz = originZ + lz + v;
                    float field = Mth.lerp(v, Mth.lerp(u, f00, f10), Mth.lerp(u, f01, f11));
                    float wet = Mth.lerp(v, Mth.lerp(u, w00, w10), Mth.lerp(u, w01, w11));
                    float detail = noise(wx * 0.9F, wz * 0.9F) * 0.6F + noise(wx * 2.7F + 11.0F, wz * 2.7F + 5.0F) * 0.4F;
                    float ragged = wet + (noise(wx * 2.0F + 17.0F, wz * 2.0F) - 0.5F) * 0.7F * (1.0F - wet * wet);
                    if (field + (detail - 0.5F) * 0.34F < threshold || ragged < 0.75F) {
                        continue;
                    }
                    if (!tagged) {
                        ShaderPacks.beginFluid(builder, waterId, pos.getX(), pos.getY(), pos.getZ());
                        tagged = true;
                    }
                    float x0 = lx + sx * step;
                    float z0 = lz + sz * step;
                    float u0 = sprite.getU(sx * step);
                    float u1 = sprite.getU((sx + 1) * step);
                    float v0 = sprite.getV(sz * step);
                    float v1 = sprite.getV((sz + 1) * step);
                    // Counter-clockwise seen from above: the normal points up (Iris derives it from the winding).
                    builder.addVertex(x0, y, z0).setColor(color).setUv(u0, v0).setLight(light).setNormal(0.0F, 1.0F, 0.0F);
                    builder.addVertex(x0, y, z0 + step).setColor(color).setUv(u0, v1).setLight(light).setNormal(0.0F, 1.0F, 0.0F);
                    builder.addVertex(x0 + step, y, z0 + step).setColor(color).setUv(u1, v1).setLight(light).setNormal(0.0F, 1.0F, 0.0F);
                    builder.addVertex(x0 + step, y, z0).setColor(color).setUv(u1, v0).setLight(light).setNormal(0.0F, 1.0F, 0.0F);
                    quads++;
                }
            }
            if (tagged) {
                ShaderPacks.endBlock(builder);
            }
        }
        chunk.waterQuads = quads;
        chunk.water = upload(chunk.water, builder.build());
    }

    /** Smooth value noise 0..1 over world coordinates, repeating every 256 blocks. */
    private static float noise(float x, float z) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        float fx = x - ix;
        float fz = z - iz;
        fx = fx * fx * (3.0F - 2.0F * fx);
        fz = fz * fz * (3.0F - 2.0F * fz);
        float a = hash(ix, iz);
        float b = hash(ix + 1, iz);
        float c = hash(ix, iz + 1);
        float d = hash(ix + 1, iz + 1);
        return Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    private static float hash(int x, int z) {
        int h = (x & 255) * 374761393 + (z & 255) * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) & 0xFFFF) / 65535.0F;
    }

    /**
     * With a shader pack: draws the water puddles with the translucent terrain shader, which Iris swaps for the pack's
     * water program. Called after the translucent blocks, where the pack draws its own water.
     */
    public void renderWater(Matrix4f modelView, Matrix4f projection, Vec3 cam, Frustum frustum) {
        if (chunks.isEmpty() || !ShaderPacks.inUse() || !ClientConfig.PUDDLES.get()) {
            return;
        }
        RenderType type = RenderType.translucent();
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader == null) {
            type.clearRenderState();
            return;
        }
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0F, -10.0F);
        shader.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, Minecraft.getInstance().getWindow());
        shader.apply();
        for (ChunkPuddles chunk : chunks.values()) {
            if (chunk.waterQuads > 0) {
                lastQuads += draw(chunk, chunk.water, chunk.waterQuads, shader.CHUNK_OFFSET, cam, frustum);
            }
        }
        VertexBuffer.unbind();
        shader.clear();
        RenderSystem.polygonOffset(0.0F, 0.0F);
        RenderSystem.disablePolygonOffset();
        type.clearRenderState();
    }

    /** Mean wetness state of the surfaces around a block corner, taking cells of neighbouring chunks from those chunks. */
    private float cornerState(ChunkPuddles chunk, SurfaceGrid grid, Surfaces surfaces, int cornerX, int cornerZ, float level) {
        float sum = 0.0F;
        for (int dz = -1; dz <= 0; dz++) {
            for (int dx = -1; dx <= 0; dx++) {
                int x = cornerX + dx;
                int z = cornerZ + dz;
                int id = surfaces.at(x, z, level, 0.01F);
                if (id >= 0) {
                    sum += stateAt(chunk, grid.x0 + x, grid.z0 + z, surfaces.top[id], surfaces.exposure[id]);
                }
            }
        }
        return sum * 0.25F;
    }

    /** The wetness state of a surface, from whichever chunk holds it; its target when that chunk has no state for it. */
    private float stateAt(ChunkPuddles self, int worldX, int worldZ, float top, float target) {
        int cx = worldX >> 4;
        int cz = worldZ >> 4;
        ChunkPuddles owner = cx == self.chunkX && cz == self.chunkZ ? self : chunks.get(ChunkPos.asLong(cx, cz));
        if (owner == null || !owner.builtOnce) {
            return target;
        }
        int column = (worldZ & 15) * 16 + (worldX & 15);
        for (int layer = 0; layer < 2; layer++) {
            float last = owner.lastTop[layer * 256 + column];
            if (!Float.isNaN(last) && Math.abs(last - top) < 0.01F) {
                return owner.state[layer * 256 + column];
            }
        }
        return target;
    }

    /**
     * Wetness follows the shelter slowly: newly covered ground dries over a couple of minutes, new or uncovered surfaces
     * soak over half a minute of rain. Chunks on the move get their mesh rebuilt once a second.
     */
    private void settle(ClientLevel level, float intensity) {
        float up = intensity > 0.05F ? (0.4F + intensity) / (20.0F * SOAK_SECONDS) : 0.0F;
        float down = 1.0F / (20.0F * DRY_SECONDS);
        int rebuilt = 0;
        for (ChunkPuddles chunk : chunks.values()) {
            if (!chunk.settling) {
                continue;
            }
            boolean any = false;
            for (int c = 0; c < chunk.state.length; c++) {
                float state = chunk.state[c];
                float target = chunk.target[c];
                if (state < target) {
                    state = Math.min(target, state + up);
                } else if (state > target) {
                    state = Math.max(target, state - down);
                }
                chunk.state[c] = state;
                any |= Math.abs(state - target) > SETTLED;
            }
            chunk.settling = any;
            if (rebuilt < BUILDS_PER_TICK && (level.getGameTime() + chunk.chunkX * 7L + chunk.chunkZ * 13L) % 20 == 0) {
                build(level, chunk);
                rebuilt++;
            }
        }
    }

    /**
     * The surfaces that can get wet, up to two per column: the top surface open to the rain, and the ground sheltered
     * under a roof, an overhang or a tree crown. Each gets how wet it should be relative to open ground: 1 in the open,
     * fading in under shelters over several blocks (diagonals included, so the fade follows the outline of the shelter
     * instead of rows and columns), a little less on open ground next to a shelter, and blurred, so there is no line
     * where wet meets dry.
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
            }
            if (kind == SurfaceKind.GROUND || kind == SurfaceKind.OTHER || kind == SurfaceKind.LEAVES) {
                shelteredFloor(level, grid, i, n + i, surfaces);
            }
        }
        int cells = 2 * n;
        boolean[] open = new boolean[cells];
        for (int id = 0; id < n; id++) {
            open[id] = !Float.isNaN(surfaces.top[id]);
        }
        float[] next = new float[cells];
        for (int pass = 0; pass < SHELTER_REACH; pass++) {
            System.arraycopy(surfaces.exposure, 0, next, 0, cells);
            for (int id = n; id < cells; id++) {
                if (Float.isNaN(surfaces.top[id])) {
                    continue;
                }
                int lx = (id - n) % grid.size;
                int lz = (id - n) / grid.size;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dz == 0) {
                            continue;
                        }
                        int j = surfaces.at(lx + dx, lz + dz, surfaces.top[id], 1.1F);
                        if (j >= 0) {
                            float falloff = dx != 0 && dz != 0 ? SHELTER_FALLOFF_DIAGONAL : SHELTER_FALLOFF;
                            next[id] = Math.max(next[id], surfaces.exposure[j] * falloff);
                        }
                    }
                }
            }
            System.arraycopy(next, 0, surfaces.exposure, 0, cells);
        }
        // Open ground next to a shelter: a touch less wet, the closer the less.
        for (int id = 0; id < n; id++) {
            if (!open[id]) {
                continue;
            }
            int lx = id % grid.size;
            int lz = id / grid.size;
            float nearest = 4.0F;
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    int j = surfaces.at(lx + dx, lz + dz, surfaces.top[id], 1.1F);
                    if (j >= n) {
                        nearest = Math.min(nearest, Mth.sqrt(dx * dx + dz * dz));
                    }
                }
            }
            next[id] = nearest < 4.0F ? 0.7F + 0.075F * nearest : 1.0F;
        }
        for (int id = 0; id < n; id++) {
            if (open[id]) {
                surfaces.exposure[id] = next[id];
            }
        }
        // Two passes of a 3x3 blur over surfaces of about the same level.
        for (int pass = 0; pass < 2; pass++) {
            for (int id = 0; id < cells; id++) {
                if (Float.isNaN(surfaces.top[id])) {
                    next[id] = 0.0F;
                    continue;
                }
                int i = id % n;
                int lx = i % grid.size;
                int lz = i / grid.size;
                float sum = 0.0F;
                int count = 0;
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int j = dx == 0 && dz == 0 ? id : surfaces.at(lx + dx, lz + dz, surfaces.top[id], 1.1F);
                        if (j >= 0) {
                            sum += surfaces.exposure[j];
                            count++;
                        }
                    }
                }
                next[id] = sum / count;
            }
            System.arraycopy(next, 0, surfaces.exposure, 0, cells);
        }
        return surfaces;
    }

    /** The ground under the top block of column {@code i} (a roof, an overhang, a crown), if there is air between. */
    private void shelteredFloor(ClientLevel level, SurfaceGrid grid, int i, int id, Surfaces surfaces) {
        int x = grid.x0 + i % grid.size;
        int z = grid.z0 + i / grid.size;
        int top = grid.height[i] - 1;
        boolean gap = false;
        for (int y = top - 1; y > top - SHELTER_DEPTH; y--) {
            pos.set(x, y, z);
            BlockState state = level.getBlockState(pos);
            if (state.isAir() || state.canBeReplaced() && state.getFluidState().isEmpty()) {
                gap = true;
                continue;
            }
            SurfaceKind.Shape shape = SurfaceKind.classify(state);
            if (gap && shape.kind() == SurfaceKind.GROUND) {
                surfaces.top[id] = y + shape.top();
                surfaces.soil[id] = shape.soil();
            }
            return;
        }
    }

    /**
     * Wettable surfaces of a grid in two layers (index {@code layer * n + i}): the open top and the sheltered ground below
     * it. Per surface its height (NaN for none), soil, puddle field and how wet it should be relative to open ground.
     */
    private static final class Surfaces {
        final int size;
        final int n;
        final float[] top;
        final boolean[] soil;
        final float[] field;
        final float[] exposure;

        Surfaces(int size) {
            this.size = size;
            n = size * size;
            top = new float[2 * n];
            soil = new boolean[2 * n];
            field = new float[2 * n];
            exposure = new float[2 * n];
            Arrays.fill(top, Float.NaN);
        }

        /** The surface of column (x, z) within {@code tolerance} of {@code level}, the nearer of the two layers, or -1. */
        int at(int x, int z, float level, float tolerance) {
            if (x < 0 || z < 0 || x >= size || z >= size) {
                return -1;
            }
            int i = z * size + x;
            int best = -1;
            float bestDistance = tolerance;
            for (int id : new int[] {i, n + i}) {
                float distance = Float.isNaN(top[id]) ? Float.MAX_VALUE : Math.abs(top[id] - level);
                if (distance <= bestDistance) {
                    best = id;
                    bestDistance = distance;
                }
            }
            return best;
        }

        /**
         * The mean of a value over the four surfaces sharing a block corner at this level; missing ones count as zero, so
         * values fade out towards steps and walls.
         */
        float corner(float[] values, int cornerX, int cornerZ, float level) {
            float sum = 0.0F;
            for (int dz = -1; dz <= 0; dz++) {
                for (int dx = -1; dx <= 0; dx++) {
                    int id = at(cornerX + dx, cornerZ + dz, level, 0.01F);
                    if (id >= 0) {
                        sum += values[id];
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
                byte surface = switch (grid.kind[j]) {
                    case WATER -> RainFx.LAND_WATER;
                    case HOT -> RainFx.LAND_HOT;
                    default -> RainFx.LAND_GROUND;
                };
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
        return String.format("ground %s, field %.2f, top %.2f, wet %.2f -> %.2f", chunk.ground[c], chunk.field[c], chunk.top[c],
                chunk.state[c], chunk.target[c]);
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
        // Iris hides unknown shaders while a pack is active, and packs bring their own wet surfaces and reflections.
        if (chunks.isEmpty() || level == null || ShaderPacks.inUse()) {
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
        RenderTarget scene = drawPuddles && steps > 0 ? SceneCopy.world() : null;

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
            // Rings on puddles follow how hard the drops hit: a few in a drizzle, the whole surface in a downpour.
            puddle.safeGetUniform("RainAmount").set(Math.min(1.0F, 0.04F + intensity * ClientWeather.splash / 2.6F * 0.95F));
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
