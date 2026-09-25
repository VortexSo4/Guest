package com.vortexso.guest_wilds.behavior;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import com.vortexso.guest_wilds.Membership;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.herd.Herds;
import com.vortexso.guest_wilds.lair.LairSpecies;
import com.vortexso.guest_wilds.lair.Lairs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

/** Daily cycle and weather reactions, expressed as destinations for {@link ShelterGoal}. */
public final class WildBehavior {
  /** Undead start looking for cover this long before sunrise (vanilla sunrise ~ tick 23500). */
  private static final long PRE_DAWN = 22_000L;

  private static final long DUSK = 12_500L;
  private static final int EMERGE_RANGE = 32;
  private static final int HIDE_TRIES = 10;
  private static final int HERD_STRAY = 24;

  private WildBehavior() {}

  public static void install(Mob mob) {
    if (!(mob instanceof PathfinderMob walker)) {
      return;
    }
    LairSpecies species = LairSpecies.of(mob);
    if (species == LairSpecies.ZOMBIE || species == LairSpecies.SKELETON) {
      // Above attack goals: before dawn getting under cover matters more than the chase.
      mob.goalSelector.addGoal(1, new ShelterGoal(walker, 1.2, WildBehavior::undead));
    } else if (species != null) {
      mob.goalSelector.addGoal(2, new ShelterGoal(walker, 1.1, WildBehavior::lairDweller));
    } else if (mob instanceof Animal
        && !(mob instanceof TamableAnimal tamable && tamable.isTame())) {
      mob.goalSelector.addGoal(3, new ShelterGoal(walker, 1.1, WildBehavior::animal));
    }
    // Sheep already graze in vanilla; cows and goats now crop grass the same way.
    if ((mob.getType() == EntityType.COW || mob.getType() == EntityType.GOAT)
        && WildsConfig.GRAZERS_EAT_GRASS.get()) {
      mob.goalSelector.addGoal(5, new EatBlockGoal(mob));
    }
  }

  private static @Nullable BlockPos undead(PathfinderMob mob) {
    if (!WildsConfig.UNDEAD_DAILY_CYCLE.get() || !(mob.level() instanceof ServerLevel level)) {
      return null;
    }
    if (!mob.is(EntityTypeTags.BURN_IN_DAYLIGHT)) {
      return lairDweller(mob);
    }
    long now = GuestTime.gameTime(level);
    long tick = GuestTime.tickOfDay(now);
    BlockPos pos = mob.blockPosition();
    if (tick >= PRE_DAWN || tick < DUSK) {
      if (!level.canSeeSky(pos) || fogShields(level, pos, now)) {
        return null;
      }
      return homeOrHide(level, mob);
    }
    return emerge(level, mob, now);
  }

  /** The lair if it can actually be walked to from here, else the nearest covered spot. */
  private static @Nullable BlockPos homeOrHide(ServerLevel level, Mob mob) {
    BlockPos home = home(level, mob);
    if (home != null && mob.getNavigation().createPath(home, 1) != null) {
      return home;
    }
    return hide(level, mob);
  }

  private static @Nullable BlockPos lairDweller(PathfinderMob mob) {
    if (!(mob.level() instanceof ServerLevel level)) {
      return null;
    }
    long now = GuestTime.gameTime(level);
    BlockPos pos = mob.blockPosition();
    if (WildsConfig.WEATHER_SHELTER.get() && level.canSeeSky(pos)) {
      if (GuestWeather.get(level, pos, now).isSevere()) {
        return homeOrHide(level, mob);
      }
    }
    return emerge(level, mob, now);
  }

  private static @Nullable BlockPos animal(PathfinderMob mob) {
    if (!WildsConfig.WEATHER_SHELTER.get() || !(mob.level() instanceof ServerLevel level)) {
      return null;
    }
    BlockPos pos = mob.blockPosition();
    if (level.canSeeSky(pos)) {
      WeatherState weather = GuestWeather.get(level, pos, GuestTime.gameTime(level));
      boolean wet = weather.type().isPrecipitation() && weather.intensity() >= 0.5F;
      if (wet || weather.isSevere()) {
        return hide(level, mob);
      }
    }
    return followHerd(level, mob);
  }

  /**
   * A herd that moved on while its members stood here: the members walk after it, so an observed
   * migration is the same event the aggregate recorded.
   */
  private static @Nullable BlockPos followHerd(ServerLevel level, Mob mob) {
    Membership membership = Membership.of(mob);
    if (membership == null || membership.kind() != Membership.Kind.HERD) {
      return null;
    }
    Herds.Herd herd = Herds.get(level).herd(membership.node());
    if (herd == null) {
      return null;
    }
    long dx = herd.center().getX() - mob.getBlockX();
    long dz = herd.center().getZ() - mob.getBlockZ();
    if (dx * dx + dz * dz <= HERD_STRAY * HERD_STRAY) {
      return null;
    }
    int x = herd.center().getX();
    int z = herd.center().getZ();
    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
      return null;
    }
    return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
  }

  /** Fog lets sun-sensitive undead keep hunting in daytime (see MobMixin for the burn side). */
  public static boolean fogShields(ServerLevel level, BlockPos pos, long now) {
    return WildsConfig.FOG_SHIELDS_UNDEAD.get()
        && GuestWeather.get(level, pos, now).type() == WeatherType.FOG;
  }

  /** At dusk lair members walk out to the surface; afterwards vanilla roaming takes over. */
  private static @Nullable BlockPos emerge(ServerLevel level, PathfinderMob mob, long now) {
    if (!GuestTime.isNight(now)
        || GuestTime.tickOfDay(now) >= PRE_DAWN
        || mob.getTarget() != null) {
      return null;
    }
    BlockPos pos = mob.blockPosition();
    if (level.canSeeSky(pos) || GuestWeather.get(level, pos, now).isSevere()) {
      return null;
    }
    Lairs.Node node = lair(level, mob);
    if (node == null || node.pos.distSqr(pos) > EMERGE_RANGE * EMERGE_RANGE) {
      return null;
    }
    return Lairs.surfacePoint(level, node, mob.getId());
  }

  private static @Nullable BlockPos home(ServerLevel level, Mob mob) {
    Lairs.Node node = lair(level, mob);
    return node != null
            && node.pos.distSqr(mob.blockPosition())
                <= (long) Lairs.SHELTER_RANGE * Lairs.SHELTER_RANGE
        ? node.pos
        : null;
  }

  private static Lairs.@Nullable Node lair(ServerLevel level, Mob mob) {
    Membership membership = Membership.of(mob);
    return membership != null && membership.kind() == Membership.Kind.LAIR
        ? Lairs.get(level).node(membership.node())
        : null;
  }

  /** Nearby standing room without sky exposure: canopy, overhang, ravine wall, structure. */
  private static @Nullable BlockPos hide(ServerLevel level, Mob mob) {
    RandomSource random = mob.getRandom();
    BlockPos origin = mob.blockPosition();
    for (int i = 0; i < HIDE_TRIES; i++) {
      BlockPos pos =
          origin.offset(random.nextInt(25) - 12, random.nextInt(9) - 4, random.nextInt(25) - 12);
      BlockPos below = pos.below();
      if (!level.canSeeSky(pos)
          && level.isEmptyBlock(pos)
          && level.isEmptyBlock(pos.above())
          && level.getFluidState(pos).isEmpty()
          && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
        return pos;
      }
    }
    return null;
  }
}
