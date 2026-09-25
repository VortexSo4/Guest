package com.vortexso.guest_settlements.memory;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.history.GuestHistory;
import com.vortexso.guest_core.api.history.HistoryRecord;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
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
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import org.jspecify.annotations.Nullable;

/**
 * Village memory on top of vanilla gossip. Meaningful player-caused events become sparse {@link
 * GuestHistory} records (with how many villagers saw them); the people who were there get vanilla
 * gossip; children inherit a faded copy from their parents, and villagers materialized after an
 * absence learn the surviving stories from history, fading by generation. There is no separate
 * reputation number: vanilla reads the gossip for prices, golems and trades.
 */
@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class VillageMemory {
  public static final Identifier VILLAGER_KILLED = GuestSettlements.id("villager_killed");
  public static final Identifier VILLAGER_SAVED = GuestSettlements.id("villager_saved");
  public static final Identifier HOME_DESTROYED = GuestSettlements.id("home_destroyed");
  public static final Identifier VILLAGER_CURED = GuestSettlements.id("villager_cured");
  public static final Identifier VILLAGER_INFECTED = GuestSettlements.id("villager_infected");
  public static final Identifier VILLAGE_FALLEN = GuestSettlements.id("village_fallen");
  public static final Identifier ILLAGER_KILLED = GuestSettlements.id("illager_killed");

  /** What a descendant remembers of each story, before fading. Values mirror vanilla events. */
  private static final Map<Identifier, Story> STORIES =
      Map.of(
          VILLAGER_KILLED,
          new Story(GossipType.MAJOR_NEGATIVE, GossipType.REPUTATION_CHANGE_PER_EVENT),
          VILLAGER_SAVED,
          new Story(GossipType.MINOR_POSITIVE, GossipType.REPUTATION_CHANGE_PER_EVENT),
          HOME_DESTROYED,
          new Story(GossipType.MINOR_NEGATIVE, GossipType.REPUTATION_CHANGE_PER_EVENT),
          VILLAGER_CURED,
          new Story(
              GossipType.MAJOR_POSITIVE, GossipType.REPUTATION_CHANGE_PER_EVERLASTING_MEMORY));

  private static final Set<Identifier> FORGETTABLE =
      Set.of(VILLAGER_KILLED, VILLAGER_SAVED, HOME_DESTROYED, VILLAGER_CURED, VILLAGER_INFECTED);

  private static final double WITNESS_RADIUS = 16.0;
  private static final int VILLAGE_MEMORY_RADIUS = 96;
  private static final int INFECTION_CONTEXT_RADIUS = 16;
  private static final int GOSSIP_PER_PARENT = 10;
  private static final long INHERIT_EVENT = 0x1A4E717L;
  private static final String CURER_TAG = "guest_settlements.curer:";

  private record Story(GossipType type, int value) {}

  private VillageMemory() {}

  @SubscribeEvent
  public static void onDeath(LivingDeathEvent event) {
    if (!(event.getEntity().level() instanceof ServerLevel level)) {
      return;
    }
    LivingEntity dead = event.getEntity();
    Entity killer = event.getSource().getEntity();

    if (dead instanceof Villager villager) {
      VillageWorldManager.get(level).onVillagerDeath(villager, killer);
      if (killer instanceof Player player) {
        // Vanilla already hands MAJOR_NEGATIVE gossip to the witnesses; only the story is new.
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

  /** Lowest priority so a cancelled break (protection mods) is never remembered. */
  @SubscribeEvent(priority = EventPriority.LOWEST)
  public static void onBreak(BreakBlockEvent event) {
    if (!(event.getLevel() instanceof ServerLevel level) || !isHomeOrWorkplace(event.getState())) {
      return;
    }
    Player player = event.getPlayer();
    if (player.isCreative() || VillageWorldManager.get(level).villageAt(event.getPos()) == null) {
      return;
    }
    List<Villager> witnesses = witnesses(level, event.getPos(), player);
    if (witnesses.isEmpty()) {
      return;
    }
    for (Villager witness : witnesses) {
      witness
          .getGossips()
          .add(player.getUUID(), GossipType.MINOR_NEGATIVE, GossipType.REPUTATION_CHANGE_PER_EVENT);
    }
    record(level, event.getPos(), HOME_DESTROYED, player, null, witnesses.size());
  }

  @SubscribeEvent
  public static void onConversion(LivingConversionEvent.Post event) {
    if (!(event.getEntity().level() instanceof ServerLevel level)) {
      return;
    }
    if (event.getEntity() instanceof Villager
        && event.getOutcome() instanceof ZombieVillager zombie) {
      // A player standing by while a villager turned "allowed" it; remember them as context.
      Player nearby = level.getNearestPlayer(zombie, INFECTION_CONTEXT_RADIUS);
      record(level, zombie.blockPosition(), VILLAGER_INFECTED, nearby, zombie, 1);
      return;
    }
    if (event.getEntity() instanceof ZombieVillager zombie
        && event.getOutcome() instanceof Villager villager) {
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
      // The same player caused or allowed the infection: vanilla's cure gratitude is withdrawn.
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

  /**
   * The curer is private to the zombie villager, so it is noted as an entity tag (persisted with
   * the zombie) when a player feeds the golden apple that starts the cure.
   */
  @SubscribeEvent
  public static void onInteract(PlayerInteractEvent.EntityInteract event) {
    if (event.getTarget() instanceof ZombieVillager zombie
        && !zombie.level().isClientSide()
        && event.getItemStack().is(Items.GOLDEN_APPLE)
        && zombie.hasEffect(MobEffects.WEAKNESS)) {
      zombie.entityTags().removeIf(tag -> tag.startsWith(CURER_TAG));
      zombie.addTag(CURER_TAG + event.getEntity().getUUID());
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

  /** Vanilla villager breeding fires no baby event; a bred baby joins with both parents at 6000. */
  @SubscribeEvent
  public static void onJoin(EntityJoinLevelEvent event) {
    if (event.loadedFromDisk()
        || !(event.getLevel() instanceof ServerLevel level)
        || !(event.getEntity() instanceof Villager child)
        || child.getAge() != -24000) {
      return;
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
      // transferFrom applies vanilla's per-transfer decay: children know a faded version.
      child.getGossips().transferFrom(parent.getGossips(), random, GOSSIP_PER_PARENT);
    }
  }

  /**
   * Stories a villager materialized after an absence grew up with. Each story halves per generation
   * since it happened and reaches a villager with odds rising with its witnesses.
   */
  public static void teachHistory(ServerLevel level, Villager villager, BlockPos villageCenter) {
    long now = GuestTime.gameTime(level);
    long generation =
        (long) SettlementsConfig.value(SettlementsConfig.MEMORY_GENERATION_DAYS)
            * GuestTime.TICKS_PER_DAY;
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
      int value = (int) Math.round(story.value() * Math.pow(0.5, generations));
      if (value > 2) {
        villager.getGossips().add(record.actor().get(), story.type(), value);
      }
    }
  }

  public static void forgetOld(ServerLevel level) {
    long horizon =
        GuestTime.gameTime(level)
            - (long) SettlementsConfig.value(SettlementsConfig.MEMORY_FORGET_DAYS)
                * GuestTime.TICKS_PER_DAY;
    GuestHistory.get(level)
        .forget(record -> FORGETTABLE.contains(record.kind()) && record.gameTime() < horizon);
  }

  /** Kills of this player's doing remembered around {@code pos}, weighted by the records. */
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
    GuestHistory.get(level)
        .record(
            level,
            new HistoryRecord(
                GuestTime.gameTime(level),
                pos.immutable(),
                kind,
                Optional.ofNullable(actor).map(Entity::getUUID),
                Optional.ofNullable(subject).map(Entity::getUUID),
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
