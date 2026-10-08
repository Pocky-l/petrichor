package com.pockyl.petrichor.network;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import com.pockyl.petrichor.Petrichor;
import com.pockyl.petrichor.client.ClientWeather;

import java.util.function.Supplier;

public final class ModNetwork {
    private static final String PROTOCOL_VERSION = "1";
    // Optional both ways: the client works on servers without the mod and vanilla clients can join a modded server.
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(Petrichor.id("main"), () -> PROTOCOL_VERSION,
            NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION), NetworkRegistry.acceptMissingOr(PROTOCOL_VERSION));

    private ModNetwork() {
    }

    public static void register() {
        CHANNEL.messageBuilder(WeatherSyncPayload.class, 0, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(WeatherSyncPayload::encode)
                .decoder(WeatherSyncPayload::decode)
                .consumerMainThread(ModNetwork::handle)
                .add();
    }

    private static void handle(WeatherSyncPayload payload, Supplier<NetworkEvent.Context> context) {
        // The client class is only resolved when a packet arrives on a client.
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientWeather.onServerSync(payload.rainType(), payload.wetness()));
        context.get().setPacketHandled(true);
    }

    public static void sendToPlayer(ServerPlayer player, WeatherSyncPayload payload) {
        if (CHANNEL.isRemotePresent(player.connection.connection)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), payload);
        }
    }

    public static void sendToPlayersInDimension(ServerLevel level, WeatherSyncPayload payload) {
        for (ServerPlayer player : level.players()) {
            sendToPlayer(player, payload);
        }
    }
}
