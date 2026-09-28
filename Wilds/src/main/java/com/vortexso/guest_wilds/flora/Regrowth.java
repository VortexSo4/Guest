package com.vortexso.guest_wilds.flora;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_wilds.Ecology;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.WildsParameters;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

public final class Regrowth extends SavedData {
  private static final WildsParameters P = WildsParameters.DEFAULT;
  private static final long SALT = 0x72656772L;
  private static final int CANOPY_RADIUS = 3;
  private static final int CANOPY_HEIGHT = 10;
  private static final int SWEEP_CHUNKS = 2;

  private static final Map<Block, Block> SAPLINGS =
      Map.of(
          Blocks.OAK_LOG, Blocks.OAK_SAPLING,
          Blocks.BIRCH_LOG, Blocks.BIRCH_SAPLING,
          Blocks.SPRUCE_LOG, Blocks.SPRUCE_SAPLING,
          Blocks.JUNGLE_LOG, Blocks.JUNGLE_SAPLING,
          Blocks.ACACIA_LOG, Blocks.ACACIA_SAPLING,
          Blocks.DARK_OAK_LOG, Blocks.DARK_OAK_SAPLING,
          Blocks.CHERRY_LOG, Blocks.CHERRY_SAPLING,
          Blocks.PALE_OAK_LOG, Blocks.PALE_OAK_SAPLING,
          Blocks.MANGROVE_LOG, Blocks.MANGROVE_PROPAGULE);

  public record Site(BlockPos pos, BlockState sapling, long felled, int stage) {
    static final Codec<Site> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(Site::pos),
                        BlockState.CODEC.fieldOf("sapling").forGetter(Site::sapling),
                        Codec.LONG.fieldOf("felled").forGetter(Site::felled),
                        Codec.INT.fieldOf("stage").forGetter(Site::stage))
                    .apply(i, Site::new));
  }

  public static final Codec<Regrowth> CODEC =
      Site.CODEC.listOf().xmap(Regrowth::new, Regrowth::save);

  public static final SavedDataType<Regrowth> TYPE =
      new SavedDataType<>(
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "regrowth"), Regrowth::new, CODEC);

  private final Long2ObjectOpenHashMap<List<Site>> sites = new Long2ObjectOpenHashMap<>();
  private long[] sweepKeys = new long[0];
  private int sweepIndex;

  private Regrowth() {}

  private Regrowth(List<Site> saved) {
    saved.forEach(
        site -> sites.computeIfAbsent(ChunkPos.pack(site.pos()), k -> new ArrayList<>()).add(site));
  }

  public static Regrowth get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public List<Site> sites() {
    List<Site> all = new ArrayList<>();
    sites.values().forEach(all::addAll);
    return all;
  }

  public void onFelled(ServerLevel level, BlockPos pos, BlockState log) {
    if (!WildsConfig.FOREST_REGROWTH.get() || !log.is(BlockTags.OVERWORLD_NATURAL_LOGS)) {
      return;
    }
    Block sapling = SAPLINGS.get(log.getBlock());
    if (sapling == null
        || !level.getBlockState(pos.below()).is(BlockTags.SUBSTRATE_OVERWORLD)
        || !grownCanopy(level, pos)) {
      return;
    }
    sites
        .computeIfAbsent(ChunkPos.pack(pos), k -> new ArrayList<>())
        .add(new Site(pos.immutable(), sapling.defaultBlockState(), GuestTime.gameTime(level), 0));
    setDirty();
  }

  private static boolean grownCanopy(ServerLevel level, BlockPos pos) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dy = 1; dy <= CANOPY_HEIGHT; dy++) {
      for (int dx = -CANOPY_RADIUS; dx <= CANOPY_RADIUS; dx++) {
        for (int dz = -CANOPY_RADIUS; dz <= CANOPY_RADIUS; dz++) {
          cursor.setWithOffset(pos, dx, dy, dz);
          if (!GuestWilds.loaded(level, cursor)) {
            continue;
          }
          BlockState state = level.getBlockState(cursor);
          if (state.getBlock() instanceof LeavesBlock
              && state.hasProperty(LeavesBlock.PERSISTENT)
              && !state.getValue(LeavesBlock.PERSISTENT)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public void onChunkLoad(ServerLevel level, ChunkPos pos) {
    advance(level, pos.pack());
  }

  public void sweep(ServerLevel level) {
    for (int i = 0; i < SWEEP_CHUNKS; i++) {
      if (sweepIndex >= sweepKeys.length) {
        sweepKeys = sites.keySet().toLongArray();
        sweepIndex = 0;
        if (sweepKeys.length == 0) {
          return;
        }
      }
      advance(level, sweepKeys[sweepIndex++]);
    }
  }

  public void advanceAll(ServerLevel level) {
    for (long key : sites.keySet().toLongArray()) {
      advance(level, key);
    }
  }

  private void advance(ServerLevel level, long key) {
    List<Site> chunk = sites.get(key);
    if (chunk == null
        || level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key)) == null) {
      return;
    }
    long now = GuestTime.gameTime(level);
    List<Site> kept = new ArrayList<>();
    for (Site site : chunk) {
      Site next = step(level, site, now);
      if (next != null) {
        kept.add(next);
      }
    }
    if (kept.size() != chunk.size() || !kept.equals(chunk)) {
      if (kept.isEmpty()) {
        sites.remove(key);
      } else {
        sites.put(key, kept);
      }
      setDirty();
    }
  }

  private static @Nullable Site step(ServerLevel level, Site site, long now) {
    double days = (now - site.felled()) / (double) Ecology.TICKS_PER_DAY;

    double pace =
        0.7 + 0.6 * GuestHash.unit(GuestHash.hash(level.getSeed(), site.pos().asLong(), SALT));
    BlockPos pos = site.pos();
    BlockState here = level.getBlockState(pos);
    if (site.stage() == 0) {
      if (days < P.bushDays() * pace) {
        return site;
      }
      BlockState bush = Blocks.BUSH.defaultBlockState();
      boolean free = here.isAir() || here.canBeReplaced();
      if (!free || !bush.canSurvive(level, pos)) {
        return null;
      }
      level.setBlock(pos, bush, GuestWilds.updateFlags(level, pos));
      if (days < P.saplingDays() * pace) {
        return new Site(pos, site.sapling(), site.felled(), 1);
      }
      here = bush;
    }
    if (days < P.saplingDays() * pace) {
      return site;
    }
    if (here.is(Blocks.BUSH) && site.sapling().canSurvive(level, pos)) {
      level.setBlock(pos, site.sapling(), GuestWilds.updateFlags(level, pos));
    }
    return null;
  }

  private List<Site> save() {
    return sites();
  }
}
