package com.vortexso.guest_settlements.illager;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.memory.VillageMemory;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.level.ModifyCustomSpawnersEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Illager raid rhythm on top of vanilla mechanics: the vanilla patrol spawner keeps its rules and
 * only its clock is scaled by season and moon (autumn stockpiling, winter reserves, spring hunger),
 * frozen during snowstorms; a pending raid omen waits for the storm to pass; and illagers who
 * remember a player killing many of their own around here avoid that player.
 */
@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class IllagerRhythm {
  private static final int RESPECT_RADIUS = 128;
  private static final int HOLD_TICKS = 40;

  private IllagerRhythm() {}

  @SubscribeEvent
  public static void onSpawners(ModifyCustomSpawnersEvent event) {
    List<CustomSpawner> spawners = event.getCustomSpawners();
    for (int i = 0; i < spawners.size(); i++) {
      if (spawners.get(i) instanceof PatrolSpawner patrols) {
        spawners.set(i, new SeasonalPatrols(patrols));
      }
    }
  }

  /**
   * Feeds the vanilla spawner {@code rate} ticks per tick: its 12000-tick countdown then runs
   * faster or slower, so patrol frequency scales without re-implementing its spawn rules. A rate of
   * zero pauses the countdown, which is how a storm delays a patrol until it ends.
   */
  private static final class SeasonalPatrols implements CustomSpawner {
    private final PatrolSpawner delegate;
    private double accumulated;

    private SeasonalPatrols(PatrolSpawner delegate) {
      this.delegate = delegate;
    }

    @Override
    public void tick(ServerLevel level, boolean spawnEnemies) {
      if (!SettlementsConfig.enabled(SettlementsConfig.SEASONAL_PATROLS)) {
        delegate.tick(level, spawnEnemies);
        return;
      }
      accumulated += patrolRate(level);
      while (accumulated >= 1.0) {
        accumulated -= 1.0;
        delegate.tick(level, spawnEnemies);
      }
    }
  }

  public static double patrolRate(ServerLevel level) {
    long now = GuestTime.gameTime(level);
    if (SettlementsConfig.enabled(SettlementsConfig.STORM_HOLDS_RAIDS)) {
      for (ServerPlayer player : level.players()) {
        if (isSnowstorm(level, player.blockPosition(), now)) {
          return 0.0;
        }
      }
    }
    double rate =
        switch (GuestTime.season(now)) {
          case SPRING -> SettlementsConfig.value(SettlementsConfig.PATROL_SPRING);
          case SUMMER -> SettlementsConfig.value(SettlementsConfig.PATROL_SUMMER);
          case AUTUMN -> SettlementsConfig.value(SettlementsConfig.PATROL_AUTUMN);
          case WINTER -> SettlementsConfig.value(SettlementsConfig.PATROL_WINTER);
        };
    // A tendency toward the dark night, not a schedule: patrols spawned that day raid by night.
    if (GuestTime.weekday(now) == 4) {
      rate *= SettlementsConfig.value(SettlementsConfig.PATROL_NEW_MOON);
    }
    return rate;
  }

  private static boolean isSnowstorm(ServerLevel level, BlockPos pos, long now) {
    WeatherState weather = GuestWeather.get(level, pos, now);
    return (weather.type() == WeatherType.BLIZZARD || weather.type() == WeatherType.SNOWSTORM)
        && weather.isSevere();
  }

  /** Vanilla starts the raid when Raid Omen reaches its last tick; keep it pending in a storm. */
  @SubscribeEvent
  public static void onPlayerTick(PlayerTickEvent.Pre event) {
    if (!(event.getEntity() instanceof ServerPlayer player)
        || player.tickCount % 20 != 0
        || !SettlementsConfig.enabled(SettlementsConfig.STORM_HOLDS_RAIDS)) {
      return;
    }
    MobEffectInstance omen = player.getEffect(MobEffects.RAID_OMEN);
    BlockPos target = player.getRaidOmenPosition();
    if (omen == null || target == null || omen.getDuration() > HOLD_TICKS) {
      return;
    }
    ServerLevel level = player.level();
    if (isSnowstorm(level, target, GuestTime.gameTime(level))) {
      player.addEffect(
          new MobEffectInstance(MobEffects.RAID_OMEN, HOLD_TICKS * 2, omen.getAmplifier()));
    }
  }

  /**
   * Respect as behaviour: an illager near a place where this player killed many illagers does not
   * take them as a target and backs away instead.
   */
  @SubscribeEvent
  public static void onTarget(LivingChangeTargetEvent event) {
    if (!(event.getEntity() instanceof AbstractIllager illager)
        || !(event.getNewAboutToBeSetTarget() instanceof Player player)
        || !(illager.level() instanceof ServerLevel level)
        || !SettlementsConfig.enabled(SettlementsConfig.ILLAGER_RESPECT)) {
      return;
    }
    int kills =
        VillageMemory.illagerKillsBy(
            level, illager.blockPosition(), player.getUUID(), RESPECT_RADIUS);
    if (kills < SettlementsConfig.value(SettlementsConfig.RESPECT_KILLS)) {
      return;
    }
    event.setCanceled(true);
    Vec3 away = DefaultRandomPos.getPosAway(illager, 16, 7, player.position());
    if (away != null) {
      illager.getNavigation().moveTo(away.x, away.y, away.z, 1.0);
    }
  }
}
