package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.event.RouteTrafficEvent;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.api.world.GuestWildlife;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.memory.VillageMemory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.zombie.ZombieVillager;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;

/**
 * Connects the aggregate model to the world of one dimension. A village is simulated only while
 * nobody can see all of it; while fully loaded, vanilla villagers live for real and the manager
 * only observes them (population, beds, farmland) and advances the economy.
 */
public final class VillageWorldManager {
  public static final Identifier CARAVAN_TRAVELER = GuestSettlements.id("caravan");
  public static final Identifier VILLAGER_TRAVELER = GuestSettlements.id("villager");

  private static final int NEIGHBOR_SEARCH_RADIUS = 4096;
  private static final int VILLAGE_MATCH_RADIUS = 256;
  private static final int PRESSURE_RADIUS = 64;
  private static final int HOUSING_MARGIN = 8;
  private static final int OBSERVE_INTERVAL = 100;

  private static final BlockPos[] NEIGHBOR_PROBES = {
    new BlockPos(160, 0, 0),
    new BlockPos(-160, 0, 0),
    new BlockPos(0, 0, 160),
    new BlockPos(0, 0, -160),
    new BlockPos(113, 0, 113),
    new BlockPos(113, 0, -113),
    new BlockPos(-113, 0, 113),
    new BlockPos(-113, 0, -113)
  };

  private static final Set<Identifier> VANILLA_VILLAGES =
      Set.of(
          Identifier.withDefaultNamespace("village_plains"),
          Identifier.withDefaultNamespace("village_desert"),
          Identifier.withDefaultNamespace("village_savanna"),
          Identifier.withDefaultNamespace("village_snowy"),
          Identifier.withDefaultNamespace("village_taiga"));

  private static final Map<ServerLevel, VillageWorldManager> INSTANCES = new WeakHashMap<>();

  private final ServerLevel level;
  private final VillageWorldData data;
  private final Map<Long, List<VillageFarmRegion>> regionsByChunk = new HashMap<>();
  private final Set<Long> queuedChunks = new HashSet<>();
  private final Set<Long> active = new HashSet<>();
  private final Map<Long, Double> pressure = new HashMap<>();
  private long lastForgetDay = Long.MIN_VALUE;

  private VillageWorldManager(ServerLevel level) {
    this.level = level;
    this.data = level.getDataStorage().computeIfAbsent(VillageWorldData.TYPE);
  }

  public static synchronized VillageWorldManager get(ServerLevel level) {
    return INSTANCES.computeIfAbsent(level, VillageWorldManager::new);
  }

  public ServerLevel level() {
    return level;
  }

  public Collection<VillageNode> nodes() {
    return data.nodes.values();
  }

  public Set<RoadEdge> roads() {
    return data.roads;
  }

  public @Nullable VillageNode node(long id) {
    return data.nodes.get(id);
  }

  public boolean isActive(VillageNode node) {
    return active.contains(node.id());
  }

  // ---------------------------------------------------------------- chunk lifecycle

  public void queueChunk(ChunkPos pos) {
    long key = pos.pack();
    if (queuedChunks.add(key)) {
      level
          .getServer()
          .execute(
              () -> {
                queuedChunks.remove(key);
                processChunk(pos);
              });
    }
  }

  public void handleChunkLoad(ChunkPos chunkPos) {
    for (VillageNode node : data.nodes.values()) {
      if (node.intersects(chunkPos)) {
        node.markChunkLoaded(chunkPos);
      }
    }
  }

  public void handleChunkUnload(ChunkPos chunkPos) {
    for (VillageNode node : data.nodes.values()) {
      if (node.intersects(chunkPos)) {
        node.markChunkUnloaded(chunkPos);
        if (!node.loaded() && active.remove(node.id())) {
          // The state already holds the last full observation; from now on it is simulated.
          data.setDirty();
        }
      }
    }
  }

  private void processChunk(ChunkPos chunkPos) {
    StructureManager structures = level.structureManager();
    for (StructureStart start :
        structures.startsForStructure(chunkPos, this::isVanillaVillageStructure)) {
      registerVillage(start);
    }
    markRegionsInChunkDirty(chunkPos);
    refreshDirtyRegions();
    for (VillageNode node : data.nodes.values()) {
      if (node.loaded() && !active.contains(node.id())) {
        activateVillage(node);
      }
    }
  }

  private void registerVillage(StructureStart start) {
    BoundingBox structureBox = start.getBoundingBox();
    BlockPos center = structureBox.getCenter();
    VillageNode node = findMatchingNode(center);
    if (node == null) {
      node = new VillageNode(center.asLong(), center, null);
      data.nodes.put(node.id(), node);
      data.setDirty();
    }
    if (node.structureBox() != null) {
      return;
    }
    node.setStructure(center, structureBox);
    refreshLoadedChunks(node);
    rebuildFarmRegions(node, start);
    connectNearestNeighbor(node);
  }

  /**
   * The village became fully observable: catch the aggregate up to today and make the concrete
   * villagers match it. This is the only place where unobserved time turns into entities.
   */
  private void activateVillage(VillageNode node) {
    BoundingBox box = node.structureBox();
    if (box == null) {
      return;
    }
    active.add(node.id());
    refreshDirtyRegions(node);
    node.setBell(findBell(node));
    List<BlockPos> homes = homes(node);
    long today = currentDay();

    VillageState old = node.state();
    if (old == null) {
      node.updateState(
          VillageState.observed(
              node.id(),
              today,
              VillagePopulationScanner.scan(level, box),
              homes.size(),
              node.farmland()));
      data.setDirty();
      return;
    }

    old = old.withObservation(old.villagePopulation(), homes.size(), node.farmland());
    if (old.day() >= today) {
      node.updateState(old);
      return;
    }

    VillageState state =
        VillageSimulator.simulate(
            old, environment(node), level.getSeed(), SettlementsConfig.parameters(), today, true);
    node.updateState(state);
    data.setDirty();
    postTraffic(node, old, state, true);
    if (state.fallen() && !old.fallen()) {
      VillageMemory.record(
          level, node.center(), VillageMemory.VILLAGE_FALLEN, null, null, state.infected());
    }
    materialize(node, state, homes);
  }

  private void materialize(VillageNode node, VillageState state, List<BlockPos> homes) {
    BoundingBox box = node.structureBox();
    VillageStateRestorer.restore(
        level,
        box,
        state,
        homes,
        villager -> VillageMemory.teachHistory(level, villager, node.center()));
    if (state.fallen() && SettlementsConfig.enabled(SettlementsConfig.FALLEN_VILLAGES)) {
      VillageStateRestorer.restoreInfected(level, box, state.infected(), homes);
    }
  }

  // ---------------------------------------------------------------- observed villages

  /** Called every server tick of the level. */
  public void tick() {
    // Real ticks, not the calendar clock: the clock may be frozen or jump.
    if (level.getGameTime() % OBSERVE_INTERVAL != 0) {
      return;
    }
    long today = currentDay();
    for (VillageNode node : data.nodes.values()) {
      if (active.contains(node.id()) && node.loaded()) {
        observe(node, today);
      }
    }
    if (today != lastForgetDay) {
      lastForgetDay = today;
      VillageMemory.forgetOld(level);
    }
  }

  private void observe(VillageNode node, long today) {
    VillageState state = node.state();
    if (state == null) {
      return;
    }
    pressure.put(node.id(), GuestWildlife.hostilePressure(level, node.center(), PRESSURE_RADIUS));
    VillagePopulation population = VillagePopulationScanner.scan(level, node.structureBox());
    int beds = state.beds();
    if (state.day() < today) {
      beds = homes(node).size();
      node.setBell(findBell(node));
      VillageState before = state;
      state =
          VillageSimulator.simulate(
              state,
              environment(node),
              level.getSeed(),
              SettlementsConfig.parameters(),
              today,
              false);
      postTraffic(node, before, state, false);
    }
    state = state.withObservation(population, beds, node.farmland());
    if (state.fallen() && population.population() > 0) {
      // Somebody moved back in: the place is a village again.
      state = state.withFallen(false, 0);
    }
    if (!state.equals(node.state())) {
      node.updateState(state);
      data.setDirty();
    }
  }

  /** Deaths seen in the loaded world feed mourning and, if the last one died, the fall. */
  public void onVillagerDeath(Villager villager, @Nullable Entity killer) {
    VillageNode node = villageAt(villager.blockPosition());
    if (node == null || node.state() == null || !active.contains(node.id())) {
      return;
    }
    VillageState state = node.state().withLastDeathDay(currentDay());
    boolean lastOne =
        VillagePopulationScanner.findVillagers(level, node.structureBox()).stream()
            .allMatch(other -> other == villager || other.isRemoved());
    if (lastOne && killer instanceof Enemy) {
      int infected =
          1
              + level
                  .getEntitiesOfClass(
                      ZombieVillager.class,
                      VillagePopulationScanner.searchArea(node.structureBox()))
                  .size();
      state = state.withFallen(true, infected);
      VillageMemory.record(
          level, node.center(), VillageMemory.VILLAGE_FALLEN, null, villager, infected);
    }
    node.updateState(state);
    data.setDirty();
  }

  // ---------------------------------------------------------------- environment & caravans

  public VillageEnvironment environment(VillageNode node) {
    double hostile = GuestWildlife.hostilePressure(level, node.center(), PRESSURE_RADIUS);
    return new VillageEnvironment(
        hostile,
        new VillageEnvironment.Timeline() {
          @Override
          public double severeFraction(long fromDay, long toDay) {
            // One sample per step (a week at most): weather is a slow input to the aggregate.
            return severeOnDay(level, node.center(), fromDay + (toDay - fromDay) / 2) ? 1.0 : 0.0;
          }

          @Override
          public double importedFood(long fromDay, long toDay) {
            double cargo = SettlementsConfig.caravans().cargoFood();
            double total = 0.0;
            for (Caravans.Caravan caravan : arrivals(node, fromDay, toDay)) {
              if (!caravan.lost()) {
                total += cargo;
              }
            }
            return total;
          }
        });
  }

  /**
   * Severe weather on a day. The vanilla fallback only knows the current weather, so past and
   * future days count as calm unless Guest Atmosphere provides a real deterministic timeline.
   */
  public static boolean severeOnDay(ServerLevel level, BlockPos pos, long day) {
    long now = GuestTime.gameTime(level);
    if (day == GuestTime.day(now)) {
      return GuestWeather.get(level, pos, now).isSevere();
    }
    return ModList.get().isLoaded("guest_atmosphere")
        && GuestWeather.get(level, pos, day * GuestTime.TICKS_PER_DAY + 6000L).isSevere();
  }

  public List<Caravans.Caravan> arrivals(VillageNode node, long fromDay, long toDay) {
    List<Caravans.Caravan> result = new ArrayList<>();
    Caravans.Parameters parameters = SettlementsConfig.caravans();
    for (RoadEdge edge : data.roads) {
      if (edge.firstVillageId() != node.id() && edge.secondVillageId() != node.id()) {
        continue;
      }
      VillageNode first = data.nodes.get(edge.firstVillageId());
      VillageNode second = data.nodes.get(edge.secondVillageId());
      if (first == null || second == null) {
        continue;
      }
      BlockPos middle = midpoint(first, second);
      for (Caravans.Caravan caravan :
          Caravans.arrivals(
              level.getSeed(),
              edge,
              distance(first, second),
              fromDay,
              toDay,
              parameters,
              day -> severeOnDay(level, middle, day))) {
        if (caravan.toId() == node.id()) {
          result.add(caravan);
        }
      }
    }
    return result;
  }

  /** Caravans on the road at {@code gameTime}, for commands and debug rendering. */
  public List<Caravans.Caravan> caravansInTransit(long gameTime) {
    List<Caravans.Caravan> result = new ArrayList<>();
    Caravans.Parameters parameters = SettlementsConfig.caravans();
    double day = gameTime / (double) GuestTime.TICKS_PER_DAY;
    long today = GuestTime.day(gameTime);
    for (RoadEdge edge : data.roads) {
      VillageNode first = data.nodes.get(edge.firstVillageId());
      VillageNode second = data.nodes.get(edge.secondVillageId());
      if (first == null || second == null) {
        continue;
      }
      double distance = distance(first, second);
      BlockPos middle = midpoint(first, second);
      for (Caravans.Caravan caravan :
          Caravans.departures(
              level.getSeed(),
              edge,
              distance,
              today - Caravans.travelDays(distance, parameters),
              today + 1,
              parameters,
              d -> severeOnDay(level, middle, d))) {
        if (caravan.arriveDay() > day && caravan.departDay() <= day) {
          result.add(caravan);
        }
      }
    }
    return result;
  }

  /**
   * Unobserved movement becomes {@link RouteTrafficEvent}s: caravans that reached this village and,
   * for simulated absences, the farmers' daily walks between the bell and the fields.
   */
  private void postTraffic(
      VillageNode node, VillageState before, VillageState after, boolean commute) {
    Map<Long, Integer> tripsFrom = new HashMap<>();
    for (Caravans.Caravan caravan : arrivals(node, before.day(), after.day())) {
      if (!caravan.lost()) {
        tripsFrom.merge(caravan.fromId(), 1, Integer::sum);
      }
    }
    tripsFrom.forEach(
        (fromId, trips) -> {
          VillageNode from = data.nodes.get(fromId);
          if (from != null) {
            NeoForge.EVENT_BUS.post(
                new RouteTrafficEvent(
                    level, from.center(), node.center(), trips, CARAVAN_TRAVELER));
          }
        });

    BlockPos bell = node.bell();
    List<VillageFarmRegion> farms =
        node.farmRegions().stream().filter(region -> region.farmBox() != null).toList();
    if (!commute || bell == null || farms.isEmpty()) {
      return;
    }
    double farmers =
        (before.villagePopulation().professionCount(VillageSimulator.FARMER)
                + after.villagePopulation().professionCount(VillageSimulator.FARMER))
            / 2.0;
    double trips =
        farmers
            * (after.day() - before.day())
            * SettlementsConfig.value(SettlementsConfig.COMMUTE_TRIPS)
            / farms.size();
    if (trips <= 0.0) {
      return;
    }
    for (VillageFarmRegion farm : farms) {
      NeoForge.EVENT_BUS.post(
          new RouteTrafficEvent(level, bell, farm.farmBox().getCenter(), trips, VILLAGER_TRAVELER));
    }
  }

  // ---------------------------------------------------------------- debug & commands

  /** Runs {@code days} of absence on a village at once (debug), then re-materializes it. */
  public VillageState forceSimulate(VillageNode node, long days) {
    VillageState old = node.state();
    if (old == null) {
      return null;
    }
    VillageState state =
        VillageSimulator.simulateDays(
                old, environment(node), level.getSeed(), SettlementsConfig.parameters(), days)
            .withDay(old.day());
    node.updateState(state);
    data.setDirty();
    if (state.fallen() && !old.fallen()) {
      VillageMemory.record(
          level, node.center(), VillageMemory.VILLAGE_FALLEN, null, null, state.infected());
    }
    if (active.contains(node.id()) && node.structureBox() != null) {
      materialize(node, state, homes(node));
    }
    return state;
  }

  public @Nullable VillageNode nearest(BlockPos pos) {
    return data.nodes.values().stream()
        .filter(node -> node.state() != null)
        .min(Comparator.comparingDouble(node -> node.center().distSqr(pos)))
        .orElse(null);
  }

  public @Nullable VillageNode villageAt(BlockPos pos) {
    for (VillageNode node : data.nodes.values()) {
      BoundingBox box = node.structureBox();
      if (box != null
          && pos.getX() >= box.minX() - HOUSING_MARGIN
          && pos.getX() <= box.maxX() + HOUSING_MARGIN
          && pos.getZ() >= box.minZ() - HOUSING_MARGIN
          && pos.getZ() <= box.maxZ() + HOUSING_MARGIN) {
        return node;
      }
    }
    return null;
  }

  public double pressure(VillageNode node) {
    return pressure.getOrDefault(node.id(), GuestWildlife.BASELINE);
  }

  public synchronized VillageDebugSnapshot snapshot() {
    long now = GuestTime.gameTime(level);
    long today = GuestTime.day(now);
    List<VillageDebugSnapshot.VillageSnapshot> villages = new ArrayList<>();
    for (VillageNode node : data.nodes.values()) {
      List<VillageDebugSnapshot.FarmSnapshot> farms = new ArrayList<>();
      for (VillageFarmRegion region : node.farmRegions()) {
        farms.add(
            new VillageDebugSnapshot.FarmSnapshot(
                region.pieceBox(), region.farmBox(), region.farmlandAmount(), region.complete()));
      }
      VillageState state = node.state();
      double hostile = pressure(node);
      VillageSimulator.Rates rates =
          state == null
              ? null
              : VillageSimulator.rates(
                  state,
                  severeOnDay(level, node.center(), today) ? 1.0 : 0.0,
                  hostile,
                  today,
                  SettlementsConfig.parameters());
      villages.add(
          new VillageDebugSnapshot.VillageSnapshot(
              node.id(),
              node.center(),
              active.contains(node.id()),
              node.structureBox(),
              node.bell(),
              state,
              rates,
              hostile,
              List.copyOf(farms)));
    }

    List<VillageDebugSnapshot.RoadSnapshot> roads = new ArrayList<>();
    for (RoadEdge road : data.roads) {
      VillageNode first = data.nodes.get(road.firstVillageId());
      VillageNode second = data.nodes.get(road.secondVillageId());
      if (first != null && second != null) {
        roads.add(new VillageDebugSnapshot.RoadSnapshot(first.center(), second.center()));
      }
    }

    List<VillageDebugSnapshot.CaravanSnapshot> caravans = new ArrayList<>();
    double day = now / (double) GuestTime.TICKS_PER_DAY;
    for (Caravans.Caravan caravan : caravansInTransit(now)) {
      VillageNode from = data.nodes.get(caravan.fromId());
      VillageNode to = data.nodes.get(caravan.toId());
      if (from != null && to != null) {
        double progress = caravan.progress(day);
        caravans.add(
            new VillageDebugSnapshot.CaravanSnapshot(
                Vec3.atCenterOf(from.center()).lerp(Vec3.atCenterOf(to.center()), progress),
                progress,
                caravan.lost()));
      }
    }
    return new VillageDebugSnapshot(
        today, List.copyOf(villages), List.copyOf(roads), List.copyOf(caravans));
  }

  // ---------------------------------------------------------------- world queries

  /** Beds (vanilla HOME points of interest) are the housing capacity, as for vanilla breeding. */
  public List<BlockPos> homes(VillageNode node) {
    BoundingBox box = node.structureBox();
    if (box == null) {
      return List.of();
    }
    return level
        .getPoiManager()
        .getInRange(
            poi -> poi.is(PoiTypes.HOME), box.getCenter(), radius(box), PoiManager.Occupancy.ANY)
        .map(PoiRecord::getPos)
        .sorted()
        .toList();
  }

  private @Nullable BlockPos findBell(VillageNode node) {
    BoundingBox box = node.structureBox();
    if (box == null) {
      return null;
    }
    return level
        .getPoiManager()
        .findClosest(
            poi -> poi.is(PoiTypes.MEETING), box.getCenter(), radius(box), PoiManager.Occupancy.ANY)
        .orElse(null);
  }

  private static int radius(BoundingBox box) {
    return Math.max(box.getXSpan(), box.getZSpan()) / 2 + HOUSING_MARGIN;
  }

  private static double distance(VillageNode first, VillageNode second) {
    return Math.sqrt(first.center().distSqr(second.center()));
  }

  private static BlockPos midpoint(VillageNode first, VillageNode second) {
    return new BlockPos(
        (first.center().getX() + second.center().getX()) / 2,
        (first.center().getY() + second.center().getY()) / 2,
        (first.center().getZ() + second.center().getZ()) / 2);
  }

  private long currentDay() {
    return GuestTime.day(GuestTime.gameTime(level));
  }

  private void refreshLoadedChunks(VillageNode node) {
    node.clearLoadedChunks();
    BoundingBox box = node.structureBox();
    if (box == null) {
      return;
    }
    box.intersectingChunks()
        .forEach(
            chunk -> {
              if (level.getChunkSource().getChunkNow(chunk.x(), chunk.z()) != null) {
                node.markChunkLoaded(chunk);
              }
            });
  }

  private void refreshDirtyRegions(VillageNode node) {
    for (VillageFarmRegion region : node.farmRegions()) {
      if (region.dirty()) {
        region.update(VillageFarmScanner.scan(level, region.pieceBox()));
      }
    }
  }

  private void rebuildFarmRegions(VillageNode node, StructureStart start) {
    for (VillageFarmRegion oldRegion : node.farmRegions()) {
      unindexRegion(oldRegion);
    }
    List<VillageFarmRegion> regions = new ArrayList<>();
    for (StructurePiece piece : start.getPieces()) {
      VillageFarmRegion region = new VillageFarmRegion(node.id(), piece.getBoundingBox());
      region.update(VillageFarmScanner.scan(level, piece.getBoundingBox()));
      if (region.farmlandAmount() > 0 || !region.complete()) {
        regions.add(region);
        indexRegion(region);
      }
    }
    node.replaceFarmRegions(regions);
  }

  /** Neighbour search is expensive, so it runs once per village and the road is persisted. */
  private void connectNearestNeighbor(VillageNode node) {
    for (RoadEdge road : data.roads) {
      if (road.firstVillageId() == node.id() || road.secondVillageId() == node.id()) {
        return;
      }
    }
    BlockPos nearest = findNearestOtherVillage(node.center());
    if (nearest == null) {
      return;
    }
    VillageNode neighbor = findMatchingNode(nearest);
    if (neighbor == null) {
      neighbor = new VillageNode(nearest.asLong(), nearest, null);
      data.nodes.put(neighbor.id(), neighbor);
    }
    if (neighbor.id() != node.id()) {
      data.roads.add(RoadEdge.of(node.id(), neighbor.id()));
      data.setDirty();
    }
  }

  private @Nullable BlockPos findNearestOtherVillage(BlockPos center) {
    BlockPos best = null;
    double bestDistance = Double.MAX_VALUE;
    for (BlockPos offset : NEIGHBOR_PROBES) {
      BlockPos candidate =
          level.findNearestMapStructure(
              StructureTags.VILLAGE, center.offset(offset), NEIGHBOR_SEARCH_RADIUS, false);
      if (candidate == null) {
        continue;
      }
      double distance = candidate.distSqr(center);
      if (distance > 128.0 * 128.0 && distance < bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }
    return best;
  }

  public void handleBlockChange(BlockPos pos) {
    List<VillageFarmRegion> regions =
        regionsByChunk.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
    if (regions == null) {
      return;
    }
    for (VillageFarmRegion region : regions) {
      if (region.pieceBox().isInside(pos)) {
        region.markDirty();
      }
    }
    level.getServer().execute(this::refreshDirtyRegions);
  }

  private void markRegionsInChunkDirty(ChunkPos chunkPos) {
    List<VillageFarmRegion> regions = regionsByChunk.get(chunkPos.pack());
    if (regions != null) {
      regions.forEach(VillageFarmRegion::markDirty);
    }
  }

  private void refreshDirtyRegions() {
    for (VillageNode node : data.nodes.values()) {
      if (node.loaded()) {
        refreshDirtyRegions(node);
      }
    }
  }

  private void indexRegion(VillageFarmRegion region) {
    region
        .pieceBox()
        .intersectingChunks()
        .forEach(
            chunk ->
                regionsByChunk
                    .computeIfAbsent(chunk.pack(), ignored -> new ArrayList<>())
                    .add(region));
  }

  private void unindexRegion(VillageFarmRegion region) {
    region
        .pieceBox()
        .intersectingChunks()
        .forEach(
            chunk -> {
              List<VillageFarmRegion> regions = regionsByChunk.get(chunk.pack());
              if (regions != null) {
                regions.remove(region);
                if (regions.isEmpty()) {
                  regionsByChunk.remove(chunk.pack());
                }
              }
            });
  }

  private @Nullable VillageNode findMatchingNode(BlockPos center) {
    double radius = (double) VILLAGE_MATCH_RADIUS * VILLAGE_MATCH_RADIUS;
    return data.nodes.values().stream()
        .filter(node -> node.center().distSqr(center) <= radius)
        .min(Comparator.comparingDouble(node -> node.center().distSqr(center)))
        .orElse(null);
  }

  private boolean isVanillaVillageStructure(Structure structure) {
    Identifier key = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
    return key != null && VANILLA_VILLAGES.contains(key);
  }
}
