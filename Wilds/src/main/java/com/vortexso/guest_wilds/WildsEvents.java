package com.vortexso.guest_wilds;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.event.RouteTrafficEvent;
import com.vortexso.guest_wilds.behavior.Variants;
import com.vortexso.guest_wilds.behavior.WildBehavior;
import com.vortexso.guest_wilds.fish.Shoals;
import com.vortexso.guest_wilds.herd.Herds;
import com.vortexso.guest_wilds.lair.LairSpecies;
import com.vortexso.guest_wilds.lair.Lairs;
import com.vortexso.guest_wilds.path.PathWear;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.LivingConversionEvent;
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Wires vanilla/NeoForge events into the Wilds systems. Overworld only. */
@EventBusSubscriber(modid = GuestWilds.MODID)
public final class WildsEvents {
  private static final int SAMPLE_TICKS = 4;
  private static final int SWEEP_TICKS = 20;
  private static final int OBSERVE_TICKS = 100;
  private static final int COAT_TICKS = 200;
  private static final double CARAVAN_WEAR = 3.0;

  /** "Places of power" that draw endermen; ruined portals are matched by prefix. */
  private static final Set<String> PLACES_OF_POWER =
      Set.of(
          "stronghold",
          "ancient_city",
          "woodland_mansion",
          "monument",
          "desert_pyramid",
          "jungle_pyramid",
          "trail_ruins");

  private WildsEvents() {}

  private static boolean isWild(Level level) {
    return level instanceof ServerLevel && level.dimension() == Level.OVERWORLD;
  }

  @SubscribeEvent
  public static void onJoin(EntityJoinLevelEvent event) {
    if (!isWild(event.getLevel())) {
      return;
    }
    ServerLevel level = (ServerLevel) event.getLevel();
    Entity entity = event.getEntity();
    if (entity instanceof Mob mob) {
      Membership membership = Membership.of(mob);
      if (membership != null) {
        boolean fromDisk = event.loadedFromDisk();
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
          event.setCanceled(true);
          return;
        }
        if (membership.kind() != Membership.Kind.SHOAL) {
          // Node members must not vanish because the player walked away.
          mob.setPersistenceRequired();
        }
      } else if (WildsConfig.HERDS.get() && Herds.isWildCandidate(mob)) {
        Herds.get(level).assign(level, mob);
      }
      WildBehavior.install(mob);
    }
    if (!event.loadedFromDisk()) {
      Variants.onNewAnimal(level, entity);
    }
  }

  @SubscribeEvent
  public static void onLeave(EntityLeaveLevelEvent event) {
    if (!isWild(event.getLevel())) {
      return;
    }
    Entity entity = event.getEntity();
    Membership membership = Membership.of(entity);
    Entity.RemovalReason reason = entity.getRemovalReason();
    if (membership == null || reason == null) {
      return;
    }
    ServerLevel level = (ServerLevel) event.getLevel();
    boolean killed = reason == Entity.RemovalReason.KILLED;
    switch (membership.kind()) {
      case LAIR -> {
        if (reason.shouldDestroy()) {
          Lairs.get(level).onMemberLeave(level, membership, killed);
        }
      }
      case HERD -> {
        if (reason.shouldDestroy()) {
          Herds.get(level).onMemberLeave(level, membership, killed);
        }
      }
      case SHOAL -> {
        if (reason != Entity.RemovalReason.CHANGED_DIMENSION) {
          Shoals.get(level).onMemberLeave(level, membership, killed);
        }
      }
    }
  }

  /** Husk from zombie, stray from skeleton, drowned...: same member, new form. */
  @SubscribeEvent
  public static void onConversion(LivingConversionEvent.Post event) {
    Membership membership = Membership.of(event.getEntity());
    if (membership != null) {
      event.getOutcome().setData(GuestWilds.MEMBERSHIP, membership);
    }
  }

  @SubscribeEvent
  public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
    Mob mob = event.getEntity();
    ServerLevel level = event.getLevel().getLevel();
    if (level.dimension() != Level.OVERWORLD) {
      return;
    }
    EntitySpawnReason reason = event.getSpawnType();
    if (mob.getType() == EntityType.SPIDER
        && WildsConfig.SUPPRESS_RANDOM_JOCKEYS.get()
        && (reason == EntitySpawnReason.NATURAL
            || reason == EntitySpawnReason.CHUNK_GENERATION
            || reason == EntitySpawnReason.EVENT)) {
      // Skips Spider#finalizeSpawn, whose 1% rider roll is the only random jockey source; riders
      // come from established lairs instead. Costs only the hard-mode random spider effect.
      event.setCanceled(true);
    }
    if (reason != EntitySpawnReason.NATURAL) {
      return;
    }
    BlockPos pos = BlockPos.containing(event.getX(), event.getY(), event.getZ());
    if (mob.getType() == EntityType.ENDERMAN) {
      if (WildsConfig.ENDERMEN_PLACES_OF_POWER.get()
          && !nearPlaceOfPower(level, pos)
          && level.getRandom().nextDouble() >= WildsConfig.ENDERMEN_ELSEWHERE_CHANCE.get()) {
        event.setSpawnCancelled(true);
      }
      return;
    }
    if (LairSpecies.of(mob) == null) {
      return;
    }
    if (WildsConfig.LAIRS.get()
        && WildsConfig.SUPPRESS_SURFACE_SPAWNS.get()
        && level.canSeeSky(pos)) {
      // Surface night population comes from lairs; spawners/structures use other reasons.
      event.setSpawnCancelled(true);
      return;
    }
    EntityType<? extends Mob> form =
        Variants.naturalForm(mob, level, pos, GuestTime.gameTime(level));
    if (form != null) {
      event.setSpawnCancelled(true);
      form.spawn(level, pos, EntitySpawnReason.NATURAL);
    }
  }

  static boolean nearPlaceOfPower(ServerLevel level, BlockPos pos) {
    var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    for (int dx = -2; dx <= 2; dx++) {
      for (int dz = -2; dz <= 2; dz++) {
        int chunkX = (pos.getX() >> 4) + dx;
        int chunkZ = (pos.getZ() >> 4) + dz;
        // Never force-load chunks from a spawn check.
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

  @SubscribeEvent
  public static void onChunkLoad(ChunkEvent.Load event) {
    if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
      return;
    }
    ChunkPos pos = event.getChunk().getPos();
    level
        .getServer()
        .execute(
            () -> {
              if (WildsConfig.PATHS.get()) {
                PathWear.get(level).onChunkLoad(level, pos);
              }
              if (WildsConfig.LAIRS.get()) {
                Lairs.get(level).onChunkLoad(level, pos);
              }
            });
  }

  @SubscribeEvent
  public static void onLevelTick(LevelTickEvent.Post event) {
    if (!isWild(event.getLevel())) {
      return;
    }
    ServerLevel level = (ServerLevel) event.getLevel();
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
    if (WildsConfig.PATHS.get() && tick % SWEEP_TICKS == 0) {
      PathWear.get(level).sweep(level);
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

  @SubscribeEvent
  public static void onFished(ItemFishedEvent event) {
    if (WildsConfig.FISH_SHOALS.get()
        && isWild(event.getHookEntity().level())
        && event.getHookEntity().level() instanceof ServerLevel level) {
      Shoals.get(level).onFished(level, event);
    }
  }

  /** Unobserved caravans and travelling villagers wear their routes like walkers do. */
  @SubscribeEvent
  public static void onRouteTraffic(RouteTrafficEvent event) {
    if (!WildsConfig.PATHS.get() || event.level().dimension() != Level.OVERWORLD) {
      return;
    }
    double weight = event.traveler().getPath().contains("caravan") ? CARAVAN_WEAR : 1.0;
    ServerLevel level = event.level();
    PathWear.get(level)
        .addRoute(
            level, event.from(), event.to(), event.trips() * weight, GuestTime.gameTime(level));
  }
}
