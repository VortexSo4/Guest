package com.vortexso.guest_settlements.client;

import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.life.client.LifeDebugRenderer;
import com.vortexso.guest_settlements.village.client.SocietyDebugRenderer;
import com.vortexso.guest_settlements.village.client.VillageDebugRenderer;

public final class GuestSettlementsClient {
  private GuestSettlementsClient() {}

  public static void init() {
    Platform.INSTANCE.registerConfigScreen(GuestSettlements.MODID);
    Events.DEBUG_RENDER.register(LifeDebugRenderer::render);
    Events.DEBUG_RENDER.register(SocietyDebugRenderer::render);
    Events.DEBUG_RENDER.register(VillageDebugRenderer::render);
  }
}
