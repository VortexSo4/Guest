package com.vortexso.guest_hands.sleep;

import com.vortexso.guest_hands.GuestHands;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.level.SleepFinishedTimeEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.jspecify.annotations.Nullable;

/**
 * Sleep passes through the night instead of skipping it. Vanilla wakes everyone once enough players
 * have slept {@link Player#SLEEP_DURATION} ticks (the fade to black) and jumps the clock. Here the
 * sleep timer is held one tick short of that while the clock is walked to the same wake-up time
 * over a few seconds, so the world keeps ticking through the night; then the timer is released and
 * vanilla wakes everyone with no further jump.
 */
@EventBusSubscriber(modid = GuestHands.MODID)
public final class SleepPass {
  private static final int HELD_SLEEP_TIMER = Player.SLEEP_DURATION - 1;

  private static final Map<ResourceKey<Level>, Pass> PASSES = new HashMap<>();

  private SleepPass() {}

  /** Exposed for the debug renderer. */
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

  private static @Nullable Holder<WorldClock> managedClock(ServerLevel level) {
    Optional<Holder<WorldClock>> clock = level.dimensionType().defaultClock();
    return HandsConfig.SLEEP_ENABLED.get()
            && clock.isPresent()
            && level.getGameRules().get(GameRules.ADVANCE_TIME)
            && level.canSleepThroughNights()
        ? clock.get()
        : null;
  }

  @SubscribeEvent
  static void levelTick(LevelTickEvent.Pre event) {
    if (!(event.getLevel() instanceof ServerLevel level)) {
      return;
    }
    Holder<WorldClock> clock = managedClock(level);
    Pass pass = PASSES.get(level.dimension());
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
      // Someone got up: the night stops passing where it is; the time already passed stays.
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

  @SubscribeEvent
  static void holdSleepTimer(PlayerTickEvent.Post event) {
    if (!(event.getEntity() instanceof ServerPlayer player)
        || !player.isSleeping()
        || player.getSleepTimer() < HELD_SLEEP_TIMER
        || managedClock(player.level()) == null) {
      return;
    }
    Pass pass = PASSES.get(player.level().dimension());
    if (pass == null || !pass.done) {
      ((PlayerAccessor) player).guest_hands$setSleepCounter(HELD_SLEEP_TIMER);
    }
  }

  /** The clock was already walked to morning; vanilla must not jump it again. */
  @SubscribeEvent
  static void sleepFinished(SleepFinishedTimeEvent event) {
    if (event.getLevel() instanceof ServerLevel level) {
      Pass pass = PASSES.get(level.dimension());
      if (pass != null && pass.done) {
        event.setCanceled(true);
      }
    }
  }

  /** Morning arrives while the pass is still running; sleepers stay in bed until it ends. */
  @SubscribeEvent
  static void canContinueSleeping(CanContinueSleepingEvent event) {
    if (!(event.getEntity() instanceof ServerPlayer player)
        || !PASSES.containsKey(player.level().dimension())) {
      return;
    }
    // Vanilla reports a missing bed with the same problem value; that one must still wake them.
    boolean inBed =
        player
            .getSleepingPos()
            .map(pos -> player.level().getBlockState(pos).is(BlockTags.BEDS))
            .orElse(false);
    if (inBed) {
      event.setContinueSleeping(true);
    }
  }
}
