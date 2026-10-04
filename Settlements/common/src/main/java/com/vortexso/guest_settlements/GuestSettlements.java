package com.vortexso.guest_settlements;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.Codec;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_core.platform.Attachment;
import com.vortexso.guest_core.platform.Events;
import com.vortexso.guest_core.platform.Platform;
import com.vortexso.guest_settlements.command.SettlementsCommands;
import com.vortexso.guest_settlements.command.SocietyCommands;
import com.vortexso.guest_settlements.illager.IllagerCamps;
import com.vortexso.guest_settlements.illager.IllagerDefection;
import com.vortexso.guest_settlements.illager.IllagerRespect;
import com.vortexso.guest_settlements.illager.IllagerRhythm;
import com.vortexso.guest_settlements.life.LifeCommands;
import com.vortexso.guest_settlements.life.VillageLife;
import com.vortexso.guest_settlements.memory.VillageMemory;
import com.vortexso.guest_settlements.society.Traces;
import com.vortexso.guest_settlements.village.CaravanScenes;
import com.vortexso.guest_settlements.village.Emigration;
import com.vortexso.guest_settlements.village.VillageWorldEvents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class GuestSettlements {
  public static final String MODID = "guest_settlements";
  public static final Logger LOGGER = LogUtils.getLogger();

  public static final String DEBUG_CHANNEL = "settlements";

  public static final TagKey<Block> CLEARABLE_COVER = blocks("clearable_cover");
  public static final TagKey<Block> VILLAGER_DIGGABLE = blocks("villager_diggable");

  public static final Attachment<Integer> LONE_TICKS =
      Platform.INSTANCE.attachment(
          MODID, "lone_ticks", () -> 0, Codec.INT.fieldOf("ticks"), null, null);

  private GuestSettlements() {}

  public static void init() {
    Platform.INSTANCE.registerConfig(
        MODID, Platform.ConfigType.SERVER, SettlementsConfig.SPEC, null);
    GuestDebug.register(DEBUG_CHANNEL);
    Events.COMMANDS.register(SettlementsCommands::register);
    Events.COMMANDS.register(SocietyCommands::register);
    Events.COMMANDS.register(LifeCommands::register);
    Events.LEVEL_TICK.register(VillageWorldEvents::onLevelTick);
    Events.LEVEL_TICK.register(IllagerCamps::onLevelTick);
    Events.LEVEL_TICK.register(IllagerDefection::onLevelTick);
    Events.LEVEL_TICK.register(IllagerRespect::onLevelTick);
    Events.LEVEL_TICK.register(Traces::onLevelTick);
    Events.LEVEL_TICK.register(CaravanScenes::onLevelTick);
    Events.LEVEL_TICK.register(Emigration::onLevelTick);
    Events.PLAYER_TICK.register(IllagerRhythm::onPlayerTick);
    Events.CHUNK_LOAD.register(VillageWorldEvents::onChunkLoad);
    Events.CHUNK_LOAD.register(IllagerCamps::onChunkLoad);
    Events.CHUNK_UNLOAD.register(VillageWorldEvents::onChunkUnload);
    Events.BLOCK_BREAK.register(VillageWorldEvents::onBlockBreak);
    Events.ENTITY_JOIN.register(IllagerCamps::onJoin);
    Events.ENTITY_JOIN.register(CaravanScenes::onJoin);
    Events.ENTITY_JOIN.register(VillageWorldEvents::onJoin);
    Events.ENTITY_JOIN.register(IllagerDefection::onJoin);
    Events.ENTITY_JOIN.register(VillageLife::onJoin);
    Events.ENTITY_JOIN.register(VillageMemory::onJoin);
    Events.LIVING_DEATH.register(IllagerCamps::onDeath);
    Events.LIVING_DEATH.register(VillageMemory::onDeath);
    Events.LIVING_DEATH.register(CaravanScenes::onDeath);
  }

  public static boolean allowTarget(LivingEntity mob, @Nullable LivingEntity target) {
    return IllagerDefection.allowTarget(mob, target) && IllagerRespect.allowTarget(mob, target);
  }

  public static Identifier id(String path) {
    return Identifier.fromNamespaceAndPath(MODID, path);
  }

  private static TagKey<Block> blocks(String path) {
    return TagKey.create(Registries.BLOCK, id(path));
  }
}
