package com.vortexso.guest_atmosphere.debug;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.trace.ChunkTraces;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_atmosphere.weather.WeatherModel;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_atmosphere.weather.WeatherParameters;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.debug.GuestDebug;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Copy of the live state around one player, taken on the server thread for the debug renderer.
 * Rendering reads only this snapshot, so it never touches server chunks from the render thread.
 */
public final class AtmosphereDebug {
  private static final int CELL_RADIUS = 1;
  private static final int CHUNK_RADIUS = 2;

  private static volatile Snapshot latest;

  private AtmosphereDebug() {}

  public static Snapshot latest() {
    return latest;
  }

  public static void capture(ServerLevel level, ServerPlayer player) {
    if (!GuestDebug.isEnabled(GuestAtmosphere.DEBUG_CHANNEL)) {
      latest = null;
      return;
    }
    long time = GuestTime.gameTime(level);
    WeatherParameters parameters = AtmosphereConfig.weather();
    double drift = WeatherModel.drift(time, parameters);
    int size = parameters.cellSize();
    long cellX = (long) Math.floor((player.getX() - drift) / size);
    long cellZ = (long) Math.floor(player.getZ() / size);

    List<Cell> cells = new ArrayList<>();
    for (long i = cellX - CELL_RADIUS; i <= cellX + CELL_RADIUS; i++) {
      for (long j = cellZ - CELL_RADIUS; j <= cellZ + CELL_RADIUS; j++) {
        int x = (int) Math.floor((i + 0.5) * size + drift);
        int z = (int) Math.floor((j + 0.5) * size);
        BlockPos pos = new BlockPos(x, player.getBlockY(), z);
        if (level.isLoaded(pos)) {
          pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING, pos);
        }
        cells.add(
            new Cell(
                i,
                j,
                pos,
                AtmosphereWeather.sample(level, pos, time),
                AtmosphereWeather.climate(level, pos).climateClass()));
      }
    }

    List<Mark> marks = new ArrayList<>();
    List<ChunkCount> counts = new ArrayList<>();
    ChunkPos origin = player.chunkPosition();
    for (int dx = -CHUNK_RADIUS; dx <= CHUNK_RADIUS; dx++) {
      for (int dz = -CHUNK_RADIUS; dz <= CHUNK_RADIUS; dz++) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(origin.x() + dx, origin.z() + dz);
        ChunkTraces traces =
            chunk == null ? null : chunk.getExistingDataOrNull(GuestAtmosphere.CHUNK_TRACES);
        if (traces == null) {
          continue;
        }
        int[] byKind = new int[ChunkTraces.Kind.values().length];
        for (Long2ObjectMap.Entry<ChunkTraces.Trace> entry : traces.view().long2ObjectEntrySet()) {
          byKind[entry.getValue().kind().ordinal()]++;
          marks.add(new Mark(BlockPos.of(entry.getLongKey()), entry.getValue().kind()));
        }
        counts.add(new ChunkCount(chunk.getPos(), byKind, time - traces.lastUpdate()));
      }
    }

    Sample here = AtmosphereWeather.sample(level, player.blockPosition(), time);
    latest =
        new Snapshot(time, drift, size, parameters.blendFraction(), here, cells, marks, counts);
  }

  public record Snapshot(
      long time,
      double drift,
      int cellSize,
      double blendFraction,
      Sample here,
      List<Cell> cells,
      List<Mark> traces,
      List<ChunkCount> chunks) {}

  public record Cell(
      long x, long z, BlockPos labelPos, Sample sample, WeatherModel.ClimateClass climate) {}

  public record Mark(BlockPos pos, ChunkTraces.Kind kind) {}

  public record ChunkCount(ChunkPos pos, int[] byKind, long sinceUpdate) {}
}
