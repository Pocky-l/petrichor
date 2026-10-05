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

    /** One emitter of falling water: a roof edge, a cliff edge, the rim of a canopy. */
    public record Emitter(float x, float y, float z, float dirX, float dirZ, float groundY, int flow, byte surface) {
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
        List<Emitter> emitters = List.of();
        int signature;

        ChunkPuddles(int chunkX, int chunkZ) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
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
        buildPuddles(level, chunk, grid, runoff, field);
        buildSheets(level, chunk, grid, runoff, rains);
        chunk.emitters = findEmitters(chunk, grid, runoff, rains);
    }

    private void buildPuddles(ClientLevel level, ChunkPuddles chunk, SurfaceGrid grid, RunoffSolver runoff, float[] field) {
        int originX = chunk.chunkX << 4;
        int originZ = chunk.chunkZ << 4;
        float[] flowX = new float[grid.size * grid.size];
        float[] flowZ = new float[grid.size * grid.size];
        for (int i = 0; i < flowX.length; i++) {
            float strength = flowStrength(grid, runoff, i);
            if (strength > 0.0F) {
                int j = runoff.downstream[i];
                flowX[i] = ((j % grid.size) - (i % grid.size)) * strength;
                flowZ[i] = ((j / grid.size) - (i / grid.size)) * strength;
            }
        }
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        int quads = 0;
        float[] cornerFlow = new float[3];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int c = lz * 16 + lx;
                if (!chunk.ground[c]) {
                    continue;
                }
                int gx = lx + MARGIN;
                int gz = lz + MARGIN;
                int i = grid.index(gx, gz);
                float top = grid.top[i];
                pos.set(originX + lx, grid.height[i], originZ + lz);
                int light = LevelRenderer.getLightColor(level, pos);
                float r;
                float g;
                float b;
                // The colour water takes over this ground: brown over soil, dark grey over stone and wood.
                if (grid.soil[i]) {
                    r = 0.36F;
                    g = 0.29F;
                    b = 0.21F;
                } else {
                    r = 0.27F;
                    g = 0.29F;
                    b = 0.31F;
                }
                float y = top + LIFT;
                int wx = Math.floorMod(originX + lx, 256);
                int wz = Math.floorMod(originZ + lz, 256);
                for (int k = 0; k < 4; k++) {
                    int cornerX = gx + CORNER_X[k];
                    int cornerZ = gz + CORNER_Z[k];
                    float cornerValue = cornerField(grid, field, cornerX, cornerZ, top);
                    cornerFlow(grid, flowX, flowZ, cornerX, cornerZ, top, cornerFlow);
                    builder.addVertex(lx + CORNER_X[k], y, lz + CORNER_Z[k]).setColor(r, g, b, cornerValue)
                            .setUv(wx + CORNER_X[k], wz + CORNER_Z[k]).setLight(light)
                            .setNormal(cornerFlow[0], cornerFlow[1], cornerFlow[2]);
                }
                quads++;
            }
        }
        chunk.puddleQuads = quads;
        chunk.puddles = upload(chunk.puddles, builder.build());
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

    /**
     * The field at a block corner: the mean over the four blocks sharing it, where blocks that are not flat ground at the
     * same height count as dry, so puddles end before steps and walls instead of hanging over them.
     */
    private static float cornerField(SurfaceGrid grid, float[] field, int cornerX, int cornerZ, float top) {
        float sum = 0.0F;
        for (int dz = -1; dz <= 0; dz++) {
            for (int dx = -1; dx <= 0; dx++) {
                int x = cornerX + dx;
                int z = cornerZ + dz;
                if (x < 0 || z < 0 || x >= grid.size || z >= grid.size) {
                    continue;
                }
                int j = grid.index(x, z);
                if (grid.kind[j] == SurfaceKind.GROUND && Math.abs(grid.top[j] - top) < 0.01F) {
                    sum += field[j];
                }
            }
        }
        return sum * 0.25F;
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

    private static List<Emitter> findEmitters(ChunkPuddles chunk, SurfaceGrid grid, RunoffSolver runoff, boolean[] rains) {
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
                emitters.add(new Emitter(originX + lx + 0.5F + dirX * 0.52F, grid.top[i], originZ + lz + 0.5F + dirZ * 0.52F, dirX, dirZ,
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
        return String.format("ground %s, field %.2f, top %.2f", chunk.ground[c], chunk.field[c], chunk.top[c]);
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
