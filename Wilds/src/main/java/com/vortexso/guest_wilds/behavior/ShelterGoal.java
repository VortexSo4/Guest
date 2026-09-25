package com.vortexso.guest_wilds.behavior;

import java.util.EnumSet;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import org.jspecify.annotations.Nullable;

/**
 * Walks to a place chosen by {@code destination} whenever it returns one. One goal serves every "go
 * somewhere because of the world state" reaction: undead seeking cover before dawn, lair members
 * surfacing at dusk, animals and spiders sheltering from storms.
 */
public final class ShelterGoal extends Goal {
  private static final int RECHECK_TICKS = 20;

  private final PathfinderMob mob;
  private final double speed;
  private final Function<PathfinderMob, @Nullable BlockPos> destination;
  private @Nullable BlockPos target;

  public ShelterGoal(
      PathfinderMob mob, double speed, Function<PathfinderMob, @Nullable BlockPos> destination) {
    this.mob = mob;
    this.speed = speed;
    this.destination = destination;
    setFlags(EnumSet.of(Flag.MOVE));
  }

  @Override
  public boolean canUse() {
    // Spread the world-state checks over ticks instead of every mob every tick.
    if ((mob.tickCount + mob.getId()) % RECHECK_TICKS != 0) {
      return false;
    }
    target = destination.apply(mob);
    return target != null;
  }

  @Override
  public void start() {
    if (target != null) {
      mob.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, speed);
    }
  }

  @Override
  public boolean canContinueToUse() {
    return !mob.getNavigation().isDone();
  }

  @Override
  public void stop() {
    target = null;
  }
}
