package com.pockyl.petrichor;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

import com.pockyl.petrichor.network.ModNetwork;

@Mod(Petrichor.MOD_ID)
public final class Petrichor {
    public static final String MOD_ID = "petrichor";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Petrichor(IEventBus modBus, ModContainer container) {
        // No items: nothing goes into the shared Pocky Mods tab.
        modBus.addListener(ModNetwork::register);
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
