package com.vortexso.guest_hands.neoforge;

import com.vortexso.guest_hands.GuestHands;
import com.vortexso.guest_hands.client.GrappleClient;
import com.vortexso.guest_hands.client.HandsClient;
import com.vortexso.guest_hands.grapple.Grapple;
import net.minecraft.world.InteractionHand;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@Mod(value = GuestHands.MODID, dist = Dist.CLIENT)
public final class GuestHandsNeoForgeClient {
  public GuestHandsNeoForgeClient(IEventBus modBus) {
    HandsClient.init();
    modBus.addListener(GuestHandsNeoForgeClient::registerPayloads);
    NeoForge.EVENT_BUS.addListener(GuestHandsNeoForgeClient::onUse);
    NeoForge.EVENT_BUS.addListener(GuestHandsNeoForgeClient::onPlayerTick);
  }

  private static void registerPayloads(RegisterClientPayloadHandlersEvent event) {
    event.register(
        Grapple.GrapplePayload.TYPE, (payload, context) -> GrappleClient.onServerDetach());
  }

  private static void onUse(InputEvent.InteractionKeyMappingTriggered event) {
    if (event.isUseItem()
        && event.getHand() == InteractionHand.MAIN_HAND
        && GrappleClient.onUse()) {
      event.setCanceled(true);
      event.setSwingHand(false);
    }
  }

  private static void onPlayerTick(PlayerTickEvent.Pre event) {
    GrappleClient.tick(event.getEntity());
  }
}
