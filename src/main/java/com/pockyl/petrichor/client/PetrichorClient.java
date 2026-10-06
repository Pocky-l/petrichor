package com.pockyl.petrichor.client;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.neoforged.neoforge.client.event.sound.SoundEngineLoadEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.render.PetrichorBoltRenderer;
import com.pockyl.petrichor.client.render.PetrichorEffects;
import com.pockyl.petrichor.client.render.PetrichorShaders;
import com.pockyl.petrichor.client.sound.Muffler;

@Mod(value = Petrichor.MOD_ID, dist = Dist.CLIENT)
public final class PetrichorClient {
    public PetrichorClient(IEventBus modBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        modBus.addListener(PetrichorShaders::register);
        // The overworld sky effects carry the rain hooks; dimensions using the overworld look get the new weather too.
        modBus.addListener((RegisterDimensionSpecialEffectsEvent event) ->
                event.register(BuiltinDimensionTypes.OVERWORLD_EFFECTS, new PetrichorEffects()));
        modBus.addListener((EntityRenderersEvent.RegisterRenderers event) ->
                event.registerEntityRenderer(EntityType.LIGHTNING_BOLT, PetrichorBoltRenderer::new));
        // A restarted sound engine has a new OpenAL context: the muffling filter must be made again.
        modBus.addListener((SoundEngineLoadEvent event) -> Muffler.reset());
    }
}
