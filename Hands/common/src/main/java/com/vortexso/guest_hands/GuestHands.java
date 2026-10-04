package com.vortexso.guest_hands;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_hands.grapple.Grapple;
import com.vortexso.guest_hands.sleep.SleepPass;
import com.vortexso.guest_hands.station.Stations;
import org.slf4j.Logger;

public final class GuestHands {
  public static final String MODID = "guest_hands";
  public static final String DEBUG_CHANNEL = "hands";
  public static final Logger LOGGER = LogUtils.getLogger();

  private GuestHands() {}

  public static void init() {
    Platform.INSTANCE.registerConfig(MODID, Platform.ConfigType.SERVER, HandsConfig.SPEC, null);
    GuestDebug.register(DEBUG_CHANNEL);
    Events.COMMANDS.register(HandsCommands::register);
    Events.USE_BLOCK.register(Stations::rightClick);
    Events.SERVER_TICK.register(Stations::sweep);
    Events.PLAYER_TICK.register(Grapple::tick);
    Events.PLAYER_TICK.register(SleepPass::holdSleepTimer);
  }
}
