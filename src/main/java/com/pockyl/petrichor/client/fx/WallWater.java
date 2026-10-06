package com.pockyl.petrichor.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.Columns;
import com.pockyl.petrichor.client.render.Puddles;
import com.pockyl.petrichor.weather.Noise;
import com.pockyl.petrichor.world.SoundMaterial;

import java.util.Arrays;

/**
 * Water on the walls: what is left of the drops that hit the side of a block.
 *
 * <p>On a wall (stone, wood, metal) a drop runs straight down as a small separate drop, leaving a wet streak, and soaks
 * in on the way - fast on rough stone, slowly on smooth planks and metal. At the bottom of the wall it joins the ground,
 * or drips off when the wall ends above open air.
 *
 * <p>On glass it behaves like rain on a real window: drops stay where they land as beads; beads grow as more drops hit
 * them, until one is heavy enough to break free. It slides down in jerks, stopping and starting, wandering a little to
 * the side, swallowing the beads in its way (and getting faster for it), and leaves a trail of tiny beads behind. When
 * the rain stops, the window slowly dries.
 *
 * <p>Drops live on the faces of blocks that rain can reach. Every drop drawn by {@link Precipitation} that hits a wall
 * lands here; the much finer rain that is not drawn adds more, at a rate set by how much the wind drives the rain at
 * the face.
 */
public final class WallWater {
    public static final byte GLASS = 0;
    public static final byte SMOOTH = 1;
    public static final byte ROUGH = 2;
    private static final byte NONE = -1;

    private static final int SCAN_RADIUS = 14;
    private static final int SCAN_EVERY = 20;
    private static final int MAX_FACES = 4096;
    private static final float CELL = 1.0F / 16.0F;
    /** Offset of the drops from the face, against z-fighting. */
    private static final float LIFT = 0.004F;
    private static final int SEED_RUN = 0x0D22_0001;
    private static final int SEED_WANDER = 0x0D22_0002;
    private static final double RENDER_RANGE = 26.0;

    /**
     * A face of a block where rain can land: the face plane, its extent and what it is made of.
     *
     * @param plane coordinate of the face plane along the face's axis
     * @param u0    extent across the face (z for faces along x, x for faces along z)
     * @param y0    lowest point the rain reaches (the ground or a roof in front may hide the lower part)
     * @param ground whether the ground in front meets the face at its bottom (otherwise the face ends above air)
     */
    private record Face(int bx, int by, int bz, Direction dir, float plane, float u0, float u1, float y0, float y1, byte material,
            int light, boolean ground) {
        boolean alongX() {
            return dir.getAxis() == Direction.Axis.X;
        }
    }

    private final RandomSource random = RandomSource.create();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final Long2ObjectOpenHashMap<Face> faces = new Long2ObjectOpenHashMap<>();
    private Face[] faceList = new Face[0];
    private ClientLevel level;
    /** Where drops falling off the bottom edges of walls go. */
    private RainFx dripTarget;
    private Puddles dripPuddles;
    private int ticks;
    private double scanX = Double.NaN;
    private double scanZ = Double.NaN;

    // The drops, as parallel arrays. Positions are world coordinates relative to an origin near the camera.
    private int capacity;
    private int count;
    private double originX;
    private double originZ;
    private float[] x = new float[0];
    private float[] y = new float[0];
    private float[] z = new float[0];
    private float[] mass = new float[0];
    /** Speed down the face, blocks per tick (positive = down). */
    private float[] fall = new float[0];
    /** Speed across the face. */
    private float[] drift = new float[0];
    /** Length of the wet streak above a running drop. */
    private float[] streak = new float[0];
    /** Distance slid since the last bead was left behind. */
    private float[] slid = new float[0];
    private int[] pinned = new int[0];
    private float[] threshold = new float[0];
    private long[] face = new long[0];
    private byte[] material = new byte[0];
    private byte[] dir = new byte[0];
    private boolean[] running = new boolean[0];
    private boolean[] dead = new boolean[0];
    private int[] light = new int[0];

    // Beads by grid cell in their face plane, rebuilt every tick: heads of linked lists through next[].
    private final Long2IntOpenHashMap grid = new Long2IntOpenHashMap();
    private int[] next = new int[0];

    public WallWater() {
        grid.defaultReturnValue(-1);
    }

    public int count() {
        return count;
    }

    public int faceCount() {
        return faces.size();
    }

    public void clear() {
        count = 0;
        faces.clear();
        faceList = new Face[0];
        grid.clear();
        scanX = Double.NaN;
        level = null;
    }

    private void setCapacity(int newCapacity) {
        if (newCapacity == capacity) {
            return;
        }
        capacity = newCapacity;
        count = Math.min(count, capacity);
        x = Arrays.copyOf(x, capacity);
        y = Arrays.copyOf(y, capacity);
        z = Arrays.copyOf(z, capacity);
        mass = Arrays.copyOf(mass, capacity);
        fall = Arrays.copyOf(fall, capacity);
        drift = Arrays.copyOf(drift, capacity);
        streak = Arrays.copyOf(streak, capacity);
        slid = Arrays.copyOf(slid, capacity);
        pinned = Arrays.copyOf(pinned, capacity);
        threshold = Arrays.copyOf(threshold, capacity);
        face = Arrays.copyOf(face, capacity);
        material = Arrays.copyOf(material, capacity);
        dir = Arrays.copyOf(dir, capacity);
        running = Arrays.copyOf(running, capacity);
        dead = Arrays.copyOf(dead, capacity);
        light = Arrays.copyOf(light, capacity);
        next = Arrays.copyOf(next, capacity);
        rebuildGrid();
    }

    private static long faceKey(int bx, int by, int bz, Direction direction) {
        return BlockPos.asLong(bx, by, bz) * 4L + direction.get2DDataValue();
    }

    private static float radius(float m) {
        return 0.011F * (float) Math.cbrt(Math.max(m, 0.05F));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Faces
    // ------------------------------------------------------------------------------------------------------------

    private static byte materialOf(BlockState state) {
        if (state.getFluidState().is(FluidTags.WATER) || state.isAir()) {
            return NONE;
        }
        return switch (SoundMaterial.of(state)) {
            case GLASS -> GLASS;
            case WOOD, METAL -> SMOOTH;
            case HARD, SOFT -> ROUGH;
            default -> NONE;
        };
    }

    /**
     * Finds the faces rain can reach around the camera: in each column, the part of each side that rises above the
     * column next to it, where rain falls.
     */
    private void scan(ClientLevel level, Columns columns, double camX, double camY, double camZ) {
        faces.clear();
        int ccx = Mth.floor(camX);
        int ccz = Mth.floor(camZ);
        int minY = Mth.floor(camY) - 12;
        int maxY = Mth.floor(camY) + 18;
        for (int cz = ccz - SCAN_RADIUS; cz <= ccz + SCAN_RADIUS && faces.size() < MAX_FACES; cz++) {
            for (int cx = ccx - SCAN_RADIUS; cx <= ccx + SCAN_RADIUS; cx++) {
                int dx0 = cx - ccx;
                int dz0 = cz - ccz;
                if (dx0 * dx0 + dz0 * dz0 > SCAN_RADIUS * SCAN_RADIUS) {
                    continue;
                }
                int top = columns.height(cx, cz);
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    int nx = cx + direction.getStepX();
                    int nz = cz + direction.getStepZ();
                    if (columns.precipitation(nx, nz) != Columns.RAIN) {
                        continue;
                    }
                    float frontTop = columns.top(nx, nz);
                    int from = Math.max(Mth.floor(frontTop), minY);
                    int to = Math.min(top - 1, maxY);
                    for (int by = from; by <= to; by++) {
                        addFace(level, cx, by, cz, direction, frontTop);
                    }
                }
            }
        }
        faceList = faces.values().toArray(new Face[0]);
    }

    private void addFace(ClientLevel level, int bx, int by, int bz, Direction direction, float frontTop) {
        pos.set(bx, by, bz);
        BlockState state = level.getBlockState(pos);
        byte kind = materialOf(state);
        if (kind == NONE) {
            return;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return;
        }
        boolean alongX = direction.getAxis() == Direction.Axis.X;
        Direction.Axis across = alongX ? Direction.Axis.Z : Direction.Axis.X;
        Direction.Axis axis = direction.getAxis();
        float u0 = (float) shape.min(across);
        float u1 = (float) shape.max(across);
        float y0 = (float) shape.min(Direction.Axis.Y);
        float y1 = (float) shape.max(Direction.Axis.Y);
        // Only faces that are walls: wide and tall enough to hold drops (fences and posts are not).
        if (u1 - u0 < 0.5F || y1 - y0 < 0.5F || y1 > 1.0F) {
            return;
        }
        float plane = direction.getAxisDirection() == Direction.AxisDirection.POSITIVE ? (float) shape.max(axis) : (float) shape.min(axis);
        int base = alongX ? bx : bz;
        int uBase = alongX ? bz : bx;
        float low = Math.max(by + y0, frontTop);
        if (by + y1 - low < 0.1F) {
            return;
        }
        pos.set(bx + direction.getStepX(), by, bz + direction.getStepZ());
        int packedLight = LevelRenderer.getLightColor(level, pos);
        boolean ground = frontTop >= by + y0 - 0.01F;
        faces.put(faceKey(bx, by, bz, direction), new Face(bx, by, bz, direction, base + plane, uBase + u0, uBase + u1, low, by + y1, kind,
                packedLight, ground));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Impacts
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A drawn drop hit the side of a block: its water stays on the face, if the face can hold it.
     *
     * @param face the face that was hit (its outward direction)
     * @return the face's material, or -1 when the face holds no water (or is not known yet)
     */
    public byte impact(int bx, int by, int bz, Direction face, double hx, double hy, double hz, float size) {
        Face f = faces.get(faceKey(bx, by, bz, face));
        if (f == null || !ClientConfig.WALL_WATER.get()) {
            return NONE;
        }
        double u = f.alongX() ? hz : hx;
        if (u < f.u0() || u > f.u1() || hy < f.y0() || hy > f.y1()) {
            return f.material();
        }
        float m = f.material() == GLASS ? (1.2F + random.nextFloat() * 1.3F) * size : (1.0F + random.nextFloat()) * size;
        land(f, u, hy, m);
        return f.material();
    }

    private void land(Face f, double u, double wy, float m) {
        double wx = f.alongX() ? f.plane() : u;
        double wz = f.alongX() ? u : f.plane();
        if (f.material() == GLASS) {
            // On glass a new drop joins a bead it touches.
            int other = nearest(f, (float) (wx - originX), (float) wy, (float) (wz - originZ), radius(m));
            if (other >= 0) {
                mass[other] += m;
                return;
            }
        } else if (random.nextFloat() > 0.55F) {
            // Rough walls drink most small drops at once; a share gathers into a drop that runs.
            return;
        }
        if (count >= capacity || f.material() != GLASS && count >= capacity * 0.45F) {
            return;
        }
        int i = count++;
        x[i] = (float) (wx - originX);
        y[i] = (float) wy;
        z[i] = (float) (wz - originZ);
        mass[i] = m;
        fall[i] = 0.0F;
        drift[i] = 0.0F;
        streak[i] = 0.0F;
        slid[i] = 0.0F;
        pinned[i] = 0;
        threshold[i] = 7.0F + random.nextFloat() * 7.0F;
        face[i] = faceKey(f.bx(), f.by(), f.bz(), f.dir());
        material[i] = f.material();
        dir[i] = (byte) f.dir().get2DDataValue();
        // Off glass a drop does not hold on: it runs at once.
        running[i] = f.material() != GLASS;
        dead[i] = false;
        light[i] = f.light();
        next[i] = -1;
    }

    // ------------------------------------------------------------------------------------------------------------
    // Ticking
    // ------------------------------------------------------------------------------------------------------------

    public void tick(ClientLevel level, Columns columns, Puddles puddles, RainFx fx, double camX, double camY, double camZ) {
        if (this.level != level) {
            clear();
            this.level = level;
        }
        boolean enabled = ClientConfig.WALL_WATER.get();
        setCapacity(enabled ? ClientConfig.quality().maxWallDrops : 0);
        if (!enabled) {
            return;
        }
        dripTarget = fx;
        dripPuddles = puddles;
        recenter(camX, camZ);
        float rain = ClientWeather.localRain();
        boolean moved = Double.isNaN(scanX) || Math.abs(camX - scanX) > 4.0 || Math.abs(camZ - scanZ) > 4.0;
        if ((rain > 0.0F || count > 0) && (++ticks % SCAN_EVERY == 0 || moved)) {
            scan(level, columns, camX, camY, camZ);
            scanX = camX;
            scanZ = camZ;
            forgetLostDrops();
        }
        if (rain > 0.0F) {
            rainOnFaces();
        }
        for (int i = 0; i < count; i++) {
            if (dead[i]) {
                continue;
            }
            if (material[i] == GLASS) {
                tickGlass(i, rain);
            } else {
                tickWall(i);
            }
        }
        compact();
        rebuildGrid();
    }

    /** The fine rain that is not drawn as drops: it lands on every face the wind drives it at. */
    private void rainOnFaces() {
        float intensity = ClientWeather.localIntensity();
        float wind = (float) (double) ClientConfig.WIND.get();
        float fallSpeed = Math.max(ClientWeather.fallSpeed, 0.05F);
        float slantX = ClientWeather.windX() * wind / fallSpeed;
        float slantZ = ClientWeather.windZ() * wind / fallSpeed;
        float density = (float) (double) ClientConfig.WALL_WATER_DENSITY.get() * Math.min(1.5F, ClientWeather.splash);
        for (Face f : faceList) {
            // Rain driven at the face; a little reaches every side through eddies around the building.
            float into = -(slantX * f.dir().getStepX() + slantZ * f.dir().getStepZ());
            float exposure = 0.12F + 0.88F * Math.clamp(into * 3.0F, 0.0F, 1.0F);
            float area = (f.u1() - f.u0()) * (f.y1() - f.y0());
            float rate = (f.material() == GLASS ? 0.55F : 0.012F) * intensity * exposure * area * density;
            int n = (int) rate;
            if (random.nextFloat() < rate - n) {
                n++;
            }
            for (int k = 0; k < n; k++) {
                double u = f.u0() + random.nextFloat() * (f.u1() - f.u0());
                double wy = f.y0() + random.nextFloat() * (f.y1() - f.y0());
                // Fine rain makes small beads; now and then a bigger drop.
                float m = f.material() == GLASS ? 0.25F + random.nextFloat() * random.nextFloat() * 2.2F : 0.8F + random.nextFloat();
                land(f, u, wy, m);
            }
        }
    }

    private void tickGlass(int i, float rain) {
        float r = radius(mass[i]);
        if (!running[i]) {
            // A bead dries slowly once the rain stops; in the rain the window stays wet.
            if (rain <= 0.05F) {
                mass[i] -= 0.0006F * mass[i] + 0.0004F;
                if (mass[i] <= 0.06F) {
                    dead[i] = true;
                }
            }
            if (mass[i] > threshold[i]) {
                running[i] = true;
                fall[i] = 0.0F;
            }
            return;
        }
        // Heavier drops slide faster; now and then a drop catches on the glass and stops for a moment.
        if (pinned[i] > 0) {
            pinned[i]--;
            fall[i] *= 0.3F;
        } else {
            float target = Math.min(0.055F, 0.0045F * Math.max(0.0F, mass[i] - 3.0F));
            fall[i] += (target - fall[i]) * 0.2F;
            float slow = 1.0F - Math.min(1.0F, fall[i] / 0.04F);
            if (random.nextFloat() < 0.035F * (0.4F + slow)) {
                pinned[i] = 3 + random.nextInt(14);
            }
        }
        float wander = Noise.value(across(i) * 7.0, y[i] * 5.0, SEED_WANDER) - 0.5F;
        drift[i] = drift[i] * 0.8F + wander * 0.006F * Math.min(1.0F, fall[i] * 40.0F);
        move(i, fall[i], drift[i]);
        if (dead[i]) {
            return;
        }
        streak[i] = Math.min(0.35F, streak[i] + fall[i]);
        // A trail of tiny beads is left behind, and the drop gets lighter for it.
        slid[i] += fall[i];
        if (slid[i] > 0.035F + Noise.unit(Float.floatToIntBits(x[i] + z[i]), Float.floatToIntBits(y[i]), SEED_RUN) * 0.05F) {
            slid[i] = 0.0F;
            float left = Math.min(mass[i] * 0.06F, 0.12F + random.nextFloat() * 0.3F);
            // Just behind the drop, clear of it, so the drop does not swallow it again when it stops.
            if (left > 0.08F && spawnBead(i, y[i] + r + radius(left) + 0.006F, left)) {
                mass[i] -= left;
            }
        }
        swallow(i, r);
        if (mass[i] < 2.2F) {
            running[i] = false;
            threshold[i] = mass[i] + 4.0F + random.nextFloat() * 6.0F;
        }
    }

    private void tickWall(int i) {
        // A drop on a wall runs steadily and soaks in: rough stone drinks it within a block or so.
        float soak = material[i] == ROUGH ? 0.035F : 0.012F;
        mass[i] -= soak * (0.6F + mass[i] * 0.4F);
        if (mass[i] <= 0.15F) {
            dead[i] = true;
            return;
        }
        float target = (material[i] == ROUGH ? 0.035F : 0.06F) * (0.7F + mass[i] * 0.2F);
        fall[i] += (target - fall[i]) * 0.3F;
        float wander = Noise.value(across(i) * 5.0, y[i] * 3.0, SEED_WANDER) - 0.5F;
        drift[i] = drift[i] * 0.85F + wander * 0.002F;
        move(i, fall[i], drift[i]);
        streak[i] = Math.min(material[i] == ROUGH ? 0.3F : 0.55F, streak[i] + fall[i]);
    }

    private float across(int i) {
        return dir[i] == Direction.EAST.get2DDataValue() || dir[i] == Direction.WEST.get2DDataValue() ? z[i] : x[i];
    }

    /** Moves a drop down and across its face, onto the next face, off the bottom edge or into the ground. */
    private void move(int i, float down, float sideways) {
        Face f = faces.get(face[i]);
        if (f == null) {
            dead[i] = true;
            return;
        }
        y[i] -= down;
        boolean alongX = f.alongX();
        if (alongX) {
            z[i] += sideways;
        } else {
            x[i] += sideways;
        }
        double u = (alongX ? z[i] + originZ : x[i] + originX);
        if (u < f.u0() || u > f.u1()) {
            int step = u < f.u0() ? -1 : 1;
            Face side = faces.get(faceKey(f.bx() + (alongX ? 0 : step), f.by(), f.bz() + (alongX ? step : 0), f.dir()));
            if (side != null && side.material() == f.material()) {
                face[i] = faceKey(side.bx(), side.by(), side.bz(), side.dir());
                f = side;
            } else {
                double clamped = Math.clamp(u, f.u0() + 0.01, f.u1() - 0.01);
                if (alongX) {
                    z[i] = (float) (clamped - originZ);
                } else {
                    x[i] = (float) (clamped - originX);
                }
                drift[i] = -drift[i] * 0.3F;
            }
        }
        if (y[i] >= f.y0()) {
            return;
        }
        Face below = faces.get(faceKey(f.bx(), f.by() - 1, f.bz(), f.dir()));
        if (below != null && below.y1() >= f.y0() - 0.02F && Math.abs(below.plane() - f.plane()) < 0.01F) {
            face[i] = faceKey(below.bx(), below.by(), below.bz(), below.dir());
            material[i] = below.material();
            light[i] = below.light();
            if (below.material() != GLASS) {
                running[i] = true;
            }
            return;
        }
        dead[i] = true;
        if (!f.ground()) {
            // The wall ends above open air: the drop hangs on the edge and falls.
            pendingDrip(i, f);
        }
    }

    private void pendingDrip(int i, Face f) {
        if (dripTarget == null || level == null || mass[i] < 0.8F || dripTarget.busy(0.9F)) {
            return;
        }
        double wx = x[i] + originX + f.dir().getStepX() * 0.03;
        double wz = z[i] + originZ + f.dir().getStepZ() * 0.03;
        double top = f.y0();
        int bx = Mth.floor(wx);
        int bz = Mth.floor(wz);
        int floor = Mth.floor(top - 0.01);
        BlockState below = null;
        for (int k = 0; k < 32; k++, floor--) {
            pos.set(bx, floor, bz);
            below = level.getBlockState(pos);
            if (!below.isAir()) {
                break;
            }
        }
        if (below == null || below.isAir()) {
            return;
        }
        double groundY;
        byte surface;
        if (below.getFluidState().is(FluidTags.WATER)) {
            groundY = floor + below.getFluidState().getHeight(level, pos);
            surface = RainFx.LAND_WATER;
        } else {
            VoxelShape shape = below.getCollisionShape(level, pos);
            groundY = floor + (shape.isEmpty() ? 0.0 : shape.max(Direction.Axis.Y));
            surface = dripPuddles != null && dripPuddles.coverAt(wx, groundY, wz) > 0.5F ? RainFx.LAND_PUDDLE : RainFx.LAND_GROUND;
        }
        if (groundY >= top) {
            return;
        }
        dripTarget.addDrip(wx, top - 0.02, wz, 0.0F, 0.0F, groundY, surface, 0.7F + Math.min(0.8F, mass[i] * 0.08F), light[i]);
    }

    /** A running drop takes in the beads it touches. */
    private void swallow(int i, float r) {
        Face f = faces.get(face[i]);
        if (f == null) {
            return;
        }
        float reach = r + 0.035F;
        boolean alongX = f.alongX();
        int cu = Mth.floor((alongX ? z[i] : x[i]) / CELL);
        int cy = Mth.floor(y[i] / CELL);
        int plane = planeKey(f);
        for (int du = -1; du <= 1; du++) {
            for (int dy = -1; dy <= 1; dy++) {
                int j = grid.get(cellKey(plane, cu + du, cy + dy));
                while (j >= 0) {
                    if (j != i && !dead[j] && !running[j] && face[j] == face[i]) {
                        float ddx = x[j] - x[i];
                        float ddy = y[j] - y[i];
                        float ddz = z[j] - z[i];
                        float touch = reach + radius(mass[j]) - 0.035F;
                        if (ddx * ddx + ddy * ddy + ddz * ddz < touch * touch) {
                            mass[i] += mass[j];
                            dead[j] = true;
                            // Swallowing a bead is a jolt: the drop breaks free if it was caught.
                            pinned[i] = 0;
                        }
                    }
                    j = next[j];
                }
            }
        }
    }

    /** The closest bead to a point on a face that a drop of radius {@code r} there would touch, or -1. */
    private int nearest(Face f, float px, float py, float pz, float r) {
        boolean alongX = f.alongX();
        int cu = Mth.floor((alongX ? pz : px) / CELL);
        int cy = Mth.floor(py / CELL);
        int plane = planeKey(f);
        long key = faceKey(f.bx(), f.by(), f.bz(), f.dir());
        int best = -1;
        float bestD = Float.MAX_VALUE;
        for (int du = -1; du <= 1; du++) {
            for (int dy = -1; dy <= 1; dy++) {
                int j = grid.get(cellKey(plane, cu + du, cy + dy));
                while (j >= 0) {
                    if (!dead[j] && !running[j] && face[j] == key) {
                        float ddx = x[j] - px;
                        float ddy = y[j] - py;
                        float ddz = z[j] - pz;
                        float d2 = ddx * ddx + ddy * ddy + ddz * ddz;
                        float touch = r + radius(mass[j]);
                        if (d2 < touch * touch && d2 < bestD) {
                            best = j;
                            bestD = d2;
                        }
                    }
                    j = next[j];
                }
            }
        }
        return best;
    }

    private boolean spawnBead(int i, float atY, float m) {
        if (count >= capacity) {
            return false;
        }
        int j = count++;
        x[j] = x[i];
        y[j] = atY;
        z[j] = z[i];
        mass[j] = m;
        fall[j] = 0.0F;
        drift[j] = 0.0F;
        streak[j] = 0.0F;
        slid[j] = 0.0F;
        pinned[j] = 0;
        threshold[j] = 7.0F + random.nextFloat() * 7.0F;
        face[j] = face[i];
        material[j] = material[i];
        dir[j] = dir[i];
        running[j] = false;
        dead[j] = false;
        light[j] = light[i];
        next[j] = -1;
        return true;
    }

    private static int planeKey(Face f) {
        return Mth.floor(f.plane() * 64.0F) * 2 + (f.alongX() ? 1 : 0);
    }

    private static long cellKey(int plane, int cu, int cy) {
        long h = plane * 0x9E3779B97F4A7C15L;
        h ^= (cu * 0xC2B2AE3D27D4EB4FL) + (h << 6) + (h >>> 2);
        h ^= (cy * 0x165667B19E3779F9L) + (h << 6) + (h >>> 2);
        return h;
    }

    private void rebuildGrid() {
        grid.clear();
        for (int i = 0; i < count; i++) {
            next[i] = -1;
            if (dead[i] || running[i]) {
                continue;
            }
            Face f = faces.get(face[i]);
            if (f == null) {
                continue;
            }
            long key = cellKey(planeKey(f), Mth.floor((f.alongX() ? z[i] : x[i]) / CELL), Mth.floor(y[i] / CELL));
            next[i] = grid.get(key);
            grid.put(key, i);
        }
    }

    private void forgetLostDrops() {
        for (int i = 0; i < count; i++) {
            if (!faces.containsKey(face[i])) {
                dead[i] = true;
            }
        }
    }

    private void compact() {
        int w = 0;
        for (int i = 0; i < count; i++) {
            if (dead[i]) {
                continue;
            }
            if (w != i) {
                x[w] = x[i];
                y[w] = y[i];
                z[w] = z[i];
                mass[w] = mass[i];
                fall[w] = fall[i];
                drift[w] = drift[i];
                streak[w] = streak[i];
                slid[w] = slid[i];
                pinned[w] = pinned[i];
                threshold[w] = threshold[i];
                face[w] = face[i];
                material[w] = material[i];
                dir[w] = dir[i];
                running[w] = running[i];
                dead[w] = false;
                light[w] = light[i];
            }
            w++;
        }
        count = w;
    }

    private void recenter(double camX, double camZ) {
        if (Math.abs(camX - originX) < 512 && Math.abs(camZ - originZ) < 512) {
            return;
        }
        float dx = (float) (originX - Math.floor(camX));
        float dz = (float) (originZ - Math.floor(camZ));
        for (int i = 0; i < count; i++) {
            x[i] += dx;
            z[i] += dz;
        }
        originX = Math.floor(camX);
        originZ = Math.floor(camZ);
        rebuildGrid();
    }

    // ------------------------------------------------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------------------------------------------------

    /**
     * Writes every drop as a quad lying on its face, positions relative to the camera, in the particle vertex format
     * for the {@code petrichor_bead} shader: UV -1..1 across a drop; V above 1.5 marks the wet streak behind it.
     */
    public int render(VertexConsumer out, double camX, double camY, double camZ, float partialTick) {
        float ox = (float) (originX - camX);
        float oz = (float) (originZ - camZ);
        int drawn = 0;
        for (int i = 0; i < count; i++) {
            if (dead[i]) {
                continue;
            }
            float cx = x[i] + ox;
            float cy = (float) (y[i] - camY);
            float cz = z[i] + oz;
            if (cx * cx + cy * cy + cz * cz > RENDER_RANGE * RENDER_RANGE) {
                continue;
            }
            // Smooth motion between ticks.
            cy += fall[i] * (1.0F - partialTick);
            Direction d = Direction.from2DDataValue(dir[i]);
            float nx = d.getStepX();
            float nz = d.getStepZ();
            cx += nx * LIFT;
            cz += nz * LIFT;
            // Across the face: for a face along x that is z, for a face along z it is x.
            float ax = nx == 0 ? 1.0F : 0.0F;
            float az = nx == 0 ? 0.0F : 1.0F;
            float r = radius(mass[i]);
            float alpha = Math.min(1.0F, mass[i] * 4.0F);
            if (running[i]) {
                // A running drop is drawn out along its way, its wet streak above it.
                float stretch = 1.0F + Math.min(1.1F, fall[i] * 28.0F);
                float half = r * stretch;
                quad(out, cx, cy + half - r, cz, ax, az, r, half, -1.0F, 1.0F, -1.0F, 1.0F, alpha, light[i]);
                float tail = streak[i];
                if (tail > 0.02F) {
                    float w = material[i] == GLASS ? r * 0.45F : r * 0.85F;
                    float a = material[i] == GLASS ? 0.35F : 0.55F;
                    quad(out, cx, cy + r * 0.5F + tail * 0.5F, cz, ax, az, w, tail * 0.5F, -1.0F, 1.0F, 3.0F, 2.0F, a, light[i]);
                }
            } else {
                quad(out, cx, cy, cz, ax, az, r, r, -1.0F, 1.0F, -1.0F, 1.0F, alpha, light[i]);
            }
            drawn++;
        }
        return drawn;
    }

    /** A quad centred on the point, {@code halfU} across the face and {@code halfV} up it. */
    private static void quad(VertexConsumer out, float cx, float cy, float cz, float ax, float az, float halfU, float halfV, float uMin,
            float uMax, float vMin, float vMax, float a, int packedLight) {
        float ux = ax * halfU;
        float uz = az * halfU;
        out.addVertex(cx - ux, cy - halfV, cz - uz).setUv(uMin, vMin).setColor(1.0F, 1.0F, 1.0F, a).setLight(packedLight);
        out.addVertex(cx + ux, cy - halfV, cz + uz).setUv(uMax, vMin).setColor(1.0F, 1.0F, 1.0F, a).setLight(packedLight);
        out.addVertex(cx + ux, cy + halfV, cz + uz).setUv(uMax, vMax).setColor(1.0F, 1.0F, 1.0F, a).setLight(packedLight);
        out.addVertex(cx - ux, cy + halfV, cz - uz).setUv(uMin, vMax).setColor(1.0F, 1.0F, 1.0F, a).setLight(packedLight);
    }
}
