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
 * Rain on windows. Drops stay where they land as beads; beads grow as more drops hit them, until one is heavy enough to
 * break free. It slides down in jerks, stopping and starting, wandering a little to the side, swallowing the beads in
 * its way (and getting faster for it), and leaves a trail of tiny beads behind. At the bottom of the window it drips
 * off when there is open air below. When the rain stops, the glass slowly dries.
 *
 * <p>Drops live on the faces of glass blocks and panes that rain can reach, landing at a rate set by how hard it rains
 * and how much the wind drives the rain at the face (a little reaches every side).
 */
public final class WindowRain {
    private static final int SCAN_RADIUS = 14;
    private static final int SCAN_EVERY = 20;
    private static final int MAX_FACES = 4096;
    private static final float CELL = 1.0F / 16.0F;
    /** Offset of the drops from the face, against z-fighting. */
    private static final float LIFT = 0.004F;
    private static final int SEED_RUN = 0x0D22_0001;
    private static final int SEED_WANDER = 0x0D22_0002;
    private static final double RENDER_RANGE = 26.0;
    /** Pixels per block of the grid drops are drawn on, the size of a block texture's pixel. */
    private static final int PIXELS = 16;

    /**
     * A face of a block where rain can land: the face plane, its extent and what it is made of.
     *
     * @param plane coordinate of the face plane along the face's axis
     * @param u0    extent across the face (z for faces along x, x for faces along z)
     * @param y0    lowest point the rain reaches (the ground or a roof in front may hide the lower part)
     * @param ground whether the ground in front meets the face at its bottom (otherwise the face ends above air)
     */
    private record Face(int bx, int by, int bz, Direction dir, float plane, float u0, float u1, float y0, float y1, int light,
            boolean ground) {
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
    private byte[] dir = new byte[0];
    private boolean[] running = new boolean[0];
    private boolean[] dead = new boolean[0];
    private int[] light = new int[0];

    // Beads by grid cell in their face plane, rebuilt every tick: heads of linked lists through next[].
    private final Long2IntOpenHashMap grid = new Long2IntOpenHashMap();
    private int[] next = new int[0];

    public WindowRain() {
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

    /**
     * Finds the glass faces rain can reach around the camera: in each column, the part of each side that rises above
     * the column next to it, where rain falls (a roof or eaves in front keep it dry).
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
        if (state.isAir() || state.getFluidState().is(FluidTags.WATER) || SoundMaterial.of(state) != SoundMaterial.GLASS) {
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
        // Only panes wide and tall enough to hold drops.
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
        faces.put(faceKey(bx, by, bz, direction), new Face(bx, by, bz, direction, base + plane, uBase + u0, uBase + u1, low, by + y1, packedLight,
                ground));
    }

    // ------------------------------------------------------------------------------------------------------------
    // Impacts
    // ------------------------------------------------------------------------------------------------------------

    private void land(Face f, double u, double wy, float m) {
        double wx = f.alongX() ? f.plane() : u;
        double wz = f.alongX() ? u : f.plane();
        // A new drop joins a bead it touches.
        int other = nearest(f, (float) (wx - originX), (float) wy, (float) (wz - originZ), radius(m));
        if (other >= 0) {
            mass[other] += m;
            return;
        }
        if (count >= capacity) {
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
        dir[i] = (byte) f.dir().get2DDataValue();
        running[i] = false;
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
        boolean enabled = ClientConfig.WINDOW_RAIN.get();
        setCapacity(enabled ? ClientConfig.quality().maxWindowDrops : 0);
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
            tick(i, rain);
        }
        compact();
        rebuildGrid();
    }

    /** Rain lands on every window, most on those the wind drives it at. */
    private void rainOnFaces() {
        float intensity = ClientWeather.localIntensity();
        float wind = (float) (double) ClientConfig.WIND.get();
        float fallSpeed = Math.max(ClientWeather.fallSpeed, 0.05F);
        float slantX = ClientWeather.windX() * wind / fallSpeed;
        float slantZ = ClientWeather.windZ() * wind / fallSpeed;
        float density = (float) (double) ClientConfig.WINDOW_RAIN_DENSITY.get() * Math.min(1.5F, ClientWeather.splash);
        for (Face f : faceList) {
            // Rain driven at the face; a little reaches every side through eddies around the building.
            float into = -(slantX * f.dir().getStepX() + slantZ * f.dir().getStepZ());
            float exposure = 0.3F + 0.7F * Math.clamp(into * 3.0F, 0.0F, 1.0F);
            float area = (f.u1() - f.u0()) * (f.y1() - f.y0());
            float rate = 0.6F * intensity * exposure * area * density;
            int n = (int) rate;
            if (random.nextFloat() < rate - n) {
                n++;
            }
            for (int k = 0; k < n; k++) {
                double u = f.u0() + random.nextFloat() * (f.u1() - f.u0());
                double wy = f.y0() + random.nextFloat() * (f.y1() - f.y0());
                // Fine rain makes small beads; now and then a bigger drop.
                float m = 0.25F + random.nextFloat() * random.nextFloat() * 2.2F;
                land(f, u, wy, m);
            }
        }
    }

    private void tick(int i, float rain) {
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
            if (side != null) {
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
            light[i] = below.light();
            return;
        }
        dead[i] = true;
        if (!f.ground()) {
            // The window ends above open air: the drop hangs on the edge and falls.
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
     * Writes every drop as pixel art lying on its window, positions relative to the camera, in the particle vertex
     * format for the {@code petrichor_bead} shader. Drops are made of whole pixels of the block texture grid (1/16 of a
     * block) and move from pixel to pixel; the colour's red and green carry the drop's width and height in pixels
     * (sixteenths), UV -1..1 covers a drop and V 2..3 the trail a running drop leaves.
     */
    public int render(VertexConsumer out, double camX, double camY, double camZ, float partialTick) {
        int drawn = 0;
        for (int i = 0; i < count; i++) {
            if (dead[i]) {
                continue;
            }
            double wx = x[i] + originX;
            double wy = y[i] + fall[i] * (1.0F - partialTick);
            double wz = z[i] + originZ;
            double dx = wx - camX;
            double dy = wy - camY;
            double dz = wz - camZ;
            if (dx * dx + dy * dy + dz * dz > RENDER_RANGE * RENDER_RANGE) {
                continue;
            }
            Direction d = Direction.from2DDataValue(dir[i]);
            boolean alongX = d.getAxis() == Direction.Axis.X;
            float plane = (float) ((alongX ? wx - camX : wz - camZ) + (alongX ? d.getStepX() : d.getStepZ()) * LIFT);
            double across = alongX ? wz : wx;
            double acrossCam = alongX ? camZ : camX;
            int width = pixels(mass[i]);
            int height = width;
            if (running[i]) {
                width = Math.max(2, width);
                height = width + 1;
            }
            // Snapped to the pixel grid: the drop's centre picks the pixels it covers.
            double left = Math.floor(across * PIXELS - width * 0.5 + 0.5) / PIXELS;
            double bottom = Math.floor(wy * PIXELS - (running[i] ? 0.5 : height * 0.5 - 0.5)) / PIXELS;
            float alpha = Math.min(1.0F, mass[i] * 4.0F);
            float u0 = (float) (left - acrossCam);
            float y0 = (float) (bottom - camY);
            quad(out, alongX, plane, u0, y0, width, height, -1.0F, 1.0F, -1.0F, 1.0F, alpha, light[i]);
            int trail = Math.min(6, (int) (streak[i] * PIXELS));
            if (running[i] && trail > 0) {
                // A one-pixel column of water above the drop.
                float column = u0 + (width / 2) / (float) PIXELS;
                quad(out, alongX, plane, column, y0 + height / (float) PIXELS, 1, trail, -1.0F, 1.0F, 3.0F, 2.0F, 0.35F, light[i]);
            }
            drawn++;
        }
        return drawn;
    }

    /** Size of a drop in pixels: a single pixel bead up to a fat drop four pixels wide. */
    private static int pixels(float m) {
        return m < 1.2F ? 1 : m < 5.0F ? 2 : m < 11.0F ? 3 : 4;
    }

    /**
     * A quad lying in the face plane: {@code width} x {@code height} pixels from the lower left corner ({@code u0}
     * across the face, {@code y0} up), relative to the camera.
     */
    private static void quad(VertexConsumer out, boolean alongX, float plane, float u0, float y0, int width, int height, float uMin,
            float uMax, float vMin, float vMax, float a, int packedLight) {
        float u1 = u0 + width / (float) PIXELS;
        float y1 = y0 + height / (float) PIXELS;
        float r = width / (float) PIXELS;
        float g = height / (float) PIXELS;
        corner(out, alongX, plane, u0, y0, uMin, vMin, r, g, a, packedLight);
        corner(out, alongX, plane, u1, y0, uMax, vMin, r, g, a, packedLight);
        corner(out, alongX, plane, u1, y1, uMax, vMax, r, g, a, packedLight);
        corner(out, alongX, plane, u0, y1, uMin, vMax, r, g, a, packedLight);
    }

    private static void corner(VertexConsumer out, boolean alongX, float plane, float u, float y, float tu, float tv, float r, float g,
            float a, int packedLight) {
        out.addVertex(alongX ? plane : u, y, alongX ? u : plane).setUv(tu, tv).setColor(r, g, 0.0F, a).setLight(packedLight);
    }
}
