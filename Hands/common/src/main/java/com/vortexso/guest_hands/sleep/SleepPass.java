package com.vortexso.guest_hands.sleep;

import com.vortexso.guest_hands.HandsConfig;
import com.vortexso.guest_hands.mixin.PlayerAccessor;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.SleepStatus;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.clock.ClockTimeMarkers;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRules;
import org.jspecify.annotations.Nullable;

public final class SleepPass {
  private static final int HELD_SLEEP_TIMER = Player.SLEEP_DURATION - 1;

  private static final Map<ResourceKey<Level>, Pass> PASSES = new HashMap<>();

  private SleepPass() {}

  public static final class Pass {
    public final long start;
    public final long target;
    public int elapsed;
    public boolean done;

    Pass(long start, long target) {
      this.start = start;
      this.target = target;
    }
  }

  public static @Nullable Pass pass(Level level) {
    return PASSES.get(level.dimension());
  }

  private static boolean anySleeping(ServerLevel level) {
    for (ServerPlayer player : level.players()) {
      if (player.isSleeping()) {
        return true;
      }
    }
    return false;
  }

  private static @Nullable Holder<WorldClock> managedClock(ServerLevel level) {
    Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();
    return HandsConfig.SLEEP_ENABLED.get()
            && clock.isPresent()
            && level.getGameRules().get(GameRules.ADVANCE_TIME)
            && level.canSleepThroughNights()
        ? clock.get()
        : null;
  }

  public static void levelTick(ServerLevel level) {
    Pass pass = PASSES.get(level.dimension());
    if (pass == null && !anySleeping(level)) {
      return;
    }
    Holder<WorldClock> clock = managedClock(level);
    if (clock == null) {
      PASSES.remove(level.dimension());
      return;
    }
    int percentage = level.getGameRules().get(GameRules.PLAYERS_SLEEPING_PERCENTAGE);
    SleepStatus status = new SleepStatus();
    status.update(level.players());
    long deepSleepers =
        level.players().stream()
            .filter(player -> player.isSleeping() && player.getSleepTimer() >= HELD_SLEEP_TIMER)
            .count();
    boolean enough =
        status.areEnoughSleeping(percentage) && deepSleepers >= status.sleepersNeeded(percentage);
    ServerClockManager clocks = level.clockManager();

    if (pass == null) {
      if (enough) {
        long now = clocks.getTotalTicks(clock);
        if (clocks.moveToTimeMarker(clock, ClockTimeMarkers.WAKE_UP_FROM_SLEEP)) {
          long target = clocks.getTotalTicks(clock);
          clocks.setTotalTicks(clock, now);
          PASSES.put(level.dimension(), new Pass(now, target));
        }
      }
      return;
    }
    if (pass.done) {
      if (status.amountSleeping() == 0) {
        PASSES.remove(level.dimension());
      }
      return;
    }
    if (!enough) {

      PASSES.remove(level.dimension());
      return;
    }
    int duration = HandsConfig.NIGHT_PASS_SECONDS.get() * 20;
    pass.elapsed++;
    clocks.setTotalTicks(
        clock,
        pass.start + (pass.target - pass.start) * Math.min(pass.elapsed, duration) / duration);
    pass.done = pass.elapsed >= duration;
  }

  public static void holdSleepTimer(ServerPlayer player) {
    if (!player.isSleeping()
        || player.getSleepTimer() < HELD_SLEEP_TIMER
        || managedClock(player.level()) == null) {
      return;
    }
    Pass pass = PASSES.get(player.level().dimension());
    if (pass == null || !pass.done) {
      ((PlayerAccessor) player).guest_hands$setSleepCounter(HELD_SLEEP_TIMER);
    }
  }

  public static boolean allowTimeJump(ServerLevel level) {
    Pass pass = PASSES.get(level.dimension());
    return pass == null || !pass.done;
  }

  public static boolean keepSleeping(ServerPlayer player) {
    return PASSES.containsKey(player.level().dimension())
        && player
            .getSleepingPos()
            .map(pos -> player.level().getBlockState(pos).is(BlockTags.BEDS))
            .orElse(false);
  }
}
