package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.event.RouteTrafficEvent;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.api.world.GuestWildlife;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.life.Cartographers;
import com.vortexso.guest_settlements.life.Construction;
import com.vortexso.guest_settlements.life.VillageLife;
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
import net.minecraft.util.Mth;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import org.jspecify.annotations.Nullable;

public final class VillageWorldManager {
  public static final Identifier CARAVAN_TRAVELER = GuestSettlements.id("caravan");
  public static final Identifier VILLAGER_TRAVELER = GuestSettlements.id("villager");

  private static final int NEIGHBOR_SEARCH_RADIUS = 1;

  private static final int VILLAGE_MATCH_RADIUS = 256;
  private static final int PRESSURE_RADIUS = 64;
  private static final int HOUSING_MARGIN = 8;
  private static final int OBSERVE_INTERVAL = 100;
  private static final int FIELD_MARGIN = 14;
  private static final int FIELD_SCAN_INTERVAL = OBSERVE_INTERVAL * 6;

  private static final BlockPos[] NEIGHBOR_PROBES = {
    new BlockPos(544, 0, 0),
    new BlockPos(-544, 0, 0),
    new BlockPos(0, 0, 544),
    new BlockPos(0, 0, -544),
    new BlockPos(544, 0, 544),
    new BlockPos(544, 0, -544),
    new BlockPos(-544, 0, 544),
    new BlockPos(-544, 0, -544)
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
      if (node.loaded() && !active.contains(node.id()) && entitiesLoaded(node)) {
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

  private void activateVillage(VillageNode node) {
    BoundingBox box = node.structureBox();
    if (box == null) {
      return;
    }
    active.add(node.id());
    refreshDirtyRegions(node);
    scanFields(node);
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
    state = Emigration.afterSimulation(this, node, old.day(), state);
    node.updateState(state);
    data.setDirty();
    postTraffic(node, old, state, true);
    if (state.fallen() && !old.fallen()) {
      VillageMemory.record(
          level, node.center(), VillageMemory.VILLAGE_FALLEN, null, null, state.infected());
    }
    Construction.catchUp(level, this, node, state, today);
    materialize(node, state, homes);
  }

  private void materialize(VillageNode node, VillageState state, List<BlockPos> homes) {
    BoundingBox box = node.structureBox();
    VillageStateRestorer.restore(
        level,
        box,
        Cartographers.withoutTravelers(level, node, state),
        homes,
        villager -> VillageMemory.teachHistory(level, villager, node.center()));
    if (state.fallen() && SettlementsConfig.enabled(SettlementsConfig.FALLEN_VILLAGES)) {
      VillageStateRestorer.restoreInfected(level, box, state.infected(), homes);
    }
  }

  public void tick() {

    if (level.getGameTime() % OBSERVE_INTERVAL != 0) {
      return;
    }
    long today = currentDay();
    for (VillageNode node : data.nodes.values()) {
      if (active.contains(node.id()) && node.loaded()) {
        observe(node, today);
      } else if (node.loaded() && entitiesLoaded(node)) {

        activateVillage(node);
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

    VillagePopulation population = VillageLife.population(level, node);

    int beds = homes(node).size();
    if (level.getGameTime() % FIELD_SCAN_INTERVAL == 0) {
      scanFields(node);
    }
    if (state.day() < today) {
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

      state = state.withFallen(false, 0);
    }
    if (!state.equals(node.state())) {
      node.updateState(state);
      data.setDirty();
    }
  }

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

  public VillageEnvironment environment(VillageNode node) {
    double hostile = GuestWildlife.hostilePressure(level, node.center(), PRESSURE_RADIUS);
    return new VillageEnvironment(
        hostile,
        new VillageEnvironment.Timeline() {
          @Override
          public double severeFraction(long fromDay, long toDay) {

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
      int travel = Caravans.travelDays(distance(first, second), parameters);
      for (Caravans.Caravan caravan : departures(edge, fromDay - travel, toDay - travel)) {
        if (caravan.toId() == node.id()) {
          result.add(caravan);
        }
      }
    }
    return result;
  }

  public List<Caravans.Caravan> departures(RoadEdge edge, long fromDay, long toDay) {
    VillageNode first = data.nodes.get(edge.firstVillageId());
    VillageNode second = data.nodes.get(edge.secondVillageId());
    if (first == null || second == null || Roads.abandoned(first, second)) {
      return List.of();
    }
    BlockPos middle = midpoint(first, second);
    List<Caravans.Caravan> result = new ArrayList<>();
    for (Caravans.Caravan caravan :
        Caravans.departures(
            level.getSeed(),
            edge,
            distance(first, second),
            fromDay,
            toDay,
            SettlementsConfig.caravans(),
            day -> severeOnDay(level, middle, day))) {
      VillageNode destination = caravan.toId() == first.id() ? first : second;
      result.add(caravan.withLost(CaravanScenes.lost(level, destination, caravan)));
    }
    return result;
  }

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
      for (Caravans.Caravan caravan :
          departures(edge, today - Caravans.travelDays(distance, parameters), today + 1)) {
        if (caravan.arriveDay() > day && caravan.departDay() <= day) {
          result.add(caravan);
        }
      }
    }
    return result;
  }

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
            Roads.postTraffic(level, from, node, trips, CARAVAN_TRAVELER);
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

  public VillageState forceSimulate(VillageNode node, long days) {
    VillageState old = node.state();
    if (old == null) {
      return null;
    }
    VillageState state =
        Emigration.afterSimulation(
                this,
                node,
                old.day(),
                VillageSimulator.simulateDays(
                    old, environment(node), level.getSeed(), SettlementsConfig.parameters(), days))
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
        List<BlockPos> route =
            RoutePlanner.cached(level, Roads.anchor(first), Roads.anchor(second));
        roads.add(
            new VillageDebugSnapshot.RoadSnapshot(
                first.center(),
                second.center(),
                route == null ? List.of() : Roads.betweenVillages(route, first, second),
                Roads.abandoned(first, second)));
      }
    }

    List<VillageDebugSnapshot.CaravanSnapshot> caravans = new ArrayList<>();
    double day = now / (double) GuestTime.TICKS_PER_DAY;
    for (Caravans.Caravan caravan : caravansInTransit(now)) {
      VillageNode from = data.nodes.get(caravan.fromId());
      VillageNode to = data.nodes.get(caravan.toId());
      if (from != null && to != null) {
        double progress = caravan.progress(day);
        List<BlockPos> route = RoutePlanner.cached(level, Roads.anchor(from), Roads.anchor(to));
        caravans.add(
            new VillageDebugSnapshot.CaravanSnapshot(
                route == null
                    ? Vec3.atCenterOf(from.center()).lerp(Vec3.atCenterOf(to.center()), progress)
                    : Roads.pointAt(route, progress),
                progress,
                caravan.lost()));
      }
    }
    return new VillageDebugSnapshot(
        today, List.copyOf(villages), List.copyOf(roads), List.copyOf(caravans));
  }

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

  private boolean entitiesLoaded(VillageNode node) {
    AABB area = VillagePopulationScanner.searchArea(node.structureBox());
    for (int x = Mth.floor(area.minX) >> 4; x <= Mth.floor(area.maxX) >> 4; x++) {
      for (int z = Mth.floor(area.minZ) >> 4; z <= Mth.floor(area.maxZ) >> 4; z++) {
        if (!level.areEntitiesLoaded(ChunkPos.pack(x, z))) {
          return false;
        }
      }
    }
    return true;
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

  private void scanFields(VillageNode node) {
    node.setFieldFarmland(
        VillageFarmScanner.scan(
                level,
                node.structureBox().inflatedBy(FIELD_MARGIN),
                node.farmRegions().stream().map(VillageFarmRegion::pieceBox).toList())
            .farmlandAmount());
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
