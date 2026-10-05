package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import com.pockyl.petrichor.Petrichor;

import java.io.IOException;

/** The mod's core shaders: drops and effects, puddles and wet ground, rivulets. */
public final class PetrichorShaders {
    private static ShaderInstance rain;
    private static ShaderInstance puddle;
    private static ShaderInstance rivulet;

    private PetrichorShaders() {
    }

    public static void register(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Petrichor.id("petrichor_rain"),
                    DefaultVertexFormat.PARTICLE), shader -> rain = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Petrichor.id("petrichor_puddle"),
                    DefaultVertexFormat.PARTICLE), shader -> puddle = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Petrichor.id("petrichor_rivulet"),
                    DefaultVertexFormat.PARTICLE), shader -> rivulet = shader);
        } catch (IOException e) {
            Petrichor.LOGGER.error("Failed to load Petrichor shaders", e);
        }
    }

    public static ShaderInstance rain() {
        return rain;
    }

    public static ShaderInstance puddle() {
        return puddle;
    }

    public static ShaderInstance rivulet() {
        return rivulet;
    }
}
