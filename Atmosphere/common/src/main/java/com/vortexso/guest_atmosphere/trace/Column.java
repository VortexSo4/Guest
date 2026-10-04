package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Kind;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Trace;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Climate;
import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.WeatherType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

final class Column {
  private static final long SALT_ROLL = 0x9B05688C2B3E6C1FL;
  private static final long SALT_COLUMN = 0x1F83D9ABFB41BD6BL;

  static final int MAX_BLOCK_LIGHT = 10;

  static final int NO_SHORE = Integer.MAX_VALUE;

  final ServerLevel level;
  final LevelChunk chunk;
  final ChunkTraces traces;
  final int x;
  final int z;

  final BlockPos top;

  final BlockPos ground;

  final long now;
  final boolean live;
  final History.Params params;

  final Sample sample;

  final Climate climate;

  final double snowDepth;

  final int shore;

  private final long rollTime;

  private @Nullable History history;

  private int highestSide = Integer.MIN_VALUE;

  private Column(
      ServerLevel level,
      LevelChunk chunk,
      ChunkTraces traces,
      BlockPos top,
      Climate climate,
      long now,
      boolean live,
      History.Params params,
      Sample sample,
      long rollTime,
      @Nullable History history,
      double snowDepth,
      int shore) {
    this.level = level;
    this.chunk = chunk;
    this.traces = traces;
    this.x = top.getX();
    this.z = top.getZ();
    this.top = top;
    this.ground = top.below();
    this.now = now;
    this.live = live;
    this.params = params;
    this.sample = sample;
    this.climate = climate;
    this.rollTime = rollTime;
    this.history = history;
    this.snowDepth = snowDepth;
    this.shore = shore;
  }

  static @Nullable Column live(
      ServerLevel level,
      LevelChunk chunk,
      ChunkTraces traces,
      int localX,
      int localZ,
      long now,
      long rollTime,
      History.Params params) {
    BlockPos top = surface(chunk, localX, localZ);
    if (top == null) {
      return null;
    }
    Climate climate = AtmosphereWeather.climate(level, top);
    Sample sample = AtmosphereWeather.sample(level, top, now, climate);
    return new Column(
        level, chunk, traces, top, climate, now, true, params, sample, rollTime, null, 0.0,
        NO_SHORE);
  }

  static Column catchUp(
      ServerLevel level,
      LevelChunk chunk,
      ChunkTraces traces,
      BlockPos top,
      Climate climate,
      long now,
      History history,
      Sample last,
      double snowDepth,
      int shore) {
    return new Column(
        level,
        chunk,
        traces,
        top,
        climate,
        now,
        false,
        history.params,
        last,
        history.growthFrom,
        history,
        snowDepth,
        shore);
  }

  static @Nullable BlockPos surface(LevelChunk chunk, int localX, int localZ) {
    int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, localX, localZ) + 1;
    if (y <= chunk.getMinY()) {
      return null;
    }
    ChunkPos pos = chunk.getPos();
    return new BlockPos(pos.getMinBlockX() + localX, y, pos.getMinBlockZ() + localZ);
  }

  WeatherType type() {
    return sample.state().type();
  }

  float intensity() {
    return sample.state().intensity();
  }

  float temperature() {
    return sample.temperature();
  }

  History history() {
    if (history == null) {
      history = replay(level, top, climate, now - lookback(), now, 0.0, now, params);
    }
    return history;
  }

  static long lookback() {
    double days =
        Math.max(
            AtmosphereConfig.MUD_DRYING_DAYS.get(),
            Math.max(
                AtmosphereConfig.SILT_GRASS_DAYS.get(), AtmosphereConfig.PERMAFROST_DAYS.get()));
    return (long) (Math.max(4.0, days + 0.5) * GuestTime.TICKS_PER_DAY);
  }

  static History replay(
      ServerLevel level,
      BlockPos pos,
      Climate climate,
      long from,
      long now,
      double initialSnow,
      long growthFrom,
      History.Params params) {
    History history = new History(params, from, initialSnow, growthFrom);
    long step = Math.max(1L, AtmosphereConfig.weather().segmentTicks() / 2);
    boolean wet = climate.climateClass() == ClimateClass.WET;
    for (long time = from + step; time <= now; time += step) {
      Sample sample = AtmosphereWeather.sample(level, pos, time, climate);
      history.add(
          time,
          step,
          sample.state().type(),
          sample.state().intensity(),
          sample.temperature(),
          sample.state().wind(),
          wet,
          climate.forested());
    }
    return history;
  }

  boolean chance(int salt, double probability) {
    if (probability <= 0.0) {
      return false;
    }
    if (probability >= 1.0) {
      return true;
    }
    long hash =
        live
            ? GuestHash.hash(level.getSeed() ^ SALT_ROLL, top.asLong(), rollTime, salt)
            : GuestHash.hash(level.getSeed() ^ SALT_COLUMN, x, z, salt);
    return GuestHash.unit(hash) < probability;
  }

  boolean growth(BlockPos pos, int salt, double probability) {
    if (probability <= 0.0) {
      return false;
    }
    long hash =
        live
            ? GuestHash.hash(level.getSeed() ^ SALT_ROLL, pos.asLong(), rollTime, salt)
            : GuestHash.hash(level.getSeed() ^ SALT_COLUMN, pos.asLong(), salt, rollTime);
    return GuestHash.unit(hash) < probability;
  }

  double fixed(BlockPos pos, int salt) {
    return GuestHash.unit(GuestHash.hash(level.getSeed() ^ SALT_COLUMN, pos.asLong(), salt));
  }

  double perVisit(double perDay) {
    return perDay / params.visitsPerDay();
  }

  BlockState state(BlockPos pos) {
    LevelChunk at = chunkAt(level, chunk, pos);
    return at == null ? Blocks.AIR.defaultBlockState() : at.getBlockState(pos);
  }

  boolean loaded(BlockPos pos) {
    return chunkAt(level, chunk, pos) != null;
  }

  static @Nullable LevelChunk chunkAt(ServerLevel level, LevelChunk chunk, BlockPos pos) {
    int chunkX = pos.getX() >> 4;
    int chunkZ = pos.getZ() >> 4;
    ChunkPos own = chunk.getPos();
    if (chunkX == own.x() && chunkZ == own.z()) {
      return chunk;
    }
    return level.getChunkSource().getChunkNow(chunkX, chunkZ);
  }

  int topY(Direction direction) {
    BlockPos side = new BlockPos(x + direction.getStepX(), 0, z + direction.getStepZ());
    LevelChunk at = chunkAt(level, chunk, side);
    if (at == null) {
      return Integer.MIN_VALUE;
    }
    return at.getHeight(Heightmap.Types.MOTION_BLOCKING, side.getX(), side.getZ()) + 1;
  }

  int highestSide() {
    if (highestSide == Integer.MIN_VALUE) {
      highestSide = Integer.MIN_VALUE + 1;
      for (Direction direction : Direction.Plane.HORIZONTAL) {
        highestSide = Math.max(highestSide, topY(direction));
      }
    }
    return highestSide;
  }

  boolean dark(BlockPos pos) {
    return level.getBrightness(LightLayer.BLOCK, pos) < MAX_BLOCK_LIGHT;
  }

  void set(BlockPos pos, BlockState state) {
    if (ChunkTraces.isFixed(level, pos)) {
      return;
    }
    write(pos, state);
  }

  boolean change(BlockPos pos, BlockState newState, Kind kind) {
    BlockState original = state(pos);
    if (ChunkTraces.isFixed(level, pos)) {
      return false;
    }
    if (traces.get(pos.asLong()) == null
        && traces.size() >= AtmosphereConfig.MAX_TRACES_PER_CHUNK.get()) {
      return false;
    }
    traces.put(pos.asLong(), new Trace(kind, original, now));
    write(pos, newState);
    chunk.markUnsaved();
    return true;
  }

  void revert(BlockPos pos, Trace trace) {
    traces.remove(pos.asLong());
    if (!ChunkTraces.isFixed(level, pos)) {
      write(pos, trace.original());
    }
    chunk.markUnsaved();
  }

  void forget(BlockPos pos) {
    traces.remove(pos.asLong());
    chunk.markUnsaved();
  }

  private void write(BlockPos pos, BlockState state) {
    LevelChunk at = chunkAt(level, chunk, pos);
    if (at != null) {
      write(level, at, pos, state);
    }
  }

  static void write(ServerLevel level, LevelChunk chunk, BlockPos pos, BlockState state) {
    if (chunk.setBlockState(pos, state, Block.UPDATE_CLIENTS) == null) {
      return;
    }
    level.getChunkSource().blockChanged(pos);
    for (Direction direction : Direction.values()) {
      BlockPos side = pos.relative(direction);
      LevelChunk at = chunkAt(level, chunk, side);
      if (at == null) {
        continue;
      }
      BlockState neighbour = at.getBlockState(side);
      BlockState shaped =
          neighbour.updateShape(
              level, level, side, direction.getOpposite(), pos, state, level.getRandom());
      if (shaped != neighbour && at.setBlockState(side, shaped, Block.UPDATE_CLIENTS) != null) {
        level.getChunkSource().blockChanged(side);
      }
    }
  }

  static ChunkTraces traces(LevelChunk chunk) {
    return GuestAtmosphere.CHUNK_TRACES.get(chunk);
  }
}
