package com.vortexso.guest_hands.fabric;

import com.vortexso.guest_hands.GuestHands;
import com.vortexso.guest_hands.grapple.Grapple;
import com.vortexso.guest_hands.sleep.SleepPass;
import com.vortexso.guest_hands.station.Stations;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.world.InteractionResult;

public final class GuestHandsFabric implements ModInitializer {
  @Override
  public void onInitialize() {
    GuestHands.init();
    PayloadTypeRegistry.serverboundPlay()
        .register(Grapple.GrapplePayload.TYPE, Grapple.GrapplePayload.CODEC);
    PayloadTypeRegistry.clientboundPlay()
        .register(Grapple.GrapplePayload.TYPE, Grapple.GrapplePayload.CODEC);
    ServerPlayNetworking.registerGlobalReceiver(
        Grapple.GrapplePayload.TYPE,
        (payload, context) -> Grapple.handleFromClient(payload, context.player()));
    ServerTickEvents.START_LEVEL_TICK.register(SleepPass::levelTick);
    AttackBlockCallback.EVENT.register(
        (player, level, hand, pos, direction) ->
            Stations.punch(player, level, pos, direction)
                ? InteractionResult.SUCCESS
                : InteractionResult.PASS);
    ServerPlayConnectionEvents.DISCONNECT.register(
        (listener, server) -> Grapple.logout(listener.player));
  }
}
