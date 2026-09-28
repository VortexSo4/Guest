package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_core.api.world.WeatherType;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

final class GrowthTraces {

  private static final int IVY_MAX_HEIGHT = 8;

  private static final Map<Block, Block> MOSSY = new IdentityHashMap<>();
  private static final Map<Block, Block> CRACKED = new IdentityHashMap<>();

  static {
    MOSSY.put(Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE);
    MOSSY.put(Blocks.COBBLESTONE_STAIRS, Blocks.MOSSY_COBBLESTONE_STAIRS);
    MOSSY.put(Blocks.COBBLESTONE_SLAB, Blocks.MOSSY_COBBLESTONE_SLAB);
    MOSSY.put(Blocks.COBBLESTONE_WALL, Blocks.MOSSY_COBBLESTONE_WALL);
    MOSSY.put(Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS);
    MOSSY.put(Blocks.STONE_BRICK_STAIRS, Blocks.MOSSY_STONE_BRICK_STAIRS);
    MOSSY.put(Blocks.STONE_BRICK_SLAB, Blocks.MOSSY_STONE_BRICK_SLAB);
    MOSSY.put(Blocks.STONE_BRICK_WALL, Blocks.MOSSY_STONE_BRICK_WALL);
    CRACKED.put(Blocks.STONE_BRICKS, Blocks.CRACKED_STONE_BRICKS);
    CRACKED.put(Blocks.DEEPSLATE_BRICKS, Blocks.CRACKED_DEEPSLATE_BRICKS);
    CRACKED.put(Blocks.DEEPSLATE_TILES, Blocks.CRACKED_DEEPSLATE_TILES);
    CRACKED.put(Blocks.POLISHED_BLACKSTONE_BRICKS, Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS);
    CRACKED.put(Blocks.NETHER_BRICKS, Blocks.CRACKED_NETHER_BRICKS);
  }

  private GrowthTraces() {}

  static @Nullable Block mossy(Block block) {
    return MOSSY.get(block);
  }

  static @Nullable Block unmossed(Block block) {
    for (Map.Entry<Block, Block> entry : MOSSY.entrySet()) {
      if (entry.getValue() == block) {
        return entry.getKey();
      }
    }
    return null;
  }

  static @Nullable Block uncracked(Block block) {
    if (block == AtmosphereBlocks.CRACKED_BRICKS.get()) {
      return Blocks.BRICKS;
    }
    for (Map.Entry<Block, Block> entry : CRACKED.entrySet()) {
      if (entry.getValue() == block) {
        return entry.getKey();
      }
    }
    return null;
  }

  static void update(Column c) {
    if (!AtmosphereConfig.GROWTH.get() || c.climate.climateClass() == ClimateClass.DRY) {
      return;
    }

    double damp;
    double freezeThaw;
    if (c.live) {
      WeatherType type = c.type();
      boolean wet = c.climate.climateClass() == ClimateClass.WET && type != WeatherType.HEAT;
      damp = type.isRain() || type == WeatherType.FOG || type.isSnow() ? 1.0 : wet ? 0.3 : 0.0;
      freezeThaw =
          Math.abs(c.temperature() - c.params.freezeTemperature()) < 0.05 && damp > 0.0 ? 1.0 : 0.0;
    } else {
      damp = c.history().dampDays;
      freezeThaw = c.history().freezeThawCycles;
    }
    if (damp <= 0.0 && freezeThaw <= 0.0) {
      return;
    }
    Habitat habitat = new Habitat(c);
    age(c, c.ground, c.state(c.ground), Direction.UP, damp, freezeThaw, habitat);
    Surfaces.walls(
        c,
        3,
        (wall, state, face) -> {
          age(c, wall, state, face, damp, freezeThaw, habitat);
          lichen(c, wall, state, face, damp, habitat);
        });
    ivy(c, damp, habitat);
  }

  private static void age(
      Column c,
      BlockPos pos,
      BlockState state,
      Direction face,
      double damp,
      double freezeThaw,
      Habitat habitat) {
    Block mossy = MOSSY.get(state.getBlock());
    if (mossy != null
        && damp > 0.0
        && grows(
            c, pos, 40, AtmosphereConfig.MOSS_RATE.get(), damp * shade(c, pos, face), habitat)) {
      c.set(pos, mossy.withPropertiesOf(state));
      return;
    }
    Block cracked =
        state.is(Blocks.BRICKS)
            ? AtmosphereBlocks.CRACKED_BRICKS.get()
            : CRACKED.get(state.getBlock());
    if (cracked != null
        && freezeThaw > 0.0
        && AtmosphereConfig.CRACKING.get()
        && c.growth(pos, 41, probability(c, AtmosphereConfig.CRACK_RATE.get(), freezeThaw, 1.0))) {
      c.set(pos, cracked.withPropertiesOf(state));
    }
  }

  private static void lichen(
      Column c, BlockPos wall, BlockState state, Direction face, double damp, Habitat habitat) {
    if (damp <= 0.0
        || !lichenHost(state)
        || !state.isFaceSturdy(c.level, wall, face)
        || shade(c, wall, face) < 1.0) {
      return;
    }
    BlockPos air = wall.relative(face);
    BlockState inside = c.state(air);
    Direction towardsWall = face.getOpposite();
    if (inside.isAir() && grows(c, air, 42, AtmosphereConfig.LICHEN_RATE.get(), damp, habitat)) {
      c.set(
          air,
          AtmosphereBlocks.LICHEN
              .get()
              .defaultBlockState()
              .setValue(MultifaceBlock.getFaceProperty(towardsWall), true));
    }
  }

  private static boolean lichenHost(BlockState state) {
    return state.is(BlockTags.BASE_STONE_OVERWORLD)
        || state.is(BlockTags.LOGS)
        || state.is(Blocks.COBBLESTONE)
        || state.is(Blocks.MOSSY_COBBLESTONE)
        || state.is(Blocks.STONE_BRICKS)
        || state.is(Blocks.BRICKS)
        || state.is(Blocks.COBBLED_DEEPSLATE);
  }

  private static void ivy(Column c, double damp, Habitat habitat) {
    if (damp <= 0.0 || !AtmosphereConfig.IVY.get()) {
      return;
    }
    BlockState ivy = AtmosphereBlocks.IVY.get().defaultBlockState();
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos foot = c.ground.above().relative(direction);
      int maxHeight = 2 + (int) (c.fixed(foot, 43) * (IVY_MAX_HEIGHT - 1));
      for (int k = 1; k <= maxHeight; k++) {
        BlockPos air = c.ground.above(k);
        BlockPos wall = air.relative(direction);
        BlockState inside = c.state(air);
        BlockState wallState = c.state(wall);
        boolean climbing = inside.is(ivy.getBlock());
        if (climbing && inside.getValue(VineBlock.getPropertyForFace(direction))) {
          continue;
        }
        if (!wallState.isFaceSturdy(c.level, wall, direction.getOpposite())
            || (!inside.isAir() && !climbing)
            || shade(c, wall, direction.getOpposite()) < 1.0) {
          break;
        }
        boolean rooted = k == 1;
        boolean below =
            c.state(air.below()).is(ivy.getBlock())
                && c.state(air.below()).getValue(VineBlock.getPropertyForFace(direction));
        if (!rooted && !below) {
          break;
        }

        double rate =
            rooted ? AtmosphereConfig.IVY_RATE.get() : AtmosphereConfig.IVY_RATE.get() * 10.0;
        if (!grows(c, air, 44 + k, rate, damp, habitat)) {
          break;
        }
        BlockState base = climbing ? inside : ivy;
        c.set(air, base.setValue(VineBlock.getPropertyForFace(direction), true));

        if (c.live) {
          break;
        }
      }
    }
  }

  private static double shade(Column c, BlockPos pos, Direction face) {
    if (face == Direction.NORTH) {
      return 1.0;
    }
    BlockPos air = pos.relative(face);
    return c.loaded(air) && c.level.getBrightness(LightLayer.SKY, air) < 13 ? 1.0 : 0.3;
  }

  private static boolean grows(
      Column c, BlockPos pos, int salt, double ratePerDay, double amount, Habitat habitat) {
    double abandoned = AtmosphereConfig.ABANDONED_GROWTH.get();
    double best = probability(c, ratePerDay, amount, abandoned);
    if (!c.growth(pos, salt, best)) {
      return false;
    }
    double factor = habitat.factor();
    return factor >= abandoned
        || c.growth(pos, salt + 1_000, probability(c, ratePerDay, amount, factor) / best);
  }

  private static double probability(Column c, double ratePerDay, double amount, double factor) {
    return c.live
        ? c.perVisit(ratePerDay) * amount * factor
        : History.coverage(ratePerDay * amount * factor);
  }

  private static final class Habitat {
    private final Column c;
    private double factor = -1.0;

    Habitat(Column c) {
      this.c = c;
    }

    double factor() {
      if (factor < 0.0) {
        boolean inhabited =
            c.level
                    .getPoiManager()
                    .getCountInRange(
                        type -> type.is(PoiTypes.HOME), c.ground, 32, PoiManager.Occupancy.ANY)
                > 0;
        factor = inhabited ? 1.0 : AtmosphereConfig.ABANDONED_GROWTH.get();
      }
      return factor;
    }
  }
}
