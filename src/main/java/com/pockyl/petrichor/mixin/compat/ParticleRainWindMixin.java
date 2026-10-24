package com.pockyl.petrichor.mixin.compat;

import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.petrichor.client.ClientWeather;
import com.pockyl.petrichor.client.WeatherClient;
import com.pockyl.petrichor.compat.ParticleRain;

/**
 * Particle Rain's own wind (see {@link ParticleRain}) blows the way this mod's wind does, so its snow and dust drift
 * along with the rain instead of across it. Its strength is left to Particle Rain.
 *
 * <p>Applies only when Particle Rain is installed, and the hook is optional. Wind from another mod that drives Particle
 * Rain's particles (WindLink) does not pass through here and is left alone.
 */
@Pseudo
@Mixin(targets = "pigcart.particlerain.ParticleRain")
abstract class ParticleRainWindMixin {
    @Inject(method = "getWind(DDD)Lorg/joml/Vector3f;", at = @At("RETURN"), cancellable = true, require = 0)
    private static void petrichor$sameWind(double x, double y, double z, CallbackInfoReturnable<Vector3f> cir) {
        Vector3f wind = cir.getReturnValue();
        if (wind == null || !ParticleRain.active() || !WeatherClient.ownsRain()) {
            return;
        }
        float[] aligned = ParticleRain.alignWind(wind.x(), wind.z(), ClientWeather.windX(), ClientWeather.windZ());
        cir.setReturnValue(new Vector3f(aligned[0], wind.y(), aligned[1]));
    }
}
