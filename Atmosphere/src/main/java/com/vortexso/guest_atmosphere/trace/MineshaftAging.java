package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

final class MineshaftAging {
  private static final long SALT = 0x510E527FADE682D1L;

  private static final long RECHECK = 8L * GuestTime.TICKS_PER_DAY;

  private static final double ROTTEN = 0.6;
  private static final double COLLAPSE = 0.004;
  private static final double FLOODED = 0.5;
  private static final double OVERGROWN = 0.3;

  private MineshaftAging() {}

  static void age(ServerLevel level, LevelChunk chunk, ChunkTraces traces, long now) {
    if (!AtmosphereConfig.MINESHAFT_AGING.get()) {
      return;
    }
    long last = traces.mineshaftAged();
    if (last != ChunkTraces.NEVER && last <= now && now - last < RECHECK) {
      return;
    }
    traces.setMineshaftAged(now);
    chunk.markUnsaved();
    Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    boolean mineshaft = false;
    for (Structure structure : chunk.getAllReferences().keySet()) {
      mineshaft |= structures.wrapAsHolder(structure).is(StructureTags.MINESHAFT);
    }
    double decay =
        1.0 - Math.exp(-GuestTime.day(now) / AtmosphereConfig.MINESHAFT_DECAY_DAYS.get());
    if (!mineshaft || decay <= 0.0) {
      return;
    }
    ChunkPos pos = chunk.getPos();
    int changed = 0;
    for (StructureStart start :
        level
            .structureManager()
            .startsForStructure(
                pos, structure -> structures.wrapAsHolder(structure).is(StructureTags.MINESHAFT))) {
      int lowest = Integer.MAX_VALUE;
      for (StructurePiece piece : start.getPieces()) {
        lowest = Math.min(lowest, piece.getBoundingBox().minY());
      }
      for (StructurePiece piece : start.getPieces()) {
        BoundingBox box = piece.getBoundingBox();
        if (box.intersects(
            pos.getMinBlockX(), pos.getMinBlockZ(), pos.getMaxBlockX(), pos.getMaxBlockZ())) {
          changed += agePiece(level, chunk, box, box.minY() <= lowest + 1, decay);
        }
      }
    }
    GuestAtmosphere.LOGGER.debug(
        "Mineshaft in chunk {} aged to {}: {} blocks changed", pos, decay, changed);
  }

  private static int agePiece(
      ServerLevel level,
      LevelChunk levelChunk,
      BoundingBox box,
      boolean lowestLevel,
      double decay) {
    ChunkPos chunk = levelChunk.getPos();
    boolean flooded =
        lowestLevel
            && unit(level, new BlockPos(box.minX(), box.minY(), box.minZ()), 4) < FLOODED * decay;
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    int changed = 0;
    for (int x = Math.max(box.minX(), chunk.getMinBlockX());
        x <= Math.min(box.maxX(), chunk.getMaxBlockX());
        x++) {
      for (int z = Math.max(box.minZ(), chunk.getMinBlockZ());
          z <= Math.min(box.maxZ(), chunk.getMaxBlockZ());
          z++) {
        for (int y = box.minY(); y <= box.maxY(); y++) {
          pos.set(x, y, z);
          BlockState state = level.getBlockState(pos);
          if (state.is(BlockTags.WOODEN_FENCES) || state.is(BlockTags.PLANKS)) {
            if (unit(level, pos, 1) < ROTTEN * decay) {
              Column.write(level, levelChunk, pos.immutable(), Blocks.AIR.defaultBlockState());
              changed++;
            }
          } else if (state.isAir()) {
            if (flooded && y == box.minY()) {
              Column.write(level, levelChunk, pos.immutable(), Blocks.WATER.defaultBlockState());
              changed++;
            } else if (unit(level, pos, 2) < COLLAPSE * decay) {
              collapse(level, levelChunk, pos.immutable());
              changed++;
            } else if (level.getBrightness(LightLayer.SKY, pos) > 0
                && unit(level, pos, 3) < OVERGROWN * decay) {
              overgrow(level, levelChunk, pos.immutable());
              changed++;
            }
          }
        }
      }
    }
    return changed;
  }

  private static void collapse(ServerLevel level, LevelChunk chunk, BlockPos air) {
    if (!level.getBlockState(air.above()).is(BlockTags.BASE_STONE_OVERWORLD)) {
      return;
    }
    BlockPos floor = air;
    while (floor.getY() > level.getMinY() && level.getBlockState(floor.below()).isAir()) {
      floor = floor.below();
    }

    if (!level.getBlockState(floor.below()).is(Blocks.GRAVEL)) {
      Column.write(level, chunk, floor, Blocks.GRAVEL.defaultBlockState());
    }
  }

  private static void overgrow(ServerLevel level, LevelChunk chunk, BlockPos air) {
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos wall = air.relative(direction);
      if (level.isLoaded(wall)
          && level.getBlockState(wall).isFaceSturdy(level, wall, direction.getOpposite())) {
        Column.write(
            level,
            chunk,
            air,
            Blocks.VINE
                .defaultBlockState()
                .setValue(VineBlock.getPropertyForFace(direction), true));
        return;
      }
    }
  }

  private static double unit(ServerLevel level, BlockPos pos, int salt) {
    return GuestHash.unit(GuestHash.hash(level.getSeed() ^ SALT, pos.asLong(), salt));
  }
}
