package com.pockyl.petrichor.client.compat;

import com.mojang.blaze3d.vertex.BufferBuilder;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Shader packs of <a href="https://modrinth.com/mod/iris">Iris</a> (or its Forge port Oculus), reached by reflection,
 * so neither is needed to build or run the mod.
 * <p>
 * While a pack is active, Iris draws nothing with core shaders it does not know (it masks their color and depth
 * writes): the mod's own rain, puddle and atmosphere shaders would be invisible. With a pack, the rain is drawn with
 * the vanilla particle shader instead, which Iris swaps for the pack's weather program, and puddles are drawn as water
 * with the translucent terrain shader - their vertices tagged with the pack's id of water, like Iris tags the fluids
 * of chunk meshes, so the pack renders them with its own water.
 */
public final class ShaderPacks {
    private static final MethodHandle IN_USE;
    private static final MethodHandle BLOCK_IDS;
    private static final MethodHandle BEGIN_BLOCK;
    private static final MethodHandle END_BLOCK;
    private static final MethodHandle PIPELINE;
    private static final MethodHandle GET_PHASE;
    private static final MethodHandle SET_PHASE;
    private static final Object WEATHER_PHASE;

    static {
        MethodHandle inUse = null;
        MethodHandle blockIds = null;
        MethodHandle beginBlock = null;
        MethodHandle endBlock = null;
        MethodHandle pipeline = null;
        MethodHandle getPhase = null;
        MethodHandle setPhase = null;
        Object weatherPhase = null;
        if (ModList.get().isLoaded("iris") || ModList.get().isLoaded("oculus")) {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                Object instance = api.getMethod("getInstance").invoke(null);
                inUse = lookup.findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class)).bindTo(instance);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                inUse = null;
            }
            // Internals (not part of the API): without them puddles are drawn as plain translucent water film.
            try {
                Class<?> settings = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
                Object instance = settings.getField("INSTANCE").get(null);
                blockIds = lookup.findVirtual(settings, "getBlockStateIds", MethodType.methodType(Object2IntMap.class))
                        .bindTo(instance).asType(MethodType.methodType(Object.class));
                Class<?> sensitive = Class.forName("net.irisshaders.iris.vertices.BlockSensitiveBufferBuilder");
                beginBlock = lookup.findVirtual(sensitive, "beginBlock", MethodType.methodType(void.class, int.class, byte.class, byte.class,
                        int.class, int.class, int.class)).asType(MethodType.methodType(void.class, Object.class, int.class, byte.class,
                        byte.class, int.class, int.class, int.class));
                endBlock = lookup.findVirtual(sensitive, "endBlock", MethodType.methodType(void.class))
                        .asType(MethodType.methodType(void.class, Object.class));
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                blockIds = null;
                beginBlock = null;
                endBlock = null;
            }
            // Internals too: without them weather drawn outside the vanilla weather pass uses the pack's particle program.
            try {
                Class<?> manager = Class.forName("net.irisshaders.iris.pipeline.PipelineManager");
                Class<?> worldPipeline = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPipeline");
                Class<?> phase = Class.forName("net.irisshaders.iris.pipeline.WorldRenderingPhase");
                pipeline = MethodHandles.filterReturnValue(
                        lookup.findStatic(Class.forName("net.irisshaders.iris.Iris"), "getPipelineManager", MethodType.methodType(manager)),
                        lookup.findVirtual(manager, "getPipelineNullable", MethodType.methodType(worldPipeline)))
                        .asType(MethodType.methodType(Object.class));
                getPhase = lookup.findVirtual(worldPipeline, "getPhase", MethodType.methodType(phase))
                        .asType(MethodType.methodType(Object.class, Object.class));
                setPhase = lookup.findVirtual(worldPipeline, "setPhase", MethodType.methodType(void.class, phase))
                        .asType(MethodType.methodType(void.class, Object.class, Object.class));
                weatherPhase = phase.getField("RAIN_SNOW").get(null);
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                pipeline = null;
                getPhase = null;
                setPhase = null;
                weatherPhase = null;
            }
        }
        IN_USE = inUse;
        BLOCK_IDS = blockIds;
        BEGIN_BLOCK = beginBlock;
        END_BLOCK = endBlock;
        PIPELINE = pipeline;
        GET_PHASE = getPhase;
        SET_PHASE = setPhase;
        WEATHER_PHASE = weatherPhase;
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

    /** The active pack's material id of a block state (its block.properties), -1 when it has none. */
    @SuppressWarnings("unchecked")
    public static int blockId(BlockState state) {
        if (BLOCK_IDS == null || BEGIN_BLOCK == null) {
            return -1;
        }
        try {
            Object2IntMap<BlockState> ids = (Object2IntMap<BlockState>) (Object) BLOCK_IDS.invokeExact();
            return ids != null && ids.containsKey(state) ? ids.getInt(state) : -1;
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Tags the vertices added to {@code builder} until {@link #endBlock} as the block with pack id {@code id} at the
     * given position, as a fluid. The builder must have been created while the pack was active.
     */
    public static void beginFluid(BufferBuilder builder, int id, int x, int y, int z) {
        if (BEGIN_BLOCK == null || id < 0) {
            return;
        }
        try {
            BEGIN_BLOCK.invokeExact((Object) builder, id, (byte) 1, (byte) 0, x, y, z);
        } catch (Throwable ignored) {
            // Untagged vertices are drawn as a plain translucent surface.
        }
    }

    public static void endBlock(BufferBuilder builder) {
        if (END_BLOCK == null) {
            return;
        }
        try {
            END_BLOCK.invokeExact((Object) builder);
        } catch (Throwable ignored) {
            // Nothing to undo.
        }
    }

    /**
     * Puts the pack into its weather pass, as if the vanilla weather were being drawn: Iris only does that around the
     * vanilla call, so weather drawn after it would otherwise get the pack's particle program.
     *
     * @return what to give {@link #endWeatherPhase} afterwards; null when nothing was changed
     */
    public static Object beginWeatherPhase() {
        if (PIPELINE == null || !inUse()) {
            return null;
        }
        try {
            Object pipeline = (Object) PIPELINE.invokeExact();
            if (pipeline == null) {
                return null;
            }
            Object previous = (Object) GET_PHASE.invokeExact(pipeline);
            SET_PHASE.invokeExact(pipeline, WEATHER_PHASE);
            return new Object[] {pipeline, previous};
        } catch (Throwable e) {
            return null;
        }
    }

    /** Puts the pack back into the pass it was in before {@link #beginWeatherPhase}. */
    public static void endWeatherPhase(Object token) {
        if (!(token instanceof Object[] saved) || SET_PHASE == null) {
            return;
        }
        try {
            SET_PHASE.invokeExact(saved[0], saved[1]);
        } catch (Throwable ignored) {
            // The pack resets its pass for the next frame itself.
        }
    }
}
