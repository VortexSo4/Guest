package com.vortexso.guest_hands.client;

import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_hands.GuestHands;

public final class HandsClient {
  private HandsClient() {}

  public static void init() {
    Platform.INSTANCE.registerConfigScreen(GuestHands.MODID);
    Events.CLIENT_TICK.register(SleepClient::tick);
    Events.CLIENT_TICK.register(EnchantingGlyphs::tick);
    Events.DEBUG_RENDER.register(HandsDebugRenderer::render);
  }
}
