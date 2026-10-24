package com.pockyl.petrichor.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.pockyl.petrichor.client.WeatherClient;

/**
 * In a sun shower the sky stays blue, the clouds white and the daylight bright: vanilla's rain level is shaded where it
 * colours the sky and clouds and dims the light. Everything that makes the rain fall keeps the real rain level.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
    @ModifyExpressionValue(method = {"getSkyDarken", "getSkyColor", "getCloudColor"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float petrichor$sunShowerSky(float rain) {
        return WeatherClient.rainShade(rain);
    }
}
