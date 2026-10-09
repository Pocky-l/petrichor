package com.pockyl.petrichor.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.pockyl.petrichor.client.WeatherClient;
import com.pockyl.petrichor.compat.Seasons;

import javax.annotation.Nullable;

/**
 * The mod draws the rain, vanilla the snow - column by column, so where rainy and snowy land meet each side keeps its
 * own weather. Vanilla draws both in one pass and the dimension effects hook can only take over all of it or none, so
 * the rain columns are taken out of vanilla's pass here.
 *
 * <p>With a season mod, rain and snow are told apart by the season, the same way as for the mod's own rain, so where
 * the season turns the rain into snow vanilla draws snow and the mod nothing.
 *
 * <p>Vanilla also hides the sun, moon and stars behind the rain; in a sun shower the sun stays out.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Shadow
    @Nullable
    private ClientLevel level;

    @Redirect(method = "renderSnowAndRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation petrichor$snowOnly(Biome biome, BlockPos pos) {
        Biome.Precipitation precipitation = level != null && Seasons.active() ? Seasons.precipitationAt(level, pos)
                : biome.getPrecipitationAt(pos);
        return precipitation == Biome.Precipitation.RAIN && WeatherClient.ownsRain() ? Biome.Precipitation.NONE : precipitation;
    }

    @ModifyExpressionValue(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float petrichor$sunThroughRain(float rain) {
        return WeatherClient.rainShade(rain);
    }
}
