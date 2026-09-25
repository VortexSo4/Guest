package com.vortexso.guest_settlements.life;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.village.VillageNode;
import com.vortexso.guest_settlements.village.VillagePopulationScanner;
import com.vortexso.guest_settlements.village.VillageSimulationParameters;
import com.vortexso.guest_settlements.village.VillageSimulator;
import com.vortexso.guest_settlements.village.VillageState;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.EntityTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

/**
 * Visible village life of fully loaded villages: lunar rites and mourning at the bell, the storm
 * bell, internal trade carried by hand, helpers and nitwits. Everything is expressed through the
 * vanilla brain by setting {@code WALK_TARGET}/{@code LOOK_TARGET} (which most vanilla behaviours
 * only replace once it is gone) and re-asserting it every {@link #INTERVAL} ticks, so the brain's
 * own schedule, sleep, panic and raid reactions stay in charge.
 */
@EventBusSubscriber(modid = GuestSettlements.MODID)
public final class VillageLife {
  public static final int INTERVAL = 40;

  private static final long WORK_START = 2000;
  private static final long WORK_END = 9000;
  private static final long RITE_START = 9000;
  private static final long RITE_END = 11500;
  private static final long CALL_WINDOW = 400;
  private static final long MOURNING_TOLL_INTERVAL = 200;
  private static final long STORM_TOLL_INTERVAL = 80;
  private static final int STORM_AWAY_DISTANCE = 24;
  private static final int CHILD_SEARCH = 32;
  private static final int SCENE_TIMEOUT = 1200;
  private static final int EXCHANGE_TICKS = 80;
  private static final float SPEED = 0.5F;
  private static final String CARRYING_TAG = "guest_settlements.carrying";
  private static final long EVENT_TRADE = 0x7EADL;

  /**
   * Who hands what to whom. Taken from the settlement spec (farmer grain to the butcher, mason
   * stone to the smiths, ink to the librarian, hides to the leatherworker).
   */
  private record TradeRoute(
      ResourceKey<VillagerProfession> supplier,
      ResourceKey<VillagerProfession> consumer,
      Item item) {}

  private static final List<TradeRoute> ROUTES =
      List.of(
          new TradeRoute(VillagerProfession.FARMER, VillagerProfession.BUTCHER, Items.WHEAT),
          new TradeRoute(VillagerProfession.MASON, VillagerProfession.TOOLSMITH, Items.STONE),
          new TradeRoute(VillagerProfession.MASON, VillagerProfession.ARMORER, Items.STONE),
          new TradeRoute(VillagerProfession.MASON, VillagerProfession.WEAPONSMITH, Items.STONE),
          new TradeRoute(VillagerProfession.FISHERMAN, VillagerProfession.LIBRARIAN, Items.INK_SAC),
          new TradeRoute(
              VillagerProfession.BUTCHER, VillagerProfession.LEATHERWORKER, Items.RABBIT_HIDE));

  /** What a villager is doing for us right now; read by the debug renderer. */
  public enum Role {
    RITE,
    RINGER,
    MOURNING,
    STORM,
    TRADE,
    HELPER,
    NITWIT
  }

  private static final class TradeScene {
    final UUID supplier;
    final UUID consumer;
    final ItemStack item;
    final long started;
    long exchanged = -1;

    TradeScene(UUID supplier, UUID consumer, ItemStack item, long started) {
      this.supplier = supplier;
      this.consumer = consumer;
      this.item = item;
      this.started = started;
    }
  }

  private static final Map<ServerLevel, List<TradeScene>> SCENES = new WeakHashMap<>();
  private static final Map<ServerLevel, Map<UUID, Role>> ROLES = new WeakHashMap<>();

  private VillageLife() {}

  public static Map<UUID, Role> roles(ServerLevel level) {
    return ROLES.getOrDefault(level, Map.of());
  }

  public static void tick(ServerLevel level, VillageWorldManager manager) {
    List<TradeScene> scenes = SCENES.computeIfAbsent(level, ignored -> new ArrayList<>());
    tickScenes(level, scenes);
    if (level.getGameTime() % INTERVAL != 0) {
      return;
    }
    long now = GuestTime.gameTime(level);
    long tickOfDay = GuestTime.tickOfDay(now);
    long day = GuestTime.day(now);
    Map<UUID, Role> roles = new HashMap<>();

    for (VillageNode node : manager.nodes()) {
      VillageState state = node.state();
      if (!manager.isActive(node) || state == null || node.structureBox() == null) {
        continue;
      }
      List<Villager> villagers = VillagePopulationScanner.findVillagers(level, node.structureBox());
      if (villagers.isEmpty()) {
        continue;
      }
      villagers.sort(Comparator.comparing(Villager::getUUID));
      BlockPos bell = node.bell();
      boolean severe = GuestWeather.get(level, node.center(), now).isSevere();
      boolean ceremonies = SettlementsConfig.enabled(SettlementsConfig.CEREMONIES);
      int weekday = GuestTime.weekday(now);
      boolean evening = tickOfDay >= RITE_START && tickOfDay < RITE_END;

      if (bell != null && ceremonies && evening && weekday == 0 && !severe) {
        gather(level, villagers, bell, Role.RITE, roles);
        boolean calling =
            tickOfDay < RITE_START + CALL_WINDOW || tickOfDay >= RITE_END - CALL_WINDOW;
        ringer(level, villagers, bell, calling, roles);
      } else if (bell != null && ceremonies && evening && weekday == 4 && mourningDue(state, day)) {
        gather(level, villagers, bell, Role.MOURNING, roles);
        ringer(level, villagers, bell, tickOfDay % MOURNING_TOLL_INTERVAL < INTERVAL, roles);
      } else if (bell != null
          && severe
          && SettlementsConfig.enabled(SettlementsConfig.STORM_BELL)) {
        stormBell(level, villagers, bell, roles);
      } else if (tickOfDay >= WORK_START && tickOfDay < WORK_END && !severe) {
        if (SettlementsConfig.enabled(SettlementsConfig.TRADE_SCENES)) {
          startTrades(level, node, state, villagers, scenes, now, tickOfDay);
        }
        if (SettlementsConfig.enabled(SettlementsConfig.HELPERS)) {
          helpers(level, state, villagers, bell, roles);
        }
      }
    }
    for (TradeScene scene : scenes) {
      roles.put(scene.supplier, Role.TRADE);
      roles.put(scene.consumer, Role.TRADE);
    }
    ROLES.put(level, roles);
  }

  /** New moon mourning happens if someone died since the previous new moon. */
  public static boolean mourningDue(VillageState state, long day) {
    return state.lastDeathDay() > day - GuestTime.DAYS_PER_WEEK && state.lastDeathDay() <= day;
  }

  // ---------------------------------------------------------------- bell

  private static void gather(
      ServerLevel level,
      List<Villager> villagers,
      BlockPos bell,
      Role role,
      Map<UUID, Role> roles) {
    for (Villager villager : villagers) {
      if (walkTo(villager, bell, 3)) {
        roles.put(villager.getUUID(), role);
      }
    }
  }

  /** The cleric leads the rite; any adult does when the village has no cleric. */
  private static void ringer(
      ServerLevel level,
      List<Villager> villagers,
      BlockPos bell,
      boolean ring,
      Map<UUID, Role> roles) {
    Villager ringer =
        villagers.stream()
            .filter(villager -> !villager.isBaby() && available(villager))
            .min(
                Comparator.comparing(
                    (Villager villager) ->
                        !villager.getVillagerData().profession().is(VillagerProfession.CLERIC)))
            .orElse(null);
    if (ringer == null) {
      return;
    }
    walkTo(ringer, bell, 1);
    roles.put(ringer.getUUID(), Role.RINGER);
    if (ring && bell.closerToCenterThan(ringer.position(), 3.0)) {
      ring(level, ringer, bell);
    }
  }

  /**
   * Severe weather: somebody stays by the bell and rings it while a villager is still outside far
   * from it; the one outside heads for the sound.
   */
  private static void stormBell(
      ServerLevel level, List<Villager> villagers, BlockPos bell, Map<UUID, Role> roles) {
    boolean someoneAway = false;
    for (Villager villager : villagers) {
      if (!villager.isSleeping()
          && !bell.closerToCenterThan(villager.position(), STORM_AWAY_DISTANCE)
          && level.canSeeSky(villager.blockPosition())) {
        someoneAway = true;
        if (walkTo(villager, bell, 4)) {
          roles.put(villager.getUUID(), Role.STORM);
        }
      }
    }
    if (someoneAway) {
      ringer(level, villagers, bell, level.getGameTime() % STORM_TOLL_INTERVAL < INTERVAL, roles);
    }
  }

  /**
   * Audible, visible ring without {@link BellBlock#attemptToRing}: a real ring tells every villager
   * within 32 blocks to hide (the raid alarm), which is exactly wrong for a rite.
   */
  private static void ring(ServerLevel level, Villager ringer, BlockPos bell) {
    BlockState state = level.getBlockState(bell);
    if (!(state.getBlock() instanceof BellBlock)) {
      return;
    }
    Direction facing = state.getValue(BellBlock.FACING);
    level
        .getServer()
        .getPlayerList()
        .broadcast(
            null,
            bell.getX(),
            bell.getY(),
            bell.getZ(),
            64.0,
            level.dimension(),
            new ClientboundBlockEventPacket(bell, state.getBlock(), 1, facing.get3DDataValue()));
    level.playSound(null, bell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0F, 1.0F);
    level.gameEvent(ringer, GameEvent.BLOCK_CHANGE, bell);
  }

  // ---------------------------------------------------------------- trade

  /**
   * Trades are scheduled from the aggregate economy: each route gets an expected number of
   * exchanges per working day from how many suppliers/consumers exist and how much the supplier's
   * work yields in this season; the start ticks are derived from seed + village + day.
   */
  private static void startTrades(
      ServerLevel level,
      VillageNode node,
      VillageState state,
      List<Villager> villagers,
      List<TradeScene> scenes,
      long now,
      long tickOfDay) {
    Season season = GuestTime.season(now);
    VillageSimulationParameters parameters = SettlementsConfig.parameters();
    double perPair = SettlementsConfig.value(SettlementsConfig.TRADES_PER_PAIR);
    long day = GuestTime.day(now);
    for (int route = 0; route < ROUTES.size(); route++) {
      TradeRoute trade = ROUTES.get(route);
      int suppliers = count(state, trade.supplier());
      int consumers = count(state, trade.consumer());
      double expected =
          perPair
              * Math.min(suppliers, consumers)
              * supply(trade.supplier(), state, season, parameters);
      long hash = GuestHash.hash(level.getSeed(), node.id(), day, EVENT_TRADE + route);
      int trades = (int) expected + (GuestHash.unit(hash) < expected - (int) expected ? 1 : 0);
      for (int k = 0; k < trades; k++) {
        long start =
            WORK_START
                + (long)
                    (GuestHash.unit(GuestHash.hash(hash, k))
                        * (WORK_END - WORK_START - SCENE_TIMEOUT));
        if (start > tickOfDay - INTERVAL && start <= tickOfDay) {
          startScene(villagers, scenes, trade, GuestHash.hash(hash, k, 1), now);
        }
      }
    }
  }

  /**
   * Share of a working day the supplier actually has goods: farm output follows the season and
   * whether fields feed the village; the other seasonal professions slow in winter.
   */
  private static double supply(
      ResourceKey<VillagerProfession> supplier,
      VillageState state,
      Season season,
      VillageSimulationParameters parameters) {
    if (supplier == VillagerProfession.FARMER) {
      VillageSimulator.Rates rates = VillageSimulator.rates(state, 0.0, 1.0, 0, parameters);
      double growth = VillageSimulator.growth(season, parameters);
      return rates.consumption() <= 0.0
          ? growth
          : Math.min(1.0, growth * rates.production() / rates.consumption());
    }
    boolean seasonal =
        supplier == VillagerProfession.FISHERMAN || supplier == VillagerProfession.MASON;
    return seasonal && season == Season.WINTER ? 0.3 : 1.0;
  }

  private static void startScene(
      List<Villager> villagers, List<TradeScene> scenes, TradeRoute trade, long pick, long now) {
    List<Villager> suppliers = withProfession(villagers, trade.supplier());
    List<Villager> consumers = withProfession(villagers, trade.consumer());
    if (suppliers.isEmpty() || consumers.isEmpty()) {
      return;
    }
    Villager supplier = suppliers.get((int) Math.floorMod(pick, (long) suppliers.size()));
    Villager consumer = consumers.get((int) Math.floorMod(pick >>> 16, (long) consumers.size()));
    ItemStack item = new ItemStack(trade.item());
    hold(supplier, item);
    scenes.add(new TradeScene(supplier.getUUID(), consumer.getUUID(), item, now));
  }

  private static void tickScenes(ServerLevel level, List<TradeScene> scenes) {
    long now = level.getGameTime();
    Iterator<TradeScene> iterator = scenes.iterator();
    while (iterator.hasNext()) {
      TradeScene scene = iterator.next();
      Villager supplier = level.getEntity(scene.supplier) instanceof Villager v ? v : null;
      Villager consumer = level.getEntity(scene.consumer) instanceof Villager v ? v : null;
      if (supplier == null
          || consumer == null
          || supplier.isRemoved()
          || consumer.isRemoved()
          || now - scene.started > SCENE_TIMEOUT) {
        release(supplier);
        release(consumer);
        iterator.remove();
        continue;
      }
      if (scene.exchanged < 0) {
        if (now % 10 == 0 && available(supplier)) {
          supplier
              .getBrain()
              .setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(consumer, SPEED, 1));
          supplier
              .getBrain()
              .setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(consumer, true));
        }
        if (supplier.distanceToSqr(consumer) < 2.5 * 2.5) {
          release(supplier);
          hold(consumer, scene.item);
          lookAtEachOther(supplier, consumer);
          // Villagers already gossip whenever they trade with each other in vanilla.
          supplier.gossip(level, consumer, now);
          level.sendParticles(
              ParticleTypes.HAPPY_VILLAGER,
              (supplier.getX() + consumer.getX()) / 2,
              supplier.getEyeY(),
              (supplier.getZ() + consumer.getZ()) / 2,
              6,
              0.4,
              0.3,
              0.4,
              0.0);
          scene.exchanged = now;
        }
      } else if (now - scene.exchanged > EXCHANGE_TICKS) {
        release(consumer);
        iterator.remove();
      }
    }
  }

  /** The villager model draws the main-hand item between the crossed sleeves. */
  private static void hold(Villager villager, ItemStack item) {
    villager.setItemSlot(EquipmentSlot.MAINHAND, item.copy());
    villager.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    villager.addTag(CARRYING_TAG);
  }

  private static void release(Villager villager) {
    if (villager != null && villager.entityTags().contains(CARRYING_TAG)) {
      villager.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
      // Vanilla default drop chance, as ShowTradesToPlayer restores it.
      villager.setDropChance(EquipmentSlot.MAINHAND, 0.085F);
      villager.removeTag(CARRYING_TAG);
    }
  }

  private static void lookAtEachOther(Villager first, Villager second) {
    first.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(second, true));
    second.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(first, true));
  }

  /** A scene interrupted by a save leaves the item in hand; take it back on load. */
  @SubscribeEvent
  public static void onJoin(EntityJoinLevelEvent event) {
    if (event.getEntity() instanceof Villager villager
        && villager.entityTags().contains(CARRYING_TAG)) {
      release(villager);
    }
  }

  @SubscribeEvent
  public static void onServerStopping(ServerStoppingEvent event) {
    SCENES.forEach(
        (level, scenes) -> {
          for (TradeScene scene : scenes) {
            release(level.getEntity(scene.supplier) instanceof Villager v ? v : null);
            release(level.getEntity(scene.consumer) instanceof Villager v ? v : null);
          }
        });
    SCENES.clear();
  }

  // ---------------------------------------------------------------- helpers & nitwits

  /**
   * Unemployed adults shadow a worker of the profession the village lacks most (untended farmland
   * first, the only need the aggregate sees directly), otherwise the nearest worker. Nitwits keep
   * the children company, or wait at the bell.
   */
  private static void helpers(
      ServerLevel level,
      VillageState state,
      List<Villager> villagers,
      BlockPos bell,
      Map<UUID, Role> roles) {
    VillageSimulationParameters parameters = SettlementsConfig.parameters();
    boolean needFarmers =
        state.farmland() > count(state, VillagerProfession.FARMER) * parameters.farmlandPerFarmer();
    List<Villager> farmers = withProfession(villagers, VillagerProfession.FARMER);
    List<Villager> workers =
        villagers.stream()
            .filter(
                villager ->
                    !villager.isBaby()
                        && !villager.getVillagerData().profession().is(VillagerProfession.NONE)
                        && !villager.getVillagerData().profession().is(VillagerProfession.NITWIT))
            .toList();

    for (Villager villager : villagers) {
      if (villager.isBaby()) {
        continue;
      }
      var profession = villager.getVillagerData().profession();
      if (profession.is(VillagerProfession.NONE)) {
        List<Villager> candidates = needFarmers && !farmers.isEmpty() ? farmers : workers;
        Villager mentor =
            candidates.stream()
                .min(Comparator.comparingDouble(villager::distanceToSqr))
                .orElse(null);
        if (mentor != null && follow(villager, mentor)) {
          roles.put(villager.getUUID(), Role.HELPER);
        }
      } else if (profession.is(VillagerProfession.NITWIT)) {
        Villager child =
            villagers.stream()
                .filter(
                    other ->
                        other.isBaby()
                            && other.distanceToSqr(villager) < CHILD_SEARCH * CHILD_SEARCH)
                .min(Comparator.comparingDouble(villager::distanceToSqr))
                .orElse(null);
        boolean moving =
            child != null ? follow(villager, child) : bell != null && walkTo(villager, bell, 4);
        if (moving) {
          roles.put(villager.getUUID(), Role.NITWIT);
        }
      }
    }
  }

  // ---------------------------------------------------------------- brain plumbing

  /** Vanilla states that must win over anything we ask for. */
  private static boolean available(Villager villager) {
    Brain<Villager> brain = villager.getBrain();
    return !villager.isSleeping()
        && !villager.isTrading()
        && !brain.isActive(Activity.PANIC)
        && !brain.isActive(Activity.HIDE)
        && !brain.isActive(Activity.RAID)
        && !brain.isActive(Activity.PRE_RAID);
  }

  private static boolean walkTo(Villager villager, BlockPos target, int closeEnough) {
    if (!available(villager)) {
      return false;
    }
    if (!target.closerToCenterThan(villager.position(), closeEnough + 1.0)) {
      villager
          .getBrain()
          .setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, SPEED, closeEnough));
    }
    villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(target));
    return true;
  }

  private static boolean follow(Villager villager, Entity target) {
    if (!available(villager)) {
      return false;
    }
    if (villager.distanceToSqr(target) > 9.0) {
      villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(target, SPEED, 2));
    }
    villager.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new EntityTracker(target, true));
    return true;
  }

  private static List<Villager> withProfession(
      List<Villager> villagers, ResourceKey<VillagerProfession> profession) {
    return villagers.stream()
        .filter(
            villager ->
                !villager.isBaby()
                    && villager.getVillagerData().profession().is(profession)
                    && available(villager)
                    && villager.getMainHandItem().isEmpty())
        .toList();
  }

  private static int count(VillageState state, ResourceKey<VillagerProfession> profession) {
    return state.villagePopulation().professionCount(profession.identifier());
  }
}
