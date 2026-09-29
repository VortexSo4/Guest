package com.vortexso.guest_settlements.village;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public final class VillageFarmScanner {
  private VillageFarmScanner() {}

  public static ScanResult scan(ServerLevel level, BoundingBox box) {
    return scan(level, box, List.of());
  }

  public static ScanResult scan(ServerLevel level, BoundingBox box, List<BoundingBox> skip) {
    int farmlandAmount = 0;

    int minX = Integer.MAX_VALUE;
    int minY = Integer.MAX_VALUE;
    int minZ = Integer.MAX_VALUE;
    int maxX = Integer.MIN_VALUE;
    int maxY = Integer.MIN_VALUE;
    int maxZ = Integer.MIN_VALUE;

    boolean complete = true;
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    int minChunkX = box.minX() >> 4;
    int maxChunkX = box.maxX() >> 4;
    int minChunkZ = box.minZ() >> 4;
    int maxChunkZ = box.maxZ() >> 4;

    for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
      for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
          complete = false;
          continue;
        }

        int x0 = Math.max(box.minX(), chunk.getPos().getMinBlockX());
        int x1 = Math.min(box.maxX(), chunk.getPos().getMaxBlockX());
        int z0 = Math.max(box.minZ(), chunk.getPos().getMinBlockZ());
        int z1 = Math.min(box.maxZ(), chunk.getPos().getMaxBlockZ());

        for (int x = x0; x <= x1; x++) {
          for (int z = z0; z <= z1; z++) {
            if (skipped(skip, x, z)) {
              continue;
            }
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15);

            if (y < box.minY() || y > box.maxY()) {
              continue;
            }

            pos.set(x, y, z);
            BlockState state = chunk.getBlockState(pos);
            if (!state.is(Blocks.FARMLAND)) {
              continue;
            }

            farmlandAmount++;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
          }
        }
      }
    }

    BoundingBox farmBox =
        farmlandAmount == 0 ? null : new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);

    return new ScanResult(farmlandAmount, farmBox, complete);
  }

  private static boolean skipped(List<BoundingBox> skip, int x, int z) {
    for (BoundingBox box : skip) {
      if (x >= box.minX() && x <= box.maxX() && z >= box.minZ() && z <= box.maxZ()) {
        return true;
      }
    }
    return false;
  }

  public record ScanResult(int farmlandAmount, BoundingBox farmBox, boolean complete) {}
}
