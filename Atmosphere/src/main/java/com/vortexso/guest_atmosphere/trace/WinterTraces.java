package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.block.Coating;
import com.vortexso.guest_atmosphere.block.Coating.Coat;
import com.vortexso.guest_atmosphere.block.SnowyPlantBlock;
import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.WeatherType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SnowyBlock;
import net.minecraft.world.level.block.state.BlockState;

final class WinterTraces {

  private static final double ICICLE_EAVES = 0.35;

  private static final long QUARTER_DAY = GuestTime.TICKS_PER_DAY / 4;

  private WinterTraces() {}

  static void update(Column c) {
    snow(c);
    if (AtmosphereConfig.COATED_BLOCKS.get()) {
      coat(c);
    }
    if (AtmosphereConfig.ICICLES.get()) {
      icicles(c);
    }
    if (AtmosphereConfig.FROST.get()) {
      frost(c);
    }
    if (AtmosphereConfig.THIN_ICE.get()) {
      thinIce(c);
    }
    if (AtmosphereConfig.PERMAFROST.get()) {
      permafrost(c);
    }
  }

  private static void snow(Column c) {
    BlockState surface = c.state(c.top);
    int layers = layers(surface);
    if (c.live) {
      WeatherType type = c.type();
      if (type.isSnow()) {
        boolean storm = type != WeatherType.SNOWFALL;
        if (c.chance(1, c.intensity() * (storm ? 1.0 : 0.6))) {
          raise(c, cap(c, storm));
        }
      } else if (layers > 0
          && c.chance(2, c.perVisit(c.params.meltPerDay(c.temperature(), type.isRain())))) {
        lower(c, surface, layers - 1);
      }
      return;
    }
    History h = c.history();
    int target = h.snowLayers(c.fixed(new BlockPos(c.x, 0, c.z), 3), cap(c, h.stormSnow));
    for (int i = layers; i < target; i++) {
      if (!raise(c, target)) {
        break;
      }
    }

    if (target < layers && h.snowDepth < h.initialSnow) {
      lower(c, surface, target);
    }
  }

  static int layers(BlockState state) {
    return state.is(Blocks.SNOW) || state.is(AtmosphereBlocks.SNOWY_PLANT.get())
        ? state.getValue(SnowLayerBlock.LAYERS)
        : 0;
  }

  private static boolean raise(Column c, int cap) {
    BlockPos pos = c.top;
    if (!c.dark(pos)) {
      return false;
    }
    BlockState state = c.level.getBlockState(pos);
    if (state.isAir() || state.is(AtmosphereBlocks.FROST.get())) {
      BlockState snow = Blocks.SNOW.defaultBlockState();
      if (!snow.canSurvive(c.level, pos)) {
        return false;
      }
      c.set(pos, snow);
      return true;
    }
    int layers = layers(state);
    if (layers > 0) {
      if (layers >= cap) {
        return false;
      }
      BlockState deeper = state.setValue(SnowLayerBlock.LAYERS, layers + 1);
      c.set(pos, Block.pushEntitiesUp(state, deeper, c.level, pos));
      return true;
    }
    if (AtmosphereConfig.SNOWY_PLANTS.get()) {
      BlockState covered = SnowyPlantBlock.cover(state, 1);
      if (covered != null && covered.canSurvive(c.level, pos)) {
        c.set(pos, covered);
        return true;
      }
    }
    return false;
  }

  private static void lower(Column c, BlockState state, int target) {
    if (state.is(Blocks.SNOW)) {
      c.set(
          c.top,
          target <= 0
              ? Blocks.AIR.defaultBlockState()
              : state.setValue(SnowLayerBlock.LAYERS, target));
    } else if (state.is(AtmosphereBlocks.SNOWY_PLANT.get())) {
      c.set(c.top, SnowyPlantBlock.withLayers(state, target));
    }
  }

  private static int cap(Column c, boolean storm) {
    if (!storm) {
      return AtmosphereConfig.SNOWFALL_MAX_LAYERS.get();
    }
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = c.top.relative(direction);
      if (c.loaded(side) && c.level.getBlockState(side).blocksMotion()) {
        return AtmosphereConfig.DRIFT_MAX_LAYERS.get();
      }
    }
    return AtmosphereConfig.STORM_MAX_LAYERS.get();
  }

  private static void coat(Column c) {
    boolean snowLies = layers(c.state(c.top)) > 0;
    if (c.live) {
      WeatherType type = c.type();
      if (type.isSnow()) {
        if (c.dark(c.top) && c.chance(5, c.intensity() * c.params.coatChance())) {
          Surfaces.coatSurface(c, Coat.SNOWY);
        }
        if (type != WeatherType.SNOWFALL && c.chance(6, c.intensity() * c.params.coatChance())) {
          Surfaces.coatWindward(c, Coat.SNOWY, 3);
        }
      } else if (!snowLies
          && (c.temperature() > c.params.meltTemperature()
              || type == WeatherType.WIND
              || type.isRain())
          && c.chance(7, 0.5)) {
        Surfaces.uncoatAll(c, Coat.SNOWY);
      }
      return;
    }
    History h = c.history();
    boolean snowy =
        h.snowDepth >= 0.5
            || (h.lastSnow != History.NEVER
                && h.lastSnow > Math.max(h.lastThaw, Math.max(h.lastWind, h.lastRain)));
    if (!snowy) {
      Surfaces.uncoatAll(c, Coat.SNOWY);
    } else if (c.chance(5, History.coverage(h.snowHits))) {
      Surfaces.coatSurface(c, Coat.SNOWY);
      if (h.stormSnow) {
        Surfaces.coatWindward(c, Coat.SNOWY, 3);
      }
    }
  }

  private static void icicles(Column c) {
    BlockPos hang = c.ground.below();
    if (c.fixed(hang, 8) >= ICICLE_EAVES || !c.loaded(hang)) {
      return;
    }
    BlockState ceiling = c.state(c.ground);
    boolean present = c.state(hang).is(AtmosphereBlocks.ICICLE.get());
    if (!present
        && !(c.state(hang).isAir()
            && ceiling.isFaceSturdy(c.level, c.ground, Direction.DOWN)
            && c.dark(hang)
            && eaves(c, hang))) {
      return;
    }
    int maxLength = 1 + (int) (c.fixed(hang, 9) * 3.0);
    boolean roofSnow = layers(c.state(c.top)) > 0 || Coating.coatOf(ceiling) == Coat.SNOWY;
    double t = c.temperature();
    if (c.live) {

      boolean refreezing =
          roofSnow
              && !GuestTime.isNight(c.now)
              && !c.type().isPrecipitation()
              && t > c.params.freezeTemperature() - 0.25
              && t < c.params.meltTemperature() + 0.05;
      boolean melting =
          t > c.params.meltTemperature() + 0.1 || (!roofSnow && t > c.params.snowTemperature());
      if (refreezing && c.chance(9, AtmosphereConfig.ICICLE_CHANCE.get())) {
        growIcicle(c, hang, maxLength);
      } else if (present && melting && c.chance(10, 0.5)) {
        shrinkIcicle(c, hang);
      }
      return;
    }
    History h = c.history();
    if (present && (h.warmFor() >= QUARTER_DAY || !roofSnow)) {
      for (int i = 0; i < 3; i++) {
        shrinkIcicle(c, hang);
      }
    } else if (!present
        && roofSnow
        && h.warmFor() == 0
        && c.chance(
            9, History.coverage(h.freezeThawCycles * AtmosphereConfig.ICICLE_CHANCE.get()))) {
      for (int i = 0; i < maxLength; i++) {
        growIcicle(c, hang, maxLength);
      }
    }
  }

  private static boolean eaves(Column c, BlockPos hang) {
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = hang.relative(direction);
      if (c.loaded(side) && c.level.getBlockState(side).isAir() && c.level.canSeeSky(side)) {
        return true;
      }
    }
    return false;
  }

  private static void growIcicle(Column c, BlockPos hang, int maxLength) {
    BlockPos tip = hang;
    int length = 0;
    while (length < maxLength && c.state(tip).is(AtmosphereBlocks.ICICLE.get())) {
      tip = tip.below();
      length++;
    }

    if (length < maxLength && c.state(tip).isAir() && c.state(tip.below()).isAir()) {
      c.set(tip, AtmosphereBlocks.ICICLE.get().defaultBlockState());
    }
  }

  private static void shrinkIcicle(Column c, BlockPos hang) {
    BlockPos tip = null;
    for (BlockPos pos = hang; c.state(pos).is(AtmosphereBlocks.ICICLE.get()); pos = pos.below()) {
      tip = pos;
    }
    if (tip != null) {
      c.set(tip, Blocks.AIR.defaultBlockState());
    }
  }

  private static void frost(Column c) {
    boolean night = GuestTime.isNight(c.now);
    double t = c.temperature();
    WeatherType type = c.type();
    boolean frosty = night && t < c.params.freezeTemperature() + 0.05 && !type.isPrecipitation();
    boolean clearing = !night || t >= c.params.freezeTemperature() + 0.15 || type.isPrecipitation();
    BlockState surface = c.state(c.top);
    if (frosty
        && c.dark(c.top)
        && c.chance(11, c.live ? AtmosphereConfig.FROST_CHANCE.get() : 0.7)) {
      BlockState ground = c.state(c.ground);
      if (surface.isAir()
          && ground.isFaceSturdy(c.level, c.ground, Direction.UP)
          && layers(ground) == 0) {
        c.set(
            c.top,
            AtmosphereBlocks.FROST
                .get()
                .defaultBlockState()
                .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true));
      } else if (surface.is(Blocks.SHORT_GRASS)) {
        c.set(c.top, AtmosphereBlocks.FROSTY_GRASS.get().defaultBlockState());
      } else if (surface.is(Blocks.FERN)) {
        c.set(c.top, AtmosphereBlocks.FROSTY_FERN.get().defaultBlockState());
      }
      Surfaces.walls(c, 4, (wall, state, face) -> frostWindow(c, wall, state, true));
    } else if (clearing && (!c.live || c.chance(12, 0.5))) {
      if (surface.is(AtmosphereBlocks.FROST.get())) {
        c.set(c.top, Blocks.AIR.defaultBlockState());
      } else if (surface.is(AtmosphereBlocks.FROSTY_GRASS.get())) {
        c.set(c.top, Blocks.SHORT_GRASS.defaultBlockState());
      } else if (surface.is(AtmosphereBlocks.FROSTY_FERN.get())) {
        c.set(c.top, Blocks.FERN.defaultBlockState());
      }
      Surfaces.walls(c, 4, (wall, state, face) -> frostWindow(c, wall, state, false));
    }
  }

  private static void frostWindow(Column c, BlockPos pos, BlockState state, boolean freeze) {
    BlockState changed = null;
    if (freeze && state.is(Blocks.GLASS)) {
      changed = AtmosphereBlocks.FROSTY_GLASS.get().defaultBlockState();
    } else if (freeze && state.is(Blocks.GLASS_PANE)) {
      changed = AtmosphereBlocks.FROSTY_GLASS_PANE.get().withPropertiesOf(state);
    } else if (!freeze && state.is(AtmosphereBlocks.FROSTY_GLASS.get())) {
      changed = Blocks.GLASS.defaultBlockState();
    } else if (!freeze && state.is(AtmosphereBlocks.FROSTY_GLASS_PANE.get())) {
      changed = Blocks.GLASS_PANE.withPropertiesOf(state);
    }
    if (changed != null) {
      c.set(pos, changed);
    }
  }

  private static void thinIce(Column c) {
    BlockState ground = c.state(c.ground);
    if (ground.is(AtmosphereBlocks.THIN_ICE.get())) {
      boolean thaw =
          c.live
              ? c.temperature() >= c.params.snowTemperature() && c.chance(13, 0.5)
              : c.temperature() >= c.params.snowTemperature() || c.history().iceHits == 0.0;
      if (thaw) {
        c.set(c.ground, Blocks.WATER.defaultBlockState());
      }
      return;
    }
    if (!ground.is(Blocks.WATER)
        || !ground.getFluidState().isSource()
        || !c.state(c.top).isAir()
        || !c.dark(c.ground)
        || c.level.getBiome(c.ground).value().coldEnoughToSnow(c.ground, c.level.getSeaLevel())) {
      return;
    }
    boolean freezing =
        c.live
            ? c.temperature() < c.params.freezeTemperature() && c.chance(14, c.params.iceChance())
            : c.chance(14, History.coverage(c.history().iceHits));
    if (freezing && shoreOrIce(c)) {
      c.set(c.ground, AtmosphereBlocks.THIN_ICE.get().defaultBlockState());
    }
  }

  private static boolean shoreOrIce(Column c) {
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = c.ground.relative(direction);
      if (!c.loaded(side)) {
        continue;
      }
      BlockState state = c.level.getBlockState(side);
      if (!state.getFluidState().isSource() || state.is(AtmosphereBlocks.THIN_ICE.get())) {
        return true;
      }
    }
    return false;
  }

  private static void permafrost(Column c) {
    BlockState ground = c.state(c.ground);
    boolean frozen =
        ground.is(AtmosphereBlocks.PERMAFROST.get()) || ground.is(AtmosphereBlocks.CRYOSOL.get());
    boolean soil = ground.is(Blocks.DIRT) || ground.is(Blocks.GRASS_BLOCK);
    if (!frozen && !(soil && c.climate.climateClass() == ClimateClass.BOREAL)) {
      return;
    }
    History h = c.history();
    double chance = c.live ? 0.25 : 1.0;
    long freezeTicks = (long) (AtmosphereConfig.PERMAFROST_DAYS.get() * GuestTime.TICKS_PER_DAY);
    if (soil && h.coldFor() >= freezeTicks && c.chance(15, chance)) {
      c.set(
          c.ground,
          ground.is(Blocks.DIRT)
              ? AtmosphereBlocks.PERMAFROST.get().defaultBlockState()
              : AtmosphereBlocks.CRYOSOL
                  .get()
                  .defaultBlockState()
                  .setValue(SnowyBlock.SNOWY, ground.getValue(SnowyBlock.SNOWY)));
    } else if (frozen && h.warmFor() >= GuestTime.TICKS_PER_DAY / 2 && c.chance(16, chance)) {
      c.set(
          c.ground,
          ground.is(AtmosphereBlocks.PERMAFROST.get())
              ? Blocks.DIRT.defaultBlockState()
              : Blocks.GRASS_BLOCK
                  .defaultBlockState()
                  .setValue(SnowyBlock.SNOWY, ground.getValue(SnowyBlock.SNOWY)));
    }
  }
}
