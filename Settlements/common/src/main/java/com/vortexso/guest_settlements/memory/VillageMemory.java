package com.vortexso.guest_settlements.memory;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.history.GuestHistory;
import com.vortexso.guest_core.api.history.HistoryRecord;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.gossip.GossipType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

public final class VillageMemory {
  public static final Identifier VILLAGER_KILLED = GuestSettlements.id("villager_killed");
  public static final Identifier VILLAGER_SAVED = GuestSettlements.id("villager_saved");
  public static final Identifier HOME_DESTROYED = GuestSettlements.id("home_destroyed");
  public static final Identifier VILLAGER_CURED = GuestSettlements.id("villager_cured");
  public static final Identifier VILLAGER_INFECTED = GuestSettlements.id("villager_infected");
  public static final Identifier VILLAGE_FALLEN = GuestSettlements.id("village_fallen");
  public static final Identifier ILLAGER_KILLED = GuestSettlements.id("illager_killed");
  public static final Identifier CARAVAN_SAVED = GuestSettlements.id("caravan_saved");
  public static final Identifier CARAVAN_LOST = GuestSettlements.id("caravan_lost");
  public static final Identifier RAID_WON = GuestSettlements.id("raid_won");
  public static final Identifier RAID_FAILED = GuestSettlements.id("raid_failed");
  public static final Identifier ILLAGER_JOINED = GuestSettlements.id("illager_joined");

  private static final Map<Identifier, Story> STORIES =
      Map.of(
          VILLAGER_KILLED,
          new Story(GossipType.MAJOR_NEGATIVE, GossipType.REPUTATION_CHANGE_PER_EVENT),
          VILLAGER_SAVED,
          new Story(GossipType.MINOR_POSITIVE, GossipType.REPUTATION_CHANGE_PER_EVENT),
          HOME_DESTROYED,
          new Story(GossipType.MINOR_NEGATIVE, GossipType.REPUTATION_CHANGE_PER_EVENT),
          VILLAGER_CURED,
          new Story(GossipType.MAJOR_POSITIVE, GossipType.REPUTATION_CHANGE_PER_EVERLASTING_MEMORY),
          CARAVAN_SAVED,
          new Story(GossipType.MINOR_POSITIVE, GossipType.REPUTATION_CHANGE_PER_EVENT));

  private static final Set<Identifier> FORGETTABLE =
      Set.of(
          VILLAGER_KILLED,
          VILLAGER_SAVED,
          HOME_DESTROYED,
          VILLAGER_CURED,
          VILLAGER_INFECTED,
          CARAVAN_SAVED,
          CARAVAN_LOST,
          ILLAGER_JOINED);

  private static final Map<GossipType, GossipType> OPPOSITE =
      Map.of(
          GossipType.MINOR_POSITIVE, GossipType.MINOR_NEGATIVE,
          GossipType.MINOR_NEGATIVE, GossipType.MINOR_POSITIVE,
          GossipType.MAJOR_POSITIVE, GossipType.MAJOR_NEGATIVE,
          GossipType.MAJOR_NEGATIVE, GossipType.MAJOR_POSITIVE);

  private static final int CARRIED_STORIES = 3;

  private static final double WITNESS_RADIUS = 16.0;
  private static final int VILLAGE_MEMORY_RADIUS = 96;
  private static final int INFECTION_CONTEXT_RADIUS = 16;
  private static final int GOSSIP_PER_PARENT = 10;
  private static final long INHERIT_EVENT = 0x1A4E717L;
  private static final long RETELL_EVENT = 0x2E7E11L;
  private static final long VERSION_EVENT = 0x5E2510L;
  private static final long DOUBT_EVENT = 0xD0B7L;
  private static final String CURER_TAG = "guest_settlements.curer:";

  private record Story(GossipType type, int value) {}

  private VillageMemory() {}

  public static void onDeath(LivingEntity dead, DamageSource source) {
    if (!(dead.level() instanceof ServerLevel level)) {
      return;
    }
    Entity killer = source.getEntity();

    if (dead instanceof Villager villager) {
      VillageWorldManager.get(level).onVillagerDeath(villager, killer);
      if (killer instanceof Player player) {

        record(level, dead.blockPosition(), VILLAGER_KILLED, player, dead, visibleWitnesses(dead));
      }
      return;
    }

    if (!(killer instanceof Player player)) {
      return;
    }
    if (dead instanceof AbstractIllager) {
      record(level, dead.blockPosition(), ILLAGER_KILLED, player, dead, 1);
    }
    if (dead instanceof Mob mob
        && dead instanceof Enemy
        && mob.getTarget() instanceof AbstractVillager saved) {
      List<Villager> witnesses = witnesses(level, saved.blockPosition(), player);
      if (saved instanceof Villager savedVillager) {
        savedVillager
            .getGossips()
            .add(
                player.getUUID(),
                GossipType.MINOR_POSITIVE,
                GossipType.REPUTATION_CHANGE_PER_EVENT);
      }
      for (Villager witness : witnesses) {
        witness
            .getGossips()
            .add(
                player.getUUID(),
                GossipType.MINOR_POSITIVE,
                GossipType.REPUTATION_CHANGE_PER_EVENT / 2);
      }
      record(level, saved.blockPosition(), VILLAGER_SAVED, player, saved, witnesses.size() + 1);
    }
  }

  public static void onBreak(Level world, Player player, BlockPos pos, BlockState state) {
    if (!(world instanceof ServerLevel level) || !isHomeOrWorkplace(state)) {
      return;
    }
    if (player.isCreative() || VillageWorldManager.get(level).villageAt(pos) == null) {
      return;
    }
    List<Villager> witnesses = witnesses(level, pos, player);
    if (witnesses.isEmpty()) {
      return;
    }
    for (Villager witness : witnesses) {
      witness
          .getGossips()
          .add(player.getUUID(), GossipType.MINOR_NEGATIVE, GossipType.REPUTATION_CHANGE_PER_EVENT);
    }
    record(level, pos, HOME_DESTROYED, player, null, witnesses.size());
  }

  public static void onConversion(LivingEntity entity, LivingEntity outcome) {
    if (!(entity.level() instanceof ServerLevel level)) {
      return;
    }
    if (entity instanceof Villager && outcome instanceof ZombieVillager zombie) {

      Player nearby = level.getNearestPlayer(zombie, INFECTION_CONTEXT_RADIUS);
      record(level, zombie.blockPosition(), VILLAGER_INFECTED, nearby, zombie, 1);
      return;
    }
    if (entity instanceof ZombieVillager zombie && outcome instanceof Villager villager) {
      onCured(level, zombie, villager);
    }
  }

  private static void onCured(ServerLevel level, ZombieVillager zombie, Villager villager) {
    UUID curer = curer(zombie);
    if (curer == null) {
      return;
    }
    boolean staged =
        !GuestHistory.get(level)
            .near(
                zombie.blockPosition(),
                VILLAGE_MEMORY_RADIUS,
                record ->
                    record.kind().equals(VILLAGER_INFECTED)
                        && record.subject().map(zombie.getUUID()::equals).orElse(false)
                        && record.actor().map(curer::equals).orElse(false))
            .isEmpty();
    if (staged) {

      villager.getGossips().remove(curer, GossipType.MAJOR_POSITIVE);
      return;
    }
    Player player = level.getPlayerByUUID(curer);
    record(
        level,
        villager.blockPosition(),
        VILLAGER_CURED,
        player,
        villager,
        witnesses(level, villager.blockPosition(), player).size() + 1);
  }

  public static void onInteract(Player player, Entity target, ItemStack stack) {
    if (target instanceof ZombieVillager zombie
        && !zombie.level().isClientSide()
        && stack.is(Items.GOLDEN_APPLE)
        && zombie.hasEffect(MobEffects.WEAKNESS)) {
      zombie.entityTags().removeIf(tag -> tag.startsWith(CURER_TAG));
      zombie.addTag(CURER_TAG + player.getUUID());
    }
  }

  private static @Nullable UUID curer(ZombieVillager zombie) {
    for (String tag : zombie.entityTags()) {
      if (tag.startsWith(CURER_TAG)) {
        try {
          return UUID.fromString(tag.substring(CURER_TAG.length()));
        } catch (IllegalArgumentException ignored) {
          return null;
        }
      }
    }
    return null;
  }

  public static boolean onJoin(Entity entity, ServerLevel level, boolean fromDisk) {
    if (fromDisk || !(entity instanceof Villager child) || child.getAge() != -24000) {
      return true;
    }
    List<Villager> parents =
        level.getEntitiesOfClass(
            Villager.class,
            child.getBoundingBox().inflate(2.0),
            villager -> villager != child && !villager.isBaby() && villager.getAge() == 6000);
    for (Villager parent : parents) {
      RandomSource random =
          RandomSource.create(
              GuestHash.hash(
                  level.getSeed(),
                  parent.getUUID().getMostSignificantBits(),
                  child.getUUID().getLeastSignificantBits(),
                  INHERIT_EVENT));

      child.getGossips().transferFrom(parent.getGossips(), random, GOSSIP_PER_PARENT);
    }
    return true;
  }

  public static void teachHistory(ServerLevel level, Villager villager, BlockPos villageCenter) {
    long now = GuestTime.gameTime(level);
    long generation =
        (long) SettlementsConfig.value(SettlementsConfig.MEMORY_GENERATION_DAYS)
            * GuestTime.TICKS_PER_DAY;
    boolean distortion = SettlementsConfig.enabled(SettlementsConfig.MEMORY_DISTORTION);
    for (HistoryRecord record :
        GuestHistory.get(level)
            .near(villageCenter, VILLAGE_MEMORY_RADIUS, r -> STORIES.containsKey(r.kind()))) {
      if (record.actor().isEmpty()) {
        continue;
      }
      double reach = Math.min(1.0, 0.25 + 0.25 * record.weight());
      long hash =
          GuestHash.hash(
              level.getSeed(),
              villager.getUUID().getMostSignificantBits(),
              record.gameTime(),
              record.pos().asLong());
      if (GuestHash.unit(hash) >= reach) {
        continue;
      }
      Story story = STORIES.get(record.kind());
      double generations = Math.max(0L, now - record.gameTime()) / (double) generation;
      Retelling retelling =
          distortion
              ? Retelling.of(
                  generations,
                  GuestHash.unit(GuestHash.hash(hash, RETELL_EVENT)),
                  GuestHash.unit(GuestHash.hash(hash, VERSION_EVENT)),
                  GuestHash.unit(GuestHash.hash(hash, DOUBT_EVENT)))
              : Retelling.faithful(generations);
      int value = (int) Math.round(story.value() * retelling.strength());
      if (value > 2) {
        villager
            .getGossips()
            .add(
                record.actor().get(),
                retelling.opposite() ? OPPOSITE.get(story.type()) : story.type(),
                value);
      }
    }
  }

  public record Retelling(double strength, boolean opposite) {

    private static final double DRIFT = 0.5;

    public static Retelling faithful(double generations) {
      return new Retelling(Math.pow(0.5, generations), false);
    }

    public static Retelling of(double generations, double drift, double version, double doubt) {

      double retold = Math.max(0.0, 1.0 + DRIFT * generations * (2.0 * drift - 0.5));

      double doubtChance = Math.min(0.6, 0.3 * generations);
      if (doubt < doubtChance) {
        return new Retelling(0.0, false);
      }
      boolean opposite = version < Math.min(0.35, 0.15 * generations);
      return new Retelling(Math.pow(0.5, generations) * retold * (1.0 - doubtChance), opposite);
    }
  }

  public static void carryStories(ServerLevel level, BlockPos fromVillage, BlockPos toVillage) {
    GuestHistory history = GuestHistory.get(level);
    List<HistoryRecord> known =
        history.near(toVillage, VILLAGE_MEMORY_RADIUS, r -> STORIES.containsKey(r.kind()));
    history.near(fromVillage, VILLAGE_MEMORY_RADIUS, r -> STORIES.containsKey(r.kind())).stream()
        .filter(record -> record.actor().isPresent())
        .filter(
            record ->
                known.stream()
                    .noneMatch(
                        other ->
                            other.kind().equals(record.kind())
                                && other.gameTime() == record.gameTime()
                                && other.actor().equals(record.actor())))
        .sorted(
            Comparator.comparingInt(HistoryRecord::weight)
                .reversed()
                .thenComparingLong(HistoryRecord::gameTime))
        .limit(CARRIED_STORIES)
        .forEach(
            record ->
                history.record(
                    level,
                    new HistoryRecord(
                        record.gameTime(),
                        toVillage.immutable(),
                        record.kind(),
                        record.actor(),
                        record.subject(),
                        1)));
  }

  public static void forgetOld(ServerLevel level) {
    long horizon =
        GuestTime.gameTime(level)
            - (long) SettlementsConfig.value(SettlementsConfig.MEMORY_FORGET_DAYS)
                * GuestTime.TICKS_PER_DAY;
    GuestHistory.get(level)
        .forget(record -> FORGETTABLE.contains(record.kind()) && record.gameTime() < horizon);
  }

  public static int illagerKillsBy(ServerLevel level, BlockPos pos, UUID player, int radius) {
    int total = 0;
    for (HistoryRecord record :
        GuestHistory.get(level)
            .near(
                pos,
                radius,
                r ->
                    r.kind().equals(ILLAGER_KILLED)
                        && r.actor().map(player::equals).orElse(false))) {
      total += record.weight();
    }
    return total;
  }

  public static void record(
      ServerLevel level, BlockPos pos, Identifier kind, Entity actor, Entity subject, int weight) {
    recordIds(
        level,
        pos,
        kind,
        actor == null ? null : actor.getUUID(),
        subject == null ? null : subject.getUUID(),
        weight);
  }

  public static void recordIds(
      ServerLevel level,
      BlockPos pos,
      Identifier kind,
      @Nullable UUID actor,
      @Nullable UUID subject,
      int weight) {
    if (kind.equals(VILLAGE_FALLEN)) {

      double radius = (double) VILLAGE_MEMORY_RADIUS * VILLAGE_MEMORY_RADIUS;
      GuestHistory.get(level)
          .forget(r -> FORGETTABLE.contains(r.kind()) && r.pos().distSqr(pos) <= radius);
    }
    GuestHistory.get(level)
        .record(
            level,
            new HistoryRecord(
                GuestTime.gameTime(level),
                pos.immutable(),
                kind,
                Optional.ofNullable(actor),
                Optional.ofNullable(subject),
                weight));
  }

  private static boolean isHomeOrWorkplace(BlockState state) {
    return PoiTypes.forState(state)
        .map(poi -> poi.is(PoiTypes.HOME) || VillagerProfession.ALL_ACQUIRABLE_JOBS.test(poi))
        .orElse(false);
  }

  private static List<Villager> witnesses(ServerLevel level, BlockPos pos, Entity actor) {
    return level.getEntitiesOfClass(
        Villager.class,
        new AABB(pos).inflate(WITNESS_RADIUS),
        villager -> actor == null || villager.hasLineOfSight(actor));
  }

  private static int visibleWitnesses(LivingEntity victim) {
    return victim
        .getBrain()
        .getMemory(MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES)
        .map(visible -> (int) visible.find(entity -> entity instanceof Villager).count())
        .orElse(0);
  }
}
