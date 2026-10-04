package com.vortexso.guest_settlements.illager;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.history.GuestHistory;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.memory.VillageMemory;
import com.vortexso.guest_settlements.society.SocietyData.Camp;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

public final class IllagerRespect {
  public enum Attitude {
    UNKNOWN,
    RESPECT,
    FOLLOW,
    REVENGE
  }

  private static final int MEMORY_RADIUS = 128;

  private static final int RAID_FAME = 5;

  private static final double FOLLOW_RADIUS = 16.0;
  private static final int GREETING_TICKS = 80;
  private static final int NOD_TICKS = 24;
  private static final long EVENT_RECOGNIZE = 0x2EC06L;
  private static final long EVENT_REVENGE = 0x2E7E6L;

  private static final Map<UUID, Attitude> SEEN = new ConcurrentHashMap<>();

  private record Greeting(Player player, long started, boolean leader) {}

  private static final Map<AbstractIllager, Greeting> GREETINGS = new WeakHashMap<>();

  private IllagerRespect() {}

  public static Map<UUID, Attitude> seen() {
    return SEEN;
  }

  public static Attitude attitude(ServerLevel level, AbstractIllager illager, Player player) {
    Camp camp = IllagerCamps.campAt(level, illager.blockPosition());
    BlockPos home = camp == null ? illager.blockPosition() : camp.center();
    int defeats = raidsBrokenBy(level, home, player.getUUID());
    int fame =
        VillageMemory.illagerKillsBy(level, home, player.getUUID(), MEMORY_RADIUS)
            + RAID_FAME * defeats;
    if (fame < SettlementsConfig.value(SettlementsConfig.RESPECT_KILLS)) {
      return Attitude.UNKNOWN;
    }
    long hash =
        GuestHash.hash(
            level.getSeed(),
            illager.getUUID().getMostSignificantBits(),
            player.getUUID().getLeastSignificantBits(),
            EVENT_RECOGNIZE);
    if (GuestHash.unit(hash) >= SettlementsConfig.value(SettlementsConfig.RECOGNITION_CHANCE)) {

      return Attitude.UNKNOWN;
    }

    double revenge = camp != null && defeats > 0 ? 0.75 : 0.25;
    return GuestHash.unit(GuestHash.hash(hash, EVENT_REVENGE)) < revenge
        ? Attitude.REVENGE
        : Attitude.RESPECT;
  }

  private static int raidsBrokenBy(ServerLevel level, BlockPos pos, UUID player) {
    return GuestHistory.get(level)
        .near(
            pos,
            MEMORY_RADIUS,
            r ->
                r.kind().equals(VillageMemory.RAID_FAILED)
                    && r.actor().map(player::equals).orElse(false))
        .size();
  }

  static boolean isLeader(AbstractIllager illager) {
    return illager.isPatrolLeader() || illager.entityTags().contains(IllagerCamps.LEADER_TAG);
  }

  public static boolean allowTarget(LivingEntity mob, @Nullable LivingEntity target) {
    if (!(mob instanceof AbstractIllager illager)
        || !(target instanceof Player player)
        || !(illager.level() instanceof ServerLevel level)
        || !SettlementsConfig.enabled(SettlementsConfig.ILLAGER_RESPECT)) {
      return true;
    }
    Attitude attitude = attitude(level, illager, player);
    if (attitude == Attitude.UNKNOWN && followsLeader(level, illager, player)) {
      attitude = Attitude.FOLLOW;
    }
    SEEN.put(illager.getUUID(), attitude);
    if (attitude != Attitude.RESPECT && attitude != Attitude.FOLLOW) {
      return true;
    }

    illager.setAggressive(false);
    if (illager instanceof Pillager pillager) {
      pillager.setChargingCrossbow(false);
    }
    GREETINGS.putIfAbsent(illager, new Greeting(player, level.getGameTime(), isLeader(illager)));
    return false;
  }

  private static boolean followsLeader(ServerLevel level, AbstractIllager illager, Player player) {
    for (AbstractIllager other :
        level.getEntitiesOfClass(
            AbstractIllager.class,
            illager.getBoundingBox().inflate(FOLLOW_RADIUS),
            other -> other != illager && isLeader(other))) {
      if (attitude(level, other, player) == Attitude.RESPECT) {
        return true;
      }
    }
    return false;
  }

  public static void onLevelTick(ServerLevel level) {
    if (GREETINGS.isEmpty()) {
      return;
    }
    long now = level.getGameTime();
    Iterator<Map.Entry<AbstractIllager, Greeting>> iterator = GREETINGS.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<AbstractIllager, Greeting> entry = iterator.next();
      AbstractIllager illager = entry.getKey();
      Greeting greeting = entry.getValue();
      long age = now - greeting.started();
      if (illager.level() != level) {
        continue;
      }
      if (age > GREETING_TICKS
          || !illager.isAlive()
          || !greeting.player().isAlive()
          || illager.getTarget() != null) {
        iterator.remove();
        continue;
      }
      Player player = greeting.player();
      double dip = greeting.leader() && age < NOD_TICKS && (age / 6) % 2 == 1 ? 0.9 : 0.0;
      illager
          .getLookControl()
          .setLookAt(player.getX(), player.getEyeY() - dip, player.getZ(), 30.0F, 40.0F);
    }
  }
}
