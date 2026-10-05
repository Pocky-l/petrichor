package com.pockyl.petrichor.client.lightning;

import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import java.util.Arrays;

/**
 * The channel of one lightning discharge as line segments relative to the strike point.
 *
 * <p>The main channel zig-zags from the cloud to the ground (random kinks, pulled towards the target), branches fork off
 * it and fork again, getting thinner, dimmer and shorter. Every point also stores when the leader reaches it (0 at the
 * cloud, 1 at the ground), which lets the renderer grow the bolt downwards with all its branches before the return
 * stroke lights the main channel. Restrokes reuse the same channel, as real ones do.
 */
public final class BoltShape {
    /** Per segment: x0 y0 z0 x1 y1 z1 width brightness arrive0 arrive1 main(1/0). */
    public static final int STRIDE = 11;
    private static final int MAX_DEPTH = 3;

    private float[] data = new float[STRIDE * 128];
    private int count;
    private final RandomSource random;
    private float totalLength;

    private BoltShape(long seed) {
        random = RandomSource.create(seed);
    }

    public int count() {
        return count;
    }

    public float[] data() {
        return data;
    }

    /**
     * A cloud-to-ground bolt from {@code (topX, topY, topZ)} to the origin.
     */
    public static BoltShape groundStrike(long seed, float topX, float topY, float topZ) {
        BoltShape shape = new BoltShape(seed);
        shape.main(topX, topY, topZ, 0.0F, 0.0F, 0.0F, false);
        return shape;
    }

    /** A discharge running inside and between clouds, from the origin to {@code (endX, endY, endZ)}. */
    public static BoltShape cloudDischarge(long seed, float endX, float endY, float endZ) {
        BoltShape shape = new BoltShape(seed);
        shape.main(0.0F, 0.0F, 0.0F, endX, endY, endZ, true);
        return shape;
    }

    private void main(float sx, float sy, float sz, float tx, float ty, float tz, boolean horizontal) {
        float dx = tx - sx;
        float dy = ty - sy;
        float dz = tz - sz;
        totalLength = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        float step = Math.max(1.5F, totalLength / 60.0F);
        float x = sx;
        float y = sy;
        float z = sz;
        float travelled = 0.0F;
        int guard = 0;
        while (guard++ < 400) {
            float rx = tx - x;
            float ry = ty - y;
            float rz = tz - z;
            float remaining = Mth.sqrt(rx * rx + ry * ry + rz * rz);
            float len = step * (0.6F + random.nextFloat() * 1.0F);
            float nx;
            float ny;
            float nz;
            if (remaining <= len * 1.3F) {
                nx = tx;
                ny = ty;
                nz = tz;
            } else {
                // Pull towards the target plus a random kink; now and then a sharp one.
                float kink = random.nextFloat() < 0.12F ? 1.6F : 0.75F;
                float ax = rx / remaining + (float) random.nextGaussian() * kink * 0.6F;
                float ay = ry / remaining + (float) random.nextGaussian() * kink * (horizontal ? 0.25F : 0.3F);
                float az = rz / remaining + (float) random.nextGaussian() * kink * 0.6F;
                if (!horizontal) {
                    ay = Math.min(ay, -0.35F);
                }
                float al = Mth.sqrt(ax * ax + ay * ay + az * az);
                nx = x + ax / al * len;
                ny = y + ay / al * len;
                nz = z + az / al * len;
            }
            float segment = Mth.sqrt((nx - x) * (nx - x) + (ny - y) * (ny - y) + (nz - z) * (nz - z));
            float a0 = travelled / totalLength;
            travelled += segment;
            float a1 = Math.min(1.0F, travelled / totalLength);
            add(x, y, z, nx, ny, nz, 1.0F, 1.0F, a0, a1, true);
            float progress = a1;
            if (random.nextFloat() < 0.24F * (1.0F - progress * 0.75F)) {
                branch(nx, ny, nz, (nx - x) / segment, (ny - y) / segment, (nz - z) / segment, 1, a1,
                        totalLength * (0.06F + random.nextFloat() * 0.22F), horizontal);
            }
            x = nx;
            y = ny;
            z = nz;
            if (x == tx && y == ty && z == tz) {
                break;
            }
        }
    }

    private void branch(float x, float y, float z, float dirX, float dirY, float dirZ, int depth, float arrive, float budget,
            boolean horizontal) {
        // Fork away from the parent: sideways, and for ground strikes still downwards.
        float angle = random.nextFloat() * Mth.TWO_PI;
        float spread = 0.6F + random.nextFloat() * 0.6F;
        float bx = dirX + Mth.cos(angle) * spread;
        float by = horizontal ? dirY + (random.nextFloat() - 0.5F) * 0.6F : Math.min(dirY, -0.2F) - random.nextFloat() * 0.3F;
        float bz = dirZ + Mth.sin(angle) * spread;
        float bl = Mth.sqrt(bx * bx + by * by + bz * bz);
        bx /= bl;
        by /= bl;
        bz /= bl;
        float width = (float) Math.pow(0.5, depth);
        float brightness = 0.7F / depth;
        float step = Math.max(1.0F, budget / 10.0F);
        float used = 0.0F;
        while (used < budget) {
            float len = step * (0.6F + random.nextFloat() * 0.8F);
            float ax = bx + (float) random.nextGaussian() * 0.45F;
            float ay = by + (float) random.nextGaussian() * 0.3F;
            float az = bz + (float) random.nextGaussian() * 0.45F;
            if (!horizontal) {
                ay = Math.min(ay, -0.15F);
            }
            float al = Mth.sqrt(ax * ax + ay * ay + az * az);
            float nx = x + ax / al * len;
            float ny = y + ay / al * len;
            float nz = z + az / al * len;
            if (!horizontal && ny < 1.0F) {
                break;
            }
            float a0 = arrive + used / totalLength;
            used += len;
            float a1 = arrive + used / totalLength;
            float fade = 1.0F - used / budget * 0.6F;
            add(x, y, z, nx, ny, nz, width * fade, brightness * fade, a0, a1, false);
            if (depth < MAX_DEPTH && random.nextFloat() < 0.16F) {
                branch(nx, ny, nz, ax / al, ay / al, az / al, depth + 1, a1, (budget - used) * (0.4F + random.nextFloat() * 0.4F), horizontal);
            }
            x = nx;
            y = ny;
            z = nz;
        }
    }

    private void add(float x0, float y0, float z0, float x1, float y1, float z1, float width, float brightness, float arrive0,
            float arrive1, boolean main) {
        if ((count + 1) * STRIDE > data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        int o = count * STRIDE;
        data[o] = x0;
        data[o + 1] = y0;
        data[o + 2] = z0;
        data[o + 3] = x1;
        data[o + 4] = y1;
        data[o + 5] = z1;
        data[o + 6] = width;
        data[o + 7] = brightness;
        data[o + 8] = arrive0;
        data[o + 9] = arrive1;
        data[o + 10] = main ? 1.0F : 0.0F;
        count++;
    }
}
