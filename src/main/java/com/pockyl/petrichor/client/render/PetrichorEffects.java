package com.pockyl.petrichor.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.client.renderer.LightTexture;
import org.joml.Vector3f;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.WeatherClient;

/**
 * Overworld sky effects with this mod's precipitation: replaces the vanilla rain and snow drawing and the vanilla rain
 * ticking (splash particles and sounds), and adds lightning flashes to the light map. With rain disabled in the config,
 * and in snowy or dry land, everything falls back to vanilla.
 */
public final class PetrichorEffects extends DimensionSpecialEffects.OverworldEffects {
    @Override
    public boolean renderSnowAndRain(ClientLevel level, int ticks, float partialTick, LightTexture lightTexture, double camX, double camY,
            double camZ) {
        if (!ClientConfig.RAIN.get() || ClientWeather.vanillaWeather()) {
            return false;
        }
        return WeatherClient.renderWeather(level, partialTick, lightTexture, camX, camY, camZ);
    }

    @Override
    public boolean tickRain(ClientLevel level, int ticks, Camera camera) {
        // In snowy or dry land vanilla ticks its own weather; elsewhere the mod's splashes and sounds replace it.
        return ClientConfig.RAIN.get() && !ClientWeather.vanillaWeather();
    }

    @Override
    public void adjustLightmapColors(ClientLevel level, float partialTicks, float skyDarken, float blockLightRedFlicker, float skyLight,
            int pixelX, int pixelY, Vector3f colors) {
        float sky = LightTexture.getBrightness(level.dimensionType(), pixelY);
        // Overcast mood: daylight under rain clouds is dimmer and a little colder.
        float gloom = WeatherClient.gloom() * sky;
        if (gloom > 0.0F) {
            float luma = colors.x() * 0.3F + colors.y() * 0.59F + colors.z() * 0.11F;
            colors.lerp(new Vector3f(luma * 0.92F, luma * 0.96F, luma), gloom * 0.35F);
            colors.mul(1.0F - gloom * 0.14F);
        }
        float flash = WeatherClient.flash(partialTicks);
        if (flash <= 0.0F) {
            return;
        }
        // Only where the sky can be seen: a flash lights the outdoors, not the cave.
        float add = flash * sky;
        colors.add(add * 0.6F, add * 0.65F, add * 0.8F);
    }
}
