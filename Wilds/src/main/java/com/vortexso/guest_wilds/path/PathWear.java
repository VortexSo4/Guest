package com.vortexso.guest_wilds.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.WildsParameters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;

/**
 * Surface wear shared by every walker (principle 11): players, villagers, herds and mobs add wear
 * by concretely stepping; unobserved caravans and migrating herds add it as aggregate route
 * traffic. Blocks change only when a column crosses a stage, and only blocks Wilds itself replaced
 * are ever reverted.
 *
 * <p>Stages: 1 trampled (small plants and snow cover gone), 2 worn (coarse dirt), 3 path.
 */
public final class PathWear extends SavedData {
  private static final WildsParameters P = WildsParameters.DEFAULT;
  private static final double PLAYER_WIDTH = 0.6;
  private static final double PRUNE_BELOW = 0.25;
  private static final int SWEEP_CHUNKS = 4;

  private record SavedColumn(
      int x,
      int z,
      float wear,
      long time,
      int stage,
      int y,
      Optional<BlockState> ground,
      Optional<BlockState> plant) {
    static final Codec<SavedColumn> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.INT.fieldOf("x").forGetter(SavedColumn::x),
                        Codec.INT.fieldOf("z").forGetter(SavedColumn::z),
                        Codec.FLOAT.fieldOf("wear").forGetter(SavedColumn::wear),
                        Codec.LONG.fieldOf("time").forGetter(SavedColumn::time),
                        Codec.INT.optionalFieldOf("stage", 0).forGetter(SavedColumn::stage),
                        Codec.INT.optionalFieldOf("y", WearMap.NO_Y).forGetter(SavedColumn::y),
                        BlockState.CODEC.optionalFieldOf("ground").forGetter(SavedColumn::ground),
                        BlockState.CODEC.optionalFieldOf("plant").forGetter(SavedColumn::plant))
                    .apply(i, SavedColumn::new));
  }

  public static final Codec<PathWear> CODEC =
      SavedColumn.CODEC.listOf().xmap(PathWear::new, PathWear::save);

  public static final SavedDataType<PathWear> TYPE =
      new SavedDataType<>(
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "surface_wear"), PathWear::new, CODEC);

  /** Last sampled position per walker; transient by nature. */
  private static final Map<Entity, Vec3> LAST_POSITION = new WeakHashMap<>();

  private final WearMap map = new WearMap(P);
  private long[] sweepKeys = new long[0];
  private int sweepIndex;

  private PathWear() {}

  private PathWear(List<SavedColumn> columns) {
    for (SavedColumn saved : columns) {
      WearMap.Column column = map.restore(saved.x(), saved.z(), saved.wear(), saved.time());
      column.stage = saved.stage();
      column.y = saved.y();
      column.ground = saved.ground().orElse(null);
      column.plant = saved.plant().orElse(null);
    }
  }

  public static PathWear get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public WearMap map() {
    return map;
  }

  /** Samples one walker. Wear is proportional to distance walked since the last sample. */
  public static void sample(ServerLevel level, LivingEntity walker) {
    Vec3 now = walker.position();
    Vec3 last = LAST_POSITION.put(walker, now);
    if (last == null || !walker.onGround() || walker.isPassenger() || walker.isSpectator()) {
      return;
    }
    // Capped so teleports and knockback don't count as a trail.
    double moved = Math.min(3.0, Math.sqrt(sqr(now.x - last.x) + sqr(now.z - last.z)));
    if (moved < 0.2) {
      return;
    }
    BlockPos ground = walker.getOnPos();
    BlockState groundState = level.getBlockState(ground);
    BlockState feet = level.getBlockState(ground.above());
    if (!isWearable(groundState) && !isPlant(feet) && !feet.is(Blocks.SNOW)) {
      return;
    }
    double size = walker.getBbWidth() / PLAYER_WIDTH;
    double weight = Math.clamp(size * size, 0.1, 3.0);
    get(level).add(level, ground.getX(), ground.getY(), ground.getZ(), moved * weight);
  }

  public void add(ServerLevel level, int x, int y, int z, double amount) {
    long now = GuestTime.gameTime(level);
    WearMap.Column column = map.add(x, z, amount * WildsConfig.WEAR_MULTIPLIER.get(), now);
    if (column.stage == 0) {
      column.y = y;
    }
    reconcile(level, column, now);
    setDirty();
  }

  /**
   * Spreads {@code amount} wear along a meandering line between two points. The meander is derived
   * from the seed and the unordered endpoints, so the same route always wears the same columns.
   */
  public void addRoute(ServerLevel level, BlockPos from, BlockPos to, double amount, long time) {
    double dx = to.getX() - from.getX();
    double dz = to.getZ() - from.getZ();
    double length = Math.sqrt(dx * dx + dz * dz);
    if (length < 1.0 || amount <= 0.0) {
      return;
    }
    long a = Math.min(from.asLong(), to.asLong());
    long b = Math.max(from.asLong(), to.asLong());
    double phase = GuestHash.unit(GuestHash.hash(level.getSeed(), a, b)) * Math.PI * 2.0;
    double nx = -dz / length;
    double nz = dx / length;
    int steps = (int) Math.ceil(length);
    long now = GuestTime.gameTime(level);
    double scaled = amount * WildsConfig.WEAR_MULTIPLIER.get();
    int lastX = Integer.MIN_VALUE;
    int lastZ = Integer.MIN_VALUE;
    for (int i = 0; i <= steps; i++) {
      double t = i / (double) steps;
      double offset =
          P.routeMeanderBlocks()
              * Math.sin(phase + i * Math.PI * 2.0 / P.routeMeanderWavelength())
              * Math.sin(Math.PI * t);
      int x = (int) Math.round(from.getX() + dx * t + nx * offset);
      int z = (int) Math.round(from.getZ() + dz * t + nz * offset);
      if (x == lastX && z == lastZ) {
        continue;
      }
      lastX = x;
      lastZ = z;
      reconcile(level, map.add(x, z, scaled, time), now);
    }
    setDirty();
  }

  /** Brings a freshly loaded chunk up to date: unobserved traffic and regrowth land here. */
  public void onChunkLoad(ServerLevel level, ChunkPos pos) {
    refreshChunk(level, pos.pack(), GuestTime.gameTime(level));
  }

  /** Round-robin over a few worn chunks per call, so regrowth needs no per-column ticking. */
  public void sweep(ServerLevel level) {
    long now = GuestTime.gameTime(level);
    for (int i = 0; i < SWEEP_CHUNKS; i++) {
      if (sweepIndex >= sweepKeys.length) {
        sweepKeys = map.chunkKeys();
        sweepIndex = 0;
        if (sweepKeys.length == 0) {
          return;
        }
      }
      refreshChunk(level, sweepKeys[sweepIndex++], now);
    }
  }

  /** Debug catch-up after a clock jump; normally the sweep and chunk loads do this lazily. */
  public void refreshAll(ServerLevel level) {
    long now = GuestTime.gameTime(level);
    for (long key : map.chunkKeys()) {
      refreshChunk(level, key, now);
    }
  }

  private void refreshChunk(ServerLevel level, long chunkKey, long now) {
    WearMap.Column[] columns = map.chunk(chunkKey);
    if (columns == null) {
      return;
    }
    boolean loaded = isLoaded(level, columns);
    for (WearMap.Column column : columns.clone()) {
      if (column == null) {
        continue;
      }
      if (loaded) {
        reconcile(level, column, now);
      }
      if (column.stage == 0 && map.wearAt(column, now) < PRUNE_BELOW) {
        map.remove(column);
        setDirty();
      }
    }
  }

  private static boolean isLoaded(ServerLevel level, WearMap.Column[] columns) {
    for (WearMap.Column column : columns) {
      if (column != null) {
        return level.getChunkSource().getChunkNow(column.x >> 4, column.z >> 4) != null;
      }
    }
    return false;
  }

  private void reconcile(ServerLevel level, WearMap.Column column, long now) {
    int target = map.targetStage(column, now);
    if (target == column.stage
        || level.getChunkSource().getChunkNow(column.x >> 4, column.z >> 4) == null) {
      return;
    }
    BlockPos ground = groundOf(level, column);
    while (column.stage < target) {
      column.stage++;
      raise(level, column, ground);
    }
    while (column.stage > target) {
      lower(level, column, ground);
      column.stage--;
    }
    setDirty();
  }

  private static BlockPos groundOf(ServerLevel level, WearMap.Column column) {
    if (column.y == WearMap.NO_Y) {
      column.y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.x, column.z) - 1;
    }
    return new BlockPos(column.x, column.y, column.z);
  }

  private static void raise(ServerLevel level, WearMap.Column column, BlockPos ground) {
    BlockState state = level.getBlockState(ground);
    BlockPos above = ground.above();
    switch (column.stage) {
      case 1 -> {
        BlockState top = level.getBlockState(above);
        if (isPlant(top)) {
          column.plant = top;
          level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        } else if (top.is(Blocks.SNOW)) {
          // Trodden snow is not remembered: snowfall restores it on its own.
          level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        BlockState settled = level.getBlockState(ground);
        column.ground = isWearable(settled) ? settled : null;
      }
      case 2 -> {
        if (column.ground != null && state.is(column.ground.getBlock())) {
          level.setBlock(ground, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
        } else {
          column.ground = null;
        }
      }
      case 3 -> {
        if (column.ground != null
            && state.is(Blocks.COARSE_DIRT)
            && level.getBlockState(above).isAir()) {
          level.setBlock(ground, Blocks.DIRT_PATH.defaultBlockState(), Block.UPDATE_ALL);
        }
      }
      default -> {}
    }
  }

  private static void lower(ServerLevel level, WearMap.Column column, BlockPos ground) {
    BlockState state = level.getBlockState(ground);
    BlockPos above = ground.above();
    switch (column.stage) {
      case 3 -> {
        if (column.ground != null && state.is(Blocks.DIRT_PATH)) {
          level.setBlock(ground, Blocks.COARSE_DIRT.defaultBlockState(), Block.UPDATE_ALL);
        }
      }
      case 2 -> {
        if (column.ground != null && state.is(Blocks.COARSE_DIRT)) {
          level.setBlock(
              ground,
              Block.updateFromNeighbourShapes(column.ground, level, ground),
              Block.UPDATE_ALL);
        }
      }
      case 1 -> {
        BlockState plant = column.plant;
        if (plant != null && level.getBlockState(above).isAir() && plant.canSurvive(level, above)) {
          level.setBlock(above, plant, Block.UPDATE_ALL);
        }
        column.plant = null;
        column.ground = null;
        column.y = WearMap.NO_Y;
      }
      default -> {}
    }
  }

  static boolean isWearable(BlockState state) {
    return state.is(Blocks.GRASS_BLOCK)
        || state.is(Blocks.DIRT)
        || state.is(Blocks.COARSE_DIRT)
        || state.is(Blocks.PODZOL)
        || state.is(Blocks.MYCELIUM)
        || state.is(Blocks.ROOTED_DIRT);
  }

  static boolean isPlant(BlockState state) {
    return state.is(Blocks.SHORT_GRASS)
        || state.is(Blocks.FERN)
        || state.is(Blocks.SHORT_DRY_GRASS)
        || state.is(BlockTags.SMALL_FLOWERS);
  }

  private List<SavedColumn> save() {
    List<SavedColumn> saved = new ArrayList<>(map.size());
    map.forEach(
        column ->
            saved.add(
                new SavedColumn(
                    column.x,
                    column.z,
                    column.storedWear(),
                    column.storedTime(),
                    column.stage,
                    column.y,
                    Optional.ofNullable(column.ground),
                    Optional.ofNullable(column.plant))));
    return saved;
  }

  private static double sqr(double value) {
    return value * value;
  }
}
