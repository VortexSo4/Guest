package com.vortexso.guest_atmosphere;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.command.AtmosphereCommands;
import com.vortexso.guest_atmosphere.trace.ChunkTraces;
import com.vortexso.guest_atmosphere.trace.FireTraces;
import com.vortexso.guest_atmosphere.trace.PlaceAllCommand;
import com.vortexso.guest_atmosphere.trace.TraceCommands;
import com.vortexso.guest_atmosphere.trace.TraceInteractions;
import com.vortexso.guest_atmosphere.trace.TraceSimulator;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_atmosphere.weather.WeatherDriver;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_core.platform.Attachment;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_core.platform.Registrar;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import org.slf4j.Logger;

public final class GuestAtmosphere {
  public static final String MODID = "guest_atmosphere";
  public static final Logger LOGGER = LogUtils.getLogger();
  public static final String DEBUG_CHANNEL = "atmosphere";

  private static final Registrar<SoundEvent> SOUNDS =
      Platform.INSTANCE.registrar(MODID, Registries.SOUND_EVENT);

  public static final Supplier<SoundEvent> WIND_SOUND = sound("weather.wind");
  public static final Supplier<SoundEvent> BLIZZARD_SOUND = sound("weather.blizzard");
  public static final Supplier<SoundEvent> SANDSTORM_SOUND = sound("weather.sandstorm");
  public static final Supplier<SoundEvent> DRIZZLE_SOUND = sound("weather.drizzle");
  public static final Supplier<SoundEvent> DOWNPOUR_SOUND = sound("weather.downpour");
  public static final Supplier<SoundEvent> INSECTS_SOUND = sound("ambient.heat.insects");
  public static final Supplier<SoundEvent> TREE_CREAK_SOUND = sound("ambient.wind.tree_creak");
  public static final Supplier<SoundEvent> BELL_RUSTLE_SOUND = sound("ambient.wind.bell");

  public static final Attachment<ChunkTraces> CHUNK_TRACES =
      Platform.INSTANCE.attachment(
          MODID, "traces", ChunkTraces::new, ChunkTraces.CODEC, ChunkTraces::shouldSave, null);

  private GuestAtmosphere() {}

  public static void init() {
    Platform.INSTANCE.registerConfig(
        MODID,
        Platform.ConfigType.SERVER,
        AtmosphereConfig.SERVER_SPEC,
        AtmosphereConfig::onServerConfig);
    Platform.INSTANCE.registerConfig(
        MODID, Platform.ConfigType.CLIENT, AtmosphereConfig.CLIENT_SPEC, null);
    AtmosphereBlocks.init();
    TraceInteractions.init();
    Events.COMMANDS.register(AtmosphereCommands::register);
    Events.COMMANDS.register(TraceCommands::register);
    Events.COMMANDS.register(PlaceAllCommand::register);
    Events.LEVEL_TICK.register(TraceSimulator::onLevelTick);
    Events.SERVER_TICK.register(WeatherDriver::onServerTick);
    Events.ENTITY_JOIN.register(FireTraces::onEntityJoin);
    GuestWeather.register(AtmosphereWeather::get);
    GuestDebug.register(DEBUG_CHANNEL);
  }

  private static Supplier<SoundEvent> sound(String name) {
    Identifier id = Identifier.fromNamespaceAndPath(MODID, name);
    return SOUNDS.register(name, key -> SoundEvent.createVariableRangeEvent(id));
  }
}
