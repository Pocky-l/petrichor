package com.pockyl.petrichor.client.sound;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.neoforged.fml.ModList;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.slf4j.Logger;

import com.pockyl.petrichor.ClientConfig;
import com.pockyl.petrichor.mixin.ChannelAccessor;
import com.pockyl.petrichor.mixin.SoundEngineAccessor;
import com.pockyl.petrichor.mixin.SoundManagerAccessor;

/**
 * Makes rain behind walls and roofs sound muffled instead of only quieter: a low-pass filter (OpenAL EFX) on the
 * source of each of the mod's sounds. Without the filter a quiet hiss through a wall sounds like a quiet drizzle; with
 * it, it is the dull rumble of rain outside.
 *
 * <p>All OpenAL calls run on the sound engine's thread (through the channel handle), like the game's own. When EFX
 * is missing or Sound Physics Remastered filters every source itself, nothing is done and occlusion only lowers the
 * volume.
 */
public final class Muffler {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Below this a change of the filter is not worth an update. */
    private static final float EPSILON = 0.01F;

    /** Implemented by the mod's sounds that can be muffled. */
    public interface Muffled {
        /** How much of the highs pass, 1 = all (no filter) .. 0.02 = a dull thud through stone. */
        float highs();
    }

    // Touched only on the sound thread.
    private static int filter;
    private static boolean checked;
    private static boolean available;

    private Muffler() {
    }

    public static boolean enabled() {
        return ClientConfig.MUFFLING.get() && !ModList.get().isLoaded("sound_physics_remastered");
    }

    /** The sound engine restarted (device change, resource reload): the old filter is gone with the old context. */
    public static void reset() {
        filter = 0;
        checked = false;
        available = false;
    }

    /** A sound of the mod got its source and is about to start: set its filter right away. Sound thread. */
    public static void onSourceReady(SoundInstance sound, Channel channel) {
        if (sound instanceof Muffled muffled && enabled()) {
            apply(((ChannelAccessor) channel).petrichor$source(), muffled.highs());
        }
    }

    /**
     * Sends the current filter of a playing sound to its source.
     *
     * @param applied the value sent last time
     * @return the value now in effect
     */
    public static float update(SoundInstance sound, float highs, float applied) {
        if (Math.abs(highs - applied) < EPSILON || !enabled()) {
            return applied;
        }
        var engine = ((SoundManagerAccessor) Minecraft.getInstance().getSoundManager()).petrichor$engine();
        ChannelAccess.ChannelHandle handle = ((SoundEngineAccessor) engine).petrichor$channels().get(sound);
        if (handle == null) {
            return applied;
        }
        handle.execute(channel -> apply(((ChannelAccessor) channel).petrichor$source(), highs));
        return highs;
    }

    private static void apply(int source, float highs) {
        if (!checked) {
            checked = true;
            long device = ALC10.alcGetContextsDevice(ALC10.alcGetCurrentContext());
            available = device != 0L && ALC10.alcIsExtensionPresent(device, "ALC_EXT_EFX");
            if (available) {
                filter = EXTEfx.alGenFilters();
                EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                    available = false;
                }
            }
            LOGGER.info("Rain muffling {}", available ? "uses OpenAL EFX low-pass filters" : "is unavailable (no OpenAL EFX)");
        }
        if (!available) {
            return;
        }
        if (highs >= 0.999F) {
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, EXTEfx.AL_FILTER_NULL);
        } else {
            // A filter is copied into the source when attached, so one filter object serves every source.
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAIN, 1.0F);
            EXTEfx.alFilterf(filter, EXTEfx.AL_LOWPASS_GAINHF, Math.clamp(highs, 0.0F, 1.0F));
            AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
        }
        AL10.alGetError();
    }
}
