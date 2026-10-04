package com.vortexso.guest_core;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_core.command.GuestCoreCommands;
import com.vortexso.guest_core.debug.GuestDevConsole;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import org.slf4j.Logger;

public final class GuestCore {
  public static final String MODID = "guest_core";
  public static final Logger LOGGER = LogUtils.getLogger();

  private GuestCore() {}

  public static void init() {
    Platform.INSTANCE.registerConfig(
        MODID, Platform.ConfigType.COMMON, GuestCoreConfig.SPEC, GuestCoreConfig::apply);
    Events.COMMANDS.register(GuestCoreCommands::register);
    Events.SERVER_TICK.register(GuestDevConsole::tick);
  }
}
