package com.vortexso.guest_core.client;

import com.vortexso.guest_core.GuestCore;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;

public final class GuestCoreClient {
  private GuestCoreClient() {}

  public static void init() {
    Platform.INSTANCE.registerConfigScreen(GuestCore.MODID);
    GuestDevConsoleClient.init();
    Events.CLIENT_TICK.register(GuestDevConsoleClient::tick);
  }
}
