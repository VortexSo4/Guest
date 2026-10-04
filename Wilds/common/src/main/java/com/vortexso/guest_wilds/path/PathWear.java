package com.vortexso.guest_wilds.path;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.WildsParameters;
import com.vortexso.guest_wilds.lair.CaveScan;
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
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class PathWear extends SavedData {
  private static final WildsParameters P = WildsParameters.DEFAULT;
  private static final double PLAYER_WIDTH = 0.6;
  private static final double PRUNE_BELOW = 0.25;
  private static final int SWEEP_CHUNKS = 4;
  private static final long STONE_SALT = 0x726f6164L;
  private static final long TRAMPLE_SALT = 0x74726d70L;
  private static final double TRAMPLE_CHANCE = 0.05;
  private static final int PACKED_LAYERS = 2;
  private static final int[] SEARCH = {0, -1, 1, -2, 2, -3, 3, -4};

  private record SavedColumn(
      int x,
      int z,
      float wear,
      long time,
      int stage,
      int y,
      Optional<BlockState> ground,
      Optional<BlockState> plant,
      boolean road) {
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
                        BlockState.CODEC.optionalFieldOf("plant").forGetter(SavedColumn::plant),
                        Codec.BOOL.optionalFieldOf("road", false).forGetter(SavedColumn::road))
                    .apply(i, SavedColumn::new));
  }

  public static final Codec<PathWear> CODEC =
      SavedColumn.CODEC.listOf().xmap(PathWear::new, PathWear::save);

  public static final SavedDataType<PathWear> TYPE =
      new SavedDataType<>(
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "surface_wear"),
          PathWear::new,
          CODEC,
          null);

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
      column.road = saved.road();
    }
  }

  public static PathWear get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public WearMap map() {
    return map;
  }

  public static void sample(ServerLevel level, LivingEntity walker) {
    Vec3 now = walker.position();
    Vec3 last = LAST_POSITION.put(walker, now);
    if (last == null || !walker.onGround() || walker.isPassenger() || walker.isSpectator()) {
      return;
    }

    double moved = Math.min(3.0, Math.sqrt(sqr(now.x - last.x) + sqr(now.z - last.z)));
    if (moved < 0.2) {
      return;
    }
    BlockPos ground = walker.getOnPos();
    BlockState groundState = level.getBlockState(ground);
    if (groundState.getBlock() instanceof SnowLayerBlock) {
      ground = ground.below();
      groundState = level.getBlockState(ground);
    }
    if (!isWearable(groundState)) {
      return;
    }
    double size = walker.getBbWidth() / PLAYER_WIDTH;
    double weight = Math.clamp(size * size, 0.1, 3.0);
    WearMap.Column column =
        get(level).add(level, ground.getX(), ground.getY(), ground.getZ(), moved * weight);
    if (column.stage >= 1) {
      trample(level, ground.above(), walker);
    }
  }

  private static void trample(ServerLevel level, BlockPos pos, LivingEntity walker) {
    BlockState cover = level.getBlockState(pos);
    if (!(cover.getBlock() instanceof SnowLayerBlock)
        || cover.getValue(SnowLayerBlock.LAYERS) > PACKED_LAYERS
        || GuestHash.unit(
                GuestHash.hash(
                    level.getSeed() ^ TRAMPLE_SALT,
                    pos.asLong(),
                    level.getGameTime(),
                    walker.getId()))
            >= TRAMPLE_CHANCE) {
      return;
    }
    int layers = cover.getValue(SnowLayerBlock.LAYERS);
    level.setBlock(
        pos,
        layers > 1
            ? cover.setValue(SnowLayerBlock.LAYERS, layers - 1)
            : Blocks.AIR.defaultBlockState(),
        GuestWilds.updateFlags(level, pos));
  }

  public WearMap.Column add(ServerLevel level, int x, int y, int z, double amount) {
    long now = GuestTime.gameTime(level);
    WearMap.Column column = map.add(x, z, amount * WildsConfig.WEAR_MULTIPLIER.get(), now);
    if (column.stage == 0) {
      column.y = y;
    }
    reconcile(level, column, now);
    setDirty();
    return column;
  }

  public void addRoute(ServerLevel level, BlockPos from, BlockPos to, double amount, long time) {
    addRoute(level, from, to, List.of(), 1, amount, time, false);
  }

  public void addRoute(
      ServerLevel level,
      BlockPos from,
      BlockPos to,
      List<BlockPos> waypoints,
      int width,
      double amount,
      long time,
      boolean road) {
    if (amount <= 0.0) {
      return;
    }
    boolean planned = waypoints.size() >= 2;
    List<BlockPos> line =
        planned
            ? waypoints
            : Route.meander(
                level.getSeed(), from, to, P.routeMeanderBlocks(), P.routeMeanderWavelength());
    long now = GuestTime.gameTime(level);
    double scaled = amount * WildsConfig.WEAR_MULTIPLIER.get();
    for (Route.Cell cell : Route.strip(line, width, P.roadVergeShare())) {
      WearMap.Column column = map.add(cell.x(), cell.z(), scaled * cell.weight(), time);
      column.road |= road && cell.weight() >= 1.0;
      reconcile(level, column, now);
    }
    setDirty();
  }

  public void onChunkLoad(ServerLevel level, ChunkPos pos) {
    refreshChunk(level, pos.pack(), GuestTime.gameTime(level));
  }

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
    if (ground == null) {
      if (target < column.stage) {

        column.stage = target;
        column.ground = null;
        column.plant = null;
        setDirty();
      }
      return;
    }
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

  private static @Nullable BlockPos groundOf(ServerLevel level, WearMap.Column column) {
    int surface =
        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column.x, column.z) - 1;
    BlockPos found = groundNear(level, column, column.y != WearMap.NO_Y ? column.y : surface);
    if (found == null && column.stage == 0 && column.y != WearMap.NO_Y && column.y != surface) {
      found = groundNear(level, column, surface);
    }
    return found;
  }

  private static @Nullable BlockPos groundNear(ServerLevel level, WearMap.Column column, int y0) {
    for (int dy : SEARCH) {
      BlockPos pos = new BlockPos(column.x, y0 + dy, column.z);
      BlockState state = level.getBlockState(pos);
      boolean ours =
          (column.stage >= 2 && state.is(Blocks.COARSE_DIRT))
              || (column.stage >= 3 && state.is(Blocks.DIRT_PATH));
      if ((ours || isWearable(state)) && open(level.getBlockState(pos.above()))) {
        column.y = pos.getY();
        return pos;
      }
    }
    return null;
  }

  private static boolean open(BlockState state) {
    return state.isAir() || isPlant(state) || state.getBlock() instanceof SnowLayerBlock;
  }

  private static void raise(ServerLevel level, WearMap.Column column, BlockPos ground) {
    BlockState state = level.getBlockState(ground);
    BlockPos above = ground.above();
    switch (column.stage) {
      case 1 -> {
        BlockState top = level.getBlockState(above);
        if (isPlant(top)) {
          column.plant = top;
          level.setBlock(
              above, Blocks.AIR.defaultBlockState(), GuestWilds.updateFlags(level, above));
        }
        column.ground = isWearable(state) ? state : null;
      }
      case 2 -> {
        if (column.ground != null && state.is(column.ground.getBlock())) {
          level.setBlock(
              ground,
              Blocks.COARSE_DIRT.defaultBlockState(),
              GuestWilds.updateFlags(level, ground));
        } else {
          column.ground = null;
        }
      }
      case 3 -> {
        if (column.ground != null
            && state.is(Blocks.COARSE_DIRT)
            && level.getBlockState(above).isAir()) {
          level.setBlock(
              ground, Blocks.DIRT_PATH.defaultBlockState(), GuestWilds.updateFlags(level, ground));
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
          level.setBlock(
              ground,
              Blocks.COARSE_DIRT.defaultBlockState(),
              GuestWilds.updateFlags(level, ground));
        }
      }
      case 2 -> {
        if (column.ground != null && state.is(Blocks.COARSE_DIRT)) {
          BlockState regrown = Block.updateFromNeighbourShapes(column.ground, level, ground);
          if (column.road) {
            regrown = layStones(level, column, ground, regrown);
          }
          level.setBlock(ground, regrown, GuestWilds.updateFlags(level, ground));
        }
      }
      case 1 -> {
        BlockState plant = column.plant;
        if (plant != null && level.getBlockState(above).isAir() && plant.canSurvive(level, above)) {
          level.setBlock(above, plant, GuestWilds.updateFlags(level, above));
        }
        column.plant = null;
        column.ground = null;
        column.road = false;
        column.y = WearMap.NO_Y;
      }
      default -> {}
    }
  }

  private static BlockState layStones(
      ServerLevel level, WearMap.Column column, BlockPos ground, BlockState regrown) {
    double roll = GuestHash.unit(GuestHash.hash(level.getSeed(), column.x, column.z, STONE_SALT));
    BlockPos bed = ground.below();
    if (CaveScan.isNatural(level.getBlockState(bed))) {
      BlockState stone =
          roll < 0.5
              ? Blocks.COBBLESTONE.defaultBlockState()
              : roll < 0.75
                  ? Blocks.GRAVEL.defaultBlockState()
                  : Blocks.MOSSY_COBBLESTONE.defaultBlockState();
      level.setBlock(bed, stone, GuestWilds.updateFlags(level, bed));
    }
    return roll < 1.0 / 6.0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : regrown;
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
                    Optional.ofNullable(column.plant),
                    column.road)));
    return saved;
  }

  private static double sqr(double value) {
    return value * value;
  }
}
