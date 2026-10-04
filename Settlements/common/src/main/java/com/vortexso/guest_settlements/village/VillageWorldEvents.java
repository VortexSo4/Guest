package com.vortexso.guest_settlements.village;

import com.vortexso.guest_settlements.life.VillageLife;
import com.vortexso.guest_settlements.mixin.MobAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.FleeSunGoal;
import net.minecraft.world.entity.ai.goal.RestrictSunGoal;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public final class VillageWorldEvents {
  private VillageWorldEvents() {}

  public static void onLevelTick(ServerLevel level) {
    VillageWorldManager manager = VillageWorldManager.get(level);
    manager.tick();
    VillageLife.tick(level, manager);
  }

  public static boolean onJoin(Entity entity, ServerLevel level, boolean fromDisk) {
    if (entity instanceof ZombieVillager zombie
        && zombie.entityTags().contains(VillageStateRestorer.INFECTED_TAG)) {
      ((MobAccessor) zombie)
          .guestSettlements$goalSelector()
          .addGoal(1, new RestrictSunGoal(zombie));
      ((MobAccessor) zombie)
          .guestSettlements$goalSelector()
          .addGoal(2, new FleeSunGoal(zombie, 1.0));
    }
    return true;
  }

  public static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean newChunk) {
    VillageWorldManager manager = VillageWorldManager.get(level);
    manager.handleChunkLoad(chunk.getPos());
    manager.queueChunk(chunk.getPos());
  }

  public static void onChunkUnload(ServerLevel level, LevelChunk chunk) {
    VillageWorldManager.get(level).handleChunkUnload(chunk.getPos());
  }

  public static boolean onBlockBreak(
      ServerLevel level, Player player, BlockPos pos, BlockState state) {
    VillageWorldManager.get(level).handleBlockChange(pos);
    return true;
  }

  public static void blockChanged(Level level, BlockPos pos) {
    if (level instanceof ServerLevel server) {
      VillageWorldManager.get(server).handleBlockChange(pos);
    }
  }
}
