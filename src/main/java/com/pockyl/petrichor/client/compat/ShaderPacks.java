package com.pockyl.petrichor.client.compat;

import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Shader packs of <a href="https://modrinth.com/mod/iris">Iris</a> (or its Forge port Oculus), asked through Iris' public
 * API by reflection, so neither is needed to build or run the mod.
 * <p>
 * While a pack is active, Iris draws nothing with core shaders it does not know (it masks their color and depth
 * writes): the mod's own rain, puddle and atmosphere shaders would be invisible. With a pack, the rain is drawn with
 * the vanilla particle shader instead, which Iris swaps for the pack's weather program.
 */
public final class ShaderPacks {
    private static final MethodHandle IN_USE;

    static {
        MethodHandle inUse = null;
        if (ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus")) {
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                inUse = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(instance);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                inUse = null;
            }
        }
        IN_USE = inUse;
    }

    private ShaderPacks() {
    }

    /** Whether a shader pack is active right now (packs can be switched in game). */
    public static boolean inUse() {
        if (IN_USE == null) {
            return false;
        }
        try {
            return (boolean) IN_USE.invokeExact();
        } catch (Throwable e) {
            return false;
        }
    }
}
