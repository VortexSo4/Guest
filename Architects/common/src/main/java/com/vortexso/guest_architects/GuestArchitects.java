package com.vortexso.guest_architects;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.vortexso.guest_architects.block.ArchitectPortalBlock;
import com.vortexso.guest_architects.block.ArchitectPortalBlockEntity;
import com.vortexso.guest_architects.command.ArchitectsCommands;
import com.vortexso.guest_architects.entity.Architect;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_core.platform.Attachment;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_core.platform.Registrar;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import org.slf4j.Logger;

public final class GuestArchitects {
  public static final String MODID = "guest_architects";
  public static final String DEBUG_CHANNEL = "architects";
  public static final Logger LOGGER = LogUtils.getLogger();

  private static final Registrar<EntityType<?>> ENTITY_TYPES =
      Platform.INSTANCE.registrar(MODID, Registries.ENTITY_TYPE);
  private static final Registrar<SoundEvent> SOUNDS =
      Platform.INSTANCE.registrar(MODID, Registries.SOUND_EVENT);
  private static final Registrar<Block> BLOCKS =
      Platform.INSTANCE.registrar(MODID, Registries.BLOCK);
  private static final Registrar<BlockEntityType<?>> BLOCK_ENTITIES =
      Platform.INSTANCE.registrar(MODID, Registries.BLOCK_ENTITY_TYPE);

  public static final Supplier<EntityType<Architect>> ARCHITECT =
      ENTITY_TYPES.register(
          "architect",
          key ->
              EntityType.Builder.of(Architect::new, MobCategory.MISC)
                  .sized(0.5F, 2.1F)
                  .eyeHeight(1.95F)
                  .clientTrackingRange(10)
                  .noLootTable()
                  .build(key));

  public static final Supplier<SoundEvent> MELODY = sound("architect.melody");
  public static final Supplier<SoundEvent> PORTAL = sound("architect.portal");
  public static final Supplier<SoundEvent> UNMAKE = sound("architect.unmake");

  public static final Supplier<ArchitectPortalBlock> PORTAL_BLOCK =
      Registrar.block(
          BLOCKS,
          "architect_portal",
          ArchitectPortalBlock::new,
          properties ->
              properties
                  .mapColor(MapColor.COLOR_BLACK)
                  .noCollision()
                  .lightLevel(state -> 15)
                  .strength(-1.0F, 3600000.0F)
                  .noLootTable()
                  .pushReaction(PushReaction.BLOCK));

  public static Supplier<BlockEntityType<ArchitectPortalBlockEntity>> PORTAL_BLOCK_ENTITY;

  public static final Attachment<Integer> CURING =
      Platform.INSTANCE.attachment(
          MODID, "curing", () -> 0, Codec.INT.fieldOf("ticks"), null, ByteBufCodecs.VAR_INT);

  public static final Attachment<Long> CONVERSION_MARK =
      Platform.INSTANCE.attachment(
          MODID,
          "conversion_delay",
          () -> Long.MIN_VALUE / 2,
          Codec.LONG.fieldOf("tick"),
          null,
          null);

  private GuestArchitects() {}

  public static void init(
      Function<Block, BlockEntityType<ArchitectPortalBlockEntity>> portalBlockEntity) {
    PORTAL_BLOCK_ENTITY =
        BLOCK_ENTITIES.register(
            "architect_portal", key -> portalBlockEntity.apply(PORTAL_BLOCK.get()));
    Platform.INSTANCE.registerConfig(
        MODID, Platform.ConfigType.SERVER, ArchitectsConfig.SPEC, null);
    GuestDebug.register(DEBUG_CHANNEL);
    Events.COMMANDS.register(ArchitectsCommands::register);
    ArchitectsEvents.init();
  }

  private static Supplier<SoundEvent> sound(String name) {
    return SOUNDS.register(name, key -> SoundEvent.createVariableRangeEvent(key.identifier()));
  }
}
