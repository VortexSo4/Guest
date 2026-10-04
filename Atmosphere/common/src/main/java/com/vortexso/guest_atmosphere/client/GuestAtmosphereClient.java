package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;

public final class GuestAtmosphereClient {
  private GuestAtmosphereClient() {}

  public static void init() {
    Platform.INSTANCE.registerConfigScreen(GuestAtmosphere.MODID);
    Events.CLIENT_TICK.register(VegetationTint::tick);
    Events.CLIENT_TICK.register(ClientWeatherEffects::tick);
    Events.CLIENT_TICK.register(WeatherFog::tick);
    Events.CLIENT_TICK.register(AuroraRenderer::tick);
    Events.CLIENT_TICK.register(FogLights::tick);
    Events.CLIENT_TICK.register(GroundMist::tick);
    Events.DEBUG_RENDER.register(AtmosphereDebugRenderer::render);
  }
}
