package com.pockyl.petrichor.world;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;

/**
 * Where a falling drop meets the terrain. The terrain is seen as columns: everything below a column's top is solid,
 * so a slanted drop can hit the top of a column or the side of a column that is taller than the one it falls through.
 * A drop that met the terrain is gone for good - it does not come out on the far side of a wall.
 */
public final class DropPath {
    /** Highest point the rain reaches in a column: the top of its surface. */
    @FunctionalInterface
    public interface Tops {
        float top(int x, int z);
    }

    /**
     * The point of impact and the face that was hit: {@link Direction#UP} for the top of a column, otherwise the side
     * of the column the drop flew into, facing back towards where the drop came from.
     */
    public record Hit(Direction face, double x, double y, double z) {
    }

    /** Horizontal distance behind a drop that is checked for walls it would have flown through. */
    private static final float BACK_REACH = 4.0F;
    private static final float SEARCH_STEP = 0.2F;

    private DropPath() {
    }

    public static boolean inside(Tops tops, double x, double y, double z) {
        return y < tops.top(Mth.floor(x), Mth.floor(z));
    }

    /**
     * Whether a drop at the given point, falling along {@code (slantX, -1, slantZ)}, has already met the terrain - it is
     * inside it now, or its way here (at most {@code fallen} blocks of fall) went through a column.
     */
    public static boolean blocked(Tops tops, double x, double y, double z, float slantX, float slantZ, double fallen) {
        if (inside(tops, x, y, z)) {
            return true;
        }
        float slant = Math.max(Math.abs(slantX), Math.abs(slantZ));
        if (slant < 0.02F) {
            return false;
        }
        // Steps short enough that no column is skipped sideways.
        double step = Math.min(1.0, 0.45 / slant);
        double reach = Math.min(fallen, BACK_REACH / slant);
        for (double d = step; d <= reach; d += step) {
            if (inside(tops, x - slantX * d, y + d, z - slantZ * d)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where a drop moving from a free point {@code (x0, y0, z0)} to {@code (x1, y1, z1)} meets the terrain.
     *
     * @return null when the segment stays free
     */
    public static Hit hit(Tops tops, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(length / SEARCH_STEP));
        double free = 0.0;
        double solid = -1.0;
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            if (inside(tops, x0 + dx * t, y0 + dy * t, z0 + dz * t)) {
                solid = t;
                break;
            }
            free = t;
        }
        if (solid < 0.0) {
            return null;
        }
        for (int i = 0; i < 10; i++) {
            double mid = (free + solid) * 0.5;
            if (inside(tops, x0 + dx * mid, y0 + dy * mid, z0 + dz * mid)) {
                solid = mid;
            } else {
                free = mid;
            }
        }
        double ax = x0 + dx * free;
        double ay = y0 + dy * free;
        double az = z0 + dz * free;
        int cellAX = Mth.floor(ax);
        int cellAZ = Mth.floor(az);
        int cellBX = Mth.floor(x0 + dx * solid);
        int cellBZ = Mth.floor(z0 + dz * solid);
        if (cellAX == cellBX && cellAZ == cellBZ) {
            return new Hit(Direction.UP, ax, tops.top(cellAX, cellAZ), az);
        }
        // Crossed into another column below its top: the side of that column, unless the crossing is above its top
        // (then the drop came down on the top just behind the edge).
        boolean crossX = cellAX != cellBX;
        boolean crossZ = cellAZ != cellBZ;
        if (crossX && crossZ) {
            // Through a corner: the boundary met first decides.
            double tX = boundaryParam(x0, dx, cellAX, cellBX);
            double tZ = boundaryParam(z0, dz, cellAZ, cellBZ);
            crossX = tX <= tZ;
            crossZ = !crossX;
        }
        double t = crossX ? boundaryParam(x0, dx, cellAX, cellBX) : boundaryParam(z0, dz, cellAZ, cellBZ);
        double hx = x0 + dx * t;
        double hy = y0 + dy * t;
        double hz = z0 + dz * t;
        int columnX = crossX ? cellBX : cellAX;
        int columnZ = crossX ? cellAZ : cellBZ;
        float top = tops.top(columnX, columnZ);
        if (hy >= top) {
            return new Hit(Direction.UP, hx, top, hz);
        }
        Direction face;
        if (crossX) {
            face = cellBX > cellAX ? Direction.WEST : Direction.EAST;
            hx = Math.max(cellAX, cellBX);
        } else {
            face = cellBZ > cellAZ ? Direction.NORTH : Direction.SOUTH;
            hz = Math.max(cellAZ, cellBZ);
        }
        return new Hit(face, hx, hy, hz);
    }

    private static double boundaryParam(double start, double delta, int from, int to) {
        if (Math.abs(delta) < 1.0E-9) {
            return 0.0;
        }
        double boundary = Math.max(from, to);
        return Mth.clamp((boundary - start) / delta, 0.0, 1.0);
    }
}
