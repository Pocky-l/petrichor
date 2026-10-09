package com.pockyl.petrichor.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import com.pockyl.petrichor.Petrichor;

/**
 * Server to client: the rain type falling in the player's level (ordinal, -1 when dry), the exact rain level between the
 * types, whether it is a sun shower and how soaked the ground is. Sent on join, on change and every few seconds.
 */
public record WeatherSyncPayload(int rainType, float rainLevel, boolean sunShower, float wetness) implements CustomPacketPayload {
    public static final Type<WeatherSyncPayload> TYPE = new Type<>(Petrichor.id("weather_sync"));

    public static final StreamCodec<ByteBuf, WeatherSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WeatherSyncPayload::rainType,
            ByteBufCodecs.FLOAT, WeatherSyncPayload::rainLevel,
            ByteBufCodecs.BOOL, WeatherSyncPayload::sunShower,
            ByteBufCodecs.FLOAT, WeatherSyncPayload::wetness,
            WeatherSyncPayload::new);

    @Override
    public Type<WeatherSyncPayload> type() {
        return TYPE;
    }
}
