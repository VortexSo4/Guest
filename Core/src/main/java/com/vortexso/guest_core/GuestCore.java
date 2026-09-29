package com.vortexso.guest_core;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(GuestCore.MODID)
public class GuestCore {
  public static final String MODID = "guest_core";
  public static final Logger LOGGER = LogUtils.getLogger();

  public GuestCore(ModContainer container) {
    container.registerConfig(ModConfig.Type.COMMON, GuestCoreConfig.SPEC);
  }
}
