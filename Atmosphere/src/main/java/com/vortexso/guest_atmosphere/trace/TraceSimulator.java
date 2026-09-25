package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Kind;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Trace;
import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Sample;
import com.vortexso.guest_atmosphere.weather.WeatherParameters;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.WeatherType;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoublePlantBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * Weather traces in blocks. Near players it works like vanilla precipitation ticking: a budgeted
 * random column per chunk gets the current weather applied (place or revert one step). A chunk that
 * was not watched for longer than one segment is instead brought up to date in one pass from the
 * deterministic weather history of the missed time (bounded), so an unwatched region ends up in the
 * same kind of state it would have reached while watched.
 *
 * <p>Only blocks Atmosphere changed are touched when reverting; every change stores the original
 * state. A trace whose block was changed by someone else is simply forgotten.
 */
@EventBusSubscriber(modid = GuestAtmosphere.MODID)
public final class TraceSimulator {
  private static final long SALT_CHUNK = 0x510E527FADE682D1L;
  private static final long SALT_ROLL = 0x9B05688C2B3E6C1FL;
  private static final long SALT_COLUMN = 0x1F83D9ABFB41BD6BL;

  /**
   * How often (ticks) a watched chunk refreshes its timestamp; far below the catch-up threshold.
   */
  private static final int LAST_UPDATE_STEP = 200;

  /** Vanilla freezes and snows only below block light 10; the same rule keeps lit areas clear. */
  private static final int MAX_BLOCK_LIGHT = 10;

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
    int radius = AtmosphereConfig.TRACE_CHUNK_RADIUS.get();
    LongOpenHashSet chunks = new LongOpenHashSet();
    for (ServerPlayer player : level.players()) {
      if (player.isSpectator()) {
        continue;
      }
      ChunkPos center = player.chunkPosition();
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          chunks.add(ChunkPos.pack(center.x() + dx, center.z() + dz));
        }
      }
    }

    double rate = AtmosphereConfig.COLUMN_VISIT_RATE.get();
    long threshold = AtmosphereConfig.weather().segmentTicks();
    for (long key : chunks) {
      LevelChunk chunk = tickingChunk(level, key);
      if (chunk == null) {
        continue;
      }
      ChunkTraces traces = chunk.getData(GuestAtmosphere.CHUNK_TRACES);
      long last = traces.lastUpdate();
      if (last == ChunkTraces.NEVER || now - last > threshold) {
        PENDING_CATCH_UP.add(key);
        continue;
      }
      long hash = GuestHash.hash(level.getSeed() ^ SALT_CHUNK, key, level.getGameTime());
      if (GuestHash.unit(hash) < rate) {
        visitColumn(level, chunk, traces, (int) (hash & 15), (int) ((hash >>> 4) & 15), now);
      }
      if (now - last >= LAST_UPDATE_STEP || now < last) {
        traces.setLastUpdate(now);
        chunk.markUnsaved();
      }
    }

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

  /** Lightning leaves a scorched patch that grows back after a few days. */
  @SubscribeEvent
  public static void onEntityJoin(EntityJoinLevelEvent event) {
    if (!(event.getEntity() instanceof LightningBolt bolt)
        || !(event.getLevel() instanceof ServerLevel level)
        || level.dimension() != Level.OVERWORLD
        || !AtmosphereConfig.TRACES_ENABLED.get()
        || !AtmosphereConfig.SCORCH_ENABLED.get()) {
      return;
    }
    long now = GuestTime.gameTime(level);
    BlockPos strike = bolt.blockPosition();
    for (int dx = -2; dx <= 2; dx++) {
      for (int dz = -2; dz <= 2; dz++) {
        int distance = dx * dx + dz * dz;
        double chance = distance == 0 ? 1.0 : distance <= 2 ? 0.7 : distance <= 5 ? 0.35 : 0.0;
        BlockPos column = strike.offset(dx, 0, dz);
        if (!level.isLoaded(column) || roll(level, column, 7) >= chance) {
          continue;
        }
        BlockPos ground =
            level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column).below();
        BlockState state = level.getBlockState(ground);
        if (Math.abs(ground.getY() - strike.getY()) <= 3
            && (state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.MOSS_BLOCK))) {
          change(
              level,
              level.getChunkAt(ground),
              ground,
              Blocks.COARSE_DIRT.defaultBlockState(),
              Kind.SCORCH,
              state,
              now);
        }
      }
    }
  }

  /** Replays the full catch-up window for loaded chunks around a position (debug command). */
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

  private static LevelChunk tickingChunk(ServerLevel level, long key) {
    if (!level.shouldTickBlocksAt(key)) {
      return null;
    }
    return level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
  }

  // ---------------------------------------------------------------- live

  private static void visitColumn(
      ServerLevel level, LevelChunk chunk, ChunkTraces traces, int localX, int localZ, long now) {
    ChunkPos chunkPos = chunk.getPos();
    BlockPos top =
        level.getHeightmapPos(
            Heightmap.Types.MOTION_BLOCKING,
            new BlockPos(chunkPos.getMinBlockX() + localX, 0, chunkPos.getMinBlockZ() + localZ));
    if (top.getY() <= level.getMinY()) {
      return;
    }
    BlockPos ground = top.below();
    Sample sample = AtmosphereWeather.sample(level, top, now);
    WeatherType type = sample.state().type();
    float intensity = sample.state().intensity();
    double snowTemperature = AtmosphereConfig.weather().snowTemperature();

    reviewLive(level, chunk, traces, top, sample, now);
    reviewLive(level, chunk, traces, ground, sample, now);

    if (type.isSnow()) {
      boolean storm = type != WeatherType.SNOWFALL;
      if (roll(level, top, 1) < intensity * (storm ? 1.0 : 0.6)) {
        raiseSnow(level, chunk, traces, top, snowCap(level, top, storm), now);
      }
    }
    if (sample.temperature() < AtmosphereConfig.FREEZE_TEMPERATURE.get()
        && roll(level, ground, 2) < AtmosphereConfig.ICE_CHANCE.get()) {
      tryIce(level, chunk, ground, now);
    }
    if (type.isRain()
        && type != WeatherType.DRIZZLE
        && sample.temperature() >= snowTemperature
        && roll(level, ground, 3) < AtmosphereConfig.MUD_CHANCE.get() * intensity) {
      tryMud(level, chunk, ground, now);
    }
    if (type == WeatherType.SANDSTORM
        && roll(level, top, 4) < AtmosphereConfig.SAND_CHANCE.get() * intensity) {
      trySand(level, chunk, top, now);
    }
    if (type == WeatherType.HEAT
        && roll(level, ground, 5) < AtmosphereConfig.DRY_CHANCE.get() * intensity) {
      tryDry(level, chunk, ground, now);
    }
  }

  private static void reviewLive(
      ServerLevel level,
      LevelChunk chunk,
      ChunkTraces traces,
      BlockPos pos,
      Sample sample,
      long now) {
    Trace trace = traces.get(pos.asLong());
    if (trace == null) {
      return;
    }
    BlockState state = level.getBlockState(pos);
    if (!intact(state, trace.kind())) {
      forget(chunk, traces, pos);
      return;
    }
    WeatherType type = sample.state().type();
    double roll = roll(level, pos, 6);
    long age = now - trace.placedAt();
    boolean thaw = sample.temperature() >= AtmosphereConfig.weather().snowTemperature();
    switch (trace.kind()) {
      case SNOW -> {
        double chance = AtmosphereConfig.MELT_CHANCE.get() * (type.isRain() ? 2.0 : 1.0);
        if (!type.isSnow() && thaw && roll < chance) {
          lowerSnow(
              level, chunk, traces, pos, state, trace, state.getValue(SnowLayerBlock.LAYERS) - 1);
        }
      }
      case MUD -> {
        if (!type.isRain()
            && roll < 0.5
            && driedFor(level, pos, now, days(AtmosphereConfig.MUD_DRYING_DAYS.get()))) {
          revert(level, chunk, traces, pos, trace);
        }
      }
      case ICE -> {
        if (thaw && roll < 0.5) {
          revert(level, chunk, traces, pos, trace);
        }
      }
      case SAND -> {
        if ((type.isRain() && roll < 0.5)
            || (type != WeatherType.SANDSTORM
                && age > days(AtmosphereConfig.SAND_LIFETIME_DAYS.get())
                && roll < 0.25)) {
          revert(level, chunk, traces, pos, trace);
        }
      }
      case DRY -> {
        if ((type.isRain() && roll < 0.5)
            || (type != WeatherType.HEAT
                && age > days(AtmosphereConfig.DRY_LIFETIME_DAYS.get())
                && roll < 0.25)) {
          revert(level, chunk, traces, pos, trace);
        }
      }
      case SCORCH -> {
        if (age > days(AtmosphereConfig.SCORCH_LIFETIME_DAYS.get()) && roll < 0.5) {
          revert(level, chunk, traces, pos, trace);
        }
      }
    }
  }

  /** True if no rain fell at the position during the last {@code ticks}. */
  private static boolean driedFor(ServerLevel level, BlockPos pos, long now, long ticks) {
    long step = Math.max(1L, AtmosphereConfig.weather().segmentTicks() / 2);
    for (long time = now - step; time >= now - ticks; time -= step) {
      if (AtmosphereWeather.sample(level, pos, time).state().type().isRain()) {
        return false;
      }
    }
    return true;
  }

  // ---------------------------------------------------------------- catch-up

  /**
   * Replays the missed weather of a chunk at its centre column with expected rates (visits per
   * column = columnVisitRate / 256 per tick, the same rate as live ticking), then sets every column
   * to the resulting state. Catch-up assumes one climate per chunk.
   */
  private static void catchUp(ServerLevel level, LevelChunk chunk, long now, boolean fullWindow) {
    ChunkTraces traces = chunk.getData(GuestAtmosphere.CHUNK_TRACES);
    WeatherParameters parameters = AtmosphereConfig.weather();
    long span = AtmosphereConfig.CATCH_UP_MAX_DAYS.get() * GuestTime.TICKS_PER_DAY;
    long from =
        fullWindow || traces.lastUpdate() == ChunkTraces.NEVER || traces.lastUpdate() > now
            ? now - span
            : Math.max(traces.lastUpdate(), now - span);
    ChunkPos chunkPos = chunk.getPos();
    BlockPos center =
        level.getHeightmapPos(
            Heightmap.Types.MOTION_BLOCKING,
            new BlockPos(chunkPos.getMiddleBlockX(), 0, chunkPos.getMiddleBlockZ()));
    // The replay continues from the snow already lying here, not from bare ground.
    int snowLayers = 0;
    int snowTraces = 0;
    for (Long2ObjectMap.Entry<Trace> entry : traces.view().long2ObjectEntrySet()) {
      BlockState state = level.getBlockState(BlockPos.of(entry.getLongKey()));
      if (entry.getValue().kind() == Kind.SNOW && state.is(Blocks.SNOW)) {
        snowLayers += state.getValue(SnowLayerBlock.LAYERS);
        snowTraces++;
      }
    }
    double initialSnow = snowTraces == 0 ? 0.0 : snowLayers / (double) snowTraces;
    History history = History.replay(level, center, from, now, initialSnow, parameters);

    for (long key : new LongArrayList(traces.view().keySet())) {
      BlockPos pos = BlockPos.of(key);
      Trace trace = traces.get(key);
      BlockState state = level.getBlockState(pos);
      if (!intact(state, trace.kind())) {
        forget(chunk, traces, pos);
        continue;
      }
      if (trace.kind() == Kind.SNOW) {
        // Only net melt lowers snow; otherwise the uneven live snow cover is kept as it is.
        if (history.snowDepth < history.initialSnow) {
          lowerSnow(level, chunk, traces, pos, state, trace, history.snowLayers(level, pos));
        }
      } else if (history.reverts(trace, now)) {
        revert(level, chunk, traces, pos, trace);
      }
    }

    if (history.placesAnything()) {
      for (int localX = 0; localX < 16; localX++) {
        for (int localZ = 0; localZ < 16; localZ++) {
          BlockPos top =
              level.getHeightmapPos(
                  Heightmap.Types.MOTION_BLOCKING,
                  new BlockPos(
                      chunkPos.getMinBlockX() + localX, 0, chunkPos.getMinBlockZ() + localZ));
          if (top.getY() <= level.getMinY()) {
            continue;
          }
          BlockPos ground = top.below();
          int layers = history.snowLayers(level, top);
          if (layers > 0) {
            BlockState state = level.getBlockState(top);
            int current = state.is(Blocks.SNOW) ? state.getValue(SnowLayerBlock.LAYERS) : 0;
            for (int i = current; i < layers; i++) {
              if (!raiseSnow(level, chunk, traces, top, layers, now)) {
                break;
              }
            }
          }
          if (coverage(level, ground, 2, history.iceHits)) {
            tryIce(level, chunk, ground, now);
          }
          if (coverage(level, ground, 3, history.mudHits)) {
            tryMud(level, chunk, ground, now);
          }
          if (coverage(level, top, 4, history.sandHits)) {
            trySand(level, chunk, top, now);
          }
          if (coverage(level, ground, 5, history.dryHits)) {
            tryDry(level, chunk, ground, now);
          }
        }
      }
    }

    traces.setLastUpdate(now);
    chunk.markUnsaved();
  }

  /** Expected hits -> share of eligible columns that carry the trace (Poisson: 1 - e^-hits). */
  private static boolean coverage(ServerLevel level, BlockPos pos, int salt, double hits) {
    return hits > 0.0
        && GuestHash.unit(
                GuestHash.hash(level.getSeed() ^ SALT_COLUMN, pos.getX(), pos.getZ(), salt))
            < 1.0 - Math.exp(-hits);
  }

  /** Aggregate of the missed weather at one column. */
  private static final class History {
    double snowDepth;
    double initialSnow;
    boolean stormSnow;
    double mudHits;
    double sandHits;
    double dryHits;
    double iceHits;
    long lastRain = ChunkTraces.NEVER;
    long lastSandstorm = ChunkTraces.NEVER;
    long lastHeat = ChunkTraces.NEVER;
    long from;

    static History replay(
        ServerLevel level,
        BlockPos pos,
        long from,
        long now,
        double initialSnow,
        WeatherParameters parameters) {
      History history = new History();
      history.from = from;
      history.snowDepth = initialSnow;
      history.initialSnow = initialSnow;
      history.stormSnow = initialSnow > AtmosphereConfig.SNOWFALL_MAX_LAYERS.get();
      long step = Math.max(1L, parameters.segmentTicks() / 2);
      double visits = AtmosphereConfig.COLUMN_VISIT_RATE.get() / 256.0 * step;
      double snowfallCap = AtmosphereConfig.SNOWFALL_MAX_LAYERS.get();
      double stormCap = AtmosphereConfig.STORM_MAX_LAYERS.get();
      double melt = AtmosphereConfig.MELT_CHANCE.get();
      double freeze = AtmosphereConfig.FREEZE_TEMPERATURE.get();
      long mudDrying = days(AtmosphereConfig.MUD_DRYING_DAYS.get());
      for (long time = from + step / 2; time < now; time += step) {
        Sample sample = AtmosphereWeather.sample(level, pos, time);
        WeatherType type = sample.state().type();
        double intensity = sample.state().intensity();
        if (type.isSnow()) {
          boolean storm = type != WeatherType.SNOWFALL;
          history.stormSnow |= storm;
          double cap =
              Math.max(storm ? stormCap : snowfallCap, Math.min(history.snowDepth, stormCap));
          history.snowDepth =
              Math.max(
                  history.snowDepth,
                  Math.min(cap, history.snowDepth + visits * intensity * (storm ? 1.0 : 0.6)));
        } else if (sample.temperature() >= parameters.snowTemperature()) {
          history.snowDepth =
              Math.max(0.0, history.snowDepth - visits * melt * (type.isRain() ? 2.0 : 1.0));
          if (history.snowDepth == 0.0) {
            history.stormSnow = false;
          }
        }
        if (type.isRain()) {
          history.lastRain = time;
          history.sandHits = 0.0;
          history.dryHits = 0.0;
          if (type != WeatherType.DRIZZLE) {
            history.mudHits += visits * AtmosphereConfig.MUD_CHANCE.get() * intensity;
          }
        } else if (history.lastRain != ChunkTraces.NEVER && time - history.lastRain > mudDrying) {
          history.mudHits = 0.0;
        }
        if (type == WeatherType.SANDSTORM) {
          history.lastSandstorm = time;
          history.sandHits += visits * AtmosphereConfig.SAND_CHANCE.get() * intensity;
        }
        if (type == WeatherType.HEAT) {
          history.lastHeat = time;
          history.dryHits += visits * AtmosphereConfig.DRY_CHANCE.get() * intensity;
        }
        if (sample.temperature() < freeze) {
          history.iceHits += visits * AtmosphereConfig.ICE_CHANCE.get();
        } else {
          history.iceHits = 0.0;
        }
      }
      return history;
    }

    boolean placesAnything() {
      return snowDepth >= 0.5 || mudHits > 0 || sandHits > 0 || dryHits > 0 || iceHits > 0;
    }

    /** Snow depth for one column: varies ±25% per column, drifts deeper against walls. */
    int snowLayers(ServerLevel level, BlockPos pos) {
      if (snowDepth < 0.5) {
        return 0;
      }
      double variation =
          0.75
              + 0.5
                  * GuestHash.unit(
                      GuestHash.hash(level.getSeed() ^ SALT_COLUMN, pos.getX(), pos.getZ()));
      int cap = snowCap(level, pos, stormSnow);
      return (int)
          Math.min(
              cap,
              Math.round(
                  snowDepth
                      * variation
                      * (cap > AtmosphereConfig.STORM_MAX_LAYERS.get() ? 1.5 : 1.0)));
    }

    boolean reverts(Trace trace, long now) {
      return switch (trace.kind()) {
        case SNOW -> false;
        case MUD -> {
          long wetUntil =
              Math.max(trace.placedAt(), lastRain == ChunkTraces.NEVER ? from : lastRain);
          yield now - wetUntil > days(AtmosphereConfig.MUD_DRYING_DAYS.get());
        }
        case ICE -> iceHits == 0.0;
        case SAND -> {
          long settled = Math.max(trace.placedAt(), lastSandstorm);
          yield lastRain > settled
              || now - settled > days(AtmosphereConfig.SAND_LIFETIME_DAYS.get());
        }
        case DRY -> {
          long dried = Math.max(trace.placedAt(), lastHeat);
          yield lastRain > dried || now - dried > days(AtmosphereConfig.DRY_LIFETIME_DAYS.get());
        }
        case SCORCH -> now - trace.placedAt() > days(AtmosphereConfig.SCORCH_LIFETIME_DAYS.get());
      };
    }
  }

  // ---------------------------------------------------------------- block changes

  private static int snowCap(ServerLevel level, BlockPos pos, boolean storm) {
    if (!storm) {
      return AtmosphereConfig.SNOWFALL_MAX_LAYERS.get();
    }
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = pos.relative(direction);
      if (level.isLoaded(side) && level.getBlockState(side).blocksMotion()) {
        return AtmosphereConfig.DRIFT_MAX_LAYERS.get();
      }
    }
    return AtmosphereConfig.STORM_MAX_LAYERS.get();
  }

  /** Adds one snow layer (new snow on air, or deepening existing snow). */
  private static boolean raiseSnow(
      ServerLevel level,
      LevelChunk chunk,
      ChunkTraces traces,
      BlockPos pos,
      int maxLayers,
      long now) {
    BlockState state = level.getBlockState(pos);
    if (level.getBrightness(LightLayer.BLOCK, pos) >= MAX_BLOCK_LIGHT) {
      return false;
    }
    if (state.isAir() || isSmallPlant(state)) {
      BlockState snow = Blocks.SNOW.defaultBlockState();
      return snow.canSurvive(level, pos) && change(level, chunk, pos, snow, Kind.SNOW, state, now);
    }
    if (!state.is(Blocks.SNOW) || state.getValue(SnowLayerBlock.LAYERS) >= maxLayers) {
      return false;
    }
    Trace trace = traces.get(pos.asLong());
    if (trace == null && !change(level, chunk, pos, state, Kind.SNOW, state, now)) {
      return false;
    }
    BlockState deeper =
        state.setValue(SnowLayerBlock.LAYERS, state.getValue(SnowLayerBlock.LAYERS) + 1);
    level.setBlockAndUpdate(pos, Block.pushEntitiesUp(state, deeper, level, pos));
    return true;
  }

  /**
   * Grass, ferns and flowers are buried by snow and come back on thaw because the trace keeps them
   * as the original state. Two-block plants are left alone: replacing one half would break them.
   */
  private static boolean isSmallPlant(BlockState state) {
    return state.canBeReplaced()
        && state.getFluidState().isEmpty()
        && !state.is(Blocks.SNOW)
        && !state.is(BlockTags.FIRE)
        && !(state.getBlock() instanceof DoublePlantBlock);
  }

  /** Melts our snow one layer at a time down to {@code targetLayers}, never below the original. */
  private static void lowerSnow(
      ServerLevel level,
      LevelChunk chunk,
      ChunkTraces traces,
      BlockPos pos,
      BlockState state,
      Trace trace,
      int targetLayers) {
    int layers = state.getValue(SnowLayerBlock.LAYERS);
    int originalLayers =
        trace.original().is(Blocks.SNOW) ? trace.original().getValue(SnowLayerBlock.LAYERS) : 0;
    int target = Math.max(originalLayers, Math.min(targetLayers, layers - 1));
    if (target >= layers) {
      return;
    }
    if (target <= originalLayers) {
      revert(level, chunk, traces, pos, trace);
    } else {
      level.setBlockAndUpdate(pos, state.setValue(SnowLayerBlock.LAYERS, target));
    }
  }

  private static void tryIce(ServerLevel level, LevelChunk chunk, BlockPos water, long now) {
    BlockState state = level.getBlockState(water);
    if (!state.is(Blocks.WATER)
        || !state.getFluidState().isSource()
        || !level.getBlockState(water.above()).isAir()
        || level.getBrightness(LightLayer.BLOCK, water) >= MAX_BLOCK_LIGHT
        // Where vanilla freezes water itself, the ice is vanilla's and permanent.
        || level.getBiome(water).value().coldEnoughToSnow(water, level.getSeaLevel())) {
      return;
    }
    boolean shore = false;
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = water.relative(direction);
      if (level.isLoaded(side) && !level.getFluidState(side).isSource()) {
        shore = true;
        break;
      }
    }
    if (shore) {
      change(level, chunk, water, Blocks.ICE.defaultBlockState(), Kind.ICE, state, now);
    }
  }

  private static void tryMud(ServerLevel level, LevelChunk chunk, BlockPos ground, long now) {
    BlockState state = level.getBlockState(ground);
    boolean bare =
        state.is(Blocks.DIRT) || state.is(Blocks.COARSE_DIRT) || state.is(Blocks.DIRT_PATH);
    if ((!bare && !state.is(Blocks.GRASS_BLOCK)) || !level.getBlockState(ground.above()).isAir()) {
      return;
    }
    if (!bare && !nextToWetGround(level, ground)) {
      return;
    }
    change(level, chunk, ground, Blocks.MUD.defaultBlockState(), Kind.MUD, state, now);
  }

  /**
   * Grass only turns to mud beside water, paths or other mud: puddles form where ground is worn.
   */
  private static boolean nextToWetGround(ServerLevel level, BlockPos pos) {
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = pos.relative(direction);
      if (!level.isLoaded(side)) {
        continue;
      }
      BlockState state = level.getBlockState(side);
      if (state.is(Blocks.WATER) || state.is(Blocks.DIRT_PATH) || state.is(Blocks.MUD)) {
        return true;
      }
    }
    return false;
  }

  /** Sand settles in cracks and against edges: an empty spot with at least two solid sides. */
  private static void trySand(ServerLevel level, LevelChunk chunk, BlockPos pos, long now) {
    BlockState state = level.getBlockState(pos);
    if (!state.isAir() || !level.getBlockState(pos.below()).blocksMotion()) {
      return;
    }
    int sides = 0;
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      BlockPos side = pos.relative(direction);
      if (level.isLoaded(side) && level.getBlockState(side).blocksMotion()) {
        sides++;
      }
    }
    if (sides >= 2) {
      Block sand = AtmosphereWeather.isRedSand(level, pos) ? Blocks.RED_SAND : Blocks.SAND;
      change(level, chunk, pos, sand.defaultBlockState(), Kind.SAND, state, now);
    }
  }

  private static void tryDry(ServerLevel level, LevelChunk chunk, BlockPos ground, long now) {
    BlockState state = level.getBlockState(ground);
    if ((state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT))
        && level.getBlockState(ground.above()).isAir()) {
      change(level, chunk, ground, Blocks.COARSE_DIRT.defaultBlockState(), Kind.DRY, state, now);
    }
  }

  /** Sets a block and records the trace; refuses when the chunk's trace budget is used up. */
  private static boolean change(
      ServerLevel level,
      LevelChunk chunk,
      BlockPos pos,
      BlockState newState,
      Kind kind,
      BlockState original,
      long now) {
    ChunkTraces traces = chunk.getData(GuestAtmosphere.CHUNK_TRACES);
    if (traces.get(pos.asLong()) == null
        && traces.size() >= AtmosphereConfig.MAX_TRACES_PER_CHUNK.get()) {
      return false;
    }
    traces.put(pos.asLong(), new Trace(kind, original, now));
    if (newState != original) {
      level.setBlockAndUpdate(pos, newState);
    }
    chunk.markUnsaved();
    return true;
  }

  private static void revert(
      ServerLevel level, LevelChunk chunk, ChunkTraces traces, BlockPos pos, Trace trace) {
    traces.remove(pos.asLong());
    level.setBlockAndUpdate(pos, trace.original());
    chunk.markUnsaved();
  }

  private static void forget(LevelChunk chunk, ChunkTraces traces, BlockPos pos) {
    traces.remove(pos.asLong());
    chunk.markUnsaved();
  }

  static boolean intact(BlockState state, Kind kind) {
    return switch (kind) {
      case SNOW -> state.is(Blocks.SNOW);
      case MUD -> state.is(Blocks.MUD);
      case ICE -> state.is(Blocks.ICE);
      case SAND -> state.is(Blocks.SAND) || state.is(Blocks.RED_SAND);
      case DRY, SCORCH -> state.is(Blocks.COARSE_DIRT);
    };
  }

  /**
   * Per-tick deterministic roll. Uses the level's game tick, not the Guest clock, so traces keep
   * evolving even when the day cycle is frozen.
   */
  private static double roll(ServerLevel level, BlockPos pos, int salt) {
    return GuestHash.unit(
        GuestHash.hash(level.getSeed() ^ SALT_ROLL, pos.asLong(), level.getGameTime(), salt));
  }

  private static long days(double days) {
    return (long) (days * GuestTime.TICKS_PER_DAY);
  }
}
