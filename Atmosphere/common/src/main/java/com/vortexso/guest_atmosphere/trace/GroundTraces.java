package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.block.Coating.Coat;
import com.vortexso.guest_atmosphere.block.CoveredPlantBlock;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Kind;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Trace;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.WeatherType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeafLitterBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

final class GroundTraces {
  private static final double SUMMER_LITTER = 0.05;

  private GroundTraces() {}

  static void update(Column c) {
    sand(c);
    mud(c);
    if (AtmosphereConfig.SILT.get()) {
      silt(c);
    }
    dry(c);
    regrass(c);
    ash(c);
    burntLeaves(c);
    if (AtmosphereConfig.LEAF_LITTER.get()) {
      leafLitter(c);
    }
  }

  private static void sand(Column c) {
    BlockState surface = c.state(c.top);
    boolean pile = isPile(surface);
    boolean piles = AtmosphereConfig.SAND_PILES.get();
    boolean coats = AtmosphereConfig.COATED_BLOCKS.get();
    if (c.live) {
      WeatherType type = c.type();
      if (type == WeatherType.SANDSTORM) {
        if (piles
            && (coverable(surface) || (surface.isAir() || pile) && shelter(c) > 0)
            && !c.state(c.ground).is(BlockTags.SAND)
            && c.chance(17, c.intensity() * c.params.sandChance())) {
          addPile(c, surface, shelter(c) >= 2 ? 4 : 2, 1);
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
        c.set(c.top, CoveredPlantBlock.withLayers(surface, 0));
      }
      Surfaces.uncoatAll(c, Coat.SANDY);
    } else if (c.chance(17, History.coverage(h.sandHits))) {
      if (piles
          && (coverable(surface) || (surface.isAir() || pile) && shelter(c) > 0)
          && !c.state(c.ground).is(BlockTags.SAND)) {
        int depth = shelter(c) >= 2 ? 4 : 2;
        int current = pile ? surface.getValue(SnowLayerBlock.LAYERS) : 0;
        if (current < depth) {
          addPile(c, surface, depth, depth - current);
        }
      }
      if (coats) {
        Surfaces.coatSurface(c, Coat.SANDY);
        Surfaces.coatWindward(c, Coat.SANDY, 2);
      }
    }
  }

  private static boolean isPile(BlockState state) {
    Block block =
        state.getBlock() instanceof CoveredPlantBlock covered ? covered.cover() : state.getBlock();
    return block == AtmosphereBlocks.SAND_PILE.get()
        || block == AtmosphereBlocks.RED_SAND_PILE.get();
  }

  private static boolean coverable(BlockState state) {
    return AtmosphereConfig.COVERED_PLANTS.get() && CoveredPlantBlock.Plant.of(state) != null;
  }

  private static int shelter(Column c) {
    int sides = 0;
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = c.top.relative(direction);
      if (c.state(side).blocksMotion()) {
        sides++;
      }
    }
    return sides;
  }

  private static void addPile(Column c, BlockState surface, int cap, int count) {
    if (isPile(surface)) {
      int layers = surface.getValue(SnowLayerBlock.LAYERS);
      if (layers < cap) {
        c.set(c.top, surface.setValue(SnowLayerBlock.LAYERS, Math.min(cap, layers + count)));
      }
      return;
    }
    Block pile =
        AtmosphereWeather.isRedSand(c.level, c.top)
            ? AtmosphereBlocks.RED_SAND_PILE.get()
            : AtmosphereBlocks.SAND_PILE.get();
    int layers = Math.min(cap, count);
    BlockState state =
        surface.isAir()
            ? pile.defaultBlockState().setValue(SnowLayerBlock.LAYERS, layers)
            : CoveredPlantBlock.cover(surface, pile, layers);
    if (state != null && state.canSurvive(c.level, c.top)) {
      c.set(c.top, state);
    }
  }

  static void lowerLayer(Column c, BlockState surface) {
    c.set(
        c.top, CoveredPlantBlock.withLayers(surface, surface.getValue(SnowLayerBlock.LAYERS) - 1));
  }

  private static void mud(Column c) {
    if (!c.state(c.top).isAir()) {
      return;
    }
    BlockState ground = c.state(c.ground);
    Trace trace = c.traces.get(c.ground.asLong());
    boolean softened =
        trace != null && trace.kind() == Kind.MUD && ground.is(AtmosphereBlocks.SILT.get());
    boolean bare =
        ground.is(Blocks.DIRT) || ground.is(Blocks.COARSE_DIRT) || ground.is(Blocks.DIRT_PATH);
    if (!softened && !bare && !(ground.is(Blocks.GRASS_BLOCK) && (nextToWater(c) || dripLine(c)))) {
      return;
    }
    int stage;
    if (c.live) {
      if (!realRain(c.type())
          || c.temperature() < c.params.snowTemperature()
          || !c.chance(22, c.params.mudChance() * c.intensity())) {
        return;
      }
      stage = softened ? 2 : 1;
    } else {
      History h = c.history();
      double coverage = History.coverage(h.mudHits);
      if (!h.rainedWithin(c.params.mudDryingTicks()) || !c.chance(22, coverage)) {
        return;
      }
      stage = c.chance(39, coverage) ? 2 : 1;
    }
    if (softened) {
      if (stage == 2) {
        c.set(c.ground, Blocks.MUD.defaultBlockState());
      }
      return;
    }
    c.change(
        c.ground,
        stage == 2
            ? Blocks.MUD.defaultBlockState()
            : AtmosphereBlocks.SILT.get().defaultBlockState(),
        Kind.MUD);
  }

  static boolean realRain(WeatherType type) {
    return type.isRain() && type != WeatherType.DRIZZLE;
  }

  private static boolean nextToWater(Column c) {
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = c.ground.relative(direction);
      if (c.loaded(side)) {
        BlockState state = c.state(side);
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
        if ((dx != 0 || dz != 0) && c.state(pos).is(Blocks.WATER)) {
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

  private static void regrass(Column c) {
    Season season = GuestTime.season(c.now);
    if ((season != Season.SUMMER && season != Season.AUTUMN)
        || c.climate.climateClass() == ClimateClass.DRY
        || !c.state(c.ground).is(Blocks.DIRT)
        || c.traces.get(c.ground.asLong()) != null) {
      return;
    }
    BlockState surface = c.state(c.top);
    if ((!surface.isAir() && CoveredPlantBlock.Plant.of(surface) == null)
        || c.level.getBrightness(LightLayer.SKY, c.top) < 9) {
      return;
    }
    double days = AtmosphereConfig.REGRASS_DAYS.get();
    double summer =
        (season == Season.SUMMER ? 0 : GuestTime.DAYS_PER_SEASON)
            + GuestTime.seasonProgress(c.now) * GuestTime.DAYS_PER_SEASON;
    boolean grows =
        c.live
            ? c.chance(40, c.perVisit(1.0 / days))
            : c.chance(40, 1.0 - Math.exp(-summer / days));
    if (grows) {
      c.set(c.ground, Blocks.GRASS_BLOCK.defaultBlockState());
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
    boolean bare = season == Season.WINTER || season == Season.SPRING;
    double stray = c.params.litterPerDay() * SUMMER_LITTER;
    int target = amount;
    if (c.live) {
      WeatherType type = c.type();
      double windy = type == WeatherType.LEAF_FALL || type == WeatherType.WIND ? 3.0 : 1.0;
      if (season == Season.AUTUMN && c.chance(27, c.perVisit(c.params.litterPerDay() * windy))) {
        target = Math.min(4, amount + 1);
      } else if (season == Season.SUMMER && amount == 0 && c.chance(27, c.perVisit(stray))) {
        target = 1;
      } else if (bare && amount > 0 && c.chance(28, c.perVisit(0.5 * windy))) {
        target = amount - 1;
      }
    } else {
      History h = c.history();
      if (bare || h.winterSeen) {
        target = 0;
      } else if (season == Season.AUTUMN) {
        double expected = h.litterDays * c.params.litterPerDay() * (0.5 + c.fixed(floor, 29));
        target = Math.max(amount, (int) Math.min(4, Math.round(expected)));
      } else if (amount == 0
          && c.fixed(floor, 31)
              < stray * GuestTime.seasonProgress(c.now) * GuestTime.DAYS_PER_SEASON) {
        target = 1;
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
      case MUD -> state.is(Blocks.MUD) || state.is(AtmosphereBlocks.SILT.get());
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
