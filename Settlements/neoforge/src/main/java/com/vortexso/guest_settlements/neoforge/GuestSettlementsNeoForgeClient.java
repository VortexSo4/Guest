package com.vortexso.guest_settlements.neoforge;

import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.client.GuestSettlementsClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = GuestSettlements.MODID, dist = Dist.CLIENT)
public final class GuestSettlementsNeoForgeClient {
  public GuestSettlementsNeoForgeClient() {
    GuestSettlementsClient.init();
  }
}
