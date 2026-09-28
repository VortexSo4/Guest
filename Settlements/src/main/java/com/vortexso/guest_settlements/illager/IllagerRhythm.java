package com.vortexso.guest_settlements.illager;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.society.Traces;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.PatrolSpawner;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ModifyCustomSpawnersEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class IllagerRhythm {
  public static final String TENT_TAG = "guest_settlements.tent";

  private static final int HOLD_TICKS = 40;
  private static final int TENT_PARTY = 3;
  private static final int TENT_DISTANCE = 48;
  private static final long EVENT_TENT = 0x7E27L;

  private record Tent(long id, BlockPos target, boolean pitched) {}

  private static final Map<UUID, Tent> TENTS = new ConcurrentHashMap<>();

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

  @SubscribeEvent
  public static void onPlayerTick(PlayerTickEvent.Pre event) {
    if (!(event.getEntity() instanceof ServerPlayer player)
        || player.tickCount % 20 != 0
        || !SettlementsConfig.enabled(SettlementsConfig.STORM_HOLDS_RAIDS)) {
      return;
    }
    ServerLevel level = player.level();
    MobEffectInstance omen = player.getEffect(MobEffects.RAID_OMEN);
    BlockPos target = player.getRaidOmenPosition();
    Tent tent = TENTS.get(player.getUUID());
    if (tent != null
        && !tent.pitched()
        && (omen == null || level.getRaidAt(tent.target()) != null)) {
      strike(level, tent);
      TENTS.remove(player.getUUID());
    }
    if (omen == null || target == null || omen.getDuration() > HOLD_TICKS) {
      return;
    }
    if (isSnowstorm(level, target, GuestTime.gameTime(level))) {
      player.addEffect(
          new MobEffectInstance(MobEffects.RAID_OMEN, HOLD_TICKS * 2, omen.getAmplifier()));
      if (tent == null && SettlementsConfig.enabled(SettlementsConfig.ILLAGER_TENTS)) {
        pitch(level, player, target, false);
      }
    }
  }

  public static boolean pitch(
      ServerLevel level, ServerPlayer player, BlockPos target, boolean command) {
    long id = GuestHash.hash(level.getSeed(), target.asLong(), EVENT_TENT);
    BlockPos site = null;
    for (int k = 0; k < 8 && site == null; k++) {
      double angle = (GuestHash.unit(id) + k / 8.0) * Math.PI * 2.0;
      BlockPos around =
          target.offset(
              (int) Math.round(Math.cos(angle) * TENT_DISTANCE),
              0,
              (int) Math.round(Math.sin(angle) * TENT_DISTANCE));
      if (level.isLoaded(around)) {
        site = Traces.findSite(level, Traces.ILLAGER_TENT, around, 10, id + k, true);
      }
    }
    if (site == null) {
      return false;
    }

    Traces.placeTemplate(
        level,
        id,
        Traces.ILLAGER_TENT,
        site,
        Rotation.values()[(int) Math.floorMod(id, 4L)],
        true,
        GuestTime.gameTime(level),
        GuestTime.TICKS_PER_DAY);
    for (int i = 0; i < TENT_PARTY; i++) {
      Pillager pillager =
          EntityType.PILLAGER.spawn(
              level, site.offset(2 + i % 2, 0, 2 + i), EntitySpawnReason.EVENT);
      if (pillager != null) {
        pillager.addTag(TENT_TAG);
        pillager.setCanJoinRaid(true);
      }
    }
    TENTS.put(player.getUUID(), new Tent(id, target, command));
    GuestSettlements.LOGGER.debug(
        "Raid party tent at {} waiting for the storm over {}", site, target);
    return true;
  }

  private static void strike(ServerLevel level, Tent tent) {
    Traces.remove(level, tent.id());
  }

  public static boolean strike(ServerLevel level, ServerPlayer player) {
    Tent tent = TENTS.remove(player.getUUID());
    if (tent != null) {
      strike(level, tent);
    }
    return tent != null;
  }

  public static java.util.Collection<Long> tents() {
    return TENTS.values().stream().map(Tent::id).toList();
  }
}
