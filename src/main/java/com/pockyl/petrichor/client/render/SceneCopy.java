package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * A copy of what is drawn so far, colour and depth, for the reflections in puddles. Taken at most once per frame.
 */
public final class SceneCopy {
    private static TextureTarget world;
    private static long frame;
    private static long worldFrame = -1;

    private SceneCopy() {
    }

    /** Starts a new frame: the next request for the world copy takes a fresh one. */
    public static void newFrame() {
        frame++;
    }

    /** The world drawn so far in this frame (opaque blocks and entities, before translucent ones). */
    public static TextureTarget world() {
        if (worldFrame != frame || world == null) {
            world = copy(world);
            worldFrame = frame;
        }
        return world;
    }

    private static TextureTarget copy(TextureTarget target) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) {
            target = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        } else if (target.width != main.width || target.height != main.height) {
            target.resize(main.width, main.height, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, target.width, target.height,
                GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(false);
        return target;
    }
}
