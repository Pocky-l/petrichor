package com.pockyl.petrichor.mixin.compat;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import com.pockyl.petrichor.client.WeatherClient;
import com.pockyl.petrichor.compat.ParticleRain;
import com.pockyl.petrichor.compat.Seasons;

import java.util.ArrayList;

/**
 * Particle Rain's spawner (see {@link ParticleRain}): its rain presets - the falling rain and its splashes, ripples,
 * streaks and steam - are skipped where this mod draws the rain, while its snow, dust and fog spawn as usual. With a
 * season mod it tells rain from snow by the season, like this mod does, so where the season turns the rain into snow
 * Particle Rain's snow falls.
 *
 * <p>Applies only when Particle Rain is installed, and every hook is optional: a Particle Rain version without these
 * methods simply keeps all its particles.
 */
@Pseudo
@Mixin(targets = "pigcart.particlerain.ParticleSpawner")
abstract class ParticleRainSpawnerMixin {
    @WrapOperation(method = {"tickSkyFX", "tickSurfaceFX", "tickBlockFX"}, require = 0, at = @At(value = "INVOKE",
            target = "Lpigcart/particlerain/VersionUtil;getPrecipitationAt(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Holder;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/biome/Biome$Precipitation;"))
    private static Biome.Precipitation petrichor$seasonalPrecipitation(Level level, Holder<Biome> biome, BlockPos pos,
            Operation<Biome.Precipitation> original) {
        return ParticleRain.active() && Seasons.active() ? Seasons.precipitationAt(level, pos) : original.call(level, biome, pos);
    }

    @WrapOperation(method = {"tickSkyFX", "tickSurfaceFX", "tickBlockFX"}, require = 0, at = @At(value = "INVOKE",
            target = "Ljava/util/ArrayList;contains(Ljava/lang/Object;)Z"))
    private static boolean petrichor$leaveRainToPetrichor(ArrayList<?> presetPrecipitation, Object precipitation,
            Operation<Boolean> original) {
        // The preset's precipitation against the one where the particle would spawn: no rain preset on rain ground.
        if (ParticleRain.yieldsRain(presetPrecipitation, precipitation == Biome.Precipitation.RAIN && WeatherClient.ownsRain())) {
            return false;
        }
        return original.call(presetPrecipitation, precipitation);
    }
}
