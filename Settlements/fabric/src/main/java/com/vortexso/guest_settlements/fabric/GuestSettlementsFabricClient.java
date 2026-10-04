package com.vortexso.guest_settlements.fabric;

import com.vortexso.guest_settlements.client.GuestSettlementsClient;
import net.fabricmc.api.ClientModInitializer;

public final class GuestSettlementsFabricClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    GuestSettlementsClient.init();
  }
}
