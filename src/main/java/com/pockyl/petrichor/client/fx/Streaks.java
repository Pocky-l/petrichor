package com.pockyl.petrichor.client.fx;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.util.Mth;

/**
 * Motion-blurred falling water: a quad stretched along the velocity and turned around it to face the camera.
 */
public final class Streaks {
    private Streaks() {
    }

    /**
     * @param hx head (leading end) relative to the camera
     * @param dx direction of motion, need not be normalized
     */
    public static void streak(VertexConsumer out, float hx, float hy, float hz, float dx, float dy, float dz, float length, float width,
            int tile, float headAlpha, float tailAlpha, int light, float r, float g, float b) {
        float dl = Mth.sqrt(dx * dx + dy * dy + dz * dz);
        if (dl < 1.0E-5F) {
            return;
        }
        dx /= dl;
        dy /= dl;
        dz /= dl;
        // Side vector: perpendicular to the motion and to the line of sight.
        float sx = dy * hz - dz * hy;
        float sy = dz * hx - dx * hz;
        float sz = dx * hy - dy * hx;
        float sl = Mth.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0E-5F) {
            return;
        }
        float half = width * 0.5F / sl;
        sx *= half;
        sy *= half;
        sz *= half;
        float tx = hx - dx * length;
        float ty = hy - dy * length;
        float tz = hz - dz * length;
        float u0 = FxAtlas.u0(tile);
        float v0 = FxAtlas.v0(tile);
        float u1 = FxAtlas.u1(tile);
        float v1 = FxAtlas.v1(tile);
        out.addVertex(hx - sx, hy - sy, hz - sz).setUv(u0, v1).setColor(r, g, b, headAlpha).setLight(light);
        out.addVertex(hx + sx, hy + sy, hz + sz).setUv(u1, v1).setColor(r, g, b, headAlpha).setLight(light);
        out.addVertex(tx + sx, ty + sy, tz + sz).setUv(u1, v0).setColor(r, g, b, tailAlpha).setLight(light);
        out.addVertex(tx - sx, ty - sy, tz - sz).setUv(u0, v0).setColor(r, g, b, tailAlpha).setLight(light);
    }
}
