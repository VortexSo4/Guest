package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.block.Coating;
import com.vortexso.guest_atmosphere.block.Coating.Coat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

final class Surfaces {

  private static final double SECOND_COURSE = 0.35;

  interface WallVisitor {

    void visit(BlockPos wall, BlockState state, Direction face);
  }

  private Surfaces() {}

  static void walls(Column c, int height, WallVisitor visitor) {
    for (int k = 1; k <= height; k++) {
      BlockPos air = c.ground.above(k);
      BlockState inside = c.state(air);
      if (!inside.isAir() && !inside.canBeReplaced()) {
        return;
      }
      for (Direction direction : Direction.Plane.HORIZONTAL) {
        BlockPos wall = air.relative(direction);
        if (!c.loaded(wall)) {
          continue;
        }
        BlockState state = c.level.getBlockState(wall);
        if (!state.isAir()) {
          visitor.visit(wall, state, direction.getOpposite());
        }
      }
    }
  }

  static Direction windward(Column c) {
    double angle = c.sample.windAngle();
    double dx = Math.cos(angle);
    double dz = Math.sin(angle);
    if (Math.abs(dx) > Math.abs(dz)) {
      return dx > 0 ? Direction.EAST : Direction.WEST;
    }
    return dz > 0 ? Direction.SOUTH : Direction.NORTH;
  }

  static void coatSurface(Column c, Coat coat) {
    coatAt(c, c.ground, coat);
    BlockPos below = c.ground.below();
    if (c.fixed(c.ground, 4) < SECOND_COURSE && openSide(c, below)) {
      coatAt(c, below, coat);
    }
  }

  private static boolean openSide(Column c, BlockPos pos) {
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = pos.relative(direction);
      if (c.loaded(side) && c.level.getBlockState(side).isAir() && c.level.canSeeSky(side)) {
        return true;
      }
    }
    return false;
  }

  static void coatWindward(Column c, Coat coat, int height) {
    Direction direction = windward(c);
    for (int k = 1; k <= height; k++) {
      BlockPos air = c.ground.above(k);
      BlockState inside = c.state(air);
      if (!inside.isAir() && !inside.canBeReplaced()) {
        return;
      }
      coatAt(c, air.relative(direction), coat);
    }
  }

  static void uncoatAll(Column c, Coat coat) {
    uncoatAt(c, c.ground, coat);
    uncoatAt(c, c.ground.below(), coat);
    walls(c, 3, (wall, state, face) -> uncoatAt(c, wall, coat));
  }

  private static void coatAt(Column c, BlockPos pos, Coat coat) {
    if (!c.loaded(pos)) {
      return;
    }
    BlockState coated = Coating.coat(c.level.getBlockState(pos), coat);
    if (coated != null) {
      c.set(pos, coated);
    }
  }

  private static void uncoatAt(Column c, BlockPos pos, Coat coat) {
    if (!c.loaded(pos)) {
      return;
    }
    BlockState state = c.level.getBlockState(pos);
    if (Coating.coatOf(state) == coat) {
      c.set(pos, Coating.uncoat(state));
    }
  }
}
