package com.vortexso.guest_atmosphere;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_atmosphere.network.WeatherSyncPayload;
import com.vortexso.guest_atmosphere.trace.ChunkTraces;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.debug.GuestDebug;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.slf4j.Logger;

@Mod(GuestAtmosphere.MODID)
public class GuestAtmosphere {
  public static final String MODID = "guest_atmosphere";
  public static final Logger LOGGER = LogUtils.getLogger();
  public static final String DEBUG_CHANNEL = "atmosphere";

  private static final DeferredRegister<SoundEvent> SOUNDS =
      DeferredRegister.create(Registries.SOUND_EVENT, MODID);
  private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
      DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MODID);

  public static final Supplier<SoundEvent> WIND_SOUND = sound("weather.wind");
  public static final Supplier<SoundEvent> BLIZZARD_SOUND = sound("weather.blizzard");
  public static final Supplier<SoundEvent> SANDSTORM_SOUND = sound("weather.sandstorm");

  public static final Supplier<AttachmentType<ChunkTraces>> CHUNK_TRACES =
      ATTACHMENTS.register(
          "traces",
          () ->
              AttachmentType.builder(() -> new ChunkTraces())
                  .serialize(ChunkTraces.CODEC, ChunkTraces::shouldSave)
                  .build());

  public GuestAtmosphere(IEventBus modBus, ModContainer container) {
    container.registerConfig(ModConfig.Type.SERVER, AtmosphereConfig.SERVER_SPEC);
    container.registerConfig(ModConfig.Type.CLIENT, AtmosphereConfig.CLIENT_SPEC);
    SOUNDS.register(modBus);
    ATTACHMENTS.register(modBus);
    modBus.addListener(WeatherSyncPayload::register);
    modBus.addListener(ModConfigEvent.Loading.class, AtmosphereConfig::onConfig);
    modBus.addListener(ModConfigEvent.Reloading.class, AtmosphereConfig::onConfig);

    GuestWeather.register(AtmosphereWeather::get);
    GuestDebug.register(DEBUG_CHANNEL);
  }

  private static Supplier<SoundEvent> sound(String name) {
    Identifier id = Identifier.fromNamespaceAndPath(MODID, name);
    return SOUNDS.register(name, () -> SoundEvent.createVariableRangeEvent(id));
  }
}
