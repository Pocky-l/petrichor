package com.pockyl.petrichor.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.pockyl.petrichor.client.WeatherClient;

/** In a sun shower the fog keeps the colour of a clear day instead of darkening for rain. */
@Mixin(FogRenderer.class)
abstract class FogRendererMixin {
    @ModifyExpressionValue(method = "setupColor", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private static float petrichor$sunShowerFog(float rain) {
        return WeatherClient.rainShade(rain);
    }
}
