package com.vortexso.guest_core.fabric;

import com.vortexso.guest_core.client.GuestCoreClient;
import com.vortexso.guest_core.platform.Events;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

public final class GuestCoreFabricClient implements ClientModInitializer {
  @Override
  public void onInitializeClient() {
    GuestCoreClient.init();
    ClientTickEvents.END_CLIENT_TICK.register(
        client -> {
          for (Runnable listener : Events.CLIENT_TICK) {
            listener.run();
          }
        });
    LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(
        context -> {
          for (Runnable listener : Events.DEBUG_RENDER) {
            listener.run();
          }
        });
  }
}
