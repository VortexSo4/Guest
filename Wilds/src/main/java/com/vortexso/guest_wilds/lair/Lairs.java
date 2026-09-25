package com.vortexso.guest_wilds.lair;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.GuestWildlife;
import com.vortexso.guest_wilds.Ecology;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.Membership;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.WildsParameters;
import com.vortexso.guest_wilds.behavior.Variants;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Undead lairs: states of existing caves, never generated structures. Each 64x64 cell has one
 * deterministic candidate position; it is validated when its chunk first loads and, if the cave is
 * roomy enough, becomes a node holding aggregate populations per species.
 *
 * <p>Concrete mobs near a player are the node's members: the population already counts them, so
 * materializing only spawns the part of the population nobody has seen yet.
 */
public final class Lairs extends SavedData {
  public static final int CELL_SHIFT = 6;
  private static final int CHUNKS_PER_CELL_SHIFT = CELL_SHIFT - 4;
  private static final long CANDIDATE_SALT = 0x6C6169725F63616EL;
  private static final long SEED_SALT = 0x6C6169725F736565L;
  private static final long TRACE_SALT = 0x6C6169725F747263L;
  private static final long SURFACE_SALT = 0x6C6169725F737566L;
  private static final WildsParameters P = WildsParameters.DEFAULT;
  private static final int SPECIES = LairSpecies.VALUES.length;
  private static final int TRACES_PER_SPECIES = 4;
  private static final int BOX_RADIUS = 4;
  private static final int BOX_HEIGHT = 5;
  private static final int BOX_VOLUME = (2 * BOX_RADIUS + 1) * (2 * BOX_RADIUS + 1) * BOX_HEIGHT;
  private static final int MIN_AIR = 40;
  public static final int SHELTER_RANGE = 96;

  public record Trace(BlockPos pos, BlockState original, BlockState placed, int species) {
    static final Codec<Trace> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(Trace::pos),
                        BlockState.CODEC.fieldOf("original").forGetter(Trace::original),
                        BlockState.CODEC.fieldOf("placed").forGetter(Trace::placed),
                        Codec.INT.fieldOf("species").forGetter(Trace::species))
                    .apply(i, Trace::new));
  }

  public static final class Node {
    public final long cell;
    public final BlockPos pos;
    public final float openness;
    public final long founded;
    long step;
    final double[] n;
    final double[] emigrants;
    final int[] concrete;
    long coexistSince;
    final List<Trace> traces;

    static final Codec<double[]> DOUBLES =
        Codec.DOUBLE
            .listOf()
            .xmap(
                list -> {
                  double[] array = new double[SPECIES];
                  for (int i = 0; i < Math.min(SPECIES, list.size()); i++) {
                    array[i] = list.get(i);
                  }
                  return array;
                },
                array -> java.util.Arrays.stream(array).boxed().toList());
    static final Codec<int[]> INTS =
        Codec.INT
            .listOf()
            .xmap(
                list -> {
                  int[] array = new int[SPECIES];
                  for (int i = 0; i < Math.min(SPECIES, list.size()); i++) {
                    array[i] = list.get(i);
                  }
                  return array;
                },
                array -> java.util.Arrays.stream(array).boxed().toList());

    static final Codec<Node> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("cell").forGetter(node -> node.cell),
                        BlockPos.CODEC.fieldOf("pos").forGetter(node -> node.pos),
                        Codec.FLOAT.fieldOf("openness").forGetter(node -> node.openness),
                        Codec.LONG.fieldOf("founded").forGetter(node -> node.founded),
                        Codec.LONG.fieldOf("step").forGetter(node -> node.step),
                        DOUBLES.fieldOf("population").forGetter(node -> node.n),
                        DOUBLES.fieldOf("emigrants").forGetter(node -> node.emigrants),
                        INTS.fieldOf("concrete").forGetter(node -> node.concrete),
                        Codec.LONG.optionalFieldOf("coexist", -1L).forGetter(n -> n.coexistSince),
                        Trace.CODEC.listOf().fieldOf("traces").forGetter(node -> node.traces))
                    .apply(i, Node::new));

    Node(
        long cell,
        BlockPos pos,
        float openness,
        long founded,
        long step,
        double[] n,
        double[] emigrants,
        int[] concrete,
        long coexistSince,
        List<Trace> traces) {
      this.cell = cell;
      this.pos = pos;
      this.openness = openness;
      this.founded = founded;
      this.step = step;
      this.n = n;
      this.emigrants = emigrants;
      this.concrete = concrete;
      this.coexistSince = coexistSince;
      this.traces = new ArrayList<>(traces);
    }

    public double population(LairSpecies species) {
      return n[species.ordinal()];
    }

    public double emigrants(LairSpecies species) {
      return emigrants[species.ordinal()];
    }

    public int concrete(LairSpecies species) {
      return concrete[species.ordinal()];
    }

    public double total() {
      double total = 0.0;
      for (int i = 0; i < SPECIES; i++) {
        total += n[i] + emigrants[i];
      }
      return total;
    }

    public boolean ridersEstablished() {
      return coexistSince >= 0 && (step - coexistSince) * Ecology.STEP_DAYS >= P.jockeyStableDays();
    }

    int cellX() {
      return pos.getX() >> CELL_SHIFT;
    }

    int cellZ() {
      return pos.getZ() >> CELL_SHIFT;
    }
  }

  public static final Codec<Lairs> CODEC =
      Node.CODEC.listOf().xmap(Lairs::new, lairs -> List.copyOf(lairs.nodes.values()));

  public static final SavedDataType<Lairs> TYPE =
      new SavedDataType<>(
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "lairs"), Lairs::new, CODEC);

  private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();

  /** Cells whose candidate cave was checked and unusable. Re-derivable, so not saved. */
  private final LongOpenHashSet rejected = new LongOpenHashSet();

  private Lairs() {}

  private Lairs(List<Node> saved) {
    saved.forEach(node -> nodes.put(node.cell, node));
  }

  public static Lairs get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public @Nullable Node node(long cell) {
    return nodes.get(cell);
  }

  public List<Node> nodes() {
    return List.copyOf(nodes.values());
  }

  public List<Node> nodesNear(BlockPos pos, int radius) {
    List<Node> result = new ArrayList<>();
    long radiusSqr = (long) radius * radius;
    for (int x = (pos.getX() - radius) >> CELL_SHIFT;
        x <= (pos.getX() + radius) >> CELL_SHIFT;
        x++) {
      for (int z = (pos.getZ() - radius) >> CELL_SHIFT;
          z <= (pos.getZ() + radius) >> CELL_SHIFT;
          z++) {
        Node node = nodes.get(ChunkPos.pack(x, z));
        if (node != null) {
          long dx = node.pos.getX() - pos.getX();
          long dz = node.pos.getZ() - pos.getZ();
          if (dx * dx + dz * dz <= radiusSqr) {
            result.add(node);
          }
        }
      }
    }
    return result;
  }

  // ---- discovery ----

  public void onChunkLoad(ServerLevel level, ChunkPos chunk) {
    int cellX = chunk.x() >> CHUNKS_PER_CELL_SHIFT;
    int cellZ = chunk.z() >> CHUNKS_PER_CELL_SHIFT;
    long key = ChunkPos.pack(cellX, cellZ);
    Node node = nodes.get(key);
    if (node != null) {
      if (chunk.contains(node.pos)) {
        advance(level, node);
        updateTraces(level, node);
      }
      return;
    }
    if (rejected.contains(key)) {
      return;
    }
    long h = GuestHash.hash(level.getSeed(), cellX, cellZ, CANDIDATE_SALT);
    int chunkX = (cellX << CHUNKS_PER_CELL_SHIFT) + (int) (h & 3);
    int chunkZ = (cellZ << CHUNKS_PER_CELL_SHIFT) + (int) ((h >>> 2) & 3);
    if (chunk.x() != chunkX || chunk.z() != chunkZ) {
      return;
    }
    // Local 4..11 keeps the whole openness box inside this one chunk.
    int x = (chunkX << 4) + 4 + (int) ((h >>> 8) & 7);
    int z = (chunkZ << 4) + 4 + (int) ((h >>> 12) & 7);
    LevelChunk levelChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
    if (levelChunk == null) {
      return;
    }
    Node found = validate(level, levelChunk, key, x, z);
    if (found == null) {
      rejected.add(key);
      return;
    }
    nodes.put(key, found);
    updateTraces(level, found);
    setDirty();
  }

  private @Nullable Node validate(ServerLevel level, LevelChunk chunk, long key, int x, int z) {
    int surface = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x & 15, z & 15);
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    int floor = Integer.MIN_VALUE;
    // Below the top 12 blocks: dark, no sky exposure, not a surface overhang.
    for (int y = surface - 12; y > level.getMinY() + 4; y--) {
      if (chunk.getBlockState(cursor.set(x, y, z)).isAir()
          && chunk.getBlockState(cursor.set(x, y + 1, z)).isAir()
          && chunk.getBlockState(cursor.set(x, y - 1, z)).blocksMotion()) {
        floor = y;
        break;
      }
    }
    if (floor == Integer.MIN_VALUE) {
      return null;
    }
    int air = 0;
    for (int dx = -BOX_RADIUS; dx <= BOX_RADIUS; dx++) {
      for (int dz = -BOX_RADIUS; dz <= BOX_RADIUS; dz++) {
        for (int dy = 0; dy < BOX_HEIGHT; dy++) {
          if (chunk.getBlockState(cursor.set(x + dx, floor + dy, z + dz)).isAir()) {
            air++;
          }
        }
      }
    }
    if (air < MIN_AIR) {
      return null;
    }
    // ~15% air is a crawlway, ~65% an open chamber.
    float openness = (float) Math.clamp((air / (double) BOX_VOLUME - 0.15) / 0.5, 0.0, 1.0);
    long now = GuestTime.gameTime(level);
    double[] n = new double[SPECIES];
    seed(level.getSeed(), key, openness, n);
    return new Node(
        key,
        new BlockPos(x, floor, z),
        openness,
        now,
        Ecology.step(now),
        n,
        new double[SPECIES],
        new int[SPECIES],
        -1L,
        List.of());
  }

  /** Deterministic starting residents: a dominant species by habitat fit, sometimes a rival. */
  static void seed(long seed, long key, double openness, double[] n) {
    long h = GuestHash.hash(seed, key, SEED_SALT);
    if (GuestHash.unit(h) >= P.lairOccupiedChance()) {
      return;
    }
    int first = pick(h, openness, -1);
    LairSpecies dominant = LairSpecies.VALUES[first];
    n[first] =
        P.lairCapacity()
            * dominant.fit(openness)
            * (0.5 + 0.5 * GuestHash.unit(GuestHash.hash(h, 7)));
    if (GuestHash.unit(GuestHash.hash(h, 8)) < 0.35) {
      int second = pick(GuestHash.hash(h, 9), openness, first);
      n[second] = 0.3 * P.lairCapacity() * LairSpecies.VALUES[second].fit(openness);
    }
  }

  private static int pick(long h, double openness, int exclude) {
    int best = -1;
    double bestScore = -1.0;
    for (int i = 0; i < SPECIES; i++) {
      if (i == exclude) {
        continue;
      }
      double score =
          LairSpecies.VALUES[i].fit(openness) * (0.6 + 0.8 * GuestHash.unit(GuestHash.hash(h, i)));
      if (score > bestScore) {
        bestScore = score;
        best = i;
      }
    }
    return best;
  }

  // ---- aggregate time ----

  public void advance(ServerLevel level, Node node) {
    long target = Ecology.step(GuestTime.gameTime(level));
    long steps = Ecology.stepsToRun(node.step, target);
    if (steps > 0) {
      double[] capacity = new double[SPECIES];
      double[] growth = new double[SPECIES];
      for (LairSpecies species : LairSpecies.VALUES) {
        capacity[species.ordinal()] = P.lairCapacity() * species.fit(node.openness);
        growth[species.ordinal()] = species.growth(P);
      }
      int spider = LairSpecies.SPIDER.ordinal();
      int skeleton = LairSpecies.SKELETON.ordinal();
      long first = target - steps;
      for (long s = 0; s < steps; s++) {
        for (int i = 0; i < SPECIES; i++) {
          // Displaced animals survive on the move but face the same attrition.
          node.emigrants[i] *= 1.0 - P.lairMortalityPerDay() * Ecology.STEP_DAYS;
        }
        Ecology.lairStep(
            node.n,
            capacity,
            growth,
            P.lairMortalityPerDay(),
            P.competition(),
            Ecology.STEP_DAYS,
            node.emigrants);
        if (node.n[spider] >= 2.0 && node.n[skeleton] >= 2.0) {
          if (node.coexistSince < 0) {
            node.coexistSince = first + s;
          }
        } else {
          node.coexistSince = -1L;
        }
      }
      // Loaded members are simulated by vanilla; the aggregate only speaks for the unseen rest.
      for (int i = 0; i < SPECIES; i++) {
        node.n[i] = Math.max(node.n[i], node.concrete[i]);
      }
      setDirty();
    }
    node.step = Math.max(node.step, target);
    deliverEmigrants(node);
  }

  /**
   * Losers move into a neighbouring known cave with room for them; with none known yet they stay on
   * the move and keep counting toward local pressure.
   *
   * <p>ponytail: migrants join the target's stored state without advancing it first, so arrival
   * time is approximate; advance targets too if migration timing ever becomes observable.
   */
  private void deliverEmigrants(Node node) {
    for (int i = 0; i < SPECIES; i++) {
      if (node.emigrants[i] < 1.0) {
        continue;
      }
      Node best = null;
      double bestRoom = 0.5;
      for (int dx = -1; dx <= 1; dx++) {
        for (int dz = -1; dz <= 1; dz++) {
          Node target =
              dx == 0 && dz == 0
                  ? null
                  : nodes.get(ChunkPos.pack(node.cellX() + dx, node.cellZ() + dz));
          if (target == null) {
            continue;
          }
          double rivals = 0.0;
          for (int j = 0; j < SPECIES; j++) {
            rivals += j == i ? 0.0 : target.n[j];
          }
          double room =
              P.lairCapacity() * LairSpecies.VALUES[i].fit(target.openness)
                  - target.n[i]
                  - P.competition() * rivals;
          if (room > bestRoom) {
            bestRoom = room;
            best = target;
          }
        }
      }
      if (best != null) {
        best.n[i] += node.emigrants[i];
        node.emigrants[i] = 0.0;
        setDirty();
      }
    }
  }

  // ---- traces ----

  private void updateTraces(ServerLevel level, Node node) {
    if (!isLoaded(level, node.pos)) {
      return;
    }
    for (LairSpecies species : LairSpecies.VALUES) {
      int i = species.ordinal();
      boolean traced = node.traces.stream().anyMatch(trace -> trace.species() == i);
      if (!traced && node.n[i] >= 2.0) {
        placeTraces(level, node, species);
      } else if (traced && node.n[i] < 0.5) {
        removeTraces(level, node, i);
      }
    }
  }

  private void placeTraces(ServerLevel level, Node node, LairSpecies species) {
    for (int j = 0; j < TRACES_PER_SPECIES; j++) {
      long h = GuestHash.hash(level.getSeed(), node.cell, TRACE_SALT, species.ordinal() * 8L + j);
      BlockPos column =
          node.pos.offset(
              (int) (GuestHash.unit(h) * 9) - 4,
              0,
              (int) (GuestHash.unit(GuestHash.hash(h, 1)) * 9) - 4);
      BlockPos air = findFloor(level, column);
      if (air == null) {
        continue;
      }
      BlockState floor = species.floorTrace(j);
      BlockPos below = air.below();
      BlockState original = level.getBlockState(below);
      if (floor != null && isNatural(original)) {
        place(level, node, below, original, floor, species);
      }
      BlockState standing = species.airTrace(j);
      if (standing != null && level.getBlockState(air).isAir() && standing.canSurvive(level, air)) {
        place(level, node, air, level.getBlockState(air), standing, species);
      }
    }
    setDirty();
  }

  private static void place(
      ServerLevel level,
      Node node,
      BlockPos pos,
      BlockState original,
      BlockState placed,
      LairSpecies species) {
    level.setBlock(pos, placed, Block.UPDATE_ALL);
    node.traces.add(new Trace(pos.immutable(), original, placed, species.ordinal()));
  }

  private void removeTraces(ServerLevel level, Node node, int species) {
    // Reverse order: standing traces go before the floor they stand on.
    for (int t = node.traces.size() - 1; t >= 0; t--) {
      Trace trace = node.traces.get(t);
      if (trace.species() != species) {
        continue;
      }
      if (level.getBlockState(trace.pos()).is(trace.placed().getBlock())) {
        level.setBlock(trace.pos(), trace.original(), Block.UPDATE_ALL);
      }
      node.traces.remove(t);
    }
    setDirty();
  }

  private static @Nullable BlockPos findFloor(ServerLevel level, BlockPos column) {
    for (int dy = 2; dy >= -3; dy--) {
      BlockPos pos = column.above(dy);
      BlockPos below = pos.below();
      if (level.getBlockState(pos).isAir()
          && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
        return pos;
      }
    }
    return null;
  }

  private static boolean isNatural(BlockState state) {
    return state.is(BlockTags.BASE_STONE_OVERWORLD)
        || state.is(BlockTags.DIRT)
        || state.is(Blocks.GRAVEL);
  }

  // ---- observation ----

  public void materializeNear(ServerLevel level, ServerPlayer player) {
    long now = GuestTime.gameTime(level);
    boolean night = GuestTime.isNight(now);
    for (Node node : nodesNear(player.blockPosition(), P.materializeRadius() + 16)) {
      if (!isLoaded(level, node.pos)) {
        continue;
      }
      advance(level, node);
      updateTraces(level, node);
      int budget = P.materializePerCheck();
      for (LairSpecies species : LairSpecies.VALUES) {
        int i = species.ordinal();
        while (budget > 0 && node.n[i] - node.concrete[i] >= 1.0) {
          // At night the population is out roaming; by day it is inside.
          BlockPos at = night ? surfacePoint(level, node, node.concrete[i] + i * 31) : node.pos;
          if (at == null
              || level.getNearestPlayer(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 16.0, false)
                  != null) {
            break;
          }
          Mob mob = spawn(level, node, species, at, now);
          if (mob == null) {
            break;
          }
          budget--;
          int skeleton = LairSpecies.SKELETON.ordinal();
          if (species == LairSpecies.SPIDER
              && node.ridersEstablished()
              && node.n[skeleton] - node.concrete[skeleton] >= 1.0) {
            Mob rider = spawn(level, node, LairSpecies.SKELETON, at, now);
            if (rider != null) {
              rider.startRiding(mob, true, false);
            }
          }
        }
      }
    }
  }

  private static @Nullable Mob spawn(
      ServerLevel level, Node node, LairSpecies species, BlockPos at, long now) {
    return Membership.spawn(
        Variants.lairForm(species, level, at, now, node),
        level,
        at,
        new Membership(Membership.Kind.LAIR, node.cell, species.id()));
  }

  /** Where members surface at night: a deterministic spot on the ground above the lair. */
  public static @Nullable BlockPos surfacePoint(ServerLevel level, Node node, int index) {
    long h = GuestHash.hash(level.getSeed(), node.cell, SURFACE_SALT, index);
    int x = node.pos.getX() + (int) (GuestHash.unit(h) * 25) - 12;
    int z = node.pos.getZ() + (int) (GuestHash.unit(GuestHash.hash(h, 1)) * 25) - 12;
    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
      return null;
    }
    BlockPos top =
        new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    return level.getFluidState(top.below()).isEmpty() ? top : null;
  }

  // ---- membership ----

  /**
   * @return false when this member died or moved away while nobody was watching
   */
  public boolean onMemberJoin(ServerLevel level, Membership membership, boolean fromDisk) {
    Node node = nodes.get(membership.node());
    LairSpecies species = LairSpecies.byId(membership.species());
    if (node == null || species == null) {
      return true;
    }
    int i = species.ordinal();
    if (fromDisk) {
      advance(level, node);
      if (node.n[i] - node.concrete[i] <= -1.0) {
        node.concrete[i]--;
        setDirty();
        return false;
      }
      return true;
    }
    node.concrete[i]++;
    setDirty();
    return true;
  }

  public void onMemberLeave(ServerLevel level, Membership membership, boolean killed) {
    Node node = nodes.get(membership.node());
    LairSpecies species = LairSpecies.byId(membership.species());
    if (node == null || species == null) {
      return;
    }
    int i = species.ordinal();
    if (killed) {
      advance(level, node);
      node.n[i] = Math.max(0.0, node.n[i] - 1.0);
    }
    node.concrete[i] = Math.max(0, node.concrete[i] - 1);
    setDirty();
  }

  // ---- pressure ----

  /**
   * Hostile pressure relative to vanilla: lair residents per cell against the vanilla monster
   * density. Never-inspected cells count as average, so unexplored land reads as vanilla (1.0).
   */
  public static double hostilePressure(ServerLevel level, BlockPos pos, int radius) {
    return pressure(level, pos, radius, true);
  }

  /**
   * @param catchUp false for read-only callers (debug rendering) that must not advance nodes
   */
  public static double pressure(ServerLevel level, BlockPos pos, int radius, boolean catchUp) {
    if (!WildsConfig.LAIRS.get()
        || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) {
      return GuestWildlife.BASELINE;
    }
    Lairs lairs = get(level);
    double total = 0.0;
    int cells = 0;
    for (int x = (pos.getX() - radius) >> CELL_SHIFT;
        x <= (pos.getX() + radius) >> CELL_SHIFT;
        x++) {
      for (int z = (pos.getZ() - radius) >> CELL_SHIFT;
          z <= (pos.getZ() + radius) >> CELL_SHIFT;
          z++) {
        long key = ChunkPos.pack(x, z);
        cells++;
        Node node = lairs.nodes.get(key);
        if (node != null) {
          if (catchUp) {
            lairs.advance(level, node);
          }
          total += node.total();
        } else if (!lairs.rejected.contains(key)) {
          total += P.monstersPerCell();
        }
      }
    }
    return total / (cells * P.monstersPerCell());
  }

  private static boolean isLoaded(ServerLevel level, BlockPos pos) {
    return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
  }
}
