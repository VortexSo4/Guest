package com.vortexso.guest_architects;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.vortexso.guest_architects.block.ArchitectPortalBlock;
import com.vortexso.guest_architects.block.ArchitectPortalBlockEntity;
import com.vortexso.guest_architects.entity.Architect;
import com.vortexso.guest_core.debug.GuestDebug;
import java.util.function.Supplier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
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
  private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MODID);
  private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
      DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, MODID);
  private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
      DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, MODID);

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
  public static final DeferredHolder<SoundEvent, SoundEvent> UNMAKE =
      SOUNDS.register("architect.unmake", SoundEvent::createVariableRangeEvent);

  public static final DeferredBlock<ArchitectPortalBlock> PORTAL_BLOCK =
      BLOCKS.registerBlock(
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

  public static final Supplier<BlockEntityType<ArchitectPortalBlockEntity>> PORTAL_BLOCK_ENTITY =
      BLOCK_ENTITIES.register(
          "architect_portal",
          () -> new BlockEntityType<>(ArchitectPortalBlockEntity::new, PORTAL_BLOCK.get()));

  public static final Supplier<AttachmentType<Integer>> CURING =
      ATTACHMENTS.register(
          "curing",
          () ->
              AttachmentType.builder(() -> 0)
                  .serialize(Codec.INT.fieldOf("ticks"))
                  .sync(ByteBufCodecs.VAR_INT)
                  .build());

  public GuestArchitects(IEventBus modBus, ModContainer container) {
    ENTITY_TYPES.register(modBus);
    SOUNDS.register(modBus);
    BLOCKS.register(modBus);
    BLOCK_ENTITIES.register(modBus);
    ATTACHMENTS.register(modBus);
    modBus.addListener(GuestArchitects::attributes);
    container.registerConfig(ModConfig.Type.SERVER, ArchitectsConfig.SPEC);
    GuestDebug.register(DEBUG_CHANNEL);
  }

  private static void attributes(EntityAttributeCreationEvent event) {
    event.put(ARCHITECT.get(), Architect.createAttributes().build());
  }
}
