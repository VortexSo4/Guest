package com.vortexso.guest_settlements.village;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

@EventBusSubscriber(modid = "guest_settlements")
public final class VillageWorldEvents {
  private VillageWorldEvents() {}

  @SubscribeEvent
  public static void onChunkLoad(ChunkEvent.Load event) {
    if (!(event.getLevel() instanceof ServerLevel level)) {
      return;
    }

    VillageWorldManager manager = VillageWorldManager.get(level);

    manager.handleChunkLoad(event.getChunk().getPos());

    manager.queueChunk(event.getChunk().getPos());
  }

  @SubscribeEvent
  public static void onChunkUnload(ChunkEvent.Unload event) {
    if (!(event.getLevel() instanceof ServerLevel level)) {
      return;
    }

    VillageWorldManager.get(level).handleChunkUnload(event.getChunk().getPos());
  }

  @SubscribeEvent
  public static void onBlockBreak(BreakBlockEvent event) {
    if (!(event.getLevel() instanceof ServerLevel level)) {
      return;
    }

    VillageWorldManager.get(level).handleBlockChange(event.getPos());
  }

  @SubscribeEvent
  public static void onEntityPlace(BlockEvent.EntityPlaceEvent event) {
    if (!(event.getLevel() instanceof ServerLevel level)) {
      return;
    }

    VillageWorldManager.get(level).handleBlockChange(event.getPos());
  }

  @SubscribeEvent
  public static void onFarmlandTrample(BlockEvent.FarmlandTrampleEvent event) {
    if (!(event.getLevel() instanceof ServerLevel level)) {
      return;
    }

    VillageWorldManager.get(level).handleBlockChange(event.getPos());
  }

  @SubscribeEvent
  public static void onToolModification(BlockEvent.BlockToolModificationEvent event) {
    if (!(event.getLevel() instanceof ServerLevel level)) {
      return;
    }

    VillageWorldManager.get(level).handleBlockChange(event.getPos());
  }
}
