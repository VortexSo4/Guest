package com.vortexso.guest_core.client;

import com.vortexso.guest_core.GuestCore;
import com.vortexso.guest_core.debug.GuestDevConsole;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;

public final class GuestDevConsoleClient {
  private GuestDevConsoleClient() {}

  static void init() {
    GuestDevConsole.setClientActions(
        action -> Minecraft.getInstance().execute(() -> perform(action.strip())));
  }

  static void tick() {
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
