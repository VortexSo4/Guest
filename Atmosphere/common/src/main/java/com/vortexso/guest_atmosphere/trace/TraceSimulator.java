package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Trace;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Climate;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

public final class TraceSimulator {
  private static final long SALT_CHUNK = 0x510E527FADE682D1L;

  private static final int LAST_UPDATE_STEP = 200;

  private static final int STALE_SCAN_TICKS = 20;

  private static final int REGION_SHIFT = 2;
  private static final int REGION_HALF = 32;

  private static final int GRID_STEP = 4;
  private static final int GRID_MARGIN = 4;
  private static final int GRID_SIZE = (16 + 2 * GRID_MARGIN) / GRID_STEP + 1;

  private static final int SHORE_MARGIN = 8;
  private static final int SHORE_SIZE = 16 + 2 * SHORE_MARGIN;

  private static final int SEND_BUDGET_FACTOR = 3;

  private static final LongLinkedOpenHashSet PENDING_CATCH_UP = new LongLinkedOpenHashSet();

  private static long sendTick = Long.MIN_VALUE;

  private static long sendNanos;

  private TraceSimulator() {}

  public static void onLevelTick(ServerLevel level) {
    if (level.dimension() != Level.OVERWORLD || !AtmosphereConfig.TRACES_ENABLED.get()) {
      return;
    }
    long now = GuestTime.gameTime(level);
    long threshold = AtmosphereConfig.weather().segmentTicks();

    LongOpenHashSet chunks = new LongOpenHashSet();
    int radius = AtmosphereConfig.TRACE_CHUNK_RADIUS.get();
    int view =
        level.getGameTime() % STALE_SCAN_TICKS == 0
            ? Math.max(radius, level.getServer().getPlayerList().getViewDistance() + 1)
            : radius;
    for (ServerPlayer player : level.players()) {
      ChunkPos center = player.chunkPosition();
      for (int ring = 0; ring <= view; ring++) {
        for (int dx = -ring; dx <= ring; dx++) {
          for (int dz = -ring; dz <= ring; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
              continue;
            }
            long key = ChunkPos.pack(center.x() + dx, center.z() + dz);
            if (ring <= radius) {
              chunks.add(key);
            } else if (!PENDING_CATCH_UP.contains(key)) {
              LevelChunk chunk = loadedChunk(level, key);
              if (chunk != null && stale(Column.traces(chunk), now, threshold)) {
                PENDING_CATCH_UP.add(key);
              }
            }
          }
        }
      }
    }
    tick(level, chunks, now, level.getGameTime());

    long deadline =
        System.nanoTime() + AtmosphereConfig.CATCH_UP_MILLIS_PER_TICK.get() * 1_000_000L;
    while (!PENDING_CATCH_UP.isEmpty() && System.nanoTime() < deadline) {
      LevelChunk chunk = loadedChunk(level, PENDING_CATCH_UP.removeFirstLong());
      if (chunk != null && stale(Column.traces(chunk), now, threshold)) {
        catchUp(level, chunk, now, false);
      }
    }
  }

  public static void beforeSend(ServerLevel level, LevelChunk chunk) {
    if (level.dimension() != Level.OVERWORLD || !AtmosphereConfig.TRACES_ENABLED.get()) {
      return;
    }
    long now = GuestTime.gameTime(level);
    if (!stale(Column.traces(chunk), now, AtmosphereConfig.weather().segmentTicks())) {
      return;
    }
    long key = chunk.getPos().pack();
    if (level.getGameTime() != sendTick) {
      sendTick = level.getGameTime();
      sendNanos = 0L;
    }
    if (sendNanos
        > SEND_BUDGET_FACTOR * AtmosphereConfig.CATCH_UP_MILLIS_PER_TICK.get() * 1_000_000L) {
      PENDING_CATCH_UP.addAndMoveToFirst(key);
      return;
    }
    long start = System.nanoTime();
    PENDING_CATCH_UP.remove(key);
    catchUp(level, chunk, now, false);
    sendNanos += System.nanoTime() - start;
  }

  private static boolean stale(ChunkTraces traces, long now, long threshold) {
    long last = traces.lastUpdate();
    return last == ChunkTraces.NEVER || now - last > threshold || now < last;
  }

  static void tick(ServerLevel level, LongCollection chunks, long now, long rollTime) {
    double rate = AtmosphereConfig.COLUMN_VISIT_RATE.get();
    long threshold = AtmosphereConfig.weather().segmentTicks();
    History.Params params = History.Params.current();
    boolean scan = rollTime % STALE_SCAN_TICKS == 0;
    for (long key : chunks) {
      long hash = GuestHash.hash(level.getSeed() ^ SALT_CHUNK, key, rollTime);
      boolean visit = GuestHash.unit(hash) < rate;
      if (!visit && !scan) {
        continue;
      }
      LevelChunk chunk = tickingChunk(level, key);
      if (chunk == null) {
        continue;
      }
      ChunkTraces traces = Column.traces(chunk);
      long last = traces.lastUpdate();
      if (stale(traces, now, threshold)) {
        PENDING_CATCH_UP.addAndMoveToFirst(key);
        continue;
      }
      if (visit) {
        Column column =
            Column.live(
                level,
                chunk,
                traces,
                (int) (hash & 15),
                (int) ((hash >>> 4) & 15),
                now,
                rollTime,
                params);
        if (column != null) {
          review(column);
          update(column);
        }
      }
      if (now - last >= LAST_UPDATE_STEP) {
        traces.setLastUpdate(now);
        chunk.markUnsaved();
      }
    }
  }

  private static void update(Column column) {
    WinterTraces.update(column);
    GroundTraces.update(column);
    GrowthTraces.update(column);
    FireTraces.soot(column);
  }

  private static void review(Column column) {
    ChunkTraces traces = column.traces;
    if (traces.size() == 0) {
      return;
    }
    LongArrayList here = new LongArrayList();
    for (long key : traces.view().keySet()) {
      if (BlockPos.getX(key) == column.x && BlockPos.getZ(key) == column.z) {
        here.add(key);
      }
    }
    for (long key : here) {
      Trace trace = traces.get(key);
      if (trace != null) {
        GroundTraces.review(column, BlockPos.of(key), trace);
      }
    }
  }

  public static int catchUpAround(ServerLevel level, BlockPos center, int radius) {
    ChunkPos origin = ChunkPos.containing(center);
    int caughtUp = 0;
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        LevelChunk chunk = loadedChunk(level, ChunkPos.pack(origin.x() + dx, origin.z() + dz));
        if (chunk != null) {
          catchUp(level, chunk, GuestTime.gameTime(level), true);
          caughtUp++;
        }
      }
    }
    return caughtUp;
  }

  public static int simulate(ServerLevel level, BlockPos center, int radius, int rounds) {
    ChunkPos origin = ChunkPos.containing(center);
    LongArrayList chunks = new LongArrayList();
    for (int dx = -radius; dx <= radius; dx++) {
      for (int dz = -radius; dz <= radius; dz++) {
        chunks.add(ChunkPos.pack(origin.x() + dx, origin.z() + dz));
      }
    }
    long now = GuestTime.gameTime(level);
    long start = level.getGameTime();
    for (int round = 0; round < rounds; round++) {
      tick(level, chunks, now, start + round);
      while (!PENDING_CATCH_UP.isEmpty()) {
        LevelChunk chunk = loadedChunk(level, PENDING_CATCH_UP.removeFirstLong());
        if (chunk != null) {
          catchUp(level, chunk, now, false);
        }
      }
    }
    return chunks.size();
  }

  private static @Nullable LevelChunk tickingChunk(ServerLevel level, long key) {
    if (!level.shouldTickBlocksAt(key)) {
      return null;
    }
    return loadedChunk(level, key);
  }

  private static @Nullable LevelChunk loadedChunk(ServerLevel level, long key) {
    return level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
  }

  static void catchUp(ServerLevel level, LevelChunk chunk, long now, boolean fullWindow) {
    ChunkTraces traces = Column.traces(chunk);
    History.Params params = History.Params.current();
    long span = AtmosphereConfig.CATCH_UP_MAX_DAYS.get() * GuestTime.TICKS_PER_DAY;
    long last = traces.lastUpdate();
    long from =
        fullWindow || last == ChunkTraces.NEVER || last > now
            ? now - span
            : Math.max(last, now - span);

    BlockPos[] tops = new BlockPos[256];
    double snow = 0.0;
    boolean water = false;
    for (int localX = 0; localX < 16; localX++) {
      for (int localZ = 0; localZ < 16; localZ++) {
        BlockPos top = Column.surface(chunk, localX, localZ);
        tops[localX * 16 + localZ] = top;
        if (top != null) {
          snow += WinterTraces.layers(chunk.getBlockState(top));
          water |= chunk.getBlockState(top.below()).is(Blocks.WATER);
        }
      }
    }
    ChunkPos chunkPos = chunk.getPos();
    BlockPos center =
        new BlockPos(
            ((chunkPos.x() >> REGION_SHIFT) << (REGION_SHIFT + 4)) + REGION_HALF,
            level.getSeaLevel(),
            ((chunkPos.z() >> REGION_SHIFT) << (REGION_SHIFT + 4)) + REGION_HALF);
    long replayFrom = Math.min(from, now - Column.lookback());
    Histories histories = new Histories(level, center, replayFrom, now, snow / 256.0, from, params);
    double[] grid = snowGrid(level, chunk, tops, histories);
    int[] shore = water ? shore(level, chunk) : null;

    Column[] columns = new Column[256];
    for (int localX = 0; localX < 16; localX++) {
      for (int localZ = 0; localZ < 16; localZ++) {
        BlockPos top = tops[localX * 16 + localZ];
        if (top == null) {
          continue;
        }
        Climate climate = AtmosphereWeather.climate(level, top);
        Histories.Entry entry = histories.get(climate);
        columns[localX * 16 + localZ] =
            Column.catchUp(
                level,
                chunk,
                traces,
                top,
                climate,
                now,
                entry.history(),
                entry.sample(),
                blend(grid, localX, localZ),
                shore == null
                    ? Column.NO_SHORE
                    : shore[(localX + SHORE_MARGIN) * SHORE_SIZE + localZ + SHORE_MARGIN]);
      }
    }
    for (long key : new LongArrayList(traces.view().keySet())) {
      Column column = columns[(BlockPos.getX(key) & 15) * 16 + (BlockPos.getZ(key) & 15)];
      Trace trace = traces.get(key);
      if (column != null && trace != null) {
        GroundTraces.review(column, BlockPos.of(key), trace);
      }
    }
    for (Column column : columns) {
      if (column != null) {
        update(column);
      }
    }
    MineshaftAging.age(level, chunk, traces, now);
    traces.setLastUpdate(now);
    chunk.markUnsaved();
  }

  private static int height(LevelChunk chunk, BlockPos[] tops, int localX, int localZ) {
    BlockPos top = tops[Math.clamp(localX, 0, 15) * 16 + Math.clamp(localZ, 0, 15)];
    return top == null ? chunk.getMinY() : top.getY();
  }

  private static double[] snowGrid(
      ServerLevel level, LevelChunk chunk, BlockPos[] tops, Histories histories) {
    ChunkPos pos = chunk.getPos();
    double[] grid = new double[GRID_SIZE * GRID_SIZE];
    for (int i = 0; i < GRID_SIZE; i++) {
      for (int j = 0; j < GRID_SIZE; j++) {
        int localX = i * GRID_STEP - GRID_MARGIN;
        int localZ = j * GRID_STEP - GRID_MARGIN;
        BlockPos point =
            new BlockPos(
                pos.getMinBlockX() + localX,
                height(chunk, tops, localX, localZ),
                pos.getMinBlockZ() + localZ);
        grid[i * GRID_SIZE + j] =
            histories.get(AtmosphereWeather.climate(level, point)).history().snowDepth;
      }
    }
    return grid;
  }

  private static double blend(double[] grid, int localX, int localZ) {
    double gx = (localX + GRID_MARGIN) / (double) GRID_STEP;
    double gz = (localZ + GRID_MARGIN) / (double) GRID_STEP;
    int i = Math.min(GRID_SIZE - 2, (int) gx);
    int j = Math.min(GRID_SIZE - 2, (int) gz);
    double tx = gx - i;
    double tz = gz - j;
    double near = grid[i * GRID_SIZE + j] * (1.0 - tx) + grid[(i + 1) * GRID_SIZE + j] * tx;
    double far = grid[i * GRID_SIZE + j + 1] * (1.0 - tx) + grid[(i + 1) * GRID_SIZE + j + 1] * tx;
    return near * (1.0 - tz) + far * tz;
  }

  private static int[] shore(ServerLevel level, LevelChunk chunk) {
    ChunkPos pos = chunk.getPos();
    int far = SHORE_SIZE * 2;
    int[] distance = new int[SHORE_SIZE * SHORE_SIZE];
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    LevelChunk[] around = new LevelChunk[9];
    for (int i = 0; i < 9; i++) {
      around[i] = level.getChunkSource().getChunkNow(pos.x() + i / 3 - 1, pos.z() + i % 3 - 1);
    }
    for (int i = 0; i < SHORE_SIZE; i++) {
      for (int j = 0; j < SHORE_SIZE; j++) {
        int x = pos.getMinBlockX() + i - SHORE_MARGIN;
        int z = pos.getMinBlockZ() + j - SHORE_MARGIN;
        cursor.set(x, 0, z);
        LevelChunk at = around[((x >> 4) - pos.x() + 1) * 3 + (z >> 4) - pos.z() + 1];
        boolean land = false;
        if (at != null) {
          cursor.setY(at.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
          land = !WinterTraces.iceable(at.getBlockState(cursor));
        }
        distance[i * SHORE_SIZE + j] = land ? 0 : far;
      }
    }
    for (int i = 0; i < SHORE_SIZE; i++) {
      for (int j = 0; j < SHORE_SIZE; j++) {
        int d = distance[i * SHORE_SIZE + j];
        if (i > 0) {
          for (int dj = -1; dj <= 1; dj++) {
            if (j + dj >= 0 && j + dj < SHORE_SIZE) {
              d = Math.min(d, distance[(i - 1) * SHORE_SIZE + j + dj] + 1);
            }
          }
        }
        if (j > 0) {
          d = Math.min(d, distance[i * SHORE_SIZE + j - 1] + 1);
        }
        distance[i * SHORE_SIZE + j] = d;
      }
    }
    for (int i = SHORE_SIZE - 1; i >= 0; i--) {
      for (int j = SHORE_SIZE - 1; j >= 0; j--) {
        int d = distance[i * SHORE_SIZE + j];
        if (i < SHORE_SIZE - 1) {
          for (int dj = -1; dj <= 1; dj++) {
            if (j + dj >= 0 && j + dj < SHORE_SIZE) {
              d = Math.min(d, distance[(i + 1) * SHORE_SIZE + j + dj] + 1);
            }
          }
        }
        if (j < SHORE_SIZE - 1) {
          d = Math.min(d, distance[i * SHORE_SIZE + j + 1] + 1);
        }
        distance[i * SHORE_SIZE + j] = d;
      }
    }
    return distance;
  }

  private static final int SHARED_LIMIT = 512;

  private static final Map<Histories.Shared, Histories.Entry> SHARED = new HashMap<>();

  private static final class Histories {
    private final ServerLevel level;
    private final BlockPos center;
    private final long from;
    private final long now;
    private final double initialSnow;
    private final long growthFrom;
    private final History.Params params;
    private final Map<Climate, Entry> entries = new HashMap<>();

    Histories(
        ServerLevel level,
        BlockPos center,
        long from,
        long now,
        double initialSnow,
        long growthFrom,
        History.Params params) {
      this.level = level;
      this.center = center;
      this.from = from;
      this.now = now;
      this.initialSnow = initialSnow;
      this.growthFrom = growthFrom;
      this.params = params;
    }

    Entry get(Climate climate) {
      Climate key =
          new Climate(
              climate.climateClass(),
              Math.round(climate.temperature() * 40.0F) / 40.0F,
              climate.sandy(),
              climate.forested(),
              climate.open(),
              climate.dampness());
      Entry entry = entries.get(key);
      if (entry == null) {
        Shared shared = new Shared(center.asLong(), key, from, now, growthFrom, initialSnow);
        entry = SHARED.get(shared);
        if (entry == null) {
          entry =
              new Entry(
                  Column.replay(level, center, key, from, now, initialSnow, growthFrom, params),
                  AtmosphereWeather.sample(level, center, now, key));
          if (SHARED.size() >= SHARED_LIMIT) {
            SHARED.clear();
          }
          SHARED.put(shared, entry);
        }
        entries.put(key, entry);
      }
      return entry;
    }

    record Entry(History history, Sample sample) {}

    record Shared(
        long center, Climate climate, long from, long now, long growthFrom, double initialSnow) {}
  }
}
