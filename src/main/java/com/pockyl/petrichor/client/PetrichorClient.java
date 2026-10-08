package com.pockyl.petrichor.client;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.client.event.RegisterShadersEvent;
import net.minecraftforge.client.event.sound.SoundEngineLoadEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.render.PetrichorBoltRenderer;
import com.pockyl.petrichor.client.render.PetrichorEffects;
import com.pockyl.petrichor.client.render.PetrichorShaders;
import com.pockyl.petrichor.client.sound.Muffler;

/**
 * Client-only setup on the mod bus. A subscriber class instead of code in the mod constructor, so a dedicated server never
 * loads any client class.
 */
@Mod.EventBusSubscriber(modid = Petrichor.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PetrichorClient {
    private PetrichorClient() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        ModList.get().getModContainerById(Petrichor.MOD_ID).ifPresent(container -> container.registerExtensionPoint(
                ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> ConfigScreen.root(parent))));
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) {
        PetrichorShaders.register(event);
    }

    @SubscribeEvent
    public static void onRegisterEffects(RegisterDimensionSpecialEffectsEvent event) {
        // The overworld sky effects carry the rain hooks; dimensions using the overworld look get the new weather too.
        event.register(BuiltinDimensionTypes.OVERWORLD_EFFECTS, new PetrichorEffects());
    }

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(EntityType.LIGHTNING_BOLT, PetrichorBoltRenderer::new);
    }

    @SubscribeEvent
    public static void onSoundEngineLoad(SoundEngineLoadEvent event) {
        // A restarted sound engine has a new OpenAL context: the muffling filter must be made again.
        Muffler.reset();
    }
}
