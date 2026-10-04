package com.vortexso.guest_hands.fabric;

import com.vortexso.guest_hands.client.GrappleClient;
import com.vortexso.guest_hands.client.HandsClient;
import com.vortexso.guest_hands.grapple.Grapple;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

public final class GuestHandsFabricClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    HandsClient.init();
    ClientPlayNetworking.registerGlobalReceiver(
        Grapple.GrapplePayload.TYPE, (payload, context) -> GrappleClient.onServerDetach());
  }
}
