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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

final class Column {
  private static final long SALT_ROLL = 0x9B05688C2B3E6C1FL;
  private static final long SALT_COLUMN = 0x1F83D9ABFB41BD6BL;

  static final int MAX_BLOCK_LIGHT = 10;

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

  private final long rollTime;

  private @Nullable History history;

  private Column(
      ServerLevel level,
      LevelChunk chunk,
      ChunkTraces traces,
      BlockPos top,
      long now,
      boolean live,
      History.Params params,
      Sample sample,
      long rollTime,
      @Nullable History history) {
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
    this.climate = AtmosphereWeather.climate(level, top);
    this.rollTime = rollTime;
    this.history = history;
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
    BlockPos top = surface(level, chunk, localX, localZ);
    if (top == null) {
      return null;
    }
    Sample sample = AtmosphereWeather.sample(level, top, now);
    return new Column(level, chunk, traces, top, now, true, params, sample, rollTime, null);
  }

  static @Nullable Column catchUp(
      ServerLevel level,
      LevelChunk chunk,
      ChunkTraces traces,
      int localX,
      int localZ,
      long now,
      History history,
      Sample last) {
    BlockPos top = surface(level, chunk, localX, localZ);
    if (top == null) {
      return null;
    }
    return new Column(
        level, chunk, traces, top, now, false, history.params, last, history.growthFrom, history);
  }

  private static @Nullable BlockPos surface(
      ServerLevel level, LevelChunk chunk, int localX, int localZ) {
    ChunkPos pos = chunk.getPos();
    BlockPos top =
        level.getHeightmapPos(
            Heightmap.Types.MOTION_BLOCKING,
            new BlockPos(pos.getMinBlockX() + localX, 0, pos.getMinBlockZ() + localZ));
    return top.getY() <= level.getMinY() ? null : top;
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
      history = replay(level, top, now - lookback(), now, 0.0, now, params);
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
      long from,
      long now,
      double initialSnow,
      long growthFrom,
      History.Params params) {
    History history = new History(params, from, initialSnow, growthFrom);
    long step = Math.max(1L, AtmosphereConfig.weather().segmentTicks() / 2);
    Climate climate = AtmosphereWeather.climate(level, pos);
    boolean wet = climate.climateClass() == ClimateClass.WET;
    for (long time = from + step; time <= now; time += step) {
      Sample sample = AtmosphereWeather.sample(level, pos, time);
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
    return level.isLoaded(pos) ? level.getBlockState(pos) : Blocks.AIR.defaultBlockState();
  }

  boolean loaded(BlockPos pos) {
    return level.isLoaded(pos);
  }

  int topY(Direction direction) {
    int nx = x + direction.getStepX();
    int nz = z + direction.getStepZ();
    if (!level.hasChunk(nx >> 4, nz >> 4)) {
      return Integer.MIN_VALUE;
    }
    return level.getHeight(Heightmap.Types.MOTION_BLOCKING, nx, nz);
  }

  boolean dark(BlockPos pos) {
    return level.getBrightness(LightLayer.BLOCK, pos) < MAX_BLOCK_LIGHT;
  }

  void set(BlockPos pos, BlockState state) {
    level.setBlockAndUpdate(pos, state);
    chunk.markUnsaved();
  }

  boolean change(BlockPos pos, BlockState newState, Kind kind) {
    BlockState original = level.getBlockState(pos);
    if (traces.get(pos.asLong()) == null
        && traces.size() >= AtmosphereConfig.MAX_TRACES_PER_CHUNK.get()) {
      return false;
    }
    traces.put(pos.asLong(), new Trace(kind, original, now));
    level.setBlockAndUpdate(pos, newState);
    chunk.markUnsaved();
    return true;
  }

  void revert(BlockPos pos, Trace trace) {
    traces.remove(pos.asLong());
    level.setBlockAndUpdate(pos, trace.original());
    chunk.markUnsaved();
  }

  void forget(BlockPos pos) {
    traces.remove(pos.asLong());
    chunk.markUnsaved();
  }

  static ChunkTraces traces(LevelChunk chunk) {
    return chunk.getData(GuestAtmosphere.CHUNK_TRACES);
  }
}
