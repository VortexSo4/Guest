package com.vortexso.guest_wilds;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.event.RouteTrafficEvent;
import com.vortexso.guest_wilds.behavior.Variants;
import com.vortexso.guest_wilds.behavior.WildBehavior;
import com.vortexso.guest_wilds.fish.Shoals;
import com.vortexso.guest_wilds.flora.Regrowth;
import com.vortexso.guest_wilds.flora.Spread;
import com.vortexso.guest_wilds.herd.Herds;
import com.vortexso.guest_wilds.lair.LairSpecies;
import com.vortexso.guest_wilds.lair.Lairs;
import com.vortexso.guest_wilds.path.PathWear;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;

public final class WildsEvents {
  private static final int SAMPLE_TICKS = 4;
  private static final int SWEEP_TICKS = 20;
  private static final int OBSERVE_TICKS = 100;
  private static final int COAT_TICKS = 200;
  private static final double CARAVAN_WEAR = 3.0;

  private static final int HEIGHTS_ABOVE_SEA = 48;

  private static final Set<String> PLACES_OF_POWER =
      Set.of(
          "stronghold",
          "ancient_city",
          "woodland_mansion",
          "monument",
          "desert_pyramid",
          "jungle_pyramid",
          "trail_ruins");

  private static final Map<ServerLevel, LongLinkedOpenHashSet> LOADED = new WeakHashMap<>();

  private WildsEvents() {}

  private static boolean isWild(Level level) {
    return level instanceof ServerLevel && level.dimension() == Level.OVERWORLD;
  }

  public enum Spawn {
    KEEP,
    SKIP_FINALIZE,
    CANCEL
  }

  static boolean onJoin(Entity entity, ServerLevel level, boolean fromDisk) {
    if (!isWild(level)) {
      return true;
    }
    if (entity instanceof Mob mob) {
      Membership membership = Membership.of(mob);
      if (membership != null) {
        boolean keep =
            switch (membership.kind()) {
              case LAIR ->
                  !WildsConfig.LAIRS.get()
                      || Lairs.get(level).onMemberJoin(level, membership, fromDisk);
              case HERD ->
                  !WildsConfig.HERDS.get()
                      || Herds.get(level).onMemberJoin(level, mob, membership, fromDisk);
              case SHOAL -> {
                Shoals.get(level).onMemberJoin(membership);
                yield true;
              }
            };
        if (!keep) {
          return false;
        }
        if (membership.kind() != Membership.Kind.SHOAL) {

          mob.setPersistenceRequired();
        }
      } else if (WildsConfig.HERDS.get()
          && Herds.isWildCandidate(mob)
          && !Herds.get(level).assign(level, mob, fromDisk)) {

        return false;
      }
      WildBehavior.install(mob);
    }
    if (!fromDisk) {
      Variants.onNewAnimal(level, entity);
    }
    return true;
  }

  static void onLeave(Entity entity, ServerLevel level) {
    if (!isWild(level)) {
      return;
    }
    Membership membership = Membership.of(entity);
    Entity.RemovalReason reason = entity.getRemovalReason();
    if (membership == null || reason == null) {
      return;
    }
    boolean killed = reason == Entity.RemovalReason.KILLED;
    switch (membership.kind()) {
      case LAIR -> {
        if (reason.shouldDestroy()) {
          Lairs.get(level).onMemberLeave(level, membership, killed);
        }
      }
      case HERD -> {
        if (reason.shouldDestroy()) {
          Herds.get(level).onMemberLeave(level, entity, membership, killed);
        }
      }
      case SHOAL -> {
        if (reason != Entity.RemovalReason.CHANGED_DIMENSION) {
          Shoals.get(level).onMemberLeave(level, membership, killed);
        }
      }
    }
  }

  public static boolean allowDamage(LivingEntity entity, DamageSource source) {
    Membership victim = Membership.of(entity);
    Entity attacker = source.getEntity();
    if (victim == null
        || attacker == null
        || attacker == entity
        || victim.kind() != Membership.Kind.LAIR) {
      return true;
    }
    Membership other = Membership.of(attacker);
    return other == null
        || other.kind() != Membership.Kind.LAIR
        || other.node() != victim.node()
        || !other.species().equals(victim.species());
  }

  public static void onConversion(LivingEntity from, LivingEntity to) {
    Membership membership = Membership.of(from);
    if (membership != null) {
      GuestWilds.MEMBERSHIP.set(to, membership);
    }
  }

  public static Spawn onFinalizeSpawn(Mob mob, ServerLevel level, EntitySpawnReason reason) {
    if (level.dimension() != Level.OVERWORLD) {
      return Spawn.KEEP;
    }
    Spawn keep = Spawn.KEEP;
    if (mob.getType() == EntityType.SPIDER
        && WildsConfig.SUPPRESS_RANDOM_JOCKEYS.get()
        && (reason == EntitySpawnReason.NATURAL
            || reason == EntitySpawnReason.CHUNK_GENERATION
            || reason == EntitySpawnReason.EVENT)) {

      keep = Spawn.SKIP_FINALIZE;
    }
    if (reason != EntitySpawnReason.NATURAL) {
      return keep;
    }
    BlockPos pos = mob.blockPosition();
    if (mob.getType() == EntityType.ENDERMAN) {
      if (WildsConfig.ENDERMEN_PLACES_OF_POWER.get()
          && !nearPlaceOfPower(level, pos)
          && level.getRandom().nextDouble() >= WildsConfig.ENDERMEN_ELSEWHERE_CHANCE.get()) {
        return Spawn.CANCEL;
      }
      return keep;
    }
    if (LairSpecies.of(mob) == null) {
      return keep;
    }
    if (WildsConfig.LAIRS.get()
        && onSurface(level, pos)
        && level.getRandom().nextDouble() >= WildsConfig.SURFACE_SPAWN_SHARE.get()) {

      return Spawn.CANCEL;
    }
    EntityType<? extends Mob> form =
        Variants.naturalForm(mob, level, pos, GuestTime.gameTime(level));
    if (form != null) {
      form.spawn(level, pos, EntitySpawnReason.NATURAL);
      return Spawn.CANCEL;
    }
    return keep;
  }

  static boolean onSurface(ServerLevel level, BlockPos pos) {
    return pos.getY()
        >= level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) - 1;
  }

  static boolean nearPlaceOfPower(ServerLevel level, BlockPos pos) {
    if (pos.getY() >= level.getSeaLevel() + HEIGHTS_ABOVE_SEA) {
      return true;
    }
    var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    for (int dx = -2; dx <= 2; dx++) {
      for (int dz = -2; dz <= 2; dz++) {
        int chunkX = (pos.getX() >> 4) + dx;
        int chunkZ = (pos.getZ() >> 4) + dz;

        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
          continue;
        }
        BlockPos probe = new BlockPos((chunkX << 4) + 8, pos.getY(), (chunkZ << 4) + 8);
        for (Structure structure : level.structureManager().getAllStructuresAt(probe).keySet()) {
          Identifier id = registry.getKey(structure);
          if (id != null
              && (PLACES_OF_POWER.contains(id.getPath())
                  || id.getPath().startsWith("ruined_portal"))) {
            return true;
          }
        }
      }
    }
    return false;
  }

  static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean newChunk) {
    if (level.dimension() != Level.OVERWORLD) {
      return;
    }
    LOADED
        .computeIfAbsent(level, ignored -> new LongLinkedOpenHashSet())
        .add(chunk.getPos().pack());
  }

  private static void afterChunkLoads(ServerLevel level) {
    LongLinkedOpenHashSet loaded = LOADED.remove(level);
    if (loaded == null) {
      return;
    }
    for (long key : loaded) {
      ChunkPos pos = ChunkPos.unpack(key);
      if (WildsConfig.PATHS.get()) {
        PathWear.get(level).onChunkLoad(level, pos);
      }
      if (WildsConfig.LAIRS.get()) {
        Lairs.get(level).onChunkLoad(level, pos);
      }
      if (WildsConfig.FOREST_REGROWTH.get()) {
        Regrowth.get(level).onChunkLoad(level, pos);
      }
      if (WildsConfig.FOREST_SPREAD.get()) {
        Spread.onChunkLoad(pos);
      }
    }
  }

  static boolean onBreak(ServerLevel level, Player player, BlockPos pos, BlockState state) {
    if (level.dimension() == Level.OVERWORLD) {
      Regrowth.get(level).onFelled(level, pos, state);
    }
    return true;
  }

  static void onLevelTick(ServerLevel level) {
    if (!isWild(level)) {
      return;
    }
    afterChunkLoads(level);
    long tick = level.getGameTime();
    boolean sample = WildsConfig.PATHS.get() && tick % SAMPLE_TICKS == 0;
    boolean coats = tick % COAT_TICKS == 0;
    if (sample || coats) {
      for (Entity entity : level.getAllEntities()) {
        if (sample && entity instanceof LivingEntity walker) {
          PathWear.sample(level, walker);
        }
        if (coats) {
          Variants.updateCoat(level, entity);
        }
      }
    }
    if (tick % SWEEP_TICKS == 0) {
      if (WildsConfig.PATHS.get()) {
        PathWear.get(level).sweep(level);
      }
      if (WildsConfig.FOREST_REGROWTH.get()) {
        Regrowth.get(level).sweep(level);
      }
      if (WildsConfig.FOREST_SPREAD.get()) {
        Spread.sweep(level);
      }
    }
    if (tick % OBSERVE_TICKS == 0) {
      for (ServerPlayer player : level.players()) {
        if (player.isSpectator()) {
          continue;
        }
        if (WildsConfig.LAIRS.get()) {
          Lairs.get(level).materializeNear(level, player);
        }
        if (WildsConfig.HERDS.get()) {
          Herds.get(level).materializeNear(level, player);
        }
        if (WildsConfig.FISH_SHOALS.get()) {
          Shoals.get(level).materializeNear(level, player);
        }
      }
    }
  }

  public static boolean onFished(FishingHook hook, Player player) {
    if (WildsConfig.FISH_SHOALS.get()
        && isWild(hook.level())
        && hook.level() instanceof ServerLevel level) {
      Shoals.get(level).onFished(level, hook, player);
      return true;
    }
    return false;
  }

  static void onRouteTraffic(RouteTrafficEvent event) {
    if (!WildsConfig.PATHS.get() || event.level().dimension() != Level.OVERWORLD) {
      return;
    }
    double weight = event.traveler().getPath().contains("caravan") ? CARAVAN_WEAR : 1.0;
    ServerLevel level = event.level();
    PathWear.get(level)
        .addRoute(
            level,
            event.from(),
            event.to(),
            event.waypoints(),
            event.width(),
            event.trips() * weight,
            GuestTime.gameTime(level),
            true);
  }
}
