package com.vortexso.guest_core.platform;

import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

public final class Network {
  private Network() {}

  public static void send(ServerPlayer player, CustomPacketPayload payload) {
    if (Platform.INSTANCE.canSend(player, payload.type())) {
      player.connection.send(new ClientboundCustomPayloadPacket(payload));
    }
  }
}
