package com.vortexso.guest_wilds.fabric;

import com.vortexso.guest_wilds.client.GuestWildsClient;
import net.fabricmc.api.ClientModInitializer;

public final class GuestWildsFabricClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    GuestWildsClient.init();
  }
}
