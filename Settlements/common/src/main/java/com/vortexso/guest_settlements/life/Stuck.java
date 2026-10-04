package com.vortexso.guest_settlements.life;

import com.vortexso.guest_settlements.GuestSettlements;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

final class Stuck {
  private static final int CHECKS = 2;
  private static final long CANT_REACH_FOR = 100;

  private static final Map<Villager, Vec3> LAST = new WeakHashMap<>();
  private static final Map<Villager, Integer> STILL = new WeakHashMap<>();
  private static final Map<Villager, BlockPos> HEADING = new WeakHashMap<>();

  private Stuck() {}

  static void check(ServerLevel level, Villager villager, BlockPos home) {
    Vec3 last = LAST.put(villager, villager.position());
    Brain<Villager> brain = villager.getBrain();
    Optional<WalkTarget> walk = brain.getMemory(MemoryModuleType.WALK_TARGET);
    boolean trying =
        walk.isPresent()
            || brain
                .getMemory(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)
                .filter(since -> level.getGameTime() - since > CANT_REACH_FOR)
                .isPresent();
    if (last == null
        || !trying
        || villager.isSleeping()
        || villager.isPassenger()
        || last.distanceToSqr(villager.position()) > 0.25) {
      STILL.remove(villager);
      HEADING.remove(villager);
      return;
    }
    Path path = villager.getNavigation().getPath();
    if (path != null) {
      BlockPos at = villager.blockPosition();
      for (int i = path.getNextNodeIndex(); i < path.getNodeCount(); i++) {
        BlockPos node = path.getNodePos(i);
        if (node.getX() != at.getX() || node.getZ() != at.getZ()) {
          HEADING.put(villager, node);
          break;
        }
      }
    }
    if (STILL.merge(villager, 1, Integer::sum) < CHECKS) {
      return;
    }
    STILL.remove(villager);
    BlockPos target =
        HEADING.getOrDefault(
            villager, walk.map(w -> w.getTarget().currentBlockPosition()).orElse(home));
    if (dig(level, villager, target)) {
      GuestSettlements.LOGGER.debug(
          "{} dug itself out at {} heading to {}",
          villager.getUUID(),
          villager.blockPosition().toShortString(),
          target.toShortString());
    }
    villager.getNavigation().stop();
    brain.eraseMemory(MemoryModuleType.PATH);
  }

  private static boolean dig(ServerLevel level, Villager villager, BlockPos target) {
    if (!level.getGameRules().get(GameRules.MOB_GRIEFING)) {
      return false;
    }
    BlockPos pos = villager.blockPosition();
    Direction heading =
        Direction.getApproximateNearest(target.getX() - pos.getX(), 0, target.getZ() - pos.getZ());
    BlockPos front = pos.relative(heading);
    for (BlockPos ahead : List.of(front, front.relative(heading))) {
      for (BlockPos cover : List.of(ahead, ahead.above())) {
        if (level.getBlockState(cover).is(GuestSettlements.CLEARABLE_COVER)) {
          return level.destroyBlock(cover, false, villager);
        }
      }
    }
    if (!enclosed(level, pos)) {
      return false;
    }
    boolean climb = target.getY() > pos.getY() && solid(level, front);
    for (BlockPos block :
        climb
            ? List.of(front.above(), front.above(2), pos.above(2))
            : List.of(front.above(), front)) {
      if (solid(level, block)) {
        return level.getBlockState(block).is(GuestSettlements.VILLAGER_DIGGABLE)
            && level.destroyBlock(block, false, villager);
      }
    }
    return false;
  }

  private static boolean enclosed(ServerLevel level, BlockPos pos) {
    if (level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ())
        > pos.getY() + 2) {
      return true;
    }
    for (Direction side : Direction.Plane.HORIZONTAL) {
      if (!solid(level, pos.relative(side).above())) {
        return false;
      }
    }
    return true;
  }

  private static boolean solid(ServerLevel level, BlockPos pos) {
    return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
  }
}
