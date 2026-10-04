package com.vortexso.guest_settlements.life;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.debug.GuestDebug;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.village.VillageNode;
import com.vortexso.guest_settlements.village.VillagePopulation;
import com.vortexso.guest_settlements.village.VillagePopulationScanner;
import com.vortexso.guest_settlements.village.VillageState;
import com.vortexso.guest_settlements.village.VillageWorldManager;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.jspecify.annotations.Nullable;

public final class VillageLife {
  public static final int INTERVAL = 40;

  static final String EMIGRANT_TAG = "guest_settlements.emigrant";

  static final int SOCIAL = 10;

  static final int WORK = 20;
  static final int RITE = 30;
  static final int AURORA = 40;
  static final int STORM = 50;

  public enum Role {
    RITE,
    RINGER,
    MOURNING,
    STORM,
    SHELTER,
    AURORA,
    TRADE,
    HELPER,
    TAG,
    CLOUDS,
    READING,
    LISTENING,
    FISHING,
    BAIT,
    TRAVEL,
    TILLING,
    BUILDING,
    FEEDING,
    CULLING,
    SHEARING,
    HERDING,
    HERBS,
    GATHERING,
    REPAIR,
    SERVICE,
    BACKLOG,
    SNOW
  }

  record Village(
      ServerLevel level,
      VillageWorldManager manager,
      VillageNode node,
      VillageState state,
      BoundingBox box,
      List<Villager> villagers,
      @Nullable BlockPos bell,
      long gameTime,
      long clock,
      long tickOfDay,
      long day,
      int weekday,
      Season season,
      WeatherState weather) {

    long at(long targetTickOfDay) {
      return gameTime + Math.max(0L, targetTickOfDay - tickOfDay);
    }

    boolean between(long from, long to) {
      return tickOfDay >= from && tickOfDay < to;
    }

    BlockPos center() {
      return bell != null ? bell : node.center();
    }

    List<Villager> adults(ResourceKey<VillagerProfession> profession) {
      List<Villager> result = new ArrayList<>();
      for (Villager villager : villagers) {
        if (!villager.isBaby() && villager.getVillagerData().profession().is(profession)) {
          result.add(villager);
        }
      }
      return result;
    }

    List<Villager> children() {
      return villagers.stream().filter(Villager::isBaby).toList();
    }

    long hash(long salt, UUID id) {
      return com.vortexso.guest_core.api.GuestHash.hash(
          level.getSeed(), node.id(), day, salt, id.getMostSignificantBits());
    }
  }

  private static final Map<ServerLevel, Map<UUID, Role>> ROLES = new WeakHashMap<>();

  private static final Map<Villager, Long> MEMBERS = new WeakHashMap<>();

  public record DebugView(
      List<Construction.View> projects, List<Vec3[]> targets, List<Vec3> away) {}

  private static volatile Map<ResourceKey<Level>, DebugView> debug = Map.of();

  private VillageLife() {}

  public static Map<UUID, Role> roles(ServerLevel level) {
    return ROLES.getOrDefault(level, Map.of());
  }

  public static @Nullable DebugView debug(ResourceKey<Level> dimension) {
    return debug.get(dimension);
  }

  public static void tick(ServerLevel level, VillageWorldManager manager) {
    if (level.getGameTime() % INTERVAL != 0) {
      return;
    }
    long clock = GuestTime.gameTime(level);
    Map<UUID, Role> roles = new HashMap<>();
    boolean debugging = GuestDebug.isEnabled(GuestSettlements.DEBUG_CHANNEL);
    List<Construction.View> projects = new ArrayList<>();
    List<Vec3[]> targets = new ArrayList<>();
    List<Vec3> away = new ArrayList<>();
    for (VillageNode node : manager.nodes()) {
      VillageState state = node.state();
      BoundingBox box = node.structureBox();
      if (!manager.isActive(node) || state == null || box == null) {
        continue;
      }
      List<Villager> villagers = members(level, node);

      villagers.removeIf(
          villager -> {
            if (villager.entityTags().contains(EMIGRANT_TAG)) {
              Errands.cancel(villager);
              return true;
            }
            return false;
          });
      for (Villager villager : villagers) {
        Errands.maintain(level, villager);
        Stuck.check(level, villager, node.bell() != null ? node.bell() : node.center());
      }
      Cartographers.tick(level, manager, node, villagers, clock);
      if (villagers.isEmpty()) {
        continue;
      }
      villagers.sort(Comparator.comparing(Villager::getUUID));
      BlockPos bell = node.bell();
      Village village =
          new Village(
              level,
              manager,
              node,
              state,
              box,
              List.copyOf(villagers),
              bell,
              level.getGameTime(),
              clock,
              GuestTime.tickOfDay(clock),
              GuestTime.day(clock),
              GuestTime.weekday(clock),
              GuestTime.season(clock),
              GuestWeather.get(level, bell != null ? bell : node.center(), clock));
      if (!Rites.tick(village)) {
        Work.tick(village);
        Company.tick(village);
      }
      Construction.tick(village);
      Work.heldTools(village);
      for (Villager villager : villagers) {
        Errands.Errand errand = Errands.current(villager);
        if (errand != null) {
          roles.put(villager.getUUID(), errand.role());
          Errands.Step step = errand.step();
          if (debugging && step != null && step.where() != null) {
            targets.add(
                new Vec3[] {
                  villager.position().add(0.0, 1.0, 0.0), step.where().currentPosition()
                });
          }
        }
      }
      if (debugging) {
        projects.addAll(Construction.views(level, node));
        for (int i = Cartographers.away(level, node); i > 0; i--) {
          away.add(Vec3.atCenterOf(village.center()).add(0.0, 2.5 + i * 0.3, 0.0));
        }
      }
    }
    ROLES.put(level, roles);
    if (debugging) {
      Map<ResourceKey<Level>, DebugView> views = new HashMap<>(debug);
      views.put(
          level.dimension(),
          new DebugView(List.copyOf(projects), List.copyOf(targets), List.copyOf(away)));
      debug = Map.copyOf(views);
    }
  }

  public static List<Villager> members(ServerLevel level, VillageNode node) {
    BoundingBox box = node.structureBox();
    List<Villager> villagers =
        box == null
            ? new ArrayList<>()
            : new ArrayList<>(VillagePopulationScanner.findVillagers(level, box));
    for (Villager villager : villagers) {
      MEMBERS.put(villager, node.id());
    }
    MEMBERS.forEach(
        (villager, village) -> {
          if (village == node.id()
              && villager.level() == level
              && villager.isAlive()
              && !villager.isRemoved()
              && !villagers.contains(villager)) {
            villagers.add(villager);
          }
        });
    return villagers;
  }

  public static VillagePopulation population(ServerLevel level, VillageNode node) {
    var registry =
        level
            .registryAccess()
            .lookupOrThrow(net.minecraft.core.registries.Registries.VILLAGER_PROFESSION);
    Map<net.minecraft.resources.Identifier, Integer> professions = new HashMap<>();
    int children = 0;
    for (Villager villager : members(level, node)) {
      if (villager.isBaby()) {
        children++;
      } else {
        var key = registry.getKey(villager.getVillagerData().profession().value());
        if (key != null) {
          professions.merge(key, 1, Integer::sum);
        }
      }
    }
    return Cartographers.withTravelers(level, node, new VillagePopulation(children, professions));
  }

  static net.minecraft.world.phys.AABB reach(BoundingBox box) {
    return VillagePopulationScanner.searchArea(box).deflate(2.0);
  }

  static boolean enabled(ModConfigSpec.BooleanValue value) {
    return SettlementsConfig.enabled(value);
  }

  public static boolean mourningDue(VillageState state, long day) {
    return Math.floorMod(day, GuestTime.DAYS_PER_WEEK) == Rites.NEW_MOON;
  }

  public static boolean onJoin(Entity entity, ServerLevel level, boolean fromDisk) {
    if (entity instanceof Villager villager) {
      Errands.onLoad(villager);
    }
    return true;
  }
}
