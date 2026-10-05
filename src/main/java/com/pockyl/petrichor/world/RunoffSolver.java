package com.pockyl.petrichor.world;

import com.pockyl.petrichor.weather.Noise;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.PriorityQueue;

/**
 * Where rain water goes once it hits a {@link SurfaceGrid}: a small hydrology model on the block grid.
 *
 * <ul>
 *   <li>every column drains into its lowest lower neighbour (four directions);</li>
 *   <li>flat areas drain towards their nearest edge (a breadth-first search from the cells that have a way down), so
 *   a flat roof or a terrace sends its water to the rim like real ones do;</li>
 *   <li>flat areas without any way down are closed hollows;</li>
 *   <li>every hollow fills up to the height where water would spill out of it ({@link #depth}, a priority flood), so
 *   a dug pit becomes a pool even when its floor is uneven;</li>
 *   <li>flow accumulates downstream: a cell carries the rain of every cell that drains through it, which is what makes
 *   a trickle at the top of a hill a stream at its foot.</li>
 * </ul>
 */
public final class RunoffSolver {
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    public final SurfaceGrid grid;
    /** Index of the cell this one drains into, or -1. */
    public final int[] downstream;
    /** Height difference to the downstream cell, 0 when draining across a flat. */
    public final int[] drop;
    /** Number of cells (this one included) whose rain flows through this cell. */
    public final int[] accumulation;
    /** Steps to the nearest cell with a way down; 0 for that cell itself, -1 when there is none. */
    public final int[] distanceToEdge;
    /** Part of a flat hollow with no way out. */
    public final boolean[] closed;
    /** How deep water would stand here if every hollow filled to its brim, in blocks. */
    public final int[] depth;

    private RunoffSolver(SurfaceGrid grid) {
        this.grid = grid;
        int n = grid.size * grid.size;
        downstream = new int[n];
        drop = new int[n];
        accumulation = new int[n];
        distanceToEdge = new int[n];
        closed = new boolean[n];
        depth = new int[n];
    }

    public static RunoffSolver solve(SurfaceGrid grid) {
        RunoffSolver solver = new RunoffSolver(grid);
        solver.route();
        solver.accumulate();
        solver.fillHollows();
        return solver;
    }

    private boolean drains(int i) {
        return grid.known(i) && grid.kind[i] != SurfaceKind.WATER && grid.kind[i] != SurfaceKind.HOT;
    }

    private void route() {
        int size = grid.size;
        int n = size * size;
        Arrays.fill(downstream, -1);
        Arrays.fill(distanceToEdge, -1);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int lz = 0; lz < size; lz++) {
            for (int lx = 0; lx < size; lx++) {
                int i = grid.index(lx, lz);
                if (!drains(i)) {
                    continue;
                }
                int h = grid.height[i];
                int best = -1;
                int bestDrop = 0;
                int bestTie = 0;
                for (int d = 0; d < 4; d++) {
                    int nx = lx + DX[d];
                    int nz = lz + DZ[d];
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                        continue;
                    }
                    int j = grid.index(nx, nz);
                    if (!grid.known(j)) {
                        continue;
                    }
                    int dh = h - grid.height[j];
                    int tie = Noise.hash(grid.x0 + nx, grid.z0 + nz, 0x0F10);
                    if (dh > bestDrop || dh == bestDrop && dh > 0 && tie > bestTie) {
                        best = j;
                        bestDrop = dh;
                        bestTie = tie;
                    }
                }
                if (best >= 0) {
                    downstream[i] = best;
                    drop[i] = bestDrop;
                    distanceToEdge[i] = 0;
                    queue.add(i);
                }
            }
        }
        // Flats drain towards the nearest cell with a way down.
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int lx = i % size;
            int lz = i / size;
            for (int d = 0; d < 4; d++) {
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                    continue;
                }
                int j = grid.index(nx, nz);
                if (!drains(j) || distanceToEdge[j] >= 0 || grid.height[j] != grid.height[i]) {
                    continue;
                }
                distanceToEdge[j] = distanceToEdge[i] + 1;
                downstream[j] = i;
                drop[j] = 0;
                queue.add(j);
            }
        }
        markClosedFlats(n);
    }

    /** Unrouted flats that touch neither the grid border nor unknown columns are hollows with no way out. */
    private void markClosedFlats(int n) {
        int size = grid.size;
        boolean[] seen = new boolean[n];
        int[] stack = new int[n];
        int[] members = new int[n];
        for (int start = 0; start < n; start++) {
            if (seen[start] || !drains(start) || distanceToEdge[start] >= 0) {
                continue;
            }
            int top = 0;
            int count = 0;
            boolean open = false;
            stack[top++] = start;
            seen[start] = true;
            while (top > 0) {
                int i = stack[--top];
                members[count++] = i;
                int lx = i % size;
                int lz = i / size;
                for (int d = 0; d < 4; d++) {
                    int nx = lx + DX[d];
                    int nz = lz + DZ[d];
                    if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                        open = true;
                        continue;
                    }
                    int j = grid.index(nx, nz);
                    if (!grid.known(j)) {
                        open = true;
                        continue;
                    }
                    if (!seen[j] && drains(j) && distanceToEdge[j] < 0 && grid.height[j] == grid.height[i]) {
                        seen[j] = true;
                        stack[top++] = j;
                    }
                }
            }
            if (!open) {
                for (int m = 0; m < count; m++) {
                    closed[members[m]] = true;
                }
            }
        }
    }

    /**
     * Priority flood from the edges of the known area inwards: a cell's spill level is the lowest height water must
     * rise to before it can run off the grid; cells below their spill level are under water once the hollow is full.
     */
    private void fillHollows() {
        int size = grid.size;
        int n = size * size;
        int[] spill = new int[n];
        boolean[] done = new boolean[n];
        PriorityQueue<int[]> queue = new PriorityQueue<>((a, b) -> Integer.compare(a[1], b[1]));
        for (int i = 0; i < n; i++) {
            if (!drains(i)) {
                continue;
            }
            int lx = i % size;
            int lz = i / size;
            boolean edge = lx == 0 || lz == 0 || lx == size - 1 || lz == size - 1;
            for (int d = 0; d < 4 && !edge; d++) {
                int j = grid.index(lx + DX[d], lz + DZ[d]);
                edge = !grid.known(j) || grid.kind[j] == SurfaceKind.WATER;
            }
            if (edge) {
                spill[i] = grid.height[i];
                done[i] = true;
                queue.add(new int[] {i, spill[i]});
            }
        }
        while (!queue.isEmpty()) {
            int[] entry = queue.poll();
            int i = entry[0];
            int lx = i % size;
            int lz = i / size;
            for (int d = 0; d < 4; d++) {
                int nx = lx + DX[d];
                int nz = lz + DZ[d];
                if (nx < 0 || nz < 0 || nx >= size || nz >= size) {
                    continue;
                }
                int j = grid.index(nx, nz);
                if (done[j] || !drains(j)) {
                    continue;
                }
                done[j] = true;
                spill[j] = Math.max(grid.height[j], spill[i]);
                queue.add(new int[] {j, spill[j]});
            }
        }
        for (int i = 0; i < n; i++) {
            depth[i] = done[i] ? spill[i] - grid.height[i] : 0;
        }
    }

    private void accumulate() {
        int n = grid.size * grid.size;
        Integer[] order = new Integer[n];
        int count = 0;
        for (int i = 0; i < n; i++) {
            if (drains(i)) {
                order[count++] = i;
                accumulation[i] = 1;
            }
        }
        // Upstream first: higher cells, and on a flat the cells farther from its edge.
        Arrays.sort(order, 0, count, (a, b) -> {
            int byHeight = Integer.compare(grid.height[b], grid.height[a]);
            return byHeight != 0 ? byHeight : Integer.compare(distanceToEdge[b], distanceToEdge[a]);
        });
        for (int k = 0; k < count; k++) {
            int i = order[k];
            int j = downstream[i];
            if (j >= 0) {
                accumulation[j] += accumulation[i];
            }
        }
    }
}
