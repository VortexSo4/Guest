package com.vortexso.guest_hands.client;

import com.vortexso.guest_hands.GuestHands;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = GuestHands.MODID, dist = Dist.CLIENT)
public final class HandsClient {
  public HandsClient(ModContainer container) {
    container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
  }
}
