package com.pockyl.petrichor.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraftforge.client.event.RegisterShadersEvent;
import org.joml.Matrix4f;

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

    /**
     * Sets the uniforms and samplers the game fills in for every shader, as it does before drawing the chunk layers. The
     * caller adds its own and then applies the shader.
     */
    public static void setDefaultUniforms(ShaderInstance shader, Matrix4f modelView, Matrix4f projection) {
        for (int i = 0; i < 12; i++) {
            shader.setSampler("Sampler" + i, RenderSystem.getShaderTexture(i));
        }
        if (shader.MODEL_VIEW_MATRIX != null) {
            shader.MODEL_VIEW_MATRIX.set(modelView);
        }
        if (shader.PROJECTION_MATRIX != null) {
            shader.PROJECTION_MATRIX.set(projection);
        }
        if (shader.COLOR_MODULATOR != null) {
            shader.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        }
        if (shader.FOG_START != null) {
            shader.FOG_START.set(RenderSystem.getShaderFogStart());
        }
        if (shader.FOG_END != null) {
            shader.FOG_END.set(RenderSystem.getShaderFogEnd());
        }
        if (shader.FOG_COLOR != null) {
            shader.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        }
        if (shader.FOG_SHAPE != null) {
            shader.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        }
        if (shader.TEXTURE_MATRIX != null) {
            shader.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        }
        if (shader.GAME_TIME != null) {
            shader.GAME_TIME.set(RenderSystem.getShaderGameTime());
        }
        RenderSystem.setupShaderLights(shader);
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
