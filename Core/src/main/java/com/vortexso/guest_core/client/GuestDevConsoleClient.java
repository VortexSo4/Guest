package com.vortexso.guest_core.client;

import com.vortexso.guest_core.GuestCore;
import com.vortexso.guest_core.debug.GuestDevConsole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = GuestCore.MODID, value = Dist.CLIENT)
public final class GuestDevConsoleClient {
  private GuestDevConsoleClient() {}

  @SubscribeEvent
  public static void onClientSetup(FMLClientSetupEvent event) {
    GuestDevConsole.setClientActions(
        action -> Minecraft.getInstance().execute(() -> perform(action.strip())));
  }

  @SubscribeEvent
  public static void onClientTick(ClientTickEvent.Post event) {
    if (System.getProperty("guest.devConsole") == null) {
      return;
    }
    Minecraft minecraft = Minecraft.getInstance();
    if (minecraft.screen instanceof PauseScreen) {
      minecraft.setScreen(null);
    }
  }

  private static void perform(String action) {
    Minecraft minecraft = Minecraft.getInstance();
    String[] parts = action.split("\\s+", 2);
    switch (parts[0]) {
      case "screenshot" -> {
        String name = parts.length > 1 ? parts[1] : "guest";
        Screenshot.grab(
            minecraft.gameDirectory,
            name.endsWith(".png") ? name : name + ".png",
            minecraft.getMainRenderTarget(),
            1,
            message ->
                GuestCore.LOGGER.info(
                    "[GuestDevConsole] screenshot {}: {}", name, message.getString()));
      }
      case "hud" -> minecraft.options.hideGui = parts.length > 1 && parts[1].equals("off");
      default -> GuestCore.LOGGER.warn("[GuestDevConsole] unknown client action: {}", action);
    }
  }
}
