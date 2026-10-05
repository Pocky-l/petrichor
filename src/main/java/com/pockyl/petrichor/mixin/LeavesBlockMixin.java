package com.pockyl.petrichor.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.ParticleUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LeavesBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import com.pockyl.petrichor.client.WeatherClient;

/**
 * Leaves in the rain spawn vanilla "dripping water" particles under them. Those are replaced by the mod's drops
 * falling from the same spots, so all water dripping from trees looks alike. NeoForge has no event for this.
 */
@Mixin(LeavesBlock.class)
abstract class LeavesBlockMixin {
    @Redirect(method = "animateTick", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/ParticleUtils;spawnParticleBelow(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/particles/ParticleOptions;)V"))
    private void petrichor$dripBelow(Level level, BlockPos pos, RandomSource random, ParticleOptions particle) {
        if (!WeatherClient.leafDrip(level, pos, random)) {
            ParticleUtils.spawnParticleBelow(level, pos, random, particle);
        }
    }
}
