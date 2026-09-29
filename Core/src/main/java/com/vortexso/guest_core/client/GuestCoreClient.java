package com.vortexso.guest_core.client;

import com.vortexso.guest_core.GuestCore;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = GuestCore.MODID, dist = Dist.CLIENT)
public final class GuestCoreClient {
  public GuestCoreClient(ModContainer container) {
    container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
  }
}
