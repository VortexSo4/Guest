package com.vortexso.guest_wilds.path;

import com.vortexso.guest_wilds.Ecology;
import com.vortexso.guest_wilds.WildsParameters;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.function.Consumer;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Sparse per-column wear, bucketed by chunk so that chunk loads and local steps stay local. Wear
 * decays lazily from the last touch time; nothing is ticked.
 */
public final class WearMap {
  public static final int NO_Y = Integer.MIN_VALUE;
  public static final int MAX_STAGE = 3;

  /** A worn column. Originals are only kept for blocks Wilds itself replaced. */
  public static final class Column {
    public final int x;
    public final int z;
    float wear;
    long time;
    public int stage;
    public int y = NO_Y;
    public @Nullable BlockState ground;
    public @Nullable BlockState plant;

    Column(int x, int z) {
      this.x = x;
      this.z = z;
    }

    public float storedWear() {
      return wear;
    }

    public long storedTime() {
      return time;
    }
  }

  private final Long2ObjectOpenHashMap<Column[]> chunks = new Long2ObjectOpenHashMap<>();
  private final double halfLifeDays;
  private final double[] thresholds;
  private final double revertFraction;
  private int size;

  public WearMap(WildsParameters parameters) {
    halfLifeDays = parameters.wearHalfLifeDays();
    thresholds =
        new double[] {0.0, parameters.trampleWear(), parameters.wornWear(), parameters.pathWear()};
    revertFraction = parameters.revertFraction();
  }

  /**
   * Adds wear that happened at {@code time}. Late reports (aggregate traffic dated in the past) are
   * decayed to the column's current time instead of rewinding it.
   */
  public Column add(int x, int z, double amount, long time) {
    Column column = getOrCreate(x, z);
    if (time >= column.time) {
      column.wear = (float) (wearAt(column, time) + amount);
      column.time = time;
    } else {
      column.wear += (float) Ecology.decay(amount, days(column.time - time), halfLifeDays);
    }
    return column;
  }

  public double wearAt(Column column, long now) {
    return Ecology.decay(column.wear, days(now - column.time), halfLifeDays);
  }

  public int targetStage(Column column, long now) {
    return targetStage(wearAt(column, now), column.stage, thresholds, revertFraction);
  }

  /** Hysteresis: climb as soon as a threshold is reached, fall only well below it. */
  static int targetStage(double wear, int current, double[] thresholds, double revertFraction) {
    int stage = current;
    while (stage < MAX_STAGE && wear >= thresholds[stage + 1]) {
      stage++;
    }
    if (stage == current) {
      while (stage > 0 && wear < thresholds[stage] * revertFraction) {
        stage--;
      }
    }
    return stage;
  }

  public @Nullable Column get(int x, int z) {
    Column[] chunk = chunks.get(chunkKey(x >> 4, z >> 4));
    return chunk == null ? null : chunk[index(x, z)];
  }

  public Column @Nullable [] chunk(long chunkKey) {
    return chunks.get(chunkKey);
  }

  public void remove(Column column) {
    long key = chunkKey(column.x >> 4, column.z >> 4);
    Column[] chunk = chunks.get(key);
    if (chunk == null || chunk[index(column.x, column.z)] != column) {
      return;
    }
    chunk[index(column.x, column.z)] = null;
    size--;
    for (Column other : chunk) {
      if (other != null) {
        return;
      }
    }
    chunks.remove(key);
  }

  /** Restores a saved column verbatim. */
  public Column restore(int x, int z, float wear, long time) {
    Column column = getOrCreate(x, z);
    column.wear = wear;
    column.time = time;
    return column;
  }

  public void forEach(Consumer<Column> action) {
    for (Column[] chunk : chunks.values()) {
      for (Column column : chunk) {
        if (column != null) {
          action.accept(column);
        }
      }
    }
  }

  public long[] chunkKeys() {
    return chunks.keySet().toLongArray();
  }

  public int size() {
    return size;
  }

  private Column getOrCreate(int x, int z) {
    Column[] chunk = chunks.computeIfAbsent(chunkKey(x >> 4, z >> 4), key -> new Column[256]);
    int index = index(x, z);
    Column column = chunk[index];
    if (column == null) {
      column = new Column(x, z);
      chunk[index] = column;
      size++;
    }
    return column;
  }

  /** Same packing as {@code ChunkPos.pack}; kept local so the hot path needs no Minecraft class. */
  public static long chunkKey(int chunkX, int chunkZ) {
    return (chunkX & 0xFFFFFFFFL) | ((chunkZ & 0xFFFFFFFFL) << 32);
  }

  private static int index(int x, int z) {
    return (x & 15) << 4 | (z & 15);
  }

  private static double days(long ticks) {
    return ticks / (double) Ecology.TICKS_PER_DAY;
  }
}
