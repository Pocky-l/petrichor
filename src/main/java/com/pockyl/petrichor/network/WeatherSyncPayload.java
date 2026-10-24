package com.pockyl.petrichor.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Server to client: the rain type falling in the player's level (ordinal, -1 when dry), the exact rain level between the
 * types, whether it is a sun shower and how soaked the ground is. Sent on join, on change and every few seconds.
 */
public record WeatherSyncPayload(int rainType, float rainLevel, boolean sunShower, float wetness) {
    public static WeatherSyncPayload decode(FriendlyByteBuf buffer) {
        return new WeatherSyncPayload(buffer.readVarInt(), buffer.readFloat(), buffer.readBoolean(), buffer.readFloat());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(rainType);
        buffer.writeFloat(rainLevel);
        buffer.writeBoolean(sunShower);
        buffer.writeFloat(wetness);
    }
}
