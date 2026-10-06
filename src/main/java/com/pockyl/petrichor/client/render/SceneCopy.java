package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * A copy of what is drawn so far, colour and depth, for effects that look through water: reflections in puddles,
 * the world seen through drops on walls and windows, the view through drops on the lens. Taken at most once per frame
 * for the world (the first effect that needs it takes it) and once more for the screen.
 */
public final class SceneCopy {
    private static TextureTarget world;
    private static TextureTarget screen;
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
            world = copy(world, true);
            worldFrame = frame;
        }
        return world;
    }

    /** Whether the world copy of this frame exists, without taking it. */
    public static boolean hasWorld() {
        return worldFrame == frame && world != null;
    }

    /** The finished picture, colour only, for drops on the lens. */
    public static TextureTarget screen() {
        screen = copy(screen, false);
        return screen;
    }

    private static TextureTarget copy(TextureTarget target, boolean depth) {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        if (target == null) {
            target = new TextureTarget(main.width, main.height, depth, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        } else if (target.width != main.width || target.height != main.height) {
            target.resize(main.width, main.height, Minecraft.ON_OSX);
            target.setFilterMode(GL11.GL_LINEAR);
        }
        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.frameBufferId);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, target.frameBufferId);
        GlStateManager._glBlitFrameBuffer(0, 0, main.width, main.height, 0, 0, target.width, target.height,
                depth ? GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT : GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        main.bindWrite(false);
        return target;
    }

    public static void close() {
        if (world != null) {
            world.destroyBuffers();
            world = null;
        }
        if (screen != null) {
            screen.destroyBuffers();
            screen = null;
        }
    }
}
