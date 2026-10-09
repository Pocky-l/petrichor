package com.pockyl.petrichor.event;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.pockyl.petrichor.Config;
import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.compat.Seasons;
import com.pockyl.petrichor.network.WeatherSyncPayload;
import com.pockyl.petrichor.weather.RainType;
import com.pockyl.petrichor.weather.StormData;
import com.pockyl.petrichor.world.StrikeTargeting;
import com.pockyl.petrichor.world.SurfaceKind;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Server side of the weather: soaks and dries the ground, keeps clients in sync and adds storm strikes.
 */
@EventBusSubscriber(modid = Petrichor.MOD_ID)
public final class WeatherEvents {
    private static final int SYNC_INTERVAL = 40;
    private static final Map<ServerLevel, RainType> LAST_SENT = new WeakHashMap<>();

    private WeatherEvents() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !StormData.hasWeather(level)) {
            return;
        }
        StormData data = StormData.get(level);
        data.tick(level);
        RainType type = data.currentType(level);
        boolean changed = !LAST_SENT.containsKey(level) || LAST_SENT.get(level) != type;
        if (changed || level.getGameTime() % SYNC_INTERVAL == 0) {
            LAST_SENT.put(level, type);
            sync(level, data, type);
        }
        if (level.isThundering()) {
            extraStrikes(level, data.rainLevel());
        }
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        SurfaceKind.clearCache();
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        sendTo(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        sendTo(event.getEntity());
    }

    /** Sends the current state of the player's level right away (also after a command changed it). */
    public static void sendTo(Player player) {
        if (player instanceof ServerPlayer serverPlayer && serverPlayer.level() instanceof ServerLevel level) {
            if (!StormData.hasWeather(level)) {
                PacketDistributor.sendToPlayer(serverPlayer, new WeatherSyncPayload(-1, 0.0F, false, 0.0F));
                return;
            }
            StormData data = StormData.get(level);
            PacketDistributor.sendToPlayer(serverPlayer, payload(level, data, data.currentType(level)));
        }
    }

    public static void syncLevel(ServerLevel level) {
        StormData data = StormData.get(level);
        RainType type = data.currentType(level);
        LAST_SENT.put(level, type);
        sync(level, data, type);
    }

    private static void sync(ServerLevel level, StormData data, RainType type) {
        PacketDistributor.sendToPlayersInDimension(level, payload(level, data, type));
    }

    private static WeatherSyncPayload payload(ServerLevel level, StormData data, RainType type) {
        return new WeatherSyncPayload(type == null ? -1 : type.ordinal(), data.rainLevel(), data.sunShower(level), data.wetness());
    }

    private static void extraStrikes(ServerLevel level, float rainLevel) {
        double perMinute = Config.EXTRA_STRIKES.get() * Seasons.strikeMultiplier(level);
        if (perMinute <= 0.0) {
            return;
        }
        // The extra strikes come as the storm builds up from a downpour.
        float storm = Math.clamp(rainLevel - RainType.DOWNPOUR.level(), 0.0F, 1.0F);
        double chance = perMinute / 1200.0 * level.getThunderLevel(1.0F) * storm;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || level.random.nextDouble() >= chance) {
                continue;
            }
            strikeNear(level, player.blockPosition());
        }
    }

    /** Strikes somewhere near {@code center}; returns where, or empty when no spot under open sky was found. */
    public static Optional<BlockPos> strikeNear(ServerLevel level, BlockPos center) {
        Optional<BlockPos> target = StrikeTargeting.pick(level, center, Config.STRIKE_RADIUS.get(), Config.TALL_ATTRACTION.get(),
                level.random);
        target.ifPresent(pos -> {
            LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
            if (bolt != null) {
                bolt.moveTo(Vec3.atBottomCenterOf(pos));
                level.addFreshEntity(bolt);
            }
        });
        return target;
    }
}
