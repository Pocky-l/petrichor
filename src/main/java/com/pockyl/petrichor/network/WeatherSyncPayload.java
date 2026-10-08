package com.pockyl.petrichor.network;

import net.minecraft.network.FriendlyByteBuf;

/**
 * Server to client: the rain type falling in the player's level (ordinal, -1 when dry) and how soaked the ground is.
 * Sent on join, on change and every few seconds.
 */
public record WeatherSyncPayload(int rainType, float wetness) {
    public static WeatherSyncPayload decode(FriendlyByteBuf buffer) {
        return new WeatherSyncPayload(buffer.readVarInt(), buffer.readFloat());
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeVarInt(rainType);
        buffer.writeFloat(wetness);
    }
}
