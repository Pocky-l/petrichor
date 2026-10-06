package com.pockyl.petrichor.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import org.joml.Vector3f;

import com.pockyl.petrichor.client.sound.RainSounds;

import java.util.Arrays;

/**
 * A fixed pool of small rain effects - splashes, ripples, droplets, falling drips, spray and sparks - simulated per
 * tick and drawn in the same batch as the rain. Kept separate from vanilla particles so the budget is under our control
 * and nothing has to be registered (the client works on any server).
 */
public final class RainFx {
    public static final byte SPLASH = 0;
    public static final byte RIPPLE = 1;
    public static final byte DROPLET = 2;
    public static final byte DRIP = 3;
    public static final byte MIST = 4;
    public static final byte SPARK = 5;
    /** A splash on a wall: a crown standing out of the face, its normal kept in the velocity fields. */
    public static final byte WALL_SPLASH = 6;

    /** What a falling drip hits. */
    public static final byte LAND_GROUND = 0;
    public static final byte LAND_WATER = 1;
    public static final byte LAND_PUDDLE = 2;

    private static final float DRIP_GRAVITY = 0.05F;
    private static final float DROPLET_GRAVITY = 0.035F;
    private static final int FULL_BRIGHT = 0xF000F0;

    private final RandomSource random = RandomSource.create();
    private int capacity;
    private byte[] kind = new byte[0];
    private float[] x = new float[0];
    private float[] y = new float[0];
    private float[] z = new float[0];
    private float[] px = new float[0];
    private float[] py = new float[0];
    private float[] pz = new float[0];
    private float[] vx = new float[0];
    private float[] vy = new float[0];
    private float[] vz = new float[0];
    private float[] size = new float[0];
    private float[] alpha = new float[0];
    private float[] ground = new float[0];
    private int[] age = new int[0];
    private int[] life = new int[0];
    private int[] light = new int[0];
    private byte[] landing = new byte[0];
    private int count;
    private double originX;
    private double originZ;
    private static final int MAX_BEADS = 768;
    private final float[] beadX = new float[MAX_BEADS];
    private final float[] beadY = new float[MAX_BEADS];
    private final float[] beadZ = new float[MAX_BEADS];
    private final float[] beadSize = new float[MAX_BEADS];
    private final int[] beadLight = new int[MAX_BEADS];
    private int beads;

    /** Positions are stored relative to an origin near the camera, which moves only when the camera gets far from it. */
    public void setCapacity(int capacity) {
        if (capacity == this.capacity) {
            return;
        }
        this.capacity = capacity;
        count = Math.min(count, capacity);
        kind = Arrays.copyOf(kind, capacity);
        x = Arrays.copyOf(x, capacity);
        y = Arrays.copyOf(y, capacity);
        z = Arrays.copyOf(z, capacity);
        px = Arrays.copyOf(px, capacity);
        py = Arrays.copyOf(py, capacity);
        pz = Arrays.copyOf(pz, capacity);
        vx = Arrays.copyOf(vx, capacity);
        vy = Arrays.copyOf(vy, capacity);
        vz = Arrays.copyOf(vz, capacity);
        size = Arrays.copyOf(size, capacity);
        alpha = Arrays.copyOf(alpha, capacity);
        ground = Arrays.copyOf(ground, capacity);
        age = Arrays.copyOf(age, capacity);
        life = Arrays.copyOf(life, capacity);
        light = Arrays.copyOf(light, capacity);
        landing = Arrays.copyOf(landing, capacity);
    }

    public void clear() {
        count = 0;
        beads = 0;
    }

    public void clearBeads() {
        beads = 0;
    }

    /** A drop hanging from an edge for this tick. */
    public void addBead(double wx, double wy, double wz, float radius, int packedLight) {
        if (beads >= MAX_BEADS) {
            return;
        }
        beadX[beads] = (float) (wx - originX);
        beadY[beads] = (float) wy;
        beadZ[beads] = (float) (wz - originZ);
        beadSize[beads] = radius;
        beadLight[beads] = packedLight;
        beads++;
    }

    public int count() {
        return count;
    }

    public boolean full() {
        return count >= capacity;
    }

    /** Whether more than {@code share} of the pool is in use; keeps one kind of effect from starving the others. */
    public boolean busy(float share) {
        return count >= capacity * share;
    }

    /** Keeps the float positions precise: re-bases them when the camera wanders more than 512 blocks away. */
    public void recenter(double camX, double camZ) {
        if (Math.abs(camX - originX) < 512 && Math.abs(camZ - originZ) < 512) {
            return;
        }
        float dx = (float) (originX - Math.floor(camX));
        float dz = (float) (originZ - Math.floor(camZ));
        for (int i = 0; i < beads; i++) {
            beadX[i] += dx;
            beadZ[i] += dz;
        }
        for (int i = 0; i < count; i++) {
            x[i] += dx;
            px[i] += dx;
            z[i] += dz;
            pz[i] += dz;
        }
        originX = Math.floor(camX);
        originZ = Math.floor(camZ);
    }

    public int add(byte type, double wx, double wy, double wz, float velX, float velY, float velZ, float scale, float opacity,
            int lifetime, int packedLight) {
        if (count >= capacity) {
            return -1;
        }
        int i = count++;
        kind[i] = type;
        x[i] = px[i] = (float) (wx - originX);
        y[i] = py[i] = (float) wy;
        z[i] = pz[i] = (float) (wz - originZ);
        vx[i] = velX;
        vy[i] = velY;
        vz[i] = velZ;
        size[i] = scale;
        alpha[i] = opacity;
        ground[i] = Float.NEGATIVE_INFINITY;
        age[i] = 0;
        life[i] = lifetime;
        light[i] = packedLight;
        landing[i] = LAND_GROUND;
        return i;
    }

    /** A falling drop that splashes when it reaches {@code groundY}. */
    public void addDrip(double wx, double wy, double wz, float velX, float velZ, double groundY, byte surface, float scale, int packedLight) {
        int i = add(DRIP, wx, wy, wz, velX, -0.01F, velZ, scale, 0.8F, 200, packedLight);
        if (i >= 0) {
            ground[i] = (float) groundY;
            landing[i] = surface;
        }
    }

    /** A splash crown with a few droplets, plus rings when it lands on water. */
    public void splash(double wx, double wy, double wz, float scale, byte surface, int packedLight, int droplets) {
        if (surface != LAND_WATER) {
            add(SPLASH, wx, wy, wz, 0.0F, 0.0F, 0.0F, scale * (0.6F + random.nextFloat() * 0.4F), 0.28F, 4 + random.nextInt(2), packedLight);
        }
        if (surface == LAND_WATER) {
            ripple(wx, wy, wz, scale, packedLight);
        }
        for (int d = 0; d < droplets; d++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            float speed = (0.02F + random.nextFloat() * 0.05F) * scale;
            int i = add(DROPLET, wx, wy + 0.02, wz, Mth.cos(angle) * speed, (0.06F + random.nextFloat() * 0.08F) * scale,
                    Mth.sin(angle) * speed, 0.7F + random.nextFloat() * 0.5F, 0.6F, 16, packedLight);
            if (i >= 0) {
                ground[i] = (float) wy;
            }
        }
    }

    /**
     * A drop hitting a wall at a slant: a flattened crown thrown out of the face, and droplets bouncing off - the
     * part of the drop's speed along the wall carries on, the part into the wall comes back weakened.
     *
     * @param nx      outward normal of the face
     * @param groundY where the droplets fall to
     */
    public void wallSplash(double wx, double wy, double wz, float nx, float nz, float velX, float velY, float velZ, float scale, int packedLight,
            int droplets, double groundY) {
        int crown = add(WALL_SPLASH, wx + nx * 0.01, wy, wz + nz * 0.01, nx, 0.0F, nz, scale * (0.5F + random.nextFloat() * 0.35F), 0.26F,
                3 + random.nextInt(2), packedLight);
        if (crown < 0) {
            return;
        }
        float into = velX * nx + velZ * nz;
        float alongX = velX - into * nx;
        float alongZ = velZ - into * nz;
        for (int d = 0; d < droplets; d++) {
            float bounce = -into * (0.25F + random.nextFloat() * 0.3F) + 0.02F + random.nextFloat() * 0.03F;
            float spread = 0.35F + random.nextFloat() * 0.3F;
            float sideways = (random.nextFloat() - 0.5F) * 0.08F * scale;
            int i = add(DROPLET, wx + nx * 0.03, wy, wz + nz * 0.03,
                    nx * bounce + alongX * spread - nz * sideways, (0.02F + random.nextFloat() * 0.05F) * scale + velY * 0.1F,
                    nz * bounce + alongZ * spread + nx * sideways, 0.55F + random.nextFloat() * 0.45F, 0.6F, 14, packedLight);
            if (i >= 0) {
                ground[i] = (float) groundY;
            }
        }
    }

    public void ripple(double wx, double wy, double wz, float scale, int packedLight) {
        add(RIPPLE, wx, wy, wz, 0.0F, 0.0F, 0.0F, scale * (0.7F + random.nextFloat() * 0.6F), 0.5F, 10 + random.nextInt(5), packedLight);
    }

    public void sparks(double wx, double wy, double wz, int amount) {
        for (int s = 0; s < amount; s++) {
            float angle = random.nextFloat() * Mth.TWO_PI;
            float speed = 0.08F + random.nextFloat() * 0.25F;
            int i = add(SPARK, wx, wy + 0.1, wz, Mth.cos(angle) * speed, 0.1F + random.nextFloat() * 0.35F, Mth.sin(angle) * speed,
                    0.6F + random.nextFloat() * 0.8F, 1.0F, 8 + random.nextInt(10), FULL_BRIGHT);
            if (i >= 0) {
                ground[i] = (float) wy;
            }
        }
    }

    public void tick(float windX, float windZ, double camX, double camY, double camZ) {
        for (int i = 0; i < count; i++) {
            px[i] = x[i];
            py[i] = y[i];
            pz[i] = z[i];
            age[i]++;
            boolean dead = age[i] >= life[i];
            switch (kind[i]) {
                case DRIP -> {
                    vy[i] = Math.max(vy[i] - DRIP_GRAVITY, -1.6F);
                    vx[i] = vx[i] * 0.96F + windX * 0.04F;
                    vz[i] = vz[i] * 0.96F + windZ * 0.04F;
                    x[i] += vx[i];
                    y[i] += vy[i];
                    z[i] += vz[i];
                    if (y[i] <= ground[i]) {
                        y[i] = ground[i];
                        land(i, camX, camY, camZ);
                        dead = true;
                    }
                }
                case DROPLET, SPARK -> {
                    vy[i] -= kind[i] == SPARK ? 0.04F : DROPLET_GRAVITY;
                    vx[i] *= 0.95F;
                    vz[i] *= 0.95F;
                    x[i] += vx[i];
                    y[i] += vy[i];
                    z[i] += vz[i];
                    if (vy[i] < 0.0F && y[i] <= ground[i]) {
                        dead = true;
                    }
                }
                case MIST -> {
                    x[i] += vx[i] + windX * 0.7F;
                    y[i] += vy[i];
                    z[i] += vz[i] + windZ * 0.7F;
                }
                default -> {
                }
            }
            if (dead) {
                remove(i);
                i--;
            }
        }
    }

    private void land(int i, double camX, double camY, double camZ) {
        double wx = x[i] + originX;
        double wz = z[i] + originZ;
        float scale = size[i];
        int droplets = 1 + random.nextInt(3);
        // Landed effects append to the pool; the swap-remove of the drip itself happens after.
        splash(wx, y[i], wz, scale * 0.9F, landing[i], light[i], droplets);
        double dx = wx - camX;
        double dy = y[i] - camY;
        double dz = wz - camZ;
        RainSounds.drip(wx, y[i], wz, dx * dx + dy * dy + dz * dz, landing[i]);
    }

    private void remove(int i) {
        int last = --count;
        if (i == last) {
            return;
        }
        kind[i] = kind[last];
        x[i] = x[last];
        y[i] = y[last];
        z[i] = z[last];
        px[i] = px[last];
        py[i] = py[last];
        pz[i] = pz[last];
        vx[i] = vx[last];
        vy[i] = vy[last];
        vz[i] = vz[last];
        size[i] = size[last];
        alpha[i] = alpha[last];
        ground[i] = ground[last];
        age[i] = age[last];
        life[i] = life[last];
        light[i] = light[last];
        landing[i] = landing[last];
    }

    /**
     * Writes every effect as quads in the particle vertex format, positions relative to the camera.
     *
     * @param out  splashes, ripples, spray: normal blending
     * @param glow falling and hanging drops and sparks: additive blending, they glint like the rain
     * @param left camera left vector
     * @param up   camera up vector
     */
    public void render(VertexConsumer out, VertexConsumer glow, double camX, double camY, double camZ, float partialTick, Vector3f left,
            Vector3f up) {
        float ox = (float) (originX - camX);
        float oz = (float) (originZ - camZ);
        for (int i = 0; i < count; i++) {
            float t = (age[i] + partialTick) / life[i];
            float cx = Mth.lerp(partialTick, px[i], x[i]) + ox;
            float cy = (float) (Mth.lerp(partialTick, py[i], y[i]) - camY);
            float cz = Mth.lerp(partialTick, pz[i], z[i]) + oz;
            switch (kind[i]) {
                case SPLASH -> {
                    int frame = Math.min(FxAtlas.SPLASH_FRAMES - 1, (int) (t * FxAtlas.SPLASH_FRAMES));
                    float a = alpha[i] * (1.0F - t * 0.6F);
                    upright(out, cx, cy, cz, 0.11F * size[i], 0.17F * size[i], FxAtlas.SPLASH + frame, a, light[i]);
                }
                case WALL_SPLASH -> {
                    int frame = Math.min(FxAtlas.SPLASH_FRAMES - 1, (int) (t * FxAtlas.SPLASH_FRAMES));
                    float a = alpha[i] * (1.0F - t * 0.6F);
                    crown(out, cx, cy, cz, vx[i], vz[i], 0.1F * size[i], 0.09F * size[i], FxAtlas.SPLASH + frame, a, light[i]);
                }
                case RIPPLE -> {
                    float r = (0.06F + t * 0.38F) * size[i];
                    float a = alpha[i] * (1.0F - t) * (1.0F - t);
                    flat(out, cx, cy + 0.012F, cz, r, FxAtlas.RIPPLE, a, light[i]);
                }
                case DROPLET -> billboard(glow, cx, cy, cz, 0.016F * size[i], FxAtlas.DROPLET, alpha[i] * 0.7F, light[i], left, up);
                case SPARK -> billboard(glow, cx, cy, cz, 0.04F * size[i] * (1.0F - t * 0.7F), FxAtlas.SPARK, alpha[i] * (1.0F - t * t),
                        light[i], left, up);
                case MIST -> {
                    float a = alpha[i] * Mth.sin(t * Mth.PI);
                    billboard(out, cx, cy, cz, size[i] * (0.7F + t * 0.6F), FxAtlas.MIST, a, light[i], left, up);
                }
                case DRIP -> {
                    // A falling drop: round while slow, then drawn out by its speed, with a bright head.
                    float speed = -Mth.lerp(partialTick, vy[i] + DRIP_GRAVITY, vy[i]);
                    float len = Math.max(0.07F, speed * 1.5F);
                    float width = 0.05F * size[i] * (1.0F - Math.min(0.35F, speed * 0.4F));
                    Streaks.streak(glow, cx, cy, cz, vx[i], -Math.max(speed, 0.05F), vz[i], len, width, FxAtlas.STREAK, alpha[i] * 0.85F,
                            alpha[i] * 0.85F, light[i], 0.82F, 0.86F, 0.92F);
                }
                default -> {
                }
            }
        }
        renderBeads(glow, ox, oz, camY, left, up);
    }

    private void renderBeads(VertexConsumer glow, float ox, float oz, double camY, Vector3f left, Vector3f up) {
        for (int b = 0; b < beads; b++) {
            float cx = beadX[b] + ox;
            float cy = (float) (beadY[b] - camY);
            float cz = beadZ[b] + oz;
            billboard(glow, cx, cy, cz, beadSize[b], FxAtlas.DROPLET, 0.55F, beadLight[b], left, up);
            // The glint on the drop.
            billboard(glow, cx, cy + beadSize[b] * 0.3F, cz, beadSize[b] * 0.35F, FxAtlas.SPARK, 0.5F, beadLight[b], left, up);
        }
    }

    private static void billboard(VertexConsumer out, float cx, float cy, float cz, float half, int tile, float a, int packedLight,
            Vector3f left, Vector3f up) {
        float lx = left.x() * half;
        float ly = left.y() * half;
        float lz = left.z() * half;
        float ux = up.x() * half;
        float uy = up.y() * half;
        float uz = up.z() * half;
        float u0 = FxAtlas.u0(tile);
        float v0 = FxAtlas.v0(tile);
        float u1 = FxAtlas.u1(tile);
        float v1 = FxAtlas.v1(tile);
        vertex(out, cx + lx - ux, cy + ly - uy, cz + lz - uz, u0, v1, a, packedLight);
        vertex(out, cx - lx - ux, cy - ly - uy, cz - lz - uz, u1, v1, a, packedLight);
        vertex(out, cx - lx + ux, cy - ly + uy, cz - lz + uz, u1, v0, a, packedLight);
        vertex(out, cx + lx + ux, cy + ly + uy, cz + lz + uz, u0, v0, a, packedLight);
    }

    /** A quad standing on the ground and turned around the vertical axis to face the camera. */
    private static void upright(VertexConsumer out, float cx, float cy, float cz, float half, float height, int tile, float a, int packedLight) {
        float len = Mth.sqrt(cx * cx + cz * cz);
        if (len < 1.0E-4F) {
            return;
        }
        float rx = -cz / len * half;
        float rz = cx / len * half;
        float u0 = FxAtlas.u0(tile);
        float v0 = FxAtlas.v0(tile);
        float u1 = FxAtlas.u1(tile);
        float v1 = FxAtlas.v1(tile);
        vertex(out, cx - rx, cy, cz - rz, u0, v1, a, packedLight);
        vertex(out, cx + rx, cy, cz + rz, u1, v1, a, packedLight);
        vertex(out, cx + rx, cy + height, cz + rz, u1, v0, a, packedLight);
        vertex(out, cx - rx, cy + height, cz - rz, u0, v0, a, packedLight);
    }

    /** A splash crown whose base sits on a wall and which stands out along the wall's normal, turned to the camera. */
    private static void crown(VertexConsumer out, float cx, float cy, float cz, float nx, float nz, float half, float height, int tile,
            float a, int packedLight) {
        // Side vector: across the normal and the line of sight.
        float sx = -nz * cy;
        float sy = nz * cx - nx * cz;
        float sz = nx * cy;
        float len = Mth.sqrt(sx * sx + sy * sy + sz * sz);
        if (len < 1.0E-4F) {
            return;
        }
        sx = sx / len * half;
        sy = sy / len * half;
        sz = sz / len * half;
        float tx = nx * height;
        float tz = nz * height;
        float u0 = FxAtlas.u0(tile);
        float v0 = FxAtlas.v0(tile);
        float u1 = FxAtlas.u1(tile);
        float v1 = FxAtlas.v1(tile);
        vertex(out, cx - sx, cy - sy, cz - sz, u0, v1, a, packedLight);
        vertex(out, cx + sx, cy + sy, cz + sz, u1, v1, a, packedLight);
        vertex(out, cx + sx + tx, cy + sy, cz + sz + tz, u1, v0, a, packedLight);
        vertex(out, cx - sx + tx, cy - sy, cz - sz + tz, u0, v0, a, packedLight);
    }

    private static void flat(VertexConsumer out, float cx, float cy, float cz, float half, int tile, float a, int packedLight) {
        float u0 = FxAtlas.u0(tile);
        float v0 = FxAtlas.v0(tile);
        float u1 = FxAtlas.u1(tile);
        float v1 = FxAtlas.v1(tile);
        vertex(out, cx - half, cy, cz - half, u0, v0, a, packedLight);
        vertex(out, cx - half, cy, cz + half, u0, v1, a, packedLight);
        vertex(out, cx + half, cy, cz + half, u1, v1, a, packedLight);
        vertex(out, cx + half, cy, cz - half, u1, v0, a, packedLight);
    }

    private static void vertex(VertexConsumer out, float vx, float vy, float vz, float u, float v, float a, int packedLight) {
        out.addVertex(vx, vy, vz).setUv(u, v).setColor(0.7F, 0.76F, 0.84F, a).setLight(packedLight);
    }
}
