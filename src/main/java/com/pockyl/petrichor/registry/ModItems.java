package com.pockyl.petrichor.registry;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.pockyl.petrichor.Petrichor;

public final class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Petrichor.MOD_ID);

    private ModItems() {
    }

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
