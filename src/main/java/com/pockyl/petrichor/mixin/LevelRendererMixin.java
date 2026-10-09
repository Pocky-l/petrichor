package com.pockyl.petrichor.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.pockyl.petrichor.client.WeatherClient;

/**
 * The mod draws the rain, vanilla the snow - column by column, so where rainy and snowy land meet each side keeps its
 * own weather. Vanilla draws both in one pass and the dimension effects hook can only take over all of it or none, so
 * the rain columns are taken out of vanilla's pass here.
 *
 * <p>Vanilla also hides the sun, moon and stars behind the rain; in a sun shower the sun stays out.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Redirect(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation petrichor$snowOnly(Biome biome, BlockPos pos) {
        Biome.Precipitation precipitation = biome.getPrecipitationAt(pos);
        return precipitation == Biome.Precipitation.RAIN && WeatherClient.ownsRain() ? Biome.Precipitation.NONE : precipitation;
    }

    @ModifyExpressionValue(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float petrichor$sunThroughRain(float rain) {
        return WeatherClient.rainShade(rain);
    }
}
