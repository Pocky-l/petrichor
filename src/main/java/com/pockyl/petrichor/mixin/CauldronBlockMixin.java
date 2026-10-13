package com.pockyl.petrichor.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.CauldronBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.pockyl.petrichor.world.RainCauldrons;

/** Rain fills cauldrons as fast as it falls ({@link RainCauldrons}); empty and water cauldrons both ask this. */
@Mixin(CauldronBlock.class)
public abstract class CauldronBlockMixin {
    @Inject(method = "shouldHandlePrecipitation", at = @At("HEAD"), cancellable = true)
    private static void petrichor$rainFill(Level level, Biome.Precipitation precipitation, CallbackInfoReturnable<Boolean> cir) {
        if (precipitation == Biome.Precipitation.RAIN && level instanceof ServerLevel server) {
            cir.setReturnValue(level.getRandom().nextFloat() < RainCauldrons.chance(server));
        }
    }
}
