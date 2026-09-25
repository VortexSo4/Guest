package com.vortexso.guest_settlements.village;

import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.life.VillageLife;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.FleeSunGoal;
import net.minecraft.world.entity.ai.goal.RestrictSunGoal;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class VillageWorldEvents {
  private VillageWorldEvents() {}

  @SubscribeEvent
  public static void onLevelTick(LevelTickEvent.Post event) {
    if (event.getLevel() instanceof ServerLevel level) {
      VillageWorldManager manager = VillageWorldManager.get(level);
      manager.tick();
      VillageLife.tick(level, manager);
    }
  }

  /** Infected families of a fallen village keep to the houses by day. */
  @SubscribeEvent
  public static void onJoin(EntityJoinLevelEvent event) {
    if (event.getEntity() instanceof ZombieVillager zombie
        && zombie.entityTags().contains(VillageStateRestorer.INFECTED_TAG)) {
      zombie.goalSelector.addGoal(1, new RestrictSunGoal(zombie));
      zombie.goalSelector.addGoal(2, new FleeSunGoal(zombie, 1.0));
    }
  }

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
