package com.vortexso.guest_wilds.flora;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.path.PathWear;
import com.vortexso.guest_wilds.path.WearMap;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import org.jspecify.annotations.Nullable;

public final class Spread {
  public static final long NEVER = Long.MIN_VALUE;

  private static final long SALT = 0x7370726561644CL;
  private static final long GROW_SALT = 0x67726F77L;
  private static final int PERIOD_DAYS = 4;
  private static final int SLOTS = 3;
  private static final double CHANCE = 0.4;
  private static final double OPEN_LAND_CHANCE = 0.2;
  private static final int RADIUS = 8;
  private static final int SPACING = 2;
  private static final int FOREST_TRUNKS = 8;
  private static final int OPEN_LAND_TRUNKS = 4;
  private static final int GROW_PERIODS = 2;
  private static final int MAX_PERIODS = 32;
  private static final int VILLAGE_RADIUS = 32;
  private static final int PLAYER_CHUNKS = 8;
  private static final int BUDGET = 48;

  private static final LongLinkedOpenHashSet PENDING = new LongLinkedOpenHashSet();
  private static int sown;

  private Spread() {}

  public static long period(long time) {
    return Math.floorDiv(time, PERIOD_DAYS * GuestTime.TICKS_PER_DAY);
  }

  public static long days(long period) {
    return period * PERIOD_DAYS;
  }

  public static int sown() {
    return sown;
  }

  public static void onChunkLoad(ChunkPos pos) {
    PENDING.add(pos.pack());
  }

  public static void sweep(ServerLevel level) {
    long current = period(GuestTime.gameTime(level));
    for (ServerPlayer player : level.players()) {
      ChunkPos center = player.chunkPosition();
      for (int dx = -PLAYER_CHUNKS; dx <= PLAYER_CHUNKS; dx++) {
        for (int dz = -PLAYER_CHUNKS; dz <= PLAYER_CHUNKS; dz++) {
          LevelChunk chunk = level.getChunkSource().getChunkNow(center.x() + dx, center.z() + dz);
          if (chunk != null && GuestWilds.SPREAD.get(chunk) < current) {
            PENDING.add(chunk.getPos().pack());
          }
        }
      }
    }
    int budget = BUDGET;
    while (budget > 0 && !PENDING.isEmpty()) {
      budget -= catchUp(level, PENDING.removeFirstLong(), current);
    }
  }

  public static int catchUp(ServerLevel level, long key, long current) {
    LevelChunk chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
    if (chunk == null) {
      return 0;
    }
    long last = GuestWilds.SPREAD.get(chunk);
    if (last == NEVER || last > current) {
      GuestWilds.SPREAD.set(chunk, current);
      return 1;
    }
    int done = 0;
    for (long p = Math.max(last + 1, current - MAX_PERIODS + 1); p <= current; p++) {
      grow(level, chunk.getPos(), p - GROW_PERIODS);
      sow(level, chunk.getPos(), p);
      done++;
    }
    if (done > 0) {
      GuestWilds.SPREAD.set(chunk, current);
    }
    return Math.max(1, done);
  }

  private static long slot(ServerLevel level, ChunkPos chunk, long period, int slot) {
    return GuestHash.hash(level.getSeed() ^ SALT, chunk.pack(), period, slot);
  }

  private static void sow(ServerLevel level, ChunkPos chunk, long period) {
    if (GuestTime.season(period * PERIOD_DAYS * GuestTime.TICKS_PER_DAY) == Season.WINTER) {
      return;
    }
    for (int s = 0; s < SLOTS; s++) {
      long h = slot(level, chunk, period, s);
      double roll = GuestHash.unit(h);
      if (roll < CHANCE) {
        trySow(level, column(chunk, h), roll);
      }
    }
  }

  private static void grow(ServerLevel level, ChunkPos chunk, long period) {
    if (GuestTime.season(period * PERIOD_DAYS * GuestTime.TICKS_PER_DAY) == Season.WINTER) {
      return;
    }
    for (int s = 0; s < SLOTS; s++) {
      long h = slot(level, chunk, period, s);
      if (GuestHash.unit(h) >= CHANCE) {
        continue;
      }
      BlockPos top = column(chunk, h);
      if (!surroundingsLoaded(level, top)) {
        continue;
      }
      top =
          top.atY(
              level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, top.getX(), top.getZ()));
      RandomSource random = RandomSource.create(GuestHash.hash(h, GROW_SALT));
      for (int i = 0; i < 3; i++) {
        BlockState state = level.getBlockState(top);
        if (!(state.getBlock() instanceof SaplingBlock sapling)) {
          break;
        }
        sapling.advanceTree(level, top, state, random);
      }
    }
  }

  private static BlockPos column(ChunkPos chunk, long h) {
    return new BlockPos(
        chunk.getMinBlockX() + (int) ((h >>> 20) & 15),
        0,
        chunk.getMinBlockZ() + (int) ((h >>> 24) & 15));
  }

  private static boolean surroundingsLoaded(ServerLevel level, BlockPos pos) {
    int cx = pos.getX() >> 4;
    int cz = pos.getZ() >> 4;
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        if (level.getChunkSource().getChunkNow(cx + dx, cz + dz) == null) {
          return false;
        }
      }
    }
    return true;
  }

  private static void trySow(ServerLevel level, BlockPos column, double roll) {
    BlockPos top = seedbed(level, column.getX(), column.getZ());
    if (top == null) {
      return;
    }
    boolean forest = forest(level, top);
    if (!forest && roll >= OPEN_LAND_CHANCE) {
      return;
    }
    BlockState parent = null;
    int nearest = Integer.MAX_VALUE;
    int trunks = 0;
    for (int dx = -RADIUS; dx <= RADIUS; dx++) {
      for (int dz = -RADIUS; dz <= RADIUS; dz++) {
        int x = top.getX() + dx;
        int z = top.getZ() + dz;
        if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
          continue;
        }
        boolean close = Math.abs(dx) <= SPACING && Math.abs(dz) <= SPACING;
        BlockPos head =
            new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
        if (close && level.getBlockState(head).getBlock() instanceof SaplingBlock) {
          return;
        }
        BlockState log = level.getBlockState(head.below());
        if (!log.is(BlockTags.LOGS)) {
          continue;
        }
        if (close) {
          return;
        }
        if (!log.is(BlockTags.OVERWORLD_NATURAL_LOGS) || !wildCrown(level.getBlockState(head))) {
          continue;
        }
        trunks++;
        int distance = dx * dx + dz * dz;
        if (distance < nearest && Regrowth.SAPLINGS.containsKey(log.getBlock())) {
          nearest = distance;
          parent = log;
        }
      }
    }
    if (parent == null || trunks >= (forest ? FOREST_TRUNKS : OPEN_LAND_TRUNKS)) {
      return;
    }
    if (level
            .getPoiManager()
            .getCountInRange(
                holder -> holder.is(PoiTypes.HOME), top, VILLAGE_RADIUS, PoiManager.Occupancy.ANY)
        > 0) {
      return;
    }
    BlockState sapling = Regrowth.SAPLINGS.get(parent.getBlock()).defaultBlockState();
    boolean mega = sapling.is(Blocks.DARK_OAK_SAPLING) || sapling.is(Blocks.PALE_OAK_SAPLING);
    BlockPos[] spots =
        mega
            ? new BlockPos[] {top, top.east(), top.south(), top.south().east()}
            : new BlockPos[] {top};
    for (BlockPos spot : spots) {
      BlockPos bed = spot == top ? top : seedbed(level, spot.getX(), spot.getZ());
      if (bed == null || bed.getY() != top.getY() || !sapling.canSurvive(level, bed)) {
        return;
      }
    }
    for (BlockPos spot : spots) {
      level.setBlock(spot, sapling, GuestWilds.updateFlags(level, spot));
    }
    sown++;
  }

  private static @Nullable BlockPos seedbed(ServerLevel level, int x, int z) {
    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
      return null;
    }
    BlockPos top =
        new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    BlockState ground = level.getBlockState(top.below());
    BlockState above = level.getBlockState(top);
    if (!(ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.PODZOL) || ground.is(Blocks.DIRT))
        || !(above.isAir() || (above.canBeReplaced() && above.getFluidState().isEmpty()))
        || !level.canSeeSky(top)) {
      return null;
    }
    WearMap.Column worn = PathWear.get(level).map().get(x, z);
    return worn != null && worn.stage > 0 ? null : top;
  }

  private static boolean wildCrown(BlockState state) {
    return state.getBlock() instanceof LeavesBlock
        && state.hasProperty(LeavesBlock.PERSISTENT)
        && !state.getValue(LeavesBlock.PERSISTENT);
  }

  private static boolean forest(ServerLevel level, BlockPos pos) {
    var biome = level.getBiome(pos);
    return biome.is(BiomeTags.IS_FOREST)
        || biome.is(BiomeTags.IS_TAIGA)
        || biome.is(BiomeTags.IS_JUNGLE);
  }
}
