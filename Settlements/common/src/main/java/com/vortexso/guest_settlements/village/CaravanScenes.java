package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.history.GuestHistory;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.memory.VillageMemory;
import com.vortexso.guest_settlements.society.Traces;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.TraderLlama;
import net.minecraft.world.entity.npc.wanderingtrader.WanderingTrader;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class CaravanScenes {
  public static final String TAG = "guest_settlements.caravan";

  private static final int INTERVAL = 20;
  private static final int WRECK_INTERVAL = 200;
  private static final double APPEAR = 64.0;
  private static final double VANISH = 96.0;
  private static final double NEAR_ROAD = 192.0;

  private static final double LOOKAHEAD_DAYS = 800.0 / GuestTime.TICKS_PER_DAY;

  private static final int LLAMAS = 2;
  private static final int ATTACKERS = 3;
  private static final long EVENT_WRECK = 0x3EC4L;

  private static final class Scene {
    Caravans.Caravan caravan;
    final UUID trader;
    final List<UUID> companions = new ArrayList<>();
    final List<UUID> attackers = new ArrayList<>();
    boolean ambushed;

    Scene(Caravans.Caravan caravan, UUID trader) {
      this.caravan = caravan;
      this.trader = trader;
    }
  }

  private static final Map<ServerLevel, Map<Long, Scene>> SCENES = new WeakHashMap<>();

  private CaravanScenes() {}

  public static UUID uuid(Caravans.Caravan caravan) {
    return new UUID(caravan.id(), 0xCA7A7A7L);
  }

  public static boolean lost(ServerLevel level, VillageNode destination, Caravans.Caravan caravan) {
    UUID id = uuid(caravan);
    boolean saved = false;
    boolean killed = false;
    for (var record :
        GuestHistory.get(level)
            .near(destination.center(), 16, r -> r.subject().map(id::equals).orElse(false))) {
      saved |= record.kind().equals(VillageMemory.CARAVAN_SAVED);
      killed |= record.kind().equals(VillageMemory.CARAVAN_LOST);
    }
    return killed || (caravan.lost() && !saved);
  }

  public static void onLevelTick(ServerLevel level) {
    if (level.getGameTime() % INTERVAL != 0) {
      return;
    }
    Map<Long, Scene> scenes = SCENES.computeIfAbsent(level, ignored -> new HashMap<>());
    if (level.players().isEmpty()) {
      if (!scenes.isEmpty()) {
        new ArrayList<>(scenes.values()).forEach(scene -> dismiss(level, scenes, scene));
      }
      return;
    }
    VillageWorldManager manager = VillageWorldManager.get(level);
    long now = GuestTime.gameTime(level);
    double day = now / (double) GuestTime.TICKS_PER_DAY;
    boolean wrecks =
        level.getGameTime() % WRECK_INTERVAL == 0
            && SettlementsConfig.enabled(SettlementsConfig.CARAVAN_WRECKS);
    boolean visible = SettlementsConfig.enabled(SettlementsConfig.VISIBLE_CARAVANS);
    Set<Long> current = new HashSet<>();

    for (RoadEdge edge : List.copyOf(manager.roads())) {
      VillageNode first = manager.node(edge.firstVillageId());
      VillageNode second = manager.node(edge.secondVillageId());
      if (first == null || second == null || !nearRoad(level, first, second)) {
        continue;
      }
      List<BlockPos> route = RoutePlanner.cached(level, Roads.anchor(first), Roads.anchor(second));
      if (route == null) {
        RoutePlanner.whenPlanned(level, Roads.anchor(first), Roads.anchor(second), ignored -> {});
        continue;
      }
      double distance = Math.sqrt(first.center().distSqr(second.center()));
      int travel = Caravans.travelDays(distance, SettlementsConfig.caravans());
      long today = (long) Math.floor(day);
      long window = wrecks ? SettlementsConfig.value(SettlementsConfig.WRECK_DAYS) : 0;
      for (Caravans.Caravan caravan :
          manager.departures(edge, today - travel - window, today + 1)) {
        VillageNode from = manager.node(caravan.fromId());
        List<BlockPos> path = from == first ? route : route.reversed();
        if (caravan.lost() && caravan.lossDay() <= day) {
          if (wrecks && !scenes.containsKey(caravan.id()) && !Traces.exists(level, caravan.id())) {
            placeWreck(level, caravan, Roads.pointAt(path, caravan.lossAt()));
          }
          if (!scenes.containsKey(caravan.id())) {
            continue;
          }
        }
        if (!visible || caravan.departDay() > day || caravan.arriveDay() <= day) {
          continue;
        }
        current.add(caravan.id());
        Vec3 at = Roads.pointAt(path, caravan.progress(day));
        Scene scene = scenes.get(caravan.id());
        double nearest = nearestPlayer(level, at);
        if (scene == null
            && nearest < APPEAR
            && level.isPositionEntityTicking(BlockPos.containing(at))) {
          scene = appear(level, caravan, at);
          if (scene != null) {
            scenes.put(caravan.id(), scene);
          }
        } else if (scene != null) {
          scene.caravan = caravan;
          if (!update(level, scenes, scene, path, day, nearest)) {
            scenes.remove(caravan.id());
          }
        }
      }
    }

    for (Scene scene : new ArrayList<>(scenes.values())) {
      if (!current.contains(scene.caravan.id())) {
        release(level, scene);
        scenes.remove(scene.caravan.id());
      }
    }
  }

  private static boolean nearRoad(ServerLevel level, VillageNode first, VillageNode second) {
    Vec3 a = Vec3.atCenterOf(first.center());
    Vec3 b = Vec3.atCenterOf(second.center());
    for (ServerPlayer player : level.players()) {
      Vec3 p = player.position();
      Vec3 ab = new Vec3(b.x - a.x, 0.0, b.z - a.z);
      double t =
          Math.max(
              0.0,
              Math.min(
                  1.0, ((p.x - a.x) * ab.x + (p.z - a.z) * ab.z) / Math.max(1.0, ab.lengthSqr())));
      double dx = a.x + ab.x * t - p.x;
      double dz = a.z + ab.z * t - p.z;
      if (dx * dx + dz * dz < NEAR_ROAD * NEAR_ROAD) {
        return true;
      }
    }
    return false;
  }

  private static double nearestPlayer(ServerLevel level, Vec3 at) {

    Player player = level.getNearestPlayer(at.x, at.y, at.z, VANISH * 2, (Predicate<Entity>) null);
    return player == null ? Double.MAX_VALUE : player.position().distanceTo(at);
  }

  private static BlockPos ground(ServerLevel level, Vec3 at) {
    int x = (int) Math.floor(at.x);
    int z = (int) Math.floor(at.z);
    return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
  }

  private static @Nullable Scene appear(ServerLevel level, Caravans.Caravan caravan, Vec3 at) {
    BlockPos pos = ground(level, at);
    WanderingTrader trader = EntityType.WANDERING_TRADER.spawn(level, pos, EntitySpawnReason.EVENT);
    if (trader == null) {
      return null;
    }
    trader.addTag(TAG);

    trader.setDespawnDelay(Integer.MAX_VALUE);
    Scene scene = new Scene(caravan, trader.getUUID());
    for (int i = 0; i < LLAMAS; i++) {
      TraderLlama llama = EntityType.TRADER_LLAMA.spawn(level, pos, EntitySpawnReason.EVENT);
      if (llama != null) {
        llama.setChest(true);
        llama.setLeashedTo(trader, true);
        llama.addTag(TAG);
        scene.companions.add(llama.getUUID());
      }
    }
    return scene;
  }

  private static boolean update(
      ServerLevel level,
      Map<Long, Scene> scenes,
      Scene scene,
      List<BlockPos> path,
      double day,
      double nearest) {
    if (!(level.getEntity(scene.trader) instanceof WanderingTrader trader) || !trader.isAlive()) {

      dismiss(level, scenes, scene);
      return false;
    }
    if (nearest > VANISH) {
      dismiss(level, scenes, scene);
      return false;
    }
    Caravans.Caravan caravan = scene.caravan;
    if (caravan.lost() && !scene.ambushed && caravan.progress(day) >= caravan.lossAt()) {
      ambush(level, scene, trader);
    }
    if (scene.ambushed
        && !scene.attackers.isEmpty()
        && scene.attackers.stream().noneMatch(id -> alive(level, id))) {
      saved(level, scene, trader);
      scene.attackers.clear();
    }
    if (scene.attackers.isEmpty() || !scene.ambushed) {
      Vec3 ahead = Roads.pointAt(path, caravan.progress(day + LOOKAHEAD_DAYS));
      trader.setWanderTarget(ground(level, ahead));
    }
    return true;
  }

  private static void ambush(ServerLevel level, Scene scene, WanderingTrader trader) {
    scene.ambushed = true;
    boolean night = GuestTime.isNight(GuestTime.gameTime(level));
    EntityType<? extends Mob> type = night ? EntityType.ZOMBIE : EntityType.PILLAGER;
    for (int i = 0; i < ATTACKERS; i++) {
      double angle = (i + GuestHash.unit(scene.caravan.id())) * Math.PI * 2.0 / ATTACKERS;
      Vec3 at = trader.position().add(Math.cos(angle) * 12.0, 0.0, Math.sin(angle) * 12.0);
      Mob attacker = type.spawn(level, ground(level, at), EntitySpawnReason.EVENT);
      if (attacker != null) {
        attacker.addTag(TAG);
        attacker.setTarget(trader);
        scene.attackers.add(attacker.getUUID());
      }
    }
  }

  private static void saved(ServerLevel level, Scene scene, WanderingTrader trader) {
    VillageNode destination = VillageWorldManager.get(level).node(scene.caravan.toId());
    if (destination == null) {
      return;
    }
    Player hero = level.getNearestPlayer(trader, 48.0);

    VillageMemory.recordIds(
        level,
        destination.center(),
        VillageMemory.CARAVAN_SAVED,
        hero == null ? null : hero.getUUID(),
        uuid(scene.caravan),
        ATTACKERS);
  }

  public static void onDeath(LivingEntity dead, DamageSource source) {
    if (!(dead instanceof WanderingTrader trader)
        || !trader.entityTags().contains(TAG)
        || !(trader.level() instanceof ServerLevel level)) {
      return;
    }
    Map<Long, Scene> scenes = SCENES.getOrDefault(level, Map.of());
    for (Scene scene : List.copyOf(scenes.values())) {
      if (scene.trader.equals(trader.getUUID())) {
        VillageNode destination = VillageWorldManager.get(level).node(scene.caravan.toId());
        Entity killer = source.getEntity();
        if (destination != null && !scene.caravan.lost()) {
          VillageMemory.recordIds(
              level,
              destination.center(),
              VillageMemory.CARAVAN_LOST,
              killer instanceof Player ? killer.getUUID() : null,
              uuid(scene.caravan),
              1);
        }
        if (SettlementsConfig.enabled(SettlementsConfig.CARAVAN_WRECKS)) {
          placeWreck(
              level, scene.caravan.withLost(true), trader.position(), GuestTime.gameTime(level));
        }

        for (UUID id : scene.companions) {
          if (level.getEntity(id) instanceof Entity companion) {
            companion.removeTag(TAG);
          }
        }
        scene.companions.clear();
        scenes.remove(scene.caravan.id());
      }
    }
  }

  private static void placeWreck(ServerLevel level, Caravans.Caravan caravan, Vec3 at) {
    placeWreck(level, caravan, at, (long) (caravan.lossDay() * GuestTime.TICKS_PER_DAY));
  }

  private static void placeWreck(ServerLevel level, Caravans.Caravan caravan, Vec3 at, long time) {
    BlockPos pos = BlockPos.containing(at);
    if (Traces.exists(level, caravan.id()) || !level.isLoaded(pos)) {
      return;
    }
    long lifetime = SettlementsConfig.value(SettlementsConfig.WRECK_DAYS) * GuestTime.TICKS_PER_DAY;
    long hash = GuestHash.hash(level.getSeed(), caravan.id(), EVENT_WRECK);
    BlockPos site = Traces.findSite(level, Traces.CARAVAN_WRECK, pos, 10, hash, true);
    if (site == null) {

      Traces.placeBlocks(
          level, caravan.id(), Traces.CARAVAN_WRECK.getPath(), Map.of(), time, lifetime);
    } else {
      Rotation rotation = Rotation.values()[(int) Math.floorMod(hash, 4L)];
      Traces.placeTemplate(
          level, caravan.id(), Traces.CARAVAN_WRECK, site, rotation, true, time, lifetime);
    }
  }

  private static boolean alive(ServerLevel level, UUID id) {
    return level.getEntity(id) instanceof Entity entity && entity.isAlive();
  }

  private static void dismiss(ServerLevel level, Map<Long, Scene> scenes, Scene scene) {
    discard(level, scene.trader);
    scene.companions.forEach(id -> discard(level, id));
    scene.attackers.forEach(id -> discard(level, id));
    scenes.remove(scene.caravan.id());
  }

  private static void release(ServerLevel level, Scene scene) {
    if (level.getEntity(scene.trader) instanceof WanderingTrader trader) {
      trader.removeTag(TAG);
      trader.setDespawnDelay((int) GuestTime.TICKS_PER_DAY);
    }
    for (UUID id : scene.companions) {
      if (level.getEntity(id) instanceof Entity companion) {
        companion.removeTag(TAG);
      }
    }
    scene.attackers.forEach(id -> discard(level, id));
  }

  private static void discard(ServerLevel level, UUID id) {
    Entity entity = level.getEntity(id);
    if (entity != null) {
      entity.discard();
    }
  }

  public static boolean onJoin(Entity entity, ServerLevel level, boolean fromDisk) {
    return !(fromDisk && entity.entityTags().contains(TAG));
  }
}
