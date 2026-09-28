package com.vortexso.guest_wilds.lair;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.common.Tags;
import org.jspecify.annotations.Nullable;

public final class CaveScan {

  static final int MIN_CELLS = 40;

  static final int MIN_REACH = 6;

  static final int MAX_CELLS = 700;
  private static final int MAX_CANDIDATES = 3;
  private static final int BELOW = 40;
  private static final int ABOVE = 6;
  private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
  private static final int[][] NEIGHBOURS = {
    {1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1}, {0, 1, 0}, {0, -1, 0}
  };

  private CaveScan() {}

  public interface Terrain {

    int top(int x, int z);

    boolean open(int x, int y, int z);

    boolean solid(int x, int y, int z);

    boolean natural(int x, int y, int z);
  }

  public record Cave(int outside, int mouth, int den, int[] cells, int[] dist, float openness) {
    public int size() {
      return cells.length;
    }
  }

  public static int pack(int x, int y, int z) {
    return (x & 15) | (z & 15) << 4 | (y + 2048) << 8;
  }

  public static int x(int cell) {
    return cell & 15;
  }

  public static int z(int cell) {
    return cell >> 4 & 15;
  }

  public static int y(int cell) {
    return (cell >>> 8) - 2048;
  }

  public static BlockPos toBlock(int chunkX, int chunkZ, int cell) {
    return new BlockPos((chunkX << 4) + x(cell), y(cell), (chunkZ << 4) + z(cell));
  }

  public static @Nullable Cave find(Terrain terrain) {
    int[] top = new int[256];
    for (int x = 0; x < 16; x++) {
      for (int z = 0; z < 16; z++) {
        top[x << 4 | z] = terrain.top(x, z);
      }
    }

    List<int[]> candidates = new ArrayList<>();
    for (int x = 0; x < 16; x++) {
      for (int z = 0; z < 16; z++) {
        int y = top[x << 4 | z];
        for (int[] side : SIDES) {
          int nx = x + side[0];
          int nz = z + side[1];
          if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
            continue;
          }
          int roofTop = top[nx << 4 | nz];

          if (roofTop < y + 3
              || !terrain.open(nx, y, nz)
              || !terrain.open(nx, y + 1, nz)
              || !terrain.natural(nx, roofTop - 1, nz)) {
            continue;
          }
          candidates.add(new int[] {roofTop - y, pack(x, y, z), pack(nx, y, nz)});
        }
      }
    }
    candidates.sort(
        Comparator.<int[]>comparingInt(c -> -c[0])
            .thenComparingInt(c -> c[2])
            .thenComparingInt(c -> c[1]));
    IntOpenHashSet seen = new IntOpenHashSet();
    Cave best = null;
    int tried = 0;
    for (int[] candidate : candidates) {
      if (tried >= MAX_CANDIDATES) {
        break;
      }
      if (seen.contains(candidate[2])) {
        continue;
      }
      tried++;
      Cave cave = flood(terrain, top, candidate[1], candidate[2], seen);
      if (cave != null && (best == null || cave.size() > best.size())) {
        best = cave;
      }
    }
    return best;
  }

  public static @Nullable Cave measure(Terrain terrain, int outside, int mouth) {
    int[] top = new int[256];
    for (int x = 0; x < 16; x++) {
      for (int z = 0; z < 16; z++) {
        top[x << 4 | z] = terrain.top(x, z);
      }
    }
    return flood(terrain, top, outside, mouth, new IntOpenHashSet());
  }

  private static @Nullable Cave flood(
      Terrain terrain, int[] top, int outside, int mouth, IntOpenHashSet seen) {
    int mouthY = y(mouth);
    IntArrayList cells = new IntArrayList();
    IntArrayList dist = new IntArrayList();
    IntOpenHashSet visited = new IntOpenHashSet();
    cells.add(mouth);
    dist.add(0);
    visited.add(mouth);
    int den = mouth;
    int denDist = -1;
    long openFaces = 0;
    int processed = 0;
    for (int head = 0; head < cells.size() && cells.size() < MAX_CELLS; head++) {
      int cell = cells.getInt(head);
      int d = dist.getInt(head);
      int x = x(cell);
      int y = y(cell);
      int z = z(cell);
      processed++;
      if (d > denDist && terrain.solid(x, y - 1, z)) {
        den = cell;
        denDist = d;
      }
      for (int[] n : NEIGHBOURS) {
        int nx = x + n[0];
        int ny = y + n[1];
        int nz = z + n[2];
        if (nx < 0 || nx > 15 || nz < 0 || nz > 15) {
          continue;
        }
        if (ny < mouthY - BELOW || ny > mouthY + ABOVE || !terrain.open(nx, ny, nz)) {
          continue;
        }
        openFaces++;
        if (ny >= top[nx << 4 | nz] - 1) {
          continue;
        }
        int key = pack(nx, ny, nz);
        if (visited.add(key)) {
          cells.add(key);
          dist.add(d + 1);
        }
      }
    }
    seen.addAll(visited);
    if (cells.size() < MIN_CELLS || denDist < MIN_REACH) {
      return null;
    }

    double faces = openFaces / (6.0 * processed);
    float openness = (float) Math.clamp((faces - 0.45) / 0.4, 0.0, 1.0);
    return new Cave(outside, mouth, den, cells.toIntArray(), dist.toIntArray(), openness);
  }

  public static Terrain terrain(LevelChunk chunk) {
    int x0 = chunk.getPos().getMinBlockX();
    int z0 = chunk.getPos().getMinBlockZ();
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    return new Terrain() {
      @Override
      public int top(int x, int z) {
        return chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) + 1;
      }

      @Override
      public boolean open(int x, int y, int z) {
        BlockState state = state(x, y, z);
        return !state.blocksMotion() && state.getFluidState().isEmpty();
      }

      @Override
      public boolean solid(int x, int y, int z) {
        return state(x, y, z).blocksMotion();
      }

      @Override
      public boolean natural(int x, int y, int z) {
        return isNatural(state(x, y, z));
      }

      private BlockState state(int x, int y, int z) {
        return chunk.getBlockState(cursor.set(x0 + x, y, z0 + z));
      }
    };
  }

  public static boolean isNatural(BlockState state) {
    return state.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES)
        || state.is(BlockTags.LUSH_GROUND_REPLACEABLE)
        || state.is(Tags.Blocks.ORES);
  }
}
