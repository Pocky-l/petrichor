package com.pockyl.petrichor.client.render;

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
 * Puddles, wet ground, rivulets and the places where water pours off edges, per chunk.
 *
 * <p>For every chunk near the camera the surface of the chunk plus a 16-block margin is sampled, the runoff solved and
 * the puddle field computed (all in {@code world}). From that a static GPU mesh is built: one quad over every flat,
 * rain-exposed top (the shader turns it into wet ground, a puddle or nothing depending on the wetness, so puddles grow
 * and dry without rebuilding) and ribbons along the streams. Meshes are rebuilt when the chunk's surface changes.
 */
public final class Puddles implements AutoCloseable {
    private static final int MARGIN = 16;
    private static final int GRID = 16 + MARGIN * 2;
    /** Cells collecting the rain of fewer cells than this carry no visible rivulet. */
    private static final int MIN_FLOW = 5;
    /** Rivulets are drawn only this close to the next step down; on wide flats water just soaks and pools. */
    private static final int MAX_EDGE_DISTANCE = 3;
    private static final int BUILDS_PER_TICK = 2;
    private static final int CHECKS_PER_TICK = 3;
    private static final float LIFT = 0.002F;
    private static final float RIVULET_LIFT = 0.012F;

    private final Long2ObjectOpenHashMap<ChunkPuddles> chunks = new Long2ObjectOpenHashMap<>();
    private final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 18);
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private ClientLevel level;
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
        VertexBuffer rivulets;
        int puddleQuads;
        int rivuletQuads;
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
            if (rivulets != null) {
                rivulets.close();
                rivulets = null;
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
    }

    public int chunkCount() {
        return chunks.size();
    }

    public int lastQuads() {
        return lastQuads;
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
        buildPuddles(level, chunk, grid, field);
        buildRivulets(level, chunk, grid, runoff, rains);
        chunk.emitters = findEmitters(chunk, grid, runoff, rains);
    }

    private void buildPuddles(ClientLevel level, ChunkPuddles chunk, SurfaceGrid grid, float[] field) {
        int originX = chunk.chunkX << 4;
        int originZ = chunk.chunkZ << 4;
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        int quads = 0;
        float[] corner = new float[4];
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
                for (int k = 0; k < 4; k++) {
                    int cornerX = gx + (k == 1 || k == 2 ? 1 : 0);
                    int cornerZ = gz + (k >= 2 ? 1 : 0);
                    corner[k] = cornerField(grid, field, cornerX, cornerZ, top);
                }
                pos.set(originX + lx, grid.height[i], originZ + lz);
                int light = LevelRenderer.getLightColor(level, pos);
                float r;
                float g;
                float b;
                if (grid.soil[i]) {
                    r = 0.46F;
                    g = 0.37F;
                    b = 0.27F;
                } else {
                    int water = level.getBiome(pos).value().getWaterColor();
                    r = 0.25F + ((water >> 16) & 0xFF) / 255.0F * 0.5F;
                    g = 0.27F + ((water >> 8) & 0xFF) / 255.0F * 0.5F;
                    b = 0.3F + (water & 0xFF) / 255.0F * 0.5F;
                }
                float y = top + LIFT;
                int wx = Math.floorMod(originX + lx, 256);
                int wz = Math.floorMod(originZ + lz, 256);
                builder.addVertex(lx, y, lz).setUv(wx, wz).setColor(r, g, b, corner[0]).setLight(light);
                builder.addVertex(lx, y, lz + 1).setUv(wx, wz + 1).setColor(r, g, b, corner[3]).setLight(light);
                builder.addVertex(lx + 1, y, lz + 1).setUv(wx + 1, wz + 1).setColor(r, g, b, corner[2]).setLight(light);
                builder.addVertex(lx + 1, y, lz).setUv(wx + 1, wz).setColor(r, g, b, corner[1]).setLight(light);
                quads++;
            }
        }
        chunk.puddleQuads = quads;
        chunk.puddles = upload(chunk.puddles, builder.build());
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

    private boolean flows(SurfaceGrid grid, RunoffSolver runoff, int i) {
        return grid.kind[i] == SurfaceKind.GROUND && runoff.accumulation[i] >= MIN_FLOW && runoff.downstream[i] >= 0
                && runoff.drop[i] <= 1 && runoff.distanceToEdge[i] <= MAX_EDGE_DISTANCE;
    }

    private static float ribbonWidth(int flow) {
        return Math.clamp(0.05F + 0.045F * log2(flow), 0.08F, 0.38F);
    }

    private static float ribbonStrength(int flow) {
        return Math.clamp(0.35F + 0.1F * log2(flow), 0.4F, 1.0F);
    }

    private static float log2(int value) {
        return (float) (Math.log(value) / Math.log(2.0));
    }

    private void buildRivulets(ClientLevel level, ChunkPuddles chunk, SurfaceGrid grid, RunoffSolver runoff, boolean[] rains) {
        if (!ClientConfig.RIVULETS.get() || !ClientConfig.quality().rivulets) {
            chunk.rivuletQuads = 0;
            chunk.rivulets = upload(chunk.rivulets, null);
            return;
        }
        int originX = chunk.chunkX << 4;
        int originZ = chunk.chunkZ << 4;
        BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.PARTICLE);
        int quads = 0;
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                int c = lz * 16 + lx;
                if (!rains[c]) {
                    continue;
                }
                int gx = lx + MARGIN;
                int gz = lz + MARGIN;
                int i = grid.index(gx, gz);
                if (grid.kind[i] != SurfaceKind.GROUND) {
                    continue;
                }
                pos.set(originX + lx, grid.height[i], originZ + lz);
                int light = LevelRenderer.getLightColor(level, pos);
                float tintR = grid.soil[i] ? 0.5F : 0.45F;
                float tintG = grid.soil[i] ? 0.42F : 0.5F;
                float tintB = grid.soil[i] ? 0.32F : 0.58F;
                float y = grid.top[i] + RIVULET_LIFT;
                float cx = lx + 0.5F;
                float cz = lz + 0.5F;
                if (flows(grid, runoff, i)) {
                    int j = runoff.downstream[i];
                    float dirX = (j % grid.size) - gx;
                    float dirZ = (j / grid.size) - gz;
                    int flow = runoff.accumulation[i];
                    float w = ribbonWidth(flow);
                    float s = ribbonStrength(flow);
                    float ex = cx + dirX * 0.5F;
                    float ez = cz + dirZ * 0.5F;
                    flat(builder, cx, y, cz, ex, ez, w, 0.0F, 0.5F, tintR, tintG, tintB, s, light);
                    quads++;
                    if (runoff.drop[i] == 1) {
                        float bottom = grid.known(j) ? grid.top[j] + RIVULET_LIFT : y - 1.0F;
                        wall(builder, ex + dirX * 0.008F, ez + dirZ * 0.008F, dirX, dirZ, y, bottom, w, 0.5F, tintR, tintG, tintB, s,
                                light);
                        quads++;
                    }
                }
                for (int d = 0; d < 4; d++) {
                    int ux = gx + (d == 0 ? 1 : d == 1 ? -1 : 0);
                    int uz = gz + (d == 2 ? 1 : d == 3 ? -1 : 0);
                    if (ux < 0 || uz < 0 || ux >= grid.size || uz >= grid.size) {
                        continue;
                    }
                    int u = grid.index(ux, uz);
                    if (runoff.downstream[u] != i || !flows(grid, runoff, u)) {
                        continue;
                    }
                    int flow = runoff.accumulation[u];
                    float sx = cx + (ux - gx) * 0.5F;
                    float sz = cz + (uz - gz) * 0.5F;
                    flat(builder, sx, y, sz, cx, cz, ribbonWidth(flow), -0.5F, 0.0F, tintR, tintG, tintB, ribbonStrength(flow), light);
                    quads++;
                }
            }
        }
        chunk.rivuletQuads = quads;
        chunk.rivulets = upload(chunk.rivulets, builder.build());
    }

    /** A horizontal ribbon from A to B. */
    private static void flat(BufferBuilder out, float ax, float y, float az, float bx, float bz, float width, float v0, float v1,
            float r, float g, float b, float a, int light) {
        float dx = bx - ax;
        float dz = bz - az;
        float len = Mth.sqrt(dx * dx + dz * dz);
        if (len < 1.0E-4F) {
            return;
        }
        float sx = -dz / len * width * 0.5F;
        float sz = dx / len * width * 0.5F;
        out.addVertex(ax - sx, y, az - sz).setUv(-1.0F, v0).setColor(r, g, b, a).setLight(light);
        out.addVertex(ax + sx, y, az + sz).setUv(1.0F, v0).setColor(r, g, b, a).setLight(light);
        out.addVertex(bx + sx, y, bz + sz).setUv(1.0F, v1).setColor(r, g, b, a).setLight(light);
        out.addVertex(bx - sx, y, bz - sz).setUv(-1.0F, v1).setColor(r, g, b, a).setLight(light);
    }

    /** A vertical ribbon running down the face of a one-block step. */
    private static void wall(BufferBuilder out, float x, float z, float dirX, float dirZ, float topY, float bottomY, float width,
            float v0, float r, float g, float b, float a, int light) {
        float sx = -dirZ * width * 0.5F;
        float sz = dirX * width * 0.5F;
        float v1 = v0 + (topY - bottomY);
        out.addVertex(x - sx, topY, z - sz).setUv(-1.0F, v0).setColor(r, g, b, a).setLight(light);
        out.addVertex(x + sx, topY, z + sz).setUv(1.0F, v0).setColor(r, g, b, a).setLight(light);
        out.addVertex(x + sx, bottomY, z + sz).setUv(1.0F, v1).setColor(r, g, b, a).setLight(light);
        out.addVertex(x - sx, bottomY, z - sz).setUv(-1.0F, v1).setColor(r, g, b, a).setLight(light);
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
                if (j < 0 || runoff.drop[i] < 2 || grid.kind[i] == SurfaceKind.WATER || grid.kind[i] == SurfaceKind.HOT) {
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

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enablePolygonOffset();
        RenderSystem.polygonOffset(-1.0F, -10.0F);
        lightTexture.turnOnLightLayer();

        ShaderInstance puddle = PetrichorShaders.puddle();
        if (puddle != null && ClientConfig.PUDDLES.get() && wetness > 0.002F) {
            puddle.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
            puddle.safeGetUniform("Wetness").set(wetness);
            puddle.safeGetUniform("Coverage").set((float) (double) ClientConfig.PUDDLE_COVERAGE.get());
            puddle.safeGetUniform("RainAmount").set(Math.min(1.0F, intensity * (0.2F + ClientWeather.density * 0.4F)));
            puddle.safeGetUniform("PetrichorTime").set(time);
            puddle.safeGetUniform("SkyColor").set((float) sky.x, (float) sky.y, (float) sky.z);
            puddle.apply();
            for (ChunkPuddles chunk : chunks.values()) {
                lastQuads += draw(chunk, chunk.puddles, chunk.puddleQuads, puddle.CHUNK_OFFSET, cam, frustum);
            }
            puddle.clear();
        }
        float flow = Wetness.runoff(wetness, intensity);
        ShaderInstance rivulet = PetrichorShaders.rivulet();
        if (rivulet != null && flow > 0.01F) {
            rivulet.setDefaultUniforms(VertexFormat.Mode.QUADS, modelView, projection, minecraft.getWindow());
            rivulet.safeGetUniform("Flow").set(flow);
            rivulet.safeGetUniform("PetrichorTime").set(time);
            rivulet.safeGetUniform("SkyColor").set((float) sky.x, (float) sky.y, (float) sky.z);
            rivulet.apply();
            for (ChunkPuddles chunk : chunks.values()) {
                lastQuads += draw(chunk, chunk.rivulets, chunk.rivuletQuads, rivulet.CHUNK_OFFSET, cam, frustum);
            }
            rivulet.clear();
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
