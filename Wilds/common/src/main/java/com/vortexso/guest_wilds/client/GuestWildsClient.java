package com.vortexso.guest_wilds.client;

import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_wilds.GuestWilds;

public final class GuestWildsClient {
  private GuestWildsClient() {}

  public static void init() {
    Platform.INSTANCE.registerConfigScreen(GuestWilds.MODID);
    Events.DEBUG_RENDER.register(WildsDebugRenderer::render);
  }
}
