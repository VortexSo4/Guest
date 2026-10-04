package com.vortexso.guest_wilds.neoforge;

import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.client.GuestWildsClient;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = GuestWilds.MODID, dist = Dist.CLIENT)
public final class GuestWildsNeoForgeClient {
  public GuestWildsNeoForgeClient() {
    GuestWildsClient.init();
  }
}
