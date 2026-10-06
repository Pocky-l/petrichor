package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import com.pockyl.petrichor.Petrichor;

import java.io.IOException;

/**
 * The mod's core shaders: drops and effects, puddles and wet ground, water spilling over steps, the rainy air, the
 * cinematic screen grade.
 */
public final class PetrichorShaders {
    private static ShaderInstance rain;
    private static ShaderInstance puddle;
    private static ShaderInstance sheet;
    private static ShaderInstance veil;
    private static ShaderInstance atmosphere;
    private static ShaderInstance grade;

    private PetrichorShaders() {
    }

    public static void register(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Petrichor.id("petrichor_rain"),
                    DefaultVertexFormat.PARTICLE), shader -> rain = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Petrichor.id("petrichor_puddle"),
                    DefaultVertexFormat.BLOCK), shader -> puddle = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Petrichor.id("petrichor_sheet"),
                    DefaultVertexFormat.BLOCK), shader -> sheet = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Petrichor.id("petrichor_veil"),
                    DefaultVertexFormat.PARTICLE), shader -> veil = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Petrichor.id("petrichor_atmosphere"),
                    DefaultVertexFormat.POSITION), shader -> atmosphere = shader);
            event.registerShader(new ShaderInstance(event.getResourceProvider(), Petrichor.id("petrichor_grade"),
                    DefaultVertexFormat.POSITION), shader -> grade = shader);
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

    public static ShaderInstance sheet() {
        return sheet;
    }

    public static ShaderInstance veil() {
        return veil;
    }

    public static ShaderInstance atmosphere() {
        return atmosphere;
    }

    public static ShaderInstance grade() {
        return grade;
    }
}
