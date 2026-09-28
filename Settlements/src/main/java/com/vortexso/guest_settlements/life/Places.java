package com.vortexso.guest_settlements.life;

import com.vortexso.guest_core.api.GuestHash;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

final class Places {
  private Places() {}

  static boolean inside(AABB area, BlockPos pos) {
    return area.contains(pos.getX() + 0.5, area.minY + 1.0, pos.getZ() + 0.5);
  }

  static boolean loaded(ServerLevel level, int x, int z) {
    return level.hasChunk(x >> 4, z >> 4);
  }

  static @Nullable BlockPos standable(ServerLevel level, BlockPos around, int dy) {
    if (!loaded(level, around.getX(), around.getZ())) {
      return null;
    }
    for (int d = 0; d <= 2 * dy; d++) {
      int y = around.getY() + ((d & 1) == 0 ? -d / 2 : (d + 1) / 2);
      BlockPos pos = new BlockPos(around.getX(), y, around.getZ());
      if (standable(level, pos)) {
        return pos;
      }
    }
    return null;
  }

  static @Nullable BlockPos beside(ServerLevel level, BlockPos pos) {
    for (Direction side : Direction.Plane.HORIZONTAL) {
      BlockPos spot = standable(level, pos.relative(side), 1);
      if (spot != null) {
        return spot;
      }
    }
    return null;
  }

  static BlockPos ground(ServerLevel level, BlockPos pos) {
    for (int dy = 0; dy <= 6; dy++) {
      if (standable(level, pos.below(dy))) {
        return pos.below(dy);
      }
    }
    return pos;
  }

  static boolean standable(ServerLevel level, BlockPos pos) {
    BlockPos below = pos.below();
    var floor = level.getBlockState(below).getCollisionShape(level, below);
    return !floor.isEmpty()
        && floor.max(Direction.Axis.Y) >= 0.5
        && free(level, pos)
        && free(level, pos.above());
  }

  private static boolean free(ServerLevel level, BlockPos pos) {
    BlockState state = level.getBlockState(pos);
    var shape = state.getCollisionShape(level, pos);
    return (shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.25)
        && state.getFluidState().isEmpty();
  }

  static @Nullable BlockPos top(ServerLevel level, int x, int z) {
    if (!loaded(level, x, z)) {
      return null;
    }
    return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, z);
  }

  static List<BlockPos> ring(
      ServerLevel level,
      BlockPos center,
      int minRadius,
      int maxRadius,
      int step,
      long salt,
      int limit,
      Predicate<BlockPos> accept) {
    List<BlockPos> found = new ArrayList<>();
    double turn = GuestHash.unit(GuestHash.hash(level.getSeed(), salt)) * 2.0 * Math.PI;
    for (int radius = minRadius; radius <= maxRadius && found.size() < limit; radius += step) {
      int points = Math.max(8, (int) (2.0 * Math.PI * radius / step));
      for (int i = 0; i < points && found.size() < limit; i++) {
        double angle = turn + 2.0 * Math.PI * i / points;
        int x = center.getX() + (int) Math.round(Math.cos(angle) * radius);
        int z = center.getZ() + (int) Math.round(Math.sin(angle) * radius);
        BlockPos top = top(level, x, z);
        if (top != null && accept.test(top)) {
          found.add(top);
        }
      }
    }
    return found;
  }

  static BlockPos @Nullable [] shore(
      ServerLevel level, BlockPos center, int radius, AABB area, long salt) {
    BlockPos[] best = null;
    int bestScore = 0;
    for (BlockPos top :
        ring(
            level,
            center,
            6,
            radius,
            3,
            salt,
            24,
            pos -> inside(area, pos) && isWater(level, pos))) {
      for (Direction side : Direction.Plane.HORIZONTAL) {
        BlockPos land =
            Places.top(level, top.getX() + side.getStepX(), top.getZ() + side.getStepZ());
        if (land == null || isWater(level, land) || Math.abs(land.getY() - top.getY()) > 1) {
          continue;
        }
        BlockPos stand = land.above();
        if (!inside(area, stand) || !standable(level, stand) || Errands.unreachable(level, stand)) {
          continue;
        }
        int score = waterAround(level, top);
        if (score > bestScore) {
          bestScore = score;
          best = new BlockPos[] {stand, top};
        }
      }
      if (bestScore >= 18) {
        break;
      }
    }
    return best;
  }

  static boolean isWater(ServerLevel level, BlockPos pos) {
    return level.getFluidState(pos).is(FluidTags.WATER)
        && level.getBlockState(pos).is(net.minecraft.world.level.block.Blocks.WATER);
  }

  private static int waterAround(ServerLevel level, BlockPos surface) {
    int score = 0;
    for (BlockPos pos :
        BlockPos.betweenClosed(surface.offset(-2, -1, -2), surface.offset(2, 0, 2))) {
      if (isWater(level, pos)) {
        score++;
      }
    }
    return score;
  }

  private static final int LEVEL_TOLERANCE = 6;

  static @Nullable BlockPos tree(
      ServerLevel level, BlockPos center, int radius, AABB area, BoundingBox exclude, long salt) {
    int ground = ground(level, center).getY();
    for (BlockPos top :
        ring(
            level,
            center,
            8,
            radius,
            2,
            salt,
            1,
            pos ->
                inside(area, pos)
                    && isTreeColumn(level, pos, exclude)
                    && Math.abs(lowestLog(level, pos).getY() - ground) <= LEVEL_TOLERANCE
                    && !Errands.unreachable(level, pos))) {
      if (Errands.unreachable(level, top)) {
        continue;
      }
      BlockPos pos = top.above();
      while (level.getBlockState(pos.above()).is(BlockTags.LOGS)) {
        pos = pos.above();
      }

      BlockPos log = null;
      for (int y = 0; y < 20; y++) {
        BlockPos probe = pos.below(y);
        if (level.getBlockState(probe).is(BlockTags.LOGS)) {
          log = probe;
        } else if (log != null) {
          break;
        }
      }
      if (log != null) {
        return log;
      }
    }
    return null;
  }

  private static BlockPos lowestLog(ServerLevel level, BlockPos log) {
    BlockPos pos = log;
    while (level.getBlockState(pos.below()).is(BlockTags.LOGS) && log.getY() - pos.getY() < 20) {
      pos = pos.below();
    }
    return pos;
  }

  private static boolean isTreeColumn(ServerLevel level, BlockPos top, BoundingBox exclude) {
    if (exclude.isInside(top) || !level.getBlockState(top).is(BlockTags.LOGS)) {
      return false;
    }
    for (int y = 1; y <= 6; y++) {
      BlockState state = level.getBlockState(top.above(y));
      if (state.is(BlockTags.LEAVES)) {
        return !state.hasProperty(LeavesBlock.PERSISTENT)
            || !state.getValue(LeavesBlock.PERSISTENT);
      }
      if (!state.is(BlockTags.LOGS) && !state.isAir()) {
        return false;
      }
    }
    return false;
  }

  static List<BlockPos> treeLogs(ServerLevel level, BlockPos base, int max) {
    List<BlockPos> logs = new ArrayList<>();
    Set<BlockPos> seen = new HashSet<>();
    ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    queue.add(base);
    seen.add(base);
    while (!queue.isEmpty() && logs.size() < max) {
      BlockPos pos = queue.poll();
      if (!level.getBlockState(pos).is(BlockTags.LOGS)) {
        continue;
      }
      logs.add(pos);
      for (BlockPos next : BlockPos.betweenClosed(pos.offset(-1, 0, -1), pos.offset(1, 1, 1))) {
        if (next.getY() >= base.getY() && seen.add(next.immutable())) {
          queue.add(next.immutable());
        }
      }
    }

    logs.sort((a, b) -> Integer.compare(b.getY(), a.getY()));
    return logs;
  }

  static @Nullable BlockPos stone(
      ServerLevel level, BlockPos center, int radius, AABB area, BoundingBox exclude, long salt) {
    int ground = ground(level, center).getY();
    List<BlockPos> found =
        ring(
            level,
            center,
            8,
            radius,
            2,
            salt,
            1,
            top ->
                inside(area, top)
                    && !exclude.isInside(top)
                    && Math.abs(top.getY() + 1 - ground) <= LEVEL_TOLERANCE
                    && !Errands.unreachable(level, top.above())
                    && level.getBlockState(top).is(BlockTags.BASE_STONE_OVERWORLD)
                    && level.getBlockState(top.above()).isAir());
    return found.isEmpty() ? null : found.get(0);
  }
}
