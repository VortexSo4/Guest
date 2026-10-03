package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_atmosphere.GuestAtmosphere;
import com.vortexso.guest_atmosphere.block.AtmosphereBlocks;
import com.vortexso.guest_atmosphere.block.TraceBlocks;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Kind;
import com.vortexso.guest_atmosphere.trace.ChunkTraces.Trace;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayDeque;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jspecify.annotations.Nullable;

@EventBusSubscriber(modid = GuestAtmosphere.MODID)
public final class FireTraces {
  private static final long SALT = 0x3C6EF372FE94F82BL;

  private static final int STRIKE_REACH = 2;

  private static final int TREE_RADIUS = 10;

  private FireTraces() {}

  @SubscribeEvent
  public static void onEntityJoin(EntityJoinLevelEvent event) {
    if (!(event.getEntity() instanceof LightningBolt bolt)
        || !(event.getLevel() instanceof ServerLevel level)
        || level.dimension() != Level.OVERWORLD
        || !AtmosphereConfig.TRACES_ENABLED.get()) {
      return;
    }
    long now = GuestTime.gameTime(level);
    BlockPos strike = bolt.blockPosition();
    BlockPos trunk = AtmosphereConfig.LIGHTNING_BURNS_TREES.get() ? findTrunk(level, strike) : null;
    if (AtmosphereConfig.SCORCH_ENABLED.get()) {

      scorch(level, trunk != null ? foot(level, trunk) : strike, now);
    }
    if (trunk != null) {
      burnTree(level, trunk, now);
    }
    if (AtmosphereConfig.FULGURITE.get()) {
      vitrify(level, strike);
    }
  }

  private static void scorch(ServerLevel level, BlockPos strike, long now) {
    for (int dx = -2; dx <= 2; dx++) {
      for (int dz = -2; dz <= 2; dz++) {
        int distance = dx * dx + dz * dz;
        double chance = distance == 0 ? 1.0 : distance <= 2 ? 0.7 : distance <= 5 ? 0.35 : 0.0;
        BlockPos column = strike.offset(dx, 0, dz);
        if (!level.isLoaded(column) || unit(level, column, 7) >= chance) {
          continue;
        }
        BlockPos ground =
            level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, column).below();
        BlockState state = level.getBlockState(ground);
        if (Math.abs(ground.getY() - strike.getY()) <= 6
            && (state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.MOSS_BLOCK))
            && record(level, ground, Blocks.COARSE_DIRT.defaultBlockState(), Kind.SCORCH, now)) {
          ash(level, ground.above(), 1);
        }
      }
    }
  }

  static void burnTree(ServerLevel level, BlockPos trunk, long now) {
    int max = AtmosphereConfig.TREE_BURN_MAX_BLOCKS.get();
    ArrayDeque<BlockPos> queue = new ArrayDeque<>();
    LongOpenHashSet seen = new LongOpenHashSet();
    queue.add(trunk);
    seen.add(trunk.asLong());
    BlockPos base = trunk;
    BlockState baseLog = level.getBlockState(trunk);
    int burnt = 0;
    while (!queue.isEmpty() && burnt < max) {
      BlockPos pos = queue.poll();
      BlockState state = level.getBlockState(pos);
      boolean log = state.is(BlockTags.LOGS_THAT_BURN);
      if (ChunkTraces.isFixed(level, pos)) {
        continue;
      }
      if (log) {
        if (pos.getY() < base.getY()) {
          base = pos;
          baseLog = state;
        }
        level.setBlockAndUpdate(pos, charredLog(level, pos, state));
      } else {
        level.setBlockAndUpdate(pos, AtmosphereBlocks.BURNT_LEAVES.get().defaultBlockState());
      }
      if (burnt++ % 3 == 0) {
        level.sendParticles(
            ParticleTypes.LARGE_SMOKE,
            pos.getX() + 0.5,
            pos.getY() + 0.5,
            pos.getZ() + 0.5,
            2,
            0.3,
            0.3,
            0.3,
            0.02);
        level.sendParticles(
            ParticleTypes.FLAME,
            pos.getX() + 0.5,
            pos.getY() + 0.5,
            pos.getZ() + 0.5,
            3,
            0.4,
            0.4,
            0.4,
            0.01);
      }
      int leafDistance = log ? 0 : distance(state);
      for (int dx = -1; dx <= 1; dx++) {
        for (int dy = -1; dy <= 1; dy++) {
          for (int dz = -1; dz <= 1; dz++) {
            BlockPos next = pos.offset(dx, dy, dz);
            if (seen.contains(next.asLong())
                || Math.abs(next.getX() - trunk.getX()) > TREE_RADIUS
                || Math.abs(next.getZ() - trunk.getZ()) > TREE_RADIUS
                || !level.isLoaded(next)) {
              continue;
            }
            BlockState nextState = level.getBlockState(next);
            boolean nextLog = nextState.is(BlockTags.LOGS_THAT_BURN);

            boolean follow =
                log
                    ? nextLog || naturalLeaves(nextState)
                    : naturalLeaves(nextState) && distance(nextState) >= leafDistance;
            if (follow) {
              seen.add(next.asLong());
              queue.add(next);
            }
          }
        }
      }
    }
    level.playSound(null, trunk, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 2.0F, 0.7F);
    level.playSound(null, trunk, SoundEvents.FIRE_AMBIENT, SoundSource.BLOCKS, 2.0F, 1.0F);

    record(level, base, level.getBlockState(base), Kind.BURN, baseLog, now);
  }

  private static BlockPos foot(ServerLevel level, BlockPos log) {
    BlockPos pos = log;
    while (pos.getY() > level.getMinY()
        && level.getBlockState(pos.below()).is(BlockTags.LOGS_THAT_BURN)) {
      pos = pos.below();
    }
    return pos;
  }

  private static @Nullable BlockPos findTrunk(ServerLevel level, BlockPos strike) {
    BlockPos leaf = null;
    for (int dy = 1; dy >= -3; dy--) {
      for (BlockPos pos :
          BlockPos.betweenClosed(
              strike.offset(-STRIKE_REACH, dy, -STRIKE_REACH),
              strike.offset(STRIKE_REACH, dy, STRIKE_REACH))) {
        if (!level.isLoaded(pos)) {
          continue;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.LOGS_THAT_BURN)) {
          return pos.immutable();
        }
        if (leaf == null && naturalLeaves(state)) {
          leaf = pos.immutable();
        }
      }
    }
    for (int steps = 0; leaf != null && steps < 8; steps++) {
      BlockPos next = null;
      int best = distance(level.getBlockState(leaf));
      for (Direction direction : Direction.values()) {
        BlockPos side = leaf.relative(direction);
        if (!level.isLoaded(side)) {
          continue;
        }
        BlockState state = level.getBlockState(side);
        if (state.is(BlockTags.LOGS_THAT_BURN)) {
          return side;
        }
        if (naturalLeaves(state) && distance(state) < best) {
          best = distance(state);
          next = side;
        }
      }
      leaf = next;
    }
    return null;
  }

  private static boolean naturalLeaves(BlockState state) {
    return state.is(BlockTags.LEAVES)
        && !(state.hasProperty(LeavesBlock.PERSISTENT) && state.getValue(LeavesBlock.PERSISTENT));
  }

  private static int distance(BlockState state) {
    return state.hasProperty(LeavesBlock.DISTANCE) ? state.getValue(LeavesBlock.DISTANCE) : 7;
  }

  private static BlockState charredLog(ServerLevel level, BlockPos pos, BlockState log) {
    BlockState charred = AtmosphereBlocks.CHARRED_LOG.get().defaultBlockState();
    if (log.hasProperty(RotatedPillarBlock.AXIS)) {
      charred = charred.setValue(RotatedPillarBlock.AXIS, log.getValue(RotatedPillarBlock.AXIS));
    }
    return charred.setValue(TraceBlocks.CharredLogBlock.SMOLDERING, unit(level, pos, 11) < 0.5);
  }

  static void knockDownLeaves(Column c) {
    int fallen = 0;
    BlockPos floor = null;
    BlockPos pos = c.ground;
    for (int i = 0; i < 48; i++, pos = pos.below()) {
      BlockState state = c.state(pos);
      if (state.is(AtmosphereBlocks.BURNT_LEAVES.get())) {
        c.set(pos, Blocks.AIR.defaultBlockState());
        fallen++;
      } else if (!state.isAir() && !state.is(AtmosphereBlocks.CHARRED_LOG.get())) {
        floor = pos.above();
        break;
      }
    }
    if (floor != null && fallen > 0 && c.state(floor).isAir()) {
      ash(c.level, floor, Math.min(3, 1 + fallen / 4));
    }
  }

  static void regrow(Column c, BlockPos base, Trace trace) {
    Block sapling = sapling(trace.original().getBlock());
    int planted = 0;
    for (int i = 0; i < 16 && planted < 5; i++) {
      double angle = c.fixed(base, 60 + i) * Math.PI * 2.0;
      double radius = 2.0 + c.fixed(base, 80 + i) * 4.0;
      int x = base.getX() + (int) Math.round(Math.cos(angle) * radius);
      int z = base.getZ() + (int) Math.round(Math.sin(angle) * radius);
      if (!c.loaded(new BlockPos(x, 0, z))) {
        continue;
      }
      BlockPos top =
          c.level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
      BlockState surface = c.state(top);
      if (!(surface.isAir() || surface.is(AtmosphereBlocks.ASH.get()))) {
        continue;
      }
      Block plant =
          planted < 2 ? sapling : c.fixed(base, 100 + i) < 0.5 ? Blocks.BUSH : Blocks.SHORT_GRASS;
      BlockState state = plant.defaultBlockState();
      if (state.canSurvive(c.level, top)) {
        c.set(top, state);
        planted++;
      }
    }
  }

  private static Block sapling(Block log) {
    Identifier id = BuiltInRegistries.BLOCK.getKey(log);
    if (id.getPath().startsWith("mangrove")) {
      return Blocks.MANGROVE_PROPAGULE;
    }
    String path =
        id.getPath()
            .replace("stripped_", "")
            .replace("_log", "_sapling")
            .replace("_wood", "_sapling");
    return BuiltInRegistries.BLOCK
        .getOptional(Identifier.fromNamespaceAndPath(id.getNamespace(), path))
        .orElse(Blocks.OAK_SAPLING);
  }

  private static void vitrify(ServerLevel level, BlockPos strike) {
    BlockPos hit = strike.below();
    if (!level.isLoaded(hit) || !level.getBlockState(hit).is(BlockTags.SAND)) {
      return;
    }
    fuse(level, strike, hit, 0);
    if (level.getBlockState(strike).isAir() && unit(level, strike, 12) < 0.5) {
      level.setBlockAndUpdate(
          strike,
          AtmosphereBlocks.FULGURITE
              .get()
              .defaultBlockState()
              .setValue(AmethystClusterBlock.FACING, Direction.UP));
    }
  }

  private static void fuse(ServerLevel level, BlockPos strike, BlockPos pos, int depth) {
    if (ChunkTraces.isFixed(level, pos)) {
      return;
    }
    level.setBlockAndUpdate(pos, AtmosphereBlocks.VITRIFIED_SAND.get().defaultBlockState());
    if (depth >= 5) {
      return;
    }
    double odds = Math.pow(0.7, depth + 1);
    for (Direction direction :
        new Direction[] {
          Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
        }) {
      BlockPos next = pos.relative(direction);
      double chance = direction == Direction.DOWN ? (depth == 0 ? 1.0 : odds) : 0.3 * odds;
      if (level.isLoaded(next)
          && level.getBlockState(next).is(BlockTags.SAND)
          && unit(level, next, 13 + depth) < chance) {
        fuse(level, strike, next, depth + 1);
      }
    }
  }

  public static boolean charInsteadOfBurning(Level level, BlockPos pos) {
    if (!(level instanceof ServerLevel)
        || !AtmosphereConfig.TRACES_ENABLED.get()
        || !AtmosphereConfig.FIRE_CHARS_WOOD.get()
        || level.getRandom().nextFloat() >= AtmosphereConfig.FIRE_CHAR_CHANCE.get()
        || ChunkTraces.isFixed(level, pos)) {
      return false;
    }
    BlockState charred = charred(level.getBlockState(pos));
    if (charred == null) {
      return false;
    }
    level.setBlockAndUpdate(pos, charred);
    return true;
  }

  static @Nullable BlockState charred(BlockState state) {
    if (state.is(BlockTags.LOGS_THAT_BURN)) {
      BlockState log = AtmosphereBlocks.CHARRED_LOG.get().defaultBlockState();
      return state.hasProperty(RotatedPillarBlock.AXIS)
          ? log.setValue(RotatedPillarBlock.AXIS, state.getValue(RotatedPillarBlock.AXIS))
          : log;
    }
    Block block = null;
    if (state.is(BlockTags.PLANKS)) {
      block = AtmosphereBlocks.CHARRED_PLANKS.get();
    } else if (state.is(BlockTags.WOODEN_STAIRS)) {
      block = AtmosphereBlocks.CHARRED_STAIRS.get();
    } else if (state.is(BlockTags.WOODEN_SLABS)) {
      block = AtmosphereBlocks.CHARRED_SLAB.get();
    } else if (state.is(BlockTags.WOODEN_FENCES)) {
      block = AtmosphereBlocks.CHARRED_FENCE.get();
    } else if (state.is(BlockTags.FENCE_GATES)) {
      block = AtmosphereBlocks.CHARRED_FENCE_GATE.get();
    }
    return block == null ? null : block.withPropertiesOf(state);
  }

  public static void afterFireTick(ServerLevel level, BlockPos pos) {
    if (AtmosphereConfig.TRACES_ENABLED.get()
        && AtmosphereConfig.FIRE_ASH.get()
        && level.getBlockState(pos).isAir()
        && level.getRandom().nextFloat() < AtmosphereConfig.FIRE_ASH_CHANCE.get()) {
      ash(level, pos, 1);
    }
  }

  static void soot(Column c) {
    if (!AtmosphereConfig.SOOT.get()) {
      return;
    }
    double chance =
        c.live
            ? AtmosphereConfig.SOOT_CHANCE.get()
            : History.coverage(
                AtmosphereConfig.SOOT_CHANCE.get()
                    * c.params.visitsPerDay()
                    * (c.now - c.history().growthFrom)
                    / GuestTime.TICKS_PER_DAY);
    if (!c.chance(50, chance)) {
      return;
    }
    if (litCampfire(c.state(c.ground))) {

      Surfaces.walls(
          c,
          6,
          (wall, state, face) -> {
            if (state.isFaceSturdy(c.level, wall, face) && c.fixed(wall, 51) < 0.5) {
              addSoot(c, wall.relative(face), face.getOpposite());
            }
          });
      return;
    }

    BlockPos ceiling = c.ground;
    for (int i = 0; i < 4 && !c.state(ceiling.below()).isAir(); i++) {
      ceiling = ceiling.below();
    }
    BlockPos air = ceiling.below();
    if (!c.state(air).isAir() || !c.state(ceiling).isFaceSturdy(c.level, ceiling, Direction.DOWN)) {
      return;
    }
    for (BlockPos pos = air.below(); pos.getY() > air.getY() - 12; pos = pos.below()) {
      BlockState state = c.state(pos);
      if (litCampfire(state)) {
        addSoot(c, air, Direction.UP);
        return;
      }
      if (!state.isAir()) {
        return;
      }
    }
  }

  private static boolean litCampfire(BlockState state) {
    return state.is(BlockTags.CAMPFIRES) && state.getValue(CampfireBlock.LIT);
  }

  private static void addSoot(Column c, BlockPos pos, Direction towards) {
    BlockState state = c.state(pos);
    BlockState soot = AtmosphereBlocks.SOOT.get().defaultBlockState();
    if (state.is(soot.getBlock())) {
      soot = state;
    } else if (!state.isAir()) {
      return;
    }
    c.set(pos, soot.setValue(MultifaceBlock.getFaceProperty(towards), true));
  }

  static void ash(ServerLevel level, BlockPos pos, int layers) {
    BlockState state = level.getBlockState(pos);
    BlockState ash = AtmosphereBlocks.ASH.get().defaultBlockState();
    if (state.is(ash.getBlock())) {
      int total = Math.min(8, state.getValue(SnowLayerBlock.LAYERS) + layers);
      level.setBlockAndUpdate(pos, state.setValue(SnowLayerBlock.LAYERS, total));
    } else if (state.isAir() && ash.canSurvive(level, pos)) {
      level.setBlockAndUpdate(pos, ash.setValue(SnowLayerBlock.LAYERS, Math.min(8, layers)));
    }
  }

  private static boolean record(
      ServerLevel level, BlockPos pos, BlockState newState, Kind kind, long now) {
    return record(level, pos, newState, kind, level.getBlockState(pos), now);
  }

  private static boolean record(
      ServerLevel level,
      BlockPos pos,
      BlockState newState,
      Kind kind,
      BlockState original,
      long now) {
    LevelChunk chunk = level.getChunkAt(pos);
    ChunkTraces traces = Column.traces(chunk);
    if (ChunkTraces.isFixed(level, pos)) {
      return false;
    }
    if (traces.get(pos.asLong()) == null
        && traces.size() >= AtmosphereConfig.MAX_TRACES_PER_CHUNK.get()) {
      return false;
    }
    traces.put(pos.asLong(), new Trace(kind, original, now));
    if (level.getBlockState(pos) != newState) {
      level.setBlockAndUpdate(pos, newState);
    }
    chunk.markUnsaved();
    return true;
  }

  private static double unit(ServerLevel level, BlockPos pos, int salt) {
    return GuestHash.unit(GuestHash.hash(level.getSeed() ^ SALT, pos.asLong(), salt));
  }
}
