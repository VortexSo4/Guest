package com.vortexso.guest_settlements.life;

import com.mojang.logging.LogUtils;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.life.Errands.Errand;
import com.vortexso.guest_settlements.life.Errands.Step;
import com.vortexso.guest_settlements.life.LifeData.Traveler;
import com.vortexso.guest_settlements.life.VillageLife.Role;
import com.vortexso.guest_settlements.village.RoadEdge;
import com.vortexso.guest_settlements.village.RoutePlanner;
import com.vortexso.guest_settlements.village.VillageNode;
import com.vortexso.guest_settlements.village.VillagePopulation;
import com.vortexso.guest_settlements.village.VillageState;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

public final class Cartographers {
  private static final Logger LOGGER = LogUtils.getLogger();
  static final long LEAVE = 2500;
  static final long RETURN = 9000;
  private static final int GATE_MARGIN = 24;
  private static final int WATCHED = 24;

  private static final int FAR = 160;

  private static final long LATE = 2400;
  private static final long EVENT_TRIP = 0xCA27L;
  private static final int MAP_PRICE = 8;
  private static final int MAX_MAP_OFFERS = 3;
  private static final String MAP_NAME = "guest_settlements.map.explored";

  private Cartographers() {}

  record Trip(long depart, long back) {}

  static @Nullable Trip trip(long seed, UUID id, long day) {
    long hash =
        GuestHash.hash(seed, id.getMostSignificantBits(), id.getLeastSignificantBits(), EVENT_TRIP);
    int away = 2 + (int) Math.floorMod(hash, 3L);
    int home = 3 + (int) Math.floorMod(hash >>> 8, 3L);
    int cycle = away + home;
    long position = Math.floorMod(day + (hash >>> 16), (long) cycle);
    long depart = day - position;
    return position <= away ? new Trip(depart, depart + away) : null;
  }

  static boolean away(long seed, UUID id, long clock) {
    long day = GuestTime.day(clock);
    long time = GuestTime.tickOfDay(clock);
    Trip trip = trip(seed, id, day);
    if (trip == null) {
      return false;
    }
    if (day == trip.depart()) {
      return time >= LEAVE;
    }
    return day < trip.back() || time < RETURN;
  }

  public static int away(ServerLevel level, VillageNode node) {
    return LifeData.get(level).travelers(node.id()).size();
  }

  public static VillagePopulation withTravelers(
      ServerLevel level, VillageNode node, VillagePopulation present) {
    List<Traveler> travelers = LifeData.get(level).travelers(node.id());
    if (travelers.isEmpty()) {
      return present;
    }
    Map<Identifier, Integer> professions = new HashMap<>(present.professions());
    professions.merge(VillagerProfession.CARTOGRAPHER.identifier(), travelers.size(), Integer::sum);
    return new VillagePopulation(present.children(), professions);
  }

  public static VillageState withoutTravelers(
      ServerLevel level, VillageNode node, VillageState state) {
    List<Traveler> travelers = LifeData.get(level).travelers(node.id());
    if (travelers.isEmpty()) {
      return state;
    }
    Map<Identifier, Integer> professions = new HashMap<>(state.professions());
    professions.merge(
        VillagerProfession.CARTOGRAPHER.identifier(), -travelers.size(), Integer::sum);
    professions.values().removeIf(count -> count <= 0);
    return state.withObservation(
        new VillagePopulation(state.children(), professions), state.beds(), state.farmland());
  }

  static void tick(
      ServerLevel level,
      VillageWorldManager manager,
      VillageNode node,
      List<Villager> villagers,
      long clock) {
    if (!SettlementsConfig.enabled(SettlementsConfig.CARTOGRAPHER_TRIPS)
        || node.structureBox() == null) {
      return;
    }
    returns(level, node, clock);
    for (Villager villager : villagers) {
      if (villager.isBaby()
          || !villager.getVillagerData().profession().is(VillagerProfession.CARTOGRAPHER)
          || !away(level.getSeed(), villager.getUUID(), clock)) {
        continue;
      }
      Trip trip = trip(level.getSeed(), villager.getUUID(), GuestTime.day(clock));
      long departed = trip.depart() * GuestTime.TICKS_PER_DAY + LEAVE;
      if (clock - departed > LATE && !watched(level, villager.position())) {

        store(level, node, villager, trip, villager.blockPosition());
      } else if (!Errands.doing(villager, Role.TRAVEL)
          && !com.vortexso.guest_core.api.world.GuestWeather.get(
                  level, villager.blockPosition(), clock)
              .isSevere()) {
        leave(level, manager, node, villager, trip);
      }
    }
  }

  private static void leave(
      ServerLevel level,
      VillageWorldManager manager,
      VillageNode node,
      Villager villager,
      Trip trip) {
    BlockPos[] route = gates(level, manager, node, villager.getUUID());
    BlockPos gate = route[0];
    BlockPos far = route[1];
    Errands.Action goIfUnseen =
        (l, traveler, tick) -> {
          if (tick % 20 == 0 && !watched(l, traveler.position())) {
            store(l, node, traveler, trip, gate);
          }
        };
    Errands.offer(
        level,
        villager,
        new Errand(
            Role.TRAVEL,
            VillageLife.WORK + 5,
            level.getGameTime() + GuestTime.TICKS_PER_DAY,
            List.of(
                new Step(
                    Errands.at(gate), 2, 0.55F, 60, null, new ItemStack(Items.MAP), goIfUnseen),
                new Step(
                    Errands.at(far),
                    3,
                    0.55F,
                    Integer.MAX_VALUE,
                    null,
                    new ItemStack(Items.MAP),
                    goIfUnseen))));
  }

  private static BlockPos[] gates(
      ServerLevel level, VillageWorldManager manager, VillageNode node, UUID id) {
    BlockPos from = node.bell() != null ? node.bell() : node.center();
    int radius = Work.radius(node.structureBox()) + GATE_MARGIN;
    Vec3 direction = null;
    List<BlockPos> route = null;
    for (RoadEdge road : manager.roads()) {
      long other =
          road.firstVillageId() == node.id()
              ? road.secondVillageId()
              : road.secondVillageId() == node.id() ? road.firstVillageId() : 0L;
      VillageNode neighbor = other == 0L ? null : manager.node(other);
      if (neighbor != null) {
        route = RoutePlanner.route(level, from, neighbor.center());
        break;
      }
    }
    if (route != null && route.size() >= 2) {
      BlockPos previous = route.get(0);
      for (BlockPos point : route) {
        if (!point.closerThan(from, radius)) {
          direction = Vec3.atLowerCornerOf(point.subtract(previous)).normalize();
          Vec3 gate =
              Vec3.atLowerCornerOf(previous)
                  .add(direction.scale(Math.max(0.0, radius - Math.sqrt(previous.distSqr(from)))));
          return new BlockPos[] {
            surface(level, gate), surface(level, gate.add(direction.scale(FAR)))
          };
        }
        previous = point;
      }
    }
    double angle =
        GuestHash.unit(GuestHash.hash(level.getSeed(), id.getMostSignificantBits(), EVENT_TRIP))
            * 2.0
            * Math.PI;
    direction = new Vec3(Math.cos(angle), 0.0, Math.sin(angle));
    Vec3 gate = Vec3.atLowerCornerOf(from).add(direction.scale(radius));
    return new BlockPos[] {surface(level, gate), surface(level, gate.add(direction.scale(FAR)))};
  }

  private static BlockPos surface(ServerLevel level, Vec3 pos) {
    BlockPos top = Places.top(level, (int) Math.floor(pos.x), (int) Math.floor(pos.z));
    return top != null ? top.above() : BlockPos.containing(pos);
  }

  private static boolean watched(ServerLevel level, Vec3 pos) {
    for (var player : level.players()) {
      if (player.distanceToSqr(pos) < WATCHED * WATCHED) {
        return true;
      }
    }
    return false;
  }

  private static void store(
      ServerLevel level, VillageNode node, Villager villager, Trip trip, BlockPos gate) {
    if (villager.isRemoved()) {
      return;
    }
    Errands.release(villager);
    try (ProblemReporter.ScopedCollector reporter =
        new ProblemReporter.ScopedCollector(villager.problemPath(), LOGGER)) {
      TagValueOutput output = TagValueOutput.createWithContext(reporter, level.registryAccess());
      if (!villager.save(output)) {
        return;
      }
      LifeData.get(level)
          .add(
              new Traveler(
                  node.id(),
                  villager.getUUID(),
                  output.buildResult(),
                  trip.depart(),
                  trip.back(),
                  gate));
    }
    villager.discard();
  }

  private static void returns(ServerLevel level, VillageNode node, long clock) {
    LifeData data = LifeData.get(level);
    for (Traveler traveler : data.travelers(node.id())) {
      long back = traveler.back() * GuestTime.TICKS_PER_DAY + RETURN;
      if (clock < back) {
        continue;
      }
      boolean late = clock - back > LATE;
      BlockPos gate = traveler.gate();
      if (!late && !Places.loaded(level, gate.getX(), gate.getZ())) {
        continue;
      }
      data.remove(traveler);
      Villager villager = restore(level, traveler);
      if (villager == null) {
        continue;
      }
      ItemStack map = map(level, node, traveler);
      BlockPos table =
          villager.getBrain().getMemory(MemoryModuleType.JOB_SITE).map(GlobalPos::pos).orElse(null);
      if (late || table == null) {
        BlockPos at = table != null ? Places.beside(level, table) : null;
        if (at == null) {
          at = Places.ground(level, node.bell() != null ? node.bell() : node.center());
        }
        villager.setPos(Vec3.atBottomCenterOf(at));
        level.addWithUUID(villager);
        sell(villager, map);
        continue;
      }
      villager.setPos(Vec3.atBottomCenterOf(gate));
      level.addWithUUID(villager);
      Errands.offer(
          level,
          villager,
          new Errand(
                  Role.TRAVEL,
                  VillageLife.WORK + 5,
                  level.getGameTime() + LATE,
                  List.of(
                      Step.at(
                          table,
                          1,
                          120,
                          map,
                          (l, v, tick) -> {
                            if (tick % 30 == 0) {
                              l.playSound(
                                  null,
                                  v,
                                  SoundEvents.BOOK_PAGE_TURN,
                                  SoundSource.NEUTRAL,
                                  0.8F,
                                  0.8F);
                            }
                          })))
              .onEnd(() -> sell(villager, map)));
    }
  }

  private static @Nullable Villager restore(ServerLevel level, Traveler traveler) {
    try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(LOGGER)) {
      Optional<net.minecraft.world.entity.Entity> entity =
          EntityType.create(
              TagValueInput.create(reporter, level.registryAccess(), traveler.entity()),
              level,
              EntitySpawnReason.LOAD);
      if (entity.isPresent()
          && entity.get() instanceof Villager villager
          && level.getEntity(villager.getUUID()) == null) {
        return villager;
      }
    }
    return null;
  }

  private static ItemStack map(ServerLevel level, VillageNode node, Traveler traveler) {
    BlockPos from = node.bell() != null ? node.bell() : node.center();
    Vec3 direction =
        Vec3.atLowerCornerOf(traveler.gate().subtract(from)).multiply(1.0, 0.0, 1.0).normalize();
    double distance = 256 + 64 * (traveler.back() - traveler.depart());
    BlockPos camp = BlockPos.containing(Vec3.atLowerCornerOf(from).add(direction.scale(distance)));
    ItemStack map = MapItem.create(level, camp.getX(), camp.getZ(), (byte) 2, true, true);
    MapItem.renderBiomePreviewMap(level, map);
    MapItemSavedData.addTargetDecoration(map, camp, "+", MapDecorationTypes.RED_X);
    map.set(DataComponents.ITEM_NAME, Component.translatable(MAP_NAME, camp.getX(), camp.getZ()));
    return map;
  }

  private static void sell(Villager villager, ItemStack map) {
    var offers = villager.getOffers();
    List<MerchantOffer> explored =
        offers.stream().filter(offer -> isExploredMap(offer.getResult())).toList();
    if (explored.size() >= MAX_MAP_OFFERS) {
      offers.remove(explored.get(0));
    }
    offers.add(
        new MerchantOffer(
            new ItemCost(Items.EMERALD, MAP_PRICE),
            Optional.of(new ItemCost(Items.COMPASS)),
            map,
            1,
            5,
            0.2F));
  }

  static boolean isExploredMap(ItemStack stack) {
    Component name = stack.get(DataComponents.ITEM_NAME);
    return stack.is(Items.FILLED_MAP)
        && name != null
        && name.getContents()
            instanceof net.minecraft.network.chat.contents.TranslatableContents contents
        && contents.getKey().equals(MAP_NAME);
  }
}
