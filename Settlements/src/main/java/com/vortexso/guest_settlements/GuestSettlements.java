package com.vortexso.guest_settlements;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_core.debug.GuestDebug;
import net.minecraft.resources.Identifier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(GuestSettlements.MODID)
public class GuestSettlements {
  public static final String MODID = "guest_settlements";
  public static final Logger LOGGER = LogUtils.getLogger();

  /** Debug channel toggled with {@code /guest debug settlements}. */
  public static final String DEBUG_CHANNEL = "settlements";

  public GuestSettlements(IEventBus modBus, ModContainer container) {
    container.registerConfig(ModConfig.Type.SERVER, SettlementsConfig.SPEC);
    GuestDebug.register(DEBUG_CHANNEL);
  }

  public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(MODID, path);
  }
}
