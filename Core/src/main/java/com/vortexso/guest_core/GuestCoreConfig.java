package com.vortexso.guest_core;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;

@EventBusSubscriber(modid = GuestCore.MODID)
public final class GuestCoreConfig {
  private static final String GUEST_LOGGERS = "com.vortexso";

  private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

  public static final ModConfigSpec.BooleanValue DEBUG_LOGGING =
      BUILDER
          .comment(
              "Log informational and debug messages of all Guest mods. When off, only warnings and"
                  + " errors are written.")
          .translation("guest_core.configuration.debugLogging")
          .define("debugLogging", true);

  public static final ModConfigSpec SPEC = BUILDER.build();

  private GuestCoreConfig() {}

  @SubscribeEvent
  public static void onLoad(ModConfigEvent.Loading event) {
    apply(event);
  }

  @SubscribeEvent
  public static void onReload(ModConfigEvent.Reloading event) {
    apply(event);
  }

  private static void apply(ModConfigEvent event) {
    if (event.getConfig().getSpec() != SPEC) {
      return;
    }
    Configurator.setLevel(
        GUEST_LOGGERS, DEBUG_LOGGING.get() ? LogManager.getRootLogger().getLevel() : Level.WARN);
  }
}
