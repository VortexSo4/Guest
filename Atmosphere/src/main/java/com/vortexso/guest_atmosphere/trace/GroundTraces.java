package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.block.Coating.Coat;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Kind;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Trace;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.WeatherType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

final class GroundTraces {
  private GroundTraces() {}

  static void update(Column c) {
    sand(c);
    mud(c);
    if (AtmosphereConfig.SILT.get()) {
      silt(c);
    }
    dry(c);
    ash(c);
    burntLeaves(c);
    if (AtmosphereConfig.LEAF_LITTER.get()) {
      leafLitter(c);
    }
  }

  private static void sand(Column c) {
    BlockState surface = c.state(c.top);
    boolean pile = isPile(surface);
    int shelter = shelter(c);
    boolean piles = AtmosphereConfig.SAND_PILES.get();
    boolean coats = AtmosphereConfig.COATED_BLOCKS.get();
    if (c.live) {
      WeatherType type = c.type();
      if (type == WeatherType.SANDSTORM) {
        if (piles
            && shelter > 0
            && (surface.isAir() || pile)
            && !c.state(c.ground).is(BlockTags.SAND)
            && c.chance(17, c.intensity() * c.params.sandChance())) {
          addPile(c, surface, shelter >= 2 ? 4 : 2);
        }
        if (coats && c.chance(18, c.intensity() * c.params.coatChance())) {
          Surfaces.coatSurface(c, Coat.SANDY);
          Surfaces.coatWindward(c, Coat.SANDY, 2);
        }
      } else {
        double wash =
            type.isRain()
                ? 0.5
                : type == WeatherType.WIND
                    ? 0.3
                    : c.perVisit(1.0 / AtmosphereConfig.SAND_LIFETIME_DAYS.get());
        if (pile && c.chance(19, wash)) {
          lowerLayer(c, surface);
        }
        if (coats && c.chance(20, wash)) {
          Surfaces.uncoatAll(c, Coat.SANDY);
        }
      }
      return;
    }
    History h = c.history();
    boolean lying =
        h.lastSandstorm != History.NEVER
            && h.lastSandstorm > Math.max(h.lastRain, h.lastWind)
            && c.chance(
                21,
                Math.exp(
                    -(c.now - h.lastSandstorm)
                        / (AtmosphereConfig.SAND_LIFETIME_DAYS.get() * GuestTime.TICKS_PER_DAY)));
    if (!lying) {
      if (pile) {
        c.set(c.top, Blocks.AIR.defaultBlockState());
      }
      Surfaces.uncoatAll(c, Coat.SANDY);
    } else if (c.chance(17, History.coverage(h.sandHits))) {
      if (piles
          && shelter > 0
          && (surface.isAir() || pile)
          && !c.state(c.ground).is(BlockTags.SAND)) {
        int depth = shelter >= 2 ? 4 : 2;
        for (int i = pile ? surface.getValue(SnowLayerBlock.LAYERS) : 0; i < depth; i++) {
          addPile(c, c.state(c.top), depth);
        }
      }
      if (coats) {
        Surfaces.coatSurface(c, Coat.SANDY);
        Surfaces.coatWindward(c, Coat.SANDY, 2);
      }
    }
  }

  private static boolean isPile(BlockState state) {
    return state.is(AtmosphereBlocks.SAND_PILE.get())
        || state.is(AtmosphereBlocks.RED_SAND_PILE.get());
  }

  private static int shelter(Column c) {
    int sides = 0;
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = c.top.relative(direction);
      if (c.loaded(side) && c.level.getBlockState(side).blocksMotion()) {
        sides++;
      }
    }
    return sides;
  }

  private static void addPile(Column c, BlockState surface, int cap) {
    if (isPile(surface)) {
      int layers = surface.getValue(SnowLayerBlock.LAYERS);
      if (layers < cap) {
        c.set(c.top, surface.setValue(SnowLayerBlock.LAYERS, layers + 1));
      }
      return;
    }
    Block pile =
        AtmosphereWeather.isRedSand(c.level, c.top)
            ? AtmosphereBlocks.RED_SAND_PILE.get()
            : AtmosphereBlocks.SAND_PILE.get();
    BlockState state = pile.defaultBlockState();
    if (state.canSurvive(c.level, c.top)) {
      c.set(c.top, state);
    }
  }

  static void lowerLayer(Column c, BlockState surface) {
    int layers = surface.getValue(SnowLayerBlock.LAYERS);
    c.set(
        c.top,
        layers <= 1
            ? Blocks.AIR.defaultBlockState()
            : surface.setValue(SnowLayerBlock.LAYERS, layers - 1));
  }

  private static void mud(Column c) {
    if (!c.state(c.top).isAir()) {
      return;
    }
    BlockState ground = c.state(c.ground);
    boolean bare =
        ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.DIRT_PATH);
    if (!bare && !(ground.is(Blocks.GRASS_BLOCK) && (nextToWater(c) || dripLine(c)))) {
      return;
    }
    boolean forms =
        c.live
            ? realRain(c.type())
                && c.temperature() >= c.params.snowTemperature()
                && c.chance(22, c.params.mudChance() * c.intensity())
            : c.history().rainedWithin(c.params.mudDryingTicks())
                && c.chance(22, History.coverage(c.history().mudHits));
    if (forms) {
      c.change(c.ground, Blocks.MUD.defaultBlockState(), Kind.MUD);
    }
  }

  static boolean realRain(WeatherType type) {
    return type.isRain() && type != WeatherType.DRIZZLE;
  }

  private static boolean nextToWater(Column c) {
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = c.ground.relative(direction);
      if (c.loaded(side)) {
        BlockState state = c.level.getBlockState(side);
        if (state.is(Blocks.WATER) || state.is(Blocks.DIRT_PATH)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean dripLine(Column c) {
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      if (c.topY(direction) >= c.top.getY() + 2) {
        return true;
      }
    }
    return false;
  }

  private static void silt(Column c) {
    BlockState ground = c.state(c.ground);
    if (!(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT))
        || c.ground.getY() > c.level.getSeaLevel() + 2
        || c.traces.get(c.ground.asLong()) != null) {
      return;
    }
    WeatherType type = c.type();
    boolean floods =
        c.live
            ? (type == WeatherType.DOWNPOUR || type == WeatherType.THUNDERSTORM)
                && c.intensity() >= 0.5F
                && c.temperature() >= c.params.snowTemperature()
                && c.chance(23, AtmosphereConfig.SILT_CHANCE.get() * c.intensity())
            : c.history().heavyRainWithin(siltTicks())
                && c.chance(23, History.coverage(c.history().floodHits));
    if (floods && nearWater(c)) {
      c.change(c.ground, AtmosphereBlocks.SILT.get().defaultBlockState(), Kind.SILT);
    }
  }

  private static long siltTicks() {
    return (long) (AtmosphereConfig.SILT_GRASS_DAYS.get() * GuestTime.TICKS_PER_DAY);
  }

  private static boolean nearWater(Column c) {
    for (int dx = -2; dx <= 2; dx++) {
      for (int dz = -2; dz <= 2; dz++) {
        BlockPos pos = c.ground.offset(dx, 0, dz);
        if ((dx != 0 || dz != 0) && c.loaded(pos) && c.level.getBlockState(pos).is(Blocks.WATER)) {
          return true;
        }
      }
    }
    return false;
  }

  private static void dry(Column c) {
    if (!c.state(c.top).isAir()) {
      return;
    }
    BlockState ground = c.state(c.ground);
    BlockState dried;
    if (ground.is(Blocks.GRASS_BLOCK)) {
      dried = Blocks.COARSE_DIRT.defaultBlockState();
    } else if (ground.is(Blocks.DIRT)
        || ground.is(Blocks.COARSE_DIRT)
        || (ground.is(Blocks.MUD) && c.traces.get(c.ground.asLong()) == null)) {
      dried = AtmosphereBlocks.CRACKED_MUD.get().defaultBlockState();
    } else {
      return;
    }
    boolean heat =
        c.live
            ? c.type() == WeatherType.HEAT && c.chance(24, c.params.dryChance() * c.intensity())
            : c.history().lastHeat > c.history().lastRain
                && c.chance(24, History.coverage(c.history().dryHits));
    if (heat) {
      c.change(c.ground, dried, Kind.DRY);
    }
  }

  private static void ash(Column c) {
    BlockState surface = c.state(c.top);
    if (!surface.is(AtmosphereBlocks.ASH.get())) {
      return;
    }
    if (c.live) {
      double wash = c.type().isRain() ? 0.5 : c.sample.state().wind() >= 0.5F ? 0.15 : 0.0;
      if (c.chance(25, wash)) {
        lowerLayer(c, surface);
      }
    } else if (c.history().lastRain > c.history().growthFrom) {
      c.set(c.top, Blocks.AIR.defaultBlockState());
    }
  }

  private static void burntLeaves(Column c) {
    if (!c.state(c.ground).is(AtmosphereBlocks.BURNT_LEAVES.get())) {
      return;
    }
    History h = c.live ? null : c.history();
    boolean knocked =
        c.live
            ? (c.type().isRain() || c.sample.state().wind() >= 0.4F) && c.chance(26, 0.3)
            : h.lastRain > h.growthFrom
                || h.lastWind > h.growthFrom
                || c.now - h.growthFrom >= GuestTime.TICKS_PER_DAY;
    if (knocked) {
      FireTraces.knockDownLeaves(c);
    }
  }

  private static void leafLitter(Column c) {
    if (!c.climate.forested() || !deciduous(c.state(c.ground))) {
      return;
    }
    BlockPos floor = c.level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.ground);
    if (floor.getY() >= c.ground.getY()) {
      return;
    }
    BlockState state = c.state(floor);
    int amount = state.is(Blocks.LEAF_LITTER) ? state.getValue(LeafLitterBlock.AMOUNT) : 0;
    if ((amount == 0 && !state.isAir())
        || !c.state(floor.below()).isFaceSturdy(c.level, floor.below(), Direction.UP)) {
      return;
    }
    Season season = GuestTime.season(c.now);
    int target = amount;
    if (c.live) {
      WeatherType type = c.type();
      double windy = type == WeatherType.LEAF_FALL || type == WeatherType.WIND ? 3.0 : 1.0;
      if (season == Season.AUTUMN && c.chance(27, c.perVisit(c.params.litterPerDay() * windy))) {
        target = Math.min(4, amount + 1);
      } else if (season == Season.WINTER && amount > 0 && c.chance(28, c.perVisit(0.5 * windy))) {
        target = amount - 1;
      }
    } else {
      History h = c.history();
      if (season == Season.WINTER || h.winterSeen) {
        target = 0;
      } else if (season == Season.AUTUMN) {
        double expected = h.litterDays * c.params.litterPerDay() * (0.5 + c.fixed(floor, 29));
        target = Math.max(amount, (int) Math.min(4, Math.round(expected)));
      }
    }
    if (target == amount) {
      return;
    }
    if (target <= 0) {
      c.set(floor, Blocks.AIR.defaultBlockState());
    } else {
      Direction facing = Direction.from2DDataValue((int) (c.fixed(floor, 30) * 4.0));
      c.set(
          floor,
          Blocks.LEAF_LITTER
              .defaultBlockState()
              .setValue(LeafLitterBlock.FACING, facing)
              .setValue(LeafLitterBlock.AMOUNT, target));
    }
  }

  private static boolean deciduous(BlockState state) {
    return state.is(BlockTags.LEAVES)
        && !state.is(Blocks.SPRUCE_LEAVES)
        && !state.is(Blocks.AZALEA_LEAVES)
        && !state.is(Blocks.FLOWERING_AZALEA_LEAVES);
  }

  static void review(Column c, BlockPos pos, Trace trace) {
    BlockState state = c.level.getBlockState(pos);
    if (!intact(state, trace.kind())) {
      c.forget(pos);
      return;
    }
    long age = c.now - trace.placedAt();
    switch (trace.kind()) {
      case SNOW -> c.forget(pos);
      case MUD -> {
        boolean wet =
            (c.live && realRain(c.type())) || c.history().rainedWithin(c.params.mudDryingTicks());
        boolean baking = c.live && c.type() == WeatherType.HEAT;
        if ((!wet || baking) && (!c.live || c.chance(31, 0.5))) {
          c.revert(pos, trace);
        }
      }
      case ICE -> {
        if (c.temperature() >= c.params.snowTemperature() && (!c.live || c.chance(32, 0.5))) {
          c.revert(pos, trace);
        }
      }
      case SAND -> {
        long lifetime = days(AtmosphereConfig.SAND_LIFETIME_DAYS.get());
        boolean washed = c.live ? c.type().isRain() : c.history().lastRain > trace.placedAt();
        if ((washed || age > lifetime) && (!c.live || c.chance(33, 0.5))) {
          c.revert(pos, trace);
        }
      }
      case DRY -> {
        long lifetime = days(AtmosphereConfig.DRY_LIFETIME_DAYS.get());
        boolean revert;
        if (c.live) {
          revert =
              (c.type().isRain() && c.chance(34, 0.5))
                  || (c.type() != WeatherType.HEAT && age > lifetime && c.chance(34, 0.25));
        } else {
          History h = c.history();
          long dried = Math.max(trace.placedAt(), h.lastHeat);
          revert = h.lastRain > dried || c.now - dried > lifetime;
        }
        if (revert) {
          c.revert(pos, trace);
        }
      }
      case SCORCH -> {
        if (age > days(AtmosphereConfig.SCORCH_LIFETIME_DAYS.get())
            && (!c.live || c.chance(35, 0.5))) {
          c.revert(pos, trace);
        }
      }
      case SILT -> {
        if (!c.history().heavyRainWithin(siltTicks()) && (!c.live || c.chance(36, 0.5))) {
          c.forget(pos);

          c.set(
              pos,
              c.level.canSeeSky(pos.above())
                  ? Blocks.GRASS_BLOCK.defaultBlockState()
                  : trace.original());
        }
      }
      case BURN -> {
        if (age > days(AtmosphereConfig.REGROWTH_DAYS.get())) {
          c.forget(pos);
          FireTraces.regrow(c, pos, trace);
        }
      }
    }
  }

  static boolean intact(BlockState state, Kind kind) {
    return switch (kind) {
      case SNOW -> state.is(Blocks.SNOW);
      case MUD -> state.is(Blocks.MUD);
      case ICE -> state.is(Blocks.ICE);
      case SAND -> state.is(Blocks.SAND) || state.is(Blocks.RED_SAND);
      case DRY -> state.is(Blocks.COARSE_DIRT) || state.is(AtmosphereBlocks.CRACKED_MUD.get());
      case SCORCH -> state.is(Blocks.COARSE_DIRT);
      case SILT -> state.is(AtmosphereBlocks.SILT.get());
      case BURN -> state.is(AtmosphereBlocks.CHARRED_LOG.get());
    };
  }

  private static long days(double days) {
    return (long) (days * GuestTime.TICKS_PER_DAY);
  }
}
