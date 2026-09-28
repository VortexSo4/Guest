package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Trace;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jspecify.annotations.Nullable;

@EventBusSubscriber(modid = GuestAtmosphere.MODID)
public final class TraceSimulator {
  private static final long SALT_CHUNK = 0x510E527FADE682D1L;

  private static final int LAST_UPDATE_STEP = 200;

  private static final LongLinkedOpenHashSet PENDING_CATCH_UP = new LongLinkedOpenHashSet();

  private TraceSimulator() {}

  @SubscribeEvent
  public static void onLevelTick(LevelTickEvent.Post event) {
    if (!(event.getLevel() instanceof ServerLevel level)
        || level.dimension() != Level.OVERWORLD
        || !AtmosphereConfig.TRACES_ENABLED.get()) {
      return;
    }
    long now = GuestTime.gameTime(level);

    LongOpenHashSet chunks = new LongOpenHashSet();
    int radius = AtmosphereConfig.TRACE_CHUNK_RADIUS.get();
    for (ServerPlayer player : level.players()) {
      ChunkPos center = player.chunkPosition();
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          chunks.add(ChunkPos.pack(center.x() + dx, center.z() + dz));
        }
      }
    }
    tick(level, chunks, now, level.getGameTime());

    int budget = AtmosphereConfig.CATCH_UP_CHUNKS_PER_TICK.get();
    while (budget > 0 && !PENDING_CATCH_UP.isEmpty()) {
      long key = PENDING_CATCH_UP.removeFirstLong();
      LevelChunk chunk = tickingChunk(level, key);
      if (chunk != null) {
        catchUp(level, chunk, now, false);
        budget--;
      }
    }
  }

  static void tick(ServerLevel level, LongCollection chunks, long now, long rollTime) {
    double rate = AtmosphereConfig.COLUMN_VISIT_RATE.get();
    long threshold = AtmosphereConfig.weather().segmentTicks();
    History.Params params = History.Params.current();
    for (long key : chunks) {
      LevelChunk chunk = tickingChunk(level, key);
      if (chunk == null) {
        continue;
      }
      ChunkTraces traces = Column.traces(chunk);
      long last = traces.lastUpdate();
      if (last == ChunkTraces.NEVER || now - last > threshold) {
        PENDING_CATCH_UP.add(key);
        continue;
      }
      long hash = GuestHash.hash(level.getSeed() ^ SALT_CHUNK, key, rollTime);
      if (GuestHash.unit(hash) < rate) {
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
      if (now - last >= LAST_UPDATE_STEP || now < last) {
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
        LevelChunk chunk = tickingChunk(level, ChunkPos.pack(origin.x() + dx, origin.z() + dz));
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
        LevelChunk chunk = tickingChunk(level, PENDING_CATCH_UP.removeFirstLong());
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
    ChunkPos chunkPos = chunk.getPos();

    double snow = 0.0;
    for (int localX = 0; localX < 16; localX++) {
      for (int localZ = 0; localZ < 16; localZ++) {
        BlockPos top =
            level.getHeightmapPos(
                Heightmap.Types.MOTION_BLOCKING,
                new BlockPos(
                    chunkPos.getMinBlockX() + localX, 0, chunkPos.getMinBlockZ() + localZ));
        snow += WinterTraces.layers(level.getBlockState(top));
      }
    }
    BlockPos center =
        level.getHeightmapPos(
            Heightmap.Types.MOTION_BLOCKING,
            new BlockPos(chunkPos.getMiddleBlockX(), 0, chunkPos.getMiddleBlockZ()));

    long replayFrom = Math.min(from, now - Column.lookback());
    History history = Column.replay(level, center, replayFrom, now, snow / 256.0, from, params);
    Sample sample = AtmosphereWeather.sample(level, center, now);

    Column[] columns = new Column[256];
    for (int localX = 0; localX < 16; localX++) {
      for (int localZ = 0; localZ < 16; localZ++) {
        columns[localX * 16 + localZ] =
            Column.catchUp(level, chunk, traces, localX, localZ, now, history, sample);
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
}
