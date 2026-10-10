package com.pockyl.petrichor.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
 * <p>Vanilla's rain ticking (splashes and rain sounds) is normally skipped while the mod owns the rain. With Particle
 * Rain it runs on, because Particle Rain plays its snow and sandstorm sounds from there; its splashes and rain sounds
 * are taken out here.
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

    /**
     * Vanilla splashes and rain sounds (only ticked while the mod does not own the rain) follow the season the same
     * way, so where a winter takes the rain away vanilla does not bring it back.
     */
    @WrapOperation(method = "tickRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/biome/Biome;getPrecipitationAt(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private Biome.Precipitation petrichor$seasonalSplashes(Biome biome, BlockPos pos, Operation<Biome.Precipitation> original) {
        return level != null && Seasons.active() ? Seasons.precipitationAt(level, pos) : original.call(biome, pos);
    }

    @WrapOperation(method = "tickRain", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/OptionInstance;get()Ljava/lang/Object;"))
    private Object petrichor$noRainSplashes(OptionInstance<?> option, Operation<Object> original) {
        // MINIMAL stops vanilla at the first rain column, before any splash, and still lets the sounds play.
        boolean ours = option == Minecraft.getInstance().options.particles() && WeatherClient.ownsRain();
        return ours ? ParticleStatus.MINIMAL : original.call(option);
    }

    @WrapOperation(method = "tickRain", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(Lnet/minecraft/core/BlockPos;Lnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private void petrichor$noRainSounds(ClientLevel level, BlockPos pos, SoundEvent sound, SoundSource source, float volume, float pitch,
            boolean delayed, Operation<Void> original) {
        if (WeatherClient.ownsRain() && (sound == SoundEvents.WEATHER_RAIN || sound == SoundEvents.WEATHER_RAIN_ABOVE)) {
            return;
        }
        original.call(level, pos, sound, source, volume, pitch, delayed);
    }

    @ModifyExpressionValue(method = "renderSky", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float petrichor$sunThroughRain(float rain) {
        return WeatherClient.rainShade(rain);
    }
}
