package com.vortexso.guest_architects;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_architects.entity.Architect;
import com.vortexso.guest_core.debug.GuestDebug;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(GuestArchitects.MODID)
public class GuestArchitects {
  public static final String MODID = "guest_architects";
  public static final String DEBUG_CHANNEL = "architects";
  public static final Logger LOGGER = LogUtils.getLogger();

  private static final DeferredRegister.Entities ENTITY_TYPES =
      DeferredRegister.createEntities(MODID);
  private static final DeferredRegister<SoundEvent> SOUNDS =
      DeferredRegister.create(Registries.SOUND_EVENT, MODID);

  /** Slightly taller than a player (1.8) and narrower (0.6). */
  public static final DeferredHolder<EntityType<?>, EntityType<Architect>> ARCHITECT =
      ENTITY_TYPES.registerEntityType(
          "architect",
          Architect::new,
          MobCategory.MISC,
          builder ->
              builder.sized(0.5F, 2.1F).eyeHeight(1.95F).clientTrackingRange(10).noLootTable());

  public static final DeferredHolder<SoundEvent, SoundEvent> MELODY =
      SOUNDS.register("architect.melody", SoundEvent::createVariableRangeEvent);
  public static final DeferredHolder<SoundEvent, SoundEvent> PORTAL =
      SOUNDS.register("architect.portal", SoundEvent::createVariableRangeEvent);

  public GuestArchitects(IEventBus modBus, ModContainer container) {
    ENTITY_TYPES.register(modBus);
    SOUNDS.register(modBus);
    modBus.addListener(GuestArchitects::attributes);
    container.registerConfig(ModConfig.Type.SERVER, ArchitectsConfig.SPEC);
    GuestDebug.register(DEBUG_CHANNEL);
  }

  private static void attributes(EntityAttributeCreationEvent event) {
    event.put(ARCHITECT.get(), Architect.createAttributes().build());
  }
}
