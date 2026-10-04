package com.vortexso.guest_core.neoforge;

import com.vortexso.guest_core.GuestCore;
import com.vortexso.guest_core.client.GuestCoreClient;
import com.vortexso.guest_core.platform.Events;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = GuestCore.MODID, dist = Dist.CLIENT)
public final class GuestCoreNeoForgeClient {
  public GuestCoreNeoForgeClient() {
    GuestCoreClient.init();
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForgeClient::onClientTick);
    NeoForge.EVENT_BUS.addListener(GuestCoreNeoForgeClient::onRender);
  }

  private static void onClientTick(ClientTickEvent.Post event) {
    for (Runnable listener : Events.CLIENT_TICK) {
      listener.run();
    }
  }

  private static void onRender(RenderLevelStageEvent.AfterTranslucentBlocks event) {
    for (Runnable listener : Events.DEBUG_RENDER) {
      listener.run();
    }
  }
}
