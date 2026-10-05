package com.pockyl.petrichor.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.WeatherClient;

/**
 * Overworld sky effects with this mod's precipitation: replaces the vanilla rain and snow drawing and the vanilla rain
 * ticking (splash particles and sounds), and adds lightning flashes to the light map. With rain disabled in the config
 * everything falls back to vanilla.
 */
public final class PetrichorEffects extends DimensionSpecialEffects.OverworldEffects {
    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, LightTexture lightTexture, double camX, double camY,
            double camZ) {
        if (!ClientConfig.RAIN.get()) {
            return false;
        }
        return WeatherClient.renderWeather(level, partialTick, lightTexture, camX, camY, camZ);
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        return ClientConfig.RAIN.get();
    }

    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float blockLightRedFlicker, float skyLight,
            int pixelX, int pixelY, Vector3f colors) {
        float flash = WeatherClient.flash(partialTicks);
        if (flash <= 0.0F) {
            return;
        }
        // Only where the sky can be seen: a flash lights the outdoors, not the cave.
        float sky = LightTexture.getBrightness(level.dimensionType(), pixelY);
        float add = flash * sky;
        colors.add(add * 0.6F, add * 0.65F, add * 0.8F);
    }
}
