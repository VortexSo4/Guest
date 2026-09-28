package com.vortexso.guest_wilds.herd;

import com.vortexso.guest_wilds.Membership;
import com.vortexso.guest_wilds.WildsParameters;
import java.util.EnumSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class HerdGoal extends Goal {
  private static final int RECHECK_TICKS = 20;
  private static final double ARRIVED = 4.0;
  private static final double CLOSE = 3.0;

  private final PathfinderMob mob;
  private @Nullable Vec3 target;
  private @Nullable Mob leader;
  private double speed;

  public HerdGoal(PathfinderMob mob) {
    this.mob = mob;
    setFlags(EnumSet.of(Flag.MOVE));
  }

  @Override
  public boolean canUse() {
    if ((mob.tickCount + mob.getId()) % RECHECK_TICKS != 0
        || !(mob.level() instanceof ServerLevel level)) {
      return false;
    }
    Membership membership = Membership.of(mob);
    if (membership == null || membership.kind() != Membership.Kind.HERD) {
      return false;
    }
    Herds herds = Herds.get(level);
    Herds.Herd herd = herds.herd(membership.node());
    if (herd == null) {
      return false;
    }
    Mob head = herds.leader(level, herd, mob);
    if (head == mob) {
      BlockPos destination = herds.destination(level, herd, mob);
      if (destination == null || mob.distanceToSqr(destination.getCenter()) < ARRIVED * ARRIVED) {
        return false;
      }
      leader = null;
      target = Vec3.atBottomCenterOf(destination);
      speed = herd.isPack() ? 1.1 : 0.9;
      return true;
    }
    int radius = WildsParameters.DEFAULT.herdFollowRadius();
    double distance = mob.distanceTo(head);
    if (distance <= radius) {
      return false;
    }
    leader = head;
    target = spot(head);

    speed = distance > 3 * radius ? 1.3 : 1.05;
    return true;
  }

  private Vec3 spot(Mob head) {
    double angle = (mob.getId() * 2.399963) % (Math.PI * 2.0);
    double ring = 2.0 + (mob.getId() % 3);
    return head.position().add(Math.cos(angle) * ring, 0.0, Math.sin(angle) * ring);
  }

  @Override
  public void start() {
    if (target != null) {
      mob.getNavigation().moveTo(target.x, target.y, target.z, speed);
    }
  }

  @Override
  public boolean canContinueToUse() {
    if (mob.getNavigation().isDone()) {
      return false;
    }
    return leader == null || (leader.isAlive() && mob.distanceTo(leader) > CLOSE);
  }

  @Override
  public void stop() {
    target = null;
    leader = null;
  }
}
