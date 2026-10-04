package com.vortexso.guest_wilds.behavior;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.Membership;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.herd.HerdGoal;
import com.vortexso.guest_wilds.lair.LairSpecies;
import com.vortexso.guest_wilds.lair.Lairs;
import com.vortexso.guest_wilds.mixin.MobAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.EntityTypeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.EatBlockGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.LightLayer;
import org.jspecify.annotations.Nullable;

public final class WildBehavior {

  private static final long PRE_DAWN = 22_000L;

  private static final long DUSK = 12_500L;
  private static final int EMERGE_RANGE = 32;
  private static final int HIDE_TRIES = 10;
  private static final float RIVAL_RANGE = 12.0F;

  private WildBehavior() {}

  public static void install(Mob mob) {
    if (!(mob instanceof PathfinderMob walker)) {
      return;
    }
    LairSpecies species = LairSpecies.of(mob);
    Membership membership = Membership.of(mob);
    if (species == LairSpecies.ZOMBIE || species == LairSpecies.SKELETON) {

      ((MobAccessor) mob)
          .guestWilds$goals()
          .addGoal(1, new ShelterGoal(walker, 1.2, WildBehavior::undead));
    } else if (species != null) {
      ((MobAccessor) mob)
          .guestWilds$goals()
          .addGoal(2, new ShelterGoal(walker, 1.1, WildBehavior::lairDweller));
    } else if (membership != null && membership.kind() == Membership.Kind.HERD) {

      ((MobAccessor) mob).guestWilds$goals().addGoal(4, new HerdGoal(walker));
    } else if (mob instanceof Animal
        && !(mob instanceof TamableAnimal tamable && tamable.isTame())) {
      ((MobAccessor) mob)
          .guestWilds$goals()
          .addGoal(3, new ShelterGoal(walker, 1.1, WildBehavior::animal));
    }
    if (membership != null && membership.kind() == Membership.Kind.LAIR) {
      installRivalry(walker);
    }

    if ((mob.getType() == EntityType.COW || mob.getType() == EntityType.GOAT)
        && WildsConfig.GRAZERS_EAT_GRASS.get()) {
      ((MobAccessor) mob).guestWilds$goals().addGoal(5, new EatBlockGoal(mob));
    }
  }

  private static void installRivalry(PathfinderMob mob) {
    ((MobAccessor) mob)
        .guestWilds$targets()
        .addGoal(
            4,
            new NearestAttackableTargetGoal<>(
                mob,
                Monster.class,
                10,
                true,
                false,
                (target, level) -> Lairs.rivalry(level, mob, target) > 0));
    ((MobAccessor) mob)
        .guestWilds$goals()
        .addGoal(
            1,
            new AvoidEntityGoal<>(
                mob,
                Monster.class,
                other ->
                    mob.level() instanceof ServerLevel level
                        && Lairs.rivalry(level, mob, other) < 0,
                RIVAL_RANGE,
                1.1,
                1.35,
                EntitySelector.NO_SPECTATORS::test));
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
    BlockPos dark = leaveLight(level, mob);
    return dark != null ? dark : emerge(level, mob, now);
  }

  private static @Nullable BlockPos leaveLight(ServerLevel level, Mob mob) {
    if (mob.getTarget() != null
        || level.getBrightness(LightLayer.BLOCK, mob.blockPosition()) < Lairs.LIT) {
      return null;
    }
    RandomSource random = mob.getRandom();
    BlockPos origin = mob.blockPosition();
    for (int i = 0; i < HIDE_TRIES; i++) {
      BlockPos pos =
          origin.offset(random.nextInt(33) - 16, random.nextInt(9) - 4, random.nextInt(33) - 16);
      if (standable(level, pos) && level.getBrightness(LightLayer.BLOCK, pos) < Lairs.LIT / 2) {
        return pos;
      }
    }
    return null;
  }

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

    if (mob.is(EntityTypeTags.UNDEAD)
        && GuestTime.isNight(now)
        && WildsConfig.UNDEAD_DAILY_CYCLE.get()) {
      BlockPos dark = leaveLight(level, mob);
      if (dark != null) {
        return dark;
      }
    }
    return emerge(level, mob, now);
  }

  private static @Nullable BlockPos animal(PathfinderMob mob) {
    if (!WildsConfig.WEATHER_SHELTER.get() || !(mob.level() instanceof ServerLevel level)) {
      return null;
    }
    BlockPos pos = mob.blockPosition();
    if (level.canSeeSky(pos)
        && seeksShelter(GuestWeather.get(level, pos, GuestTime.gameTime(level)))) {
      return hide(level, mob);
    }
    return null;
  }

  public static boolean seeksShelter(WeatherState weather) {
    return (weather.type().isPrecipitation() && weather.intensity() >= 0.5F) || weather.isSevere();
  }

  public static boolean fogShields(ServerLevel level, BlockPos pos, long now) {
    return WildsConfig.FOG_SHIELDS_UNDEAD.get()
        && GuestWeather.get(level, pos, now).type() == WeatherType.FOG;
  }

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

  public static @Nullable BlockPos hide(ServerLevel level, Mob mob) {
    RandomSource random = mob.getRandom();
    BlockPos origin = mob.blockPosition();
    for (int i = 0; i < HIDE_TRIES; i++) {
      BlockPos pos =
          origin.offset(random.nextInt(25) - 12, random.nextInt(9) - 4, random.nextInt(25) - 12);
      if (standable(level, pos) && !level.canSeeSky(pos)) {
        return pos;
      }
    }
    return null;
  }

  private static boolean standable(ServerLevel level, BlockPos pos) {
    BlockPos below = pos.below();
    return GuestWilds.loaded(level, pos)
        && level.isEmptyBlock(pos)
        && level.isEmptyBlock(pos.above())
        && level.getFluidState(pos).isEmpty()
        && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
  }
}
