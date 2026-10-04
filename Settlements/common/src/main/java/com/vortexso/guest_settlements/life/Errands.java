package com.vortexso.guest_settlements.life;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_settlements.mixin.ActivityAccessor;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.behavior.PositionTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class Errands {

  public static final Activity ACTIVITY =
      ActivityAccessor.guestSettlements$create("guest_settlements:errand");

  private static final String CARRYING_TAG = "guest_settlements.carrying";
  private static final int MAX_WALK_TICKS = 1200;

  private static final int STUCK_TICKS = 200;

  private static final long UNREACHABLE_FOR = 24000;
  private static final int UNREACHABLE_RADIUS = 3;

  private static final java.util.Set<VillageLife.Role> SETTLING =
      java.util.EnumSet.of(
          VillageLife.Role.RITE,
          VillageLife.Role.RINGER,
          VillageLife.Role.MOURNING,
          VillageLife.Role.AURORA,
          VillageLife.Role.STORM,
          VillageLife.Role.SHELTER,
          VillageLife.Role.LISTENING,
          VillageLife.Role.READING,
          VillageLife.Role.CLOUDS,
          VillageLife.Role.SERVICE);

  private static final Map<ServerLevel, Map<BlockPos, Long>> UNREACHABLE = new WeakHashMap<>();

  private static final Map<Villager, Errand> ERRANDS = new WeakHashMap<>();
  private static final Map<Brain<?>, Boolean> EXTENDED = new WeakHashMap<>();

  private Errands() {}

  @FunctionalInterface
  public interface Action {
    Action NONE = (level, villager, tick) -> {};

    void at(ServerLevel level, Villager villager, int tick);
  }

  public record Step(
      @Nullable PositionTracker where,
      int closeEnough,
      float speed,
      int ticks,
      @Nullable PositionTracker look,
      @Nullable ItemStack held,
      Action action) {

    public static Step walk(BlockPos pos, int closeEnough, float speed) {
      return new Step(new BlockPosTracker(pos), closeEnough, speed, 1, null, null, Action.NONE);
    }

    public static Step at(BlockPos pos, int closeEnough, int ticks, ItemStack held, Action action) {
      return new Step(new BlockPosTracker(pos), closeEnough, 0.5F, ticks, null, held, action);
    }

    public static Step wait(int ticks, @Nullable PositionTracker look, Action action) {
      return new Step(null, 0, 0.5F, ticks, look, null, action);
    }

    public Step looking(PositionTracker look) {
      return new Step(where, closeEnough, speed, ticks, look, held, action);
    }

    public Step holding(ItemStack held) {
      return new Step(where, closeEnough, speed, ticks, look, held, action);
    }

    public Step fast(float speed) {
      return new Step(where, closeEnough, speed, ticks, look, held, action);
    }
  }

  public static final class Errand {
    final VillageLife.Role role;
    final int priority;
    long deadline;
    final List<Step> steps;
    int index;
    int ticks;
    int walking;
    int stuck;
    boolean settled;
    @Nullable Vec3 standing;
    @Nullable Runnable onEnd;

    public Errand(VillageLife.Role role, int priority, long deadline, List<Step> steps) {
      this.role = role;
      this.priority = priority;
      this.deadline = deadline;
      this.steps = List.copyOf(steps);
    }

    public Errand onEnd(Runnable onEnd) {
      this.onEnd = onEnd;
      return this;
    }

    public VillageLife.Role role() {
      return role;
    }

    public void extend(long deadline) {
      this.deadline = Math.max(this.deadline, deadline);
    }

    public @Nullable Step step() {
      return index < steps.size() ? steps.get(index) : null;
    }

    public void skipTo(int step) {
      index = step;
      ticks = 0;
      walking = 0;
      stuck = 0;
      settled = false;
      standing = null;
    }

    public int stepIndex() {
      return index;
    }
  }

  public static @Nullable Errand current(Villager villager) {
    return ERRANDS.get(villager);
  }

  public static boolean busy(Villager villager) {
    return ERRANDS.containsKey(villager);
  }

  public static boolean canTake(Villager villager, int priority) {
    Errand errand = ERRANDS.get(villager);
    return (errand == null || errand.priority < priority) && available(villager);
  }

  public static boolean doing(Villager villager, VillageLife.Role role) {
    Errand errand = ERRANDS.get(villager);
    return errand != null && errand.role == role;
  }

  public static boolean offer(ServerLevel level, Villager villager, Errand errand) {
    Errand old = ERRANDS.get(villager);
    if (old != null && old.priority >= errand.priority) {
      return false;
    }
    if (!available(villager)) {
      return false;
    }
    if (old != null) {
      finish(villager, old, false);
    }
    ERRANDS.put(villager, errand);
    Brain<Villager> brain = villager.getBrain();
    brain.eraseMemory(MemoryModuleType.WALK_TARGET);
    brain.eraseMemory(MemoryModuleType.PATH);
    brain.eraseMemory(MemoryModuleType.LOOK_TARGET);
    activate(villager);
    return true;
  }

  public static void cancel(Villager villager) {
    Errand errand = ERRANDS.get(villager);
    if (errand != null) {
      end(villager, errand);
    }
  }

  public static void maintain(ServerLevel level, Villager villager) {
    Errand errand = ERRANDS.get(villager);
    if (errand == null) {
      return;
    }
    if (level.getGameTime() > errand.deadline || errand.step() == null) {
      end(villager, errand);
    } else if (!villager.getBrain().isActive(ACTIVITY) && available(villager)) {
      activate(villager);
    }
  }

  public static boolean unreachable(ServerLevel level, BlockPos pos) {
    Map<BlockPos, Long> marks = UNREACHABLE.get(level);
    if (marks == null) {
      return false;
    }
    long now = level.getGameTime();
    marks.values().removeIf(time -> now - time > UNREACHABLE_FOR);
    for (BlockPos mark : marks.keySet()) {
      if (mark.closerThan(pos, UNREACHABLE_RADIUS)) {
        return true;
      }
    }
    return false;
  }

  public static boolean available(Villager villager) {
    Brain<Villager> brain = villager.getBrain();
    return villager.isAlive()
        && !villager.isTrading()
        && !brain.isActive(Activity.PANIC)
        && !brain.isActive(Activity.HIDE)
        && !brain.isActive(Activity.RAID)
        && !brain.isActive(Activity.PRE_RAID);
  }

  public static void hold(Villager villager, ItemStack item) {
    if (item.isEmpty()) {
      release(villager);
      return;
    }
    if (!ItemStack.isSameItemSameComponents(villager.getMainHandItem(), item)) {
      villager.setItemSlot(EquipmentSlot.MAINHAND, item.copy());
    }
    villager.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    villager.addTag(CARRYING_TAG);
  }

  public static void release(@Nullable Villager villager) {
    if (villager != null && villager.entityTags().contains(CARRYING_TAG)) {
      villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);

      villager.setDropChance(EquipmentSlot.MAINHAND, 0.085F);
      villager.removeTag(CARRYING_TAG);
    }
  }

  public static boolean carrying(Villager villager) {
    return villager.entityTags().contains(CARRYING_TAG);
  }

  public static PositionTracker at(BlockPos pos) {
    return new BlockPosTracker(pos);
  }

  public static PositionTracker at(Vec3 pos) {
    return new BlockPosTracker(pos);
  }

  public static PositionTracker at(Entity entity) {
    return new EntityTracker(entity, true);
  }

  private static void activate(Villager villager) {
    Brain<Villager> brain = villager.getBrain();

    if (EXTENDED.put(brain, Boolean.TRUE) == null) {
      brain.addActivity(
          ACTIVITY,
          ImmutableList.of(Pair.of(0, new Runner())),
          ImmutableSet.of(),
          ImmutableSet.of());
    }
    brain.setActiveActivityIfPossible(ACTIVITY);
  }

  private static void end(Villager villager, Errand errand) {
    finish(villager, errand, true);
    ERRANDS.remove(villager);
  }

  private static void finish(Villager villager, Errand errand, boolean returnToSchedule) {
    release(villager);
    if (errand.onEnd != null) {
      errand.onEnd.run();
      errand.onEnd = null;
    }
    if (returnToSchedule && villager.getBrain().isActive(ACTIVITY)) {
      Brain<Villager> brain = villager.getBrain();
      brain.eraseMemory(MemoryModuleType.WALK_TARGET);
      brain.eraseMemory(MemoryModuleType.LOOK_TARGET);

      brain.useDefaultActivity();
    }
  }

  private static final class Runner extends Behavior<Villager> {
    Runner() {
      super(ImmutableMap.of(), Integer.MAX_VALUE);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, Villager body) {
      return ERRANDS.containsKey(body);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, Villager body, long timestamp) {
      return body.getBrain().isActive(ACTIVITY) && ERRANDS.containsKey(body);
    }

    @Override
    protected void tick(ServerLevel level, Villager body, long timestamp) {
      Errand errand = ERRANDS.get(body);
      if (errand == null) {
        return;
      }
      Step step = errand.step();
      if (step == null || timestamp > errand.deadline) {
        end(body, errand);
        return;
      }
      if (step.held() != null) {
        hold(body, step.held());
      }
      hush(level, body, errand.role);
      Brain<Villager> brain = body.getBrain();
      if (step.where() != null && !errand.settled && !arrived(body, step)) {

        boolean place = step.where() instanceof BlockPosTracker;
        if (errand.standing == null
            || errand.standing.distanceToSqr(body.position()) > 0.25
            || !place) {
          errand.standing = body.position();
          errand.stuck = 0;
        }
        if (++errand.walking > MAX_WALK_TICKS || ++errand.stuck > STUCK_TICKS) {
          if (SETTLING.contains(errand.role)) {

            errand.settled = true;
          } else {
            if (place) {

              UNREACHABLE
                  .computeIfAbsent(level, ignored -> new java.util.HashMap<>())
                  .put(step.where().currentBlockPosition(), timestamp);
            }
            com.vortexso.guest_settlements.GuestSettlements.LOGGER.debug(
                "{} gave up {} at {}: could not reach {}",
                body.getUUID(),
                errand.role,
                body.blockPosition().toShortString(),
                step.where().currentBlockPosition().toShortString());
            end(body, errand);
            return;
          }
        } else {
          WalkTarget walk = brain.getMemory(MemoryModuleType.WALK_TARGET).orElse(null);
          if (walk == null
              || walk.getTarget()
                      .currentBlockPosition()
                      .distManhattan(step.where().currentBlockPosition())
                  > 1) {
            brain.setMemory(
                MemoryModuleType.WALK_TARGET,
                new WalkTarget(step.where(), step.speed(), step.closeEnough()));
          }
          return;
        }
      }
      errand.walking = 0;
      if (step.where() != null) {

        brain.eraseMemory(MemoryModuleType.WALK_TARGET);
      }
      PositionTracker look = step.look() != null ? step.look() : step.where();
      if (look != null) {
        brain.setMemory(MemoryModuleType.LOOK_TARGET, look);
      }
      step.action().at(level, body, errand.ticks);
      if (ERRANDS.get(body) != errand) {
        return;
      }
      if (++errand.ticks >= step.ticks()) {
        errand.skipTo(errand.index + 1);
        if (errand.step() == null) {
          end(body, errand);
        }
      }
    }

    private static void hush(ServerLevel level, Villager body, VillageLife.Role role) {
      boolean mourning =
          role == VillageLife.Role.MOURNING
              || (role == VillageLife.Role.RINGER
                  && GuestTime.weekday(GuestTime.gameTime(level)) == Rites.NEW_MOON);
      if (mourning) {
        body.ambientSoundTime = -body.getAmbientSoundInterval();
      } else if (role == VillageLife.Role.AURORA && level.getGameTime() % 4 != 0) {
        body.ambientSoundTime--;
      }
    }

    private static boolean arrived(Villager body, Step step) {
      BlockPos target = step.where().currentBlockPosition();
      return target.distManhattan(body.blockPosition()) <= step.closeEnough()
          || (Math.abs(target.getY() - body.getY()) <= 3 * Math.max(1, step.closeEnough())
              && Math.abs(target.getX() + 0.5 - body.getX()) <= step.closeEnough() + 0.5
              && Math.abs(target.getZ() + 0.5 - body.getZ()) <= step.closeEnough() + 0.5);
    }
  }

  public static void onLoad(Villager villager) {
    release(villager);
  }
}
