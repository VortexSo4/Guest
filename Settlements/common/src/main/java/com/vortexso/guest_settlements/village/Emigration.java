package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.memory.VillageMemory;
import com.vortexso.guest_settlements.society.SocietyData;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class Emigration {
  public static final String EMIGRANT_TAG = "guest_settlements.emigrant";

  private static final Identifier NONE = VillageSimulator.NONE;
  private static final long EVENT_LEAVE = 0xE3164L;
  private static final long EVENT_WALKOUT = 0xE3165L;
  private static final int INTERVAL = 20;
  private static final int DECIDE_INTERVAL = 100;

  private static final long WALK_START = 200;

  private static final long WALK_END = 1800;
  private static final int BEYOND_EDGE = 20;
  private static final float SPEED = 0.6F;

  private record Walk(long from, long to, Vec3 goal, long until) {}

  private static final Map<ServerLevel, Map<UUID, Walk>> WALKS = new WeakHashMap<>();

  private static final Map<ServerLevel, Map<Long, Long>> LAST_WALK = new WeakHashMap<>();

  private Emigration() {}

  static int leavers(int candidates, long days, double chance, long hash) {
    if (candidates <= 0 || days <= 0 || chance <= 0.0) {
      return 0;
    }
    double expected = candidates * (1.0 - Math.pow(1.0 - Math.min(1.0, chance), days));
    return Math.min(candidates, VillageSimulator.round(expected, hash));
  }

  public static VillageState afterSimulation(
      VillageWorldManager manager, VillageNode node, long fromDay, VillageState state) {
    ServerLevel level = manager.level();
    SocietyData data = SocietyData.get(level);
    state = addUnemployed(state, data.takeImmigrants(node.id()));
    if (!SettlementsConfig.enabled(SettlementsConfig.EMIGRATION) || state.fallen()) {
      return state;
    }
    VillageNode target = destination(manager, node, data);
    if (target == null) {
      return state;
    }
    int room = target.state().freeHousing() - data.immigrants(target.id());
    int leaving =
        Math.min(
            room,
            leavers(
                state.villagePopulation().professionCount(NONE) - 1,
                state.day() - fromDay,
                SettlementsConfig.value(SettlementsConfig.EMIGRATION_CHANCE),
                GuestHash.hash(level.getSeed(), node.id(), state.day(), EVENT_LEAVE)));
    if (leaving <= 0) {
      return state;
    }
    arrive(level, node, target, leaving);
    Roads.postTraffic(level, node, target, leaving, VillageWorldManager.VILLAGER_TRAVELER);
    return addUnemployed(state, -leaving);
  }

  private static void arrive(ServerLevel level, VillageNode from, VillageNode to, int count) {
    SocietyData.get(level).addImmigrants(to.id(), count);
    VillageMemory.carryStories(level, from.center(), to.center());
  }

  private static VillageState addUnemployed(VillageState state, int count) {
    if (count == 0) {
      return state;
    }
    Map<Identifier, Integer> professions = new HashMap<>(state.professions());
    professions.merge(NONE, count, Integer::sum);
    VillagePopulation population = new VillagePopulation(state.children(), professions);
    return state.withObservation(population, state.beds(), state.farmland());
  }

  public static @Nullable VillageNode destination(
      VillageWorldManager manager, VillageNode node, SocietyData data) {
    VillageNode best = null;
    int bestRoom = 0;
    for (RoadEdge edge : manager.roads()) {
      long other =
          edge.firstVillageId() == node.id()
              ? edge.secondVillageId()
              : edge.secondVillageId() == node.id() ? edge.firstVillageId() : Long.MIN_VALUE;
      VillageNode neighbor = other == Long.MIN_VALUE ? null : manager.node(other);
      if (neighbor == null || neighbor.state() == null || neighbor.state().fallen()) {
        continue;
      }
      int room = neighbor.state().freeHousing() - data.immigrants(neighbor.id());
      if (room > bestRoom
          || (room == bestRoom && room > 0 && best != null && neighbor.id() < best.id())) {
        best = neighbor;
        bestRoom = room;
      }
    }
    return best;
  }

  public static void onLevelTick(ServerLevel level) {
    if (level.getGameTime() % INTERVAL != 0) {
      return;
    }
    Map<UUID, Walk> walks = WALKS.computeIfAbsent(level, ignored -> new HashMap<>());
    walk(level, walks);
    if (level.getGameTime() % DECIDE_INTERVAL != 0) {
      return;
    }
    VillageWorldManager manager = VillageWorldManager.get(level);
    SocietyData data = SocietyData.get(level);
    long now = GuestTime.gameTime(level);
    long tickOfDay = GuestTime.tickOfDay(now);
    for (VillageNode node : List.copyOf(manager.nodes())) {
      if (!manager.isActive(node) || node.state() == null || node.structureBox() == null) {
        continue;
      }
      welcome(level, manager, node, data);
      if (SettlementsConfig.enabled(SettlementsConfig.EMIGRATION)
          && tickOfDay >= WALK_START
          && tickOfDay < WALK_END - 600
          && walks.values().stream().noneMatch(walk -> walk.from() == node.id())) {
        walkOut(level, manager, node, data, walks, GuestTime.day(now), now, false);
      }
    }
  }

  public static boolean forceWalkOut(
      ServerLevel level, VillageWorldManager manager, VillageNode node) {
    if (node.structureBox() == null) {
      return false;
    }
    long now = GuestTime.gameTime(level);
    Map<UUID, Walk> walks = WALKS.computeIfAbsent(level, ignored -> new HashMap<>());
    return walkOut(
        level, manager, node, SocietyData.get(level), walks, GuestTime.day(now), now, true);
  }

  private static boolean walkOut(
      ServerLevel level,
      VillageWorldManager manager,
      VillageNode node,
      SocietyData data,
      Map<UUID, Walk> walks,
      long day,
      long now,
      boolean force) {
    BoundingBox box = node.structureBox();
    List<Villager> unemployed =
        VillagePopulationScanner.findVillagers(level, box).stream()
            .filter(
                v -> !v.isBaby() && v.getVillagerData().profession().is(VillagerProfession.NONE))
            .filter(
                v ->
                    !v.hasCustomName() && !v.isSleeping() && !v.entityTags().contains(EMIGRANT_TAG))
            .sorted(Comparator.comparing(Villager::getUUID))
            .toList();
    long freeWorkplaces =
        level
            .getPoiManager()
            .getCountInRange(
                VillagerProfession.ALL_ACQUIRABLE_JOBS,
                box.getCenter(),
                Math.max(box.getXSpan(), box.getZSpan()) / 2 + 8,
                PoiManager.Occupancy.HAS_SPACE);
    int candidates = unemployed.size() - (int) freeWorkplaces - 1;
    double chance = SettlementsConfig.value(SettlementsConfig.EMIGRATION_CHANCE);
    long lastWalk =
        LAST_WALK
            .computeIfAbsent(level, ignored -> new HashMap<>())
            .getOrDefault(node.id(), Long.MIN_VALUE);
    boolean due =
        force
            || (candidates > 0
                && lastWalk != day
                && GuestHash.unit(GuestHash.hash(level.getSeed(), node.id(), day, EVENT_WALKOUT))
                    < 1.0 - Math.pow(1.0 - chance, candidates));
    if (unemployed.isEmpty() || !due) {
      return false;
    }
    VillageNode target = destination(manager, node, data);
    if (target == null) {
      return false;
    }
    List<BlockPos> route = RoutePlanner.cached(level, Roads.anchor(node), Roads.anchor(target));
    if (route == null) {
      RoutePlanner.whenPlanned(level, Roads.anchor(node), Roads.anchor(target), ignored -> {});
      return false;
    }
    List<BlockPos> road = Roads.betweenVillages(route, node, target);
    double length = Roads.length(road);
    Vec3 goal = Roads.pointAt(road, length <= 0.0 ? 1.0 : Math.min(1.0, BEYOND_EDGE / length));
    Villager leaver = unemployed.getFirst();
    leaver.addTag(EMIGRANT_TAG);
    LAST_WALK.get(level).put(node.id(), day);
    long until = force ? now + WALK_END : now + (WALK_END - GuestTime.tickOfDay(now));
    walks.put(leaver.getUUID(), new Walk(node.id(), target.id(), goal, until));
    GuestSettlements.LOGGER.debug(
        "Emigrant {} leaves {} for {}", leaver.getUUID(), node.center(), target.center());
    return true;
  }

  private static void walk(ServerLevel level, Map<UUID, Walk> walks) {
    long now = GuestTime.gameTime(level);
    VillageWorldManager manager = VillageWorldManager.get(level);
    Iterator<Map.Entry<UUID, Walk>> iterator = walks.entrySet().iterator();
    while (iterator.hasNext()) {
      Map.Entry<UUID, Walk> entry = iterator.next();
      Walk walk = entry.getValue();
      Villager villager = level.getEntity(entry.getKey()) instanceof Villager v ? v : null;
      if (villager == null || !villager.isAlive() || now > walk.until()) {

        if (villager != null) {
          villager.removeTag(EMIGRANT_TAG);
        }
        iterator.remove();
        continue;
      }
      if (villager.position().distanceToSqr(walk.goal()) < 4.0 * 4.0) {
        VillageNode from = manager.node(walk.from());
        VillageNode to = manager.node(walk.to());
        if (from != null && to != null) {
          arrive(level, from, to, 1);
        }
        villager.discard();
        iterator.remove();
        continue;
      }
      BlockPos goal = BlockPos.containing(walk.goal());
      villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(goal, SPEED, 2));
    }
  }

  private static void welcome(
      ServerLevel level, VillageWorldManager manager, VillageNode node, SocietyData data) {
    if (data.immigrants(node.id()) <= 0) {
      return;
    }
    BlockPos entry = entry(level, manager, node);
    if (entry == null || !level.isPositionEntityTicking(entry)) {
      return;
    }
    Villager villager = EntityType.VILLAGER.spawn(level, entry, EntitySpawnReason.EVENT);
    if (villager == null) {
      return;
    }
    data.takeImmigrant(node.id());
    VillageMemory.teachHistory(level, villager, node.center());
    BlockPos bell = node.bell() != null ? node.bell() : node.center();
    villager.getBrain().setMemory(MemoryModuleType.WALK_TARGET, new WalkTarget(bell, SPEED, 3));
  }

  private static @Nullable BlockPos entry(
      ServerLevel level, VillageWorldManager manager, VillageNode node) {
    for (RoadEdge edge : manager.roads()) {
      long other =
          edge.firstVillageId() == node.id()
              ? edge.secondVillageId()
              : edge.secondVillageId() == node.id() ? edge.firstVillageId() : Long.MIN_VALUE;
      VillageNode neighbor = other == Long.MIN_VALUE ? null : manager.node(other);
      List<BlockPos> route =
          neighbor == null
              ? null
              : RoutePlanner.cached(level, Roads.anchor(neighbor), Roads.anchor(node));
      if (route != null) {
        BlockPos end = Roads.betweenVillages(route, neighbor, node).getLast();
        return new BlockPos(
            end.getX(),
            level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, end.getX(), end.getZ()),
            end.getZ());
      }
    }
    return null;
  }

  public static Map<UUID, Long> walking(ServerLevel level) {
    Map<UUID, Long> result = new HashMap<>();
    WALKS.getOrDefault(level, Map.of()).forEach((id, walk) -> result.put(id, walk.to()));
    return result;
  }
}
