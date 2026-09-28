package com.vortexso.guest_settlements.client;

import com.vortexso.guest_settlements.GuestSettlements;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = GuestSettlements.MODID, dist = Dist.CLIENT)
public final class GuestSettlementsClient {
  public GuestSettlementsClient(ModContainer container) {
    container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
  }
}
