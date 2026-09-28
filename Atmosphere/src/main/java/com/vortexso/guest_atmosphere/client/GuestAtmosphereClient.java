package com.vortexso.guest_atmosphere.client;

import com.vortexso.guest_atmosphere.GuestAtmosphere;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = GuestAtmosphere.MODID, dist = Dist.CLIENT)
public final class GuestAtmosphereClient {
  public GuestAtmosphereClient(ModContainer container) {
    container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
  }
}
