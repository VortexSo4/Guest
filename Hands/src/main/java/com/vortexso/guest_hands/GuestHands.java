package com.vortexso.guest_hands;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_core.debug.GuestDebug;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(GuestHands.MODID)
public class GuestHands {
  public static final String MODID = "guest_hands";
  public static final String DEBUG_CHANNEL = "hands";
  public static final Logger LOGGER = LogUtils.getLogger();

  public GuestHands(IEventBus modBus, ModContainer container) {
    container.registerConfig(ModConfig.Type.SERVER, HandsConfig.SPEC);
    GuestDebug.register(DEBUG_CHANNEL);
  }
}
