package com.vortexso.guest_wilds.lair;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.GuestWeather;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BrushableBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.loot.LootTable;
import org.jspecify.annotations.Nullable;

public final class Lairs extends SavedData {
  private static final long LAIR_SALT = 0x6C6169725F63616EL;
  private static final long SEED_SALT = 0x6C6169725F736565L;
  private static final long TRACE_SALT = 0x6C6169725F747263L;
  private static final long ROAM_SALT = 0x6C6169725F737566L;
  private static final long FORM_SALT = 0x6C6169725F666F72L;
  private static final long RANK_SALT = 0x6C6169725F726E6BL;

  private static final ResourceKey<LootTable> GEAR =
      ResourceKey.create(
          Registries.LOOT_TABLE,
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "archaeology/skeleton_lair"));

  private static final int SPACING = 16;

  private static final WildsParameters P = WildsParameters.DEFAULT;
  private static final int SPECIES = LairSpecies.VALUES.length;
  public static final int SHELTER_RANGE = 96;
  private static final int MOUTH_ZONE_STEPS = 3;
  private static final int DEN_ZONE_RADIUS = 4;
  private static final int MOSS_RADIUS = 4;
  private static final int MOSSY = 6;

  private static final int MOUTH_PICK = 6;

  private static final long EXIT_TICKS = 2_500L;

  private static final int UNSEEN_DISTANCE = 16;

  private static final long PRE_DAWN = 22_000L;

  public static final int LIT = 8;

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
    public final long key;

    public final BlockPos pos;

    public final @Nullable BlockPos mouth;

    public final @Nullable BlockPos outside;

    public final float openness;
    public final int size;
    public final long founded;
    long step;
    final double[] n;
    final double[] emigrants;
    final int[] concrete;
    long coexistSince;
    final List<Trace> traces;
    long renewed;

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
                array -> Arrays.stream(array).boxed().toList());
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
                array -> Arrays.stream(array).boxed().toList());

    static final Codec<Node> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        BlockPos.CODEC.fieldOf("pos").forGetter(node -> node.pos),
                        BlockPos.CODEC
                            .optionalFieldOf("mouth")
                            .forGetter(node -> Optional.ofNullable(node.mouth)),
                        BlockPos.CODEC
                            .optionalFieldOf("outside")
                            .forGetter(node -> Optional.ofNullable(node.outside)),
                        Codec.FLOAT.fieldOf("openness").forGetter(node -> node.openness),
                        Codec.INT.optionalFieldOf("size", 0).forGetter(node -> node.size),
                        Codec.LONG.fieldOf("founded").forGetter(node -> node.founded),
                        Codec.LONG.fieldOf("step").forGetter(node -> node.step),
                        DOUBLES.fieldOf("population").forGetter(node -> node.n),
                        DOUBLES.fieldOf("emigrants").forGetter(node -> node.emigrants),
                        INTS.fieldOf("concrete").forGetter(node -> node.concrete),
                        Codec.LONG.optionalFieldOf("coexist", -1L).forGetter(n -> n.coexistSince),
                        Trace.CODEC.listOf().fieldOf("traces").forGetter(node -> node.traces),
                        Codec.LONG.optionalFieldOf("renewed", -1L).forGetter(n -> n.renewed))
                    .apply(
                        i,
                        (pos,
                            mouth,
                            outside,
                            openness,
                            size,
                            founded,
                            step,
                            n,
                            emigrants,
                            concrete,
                            coexist,
                            traces,
                            renewed) ->
                            new Node(
                                pos,
                                mouth.orElse(null),
                                outside.orElse(null),
                                openness,
                                size,
                                founded,
                                step,
                                n,
                                emigrants,
                                concrete,
                                coexist,
                                traces,
                                renewed)));

    Node(
        BlockPos pos,
        @Nullable BlockPos mouth,
        @Nullable BlockPos outside,
        float openness,
        int size,
        long founded,
        long step,
        double[] n,
        double[] emigrants,
        int[] concrete,
        long coexistSince,
        List<Trace> traces,
        long renewed) {
      this.key = ChunkPos.pack(mouth != null ? mouth : pos);
      this.pos = pos;
      this.mouth = mouth;
      this.outside = outside;
      this.openness = openness;
      this.size = size;
      this.founded = founded;
      this.step = step;
      this.n = n;
      this.emigrants = emigrants;
      this.concrete = concrete;
      this.coexistSince = coexistSince;
      this.traces = new ArrayList<>(traces);
      this.renewed = renewed;
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

    public BlockPos entrance() {
      return mouth != null ? mouth : pos;
    }

    public int depth() {
      return mouth != null ? mouth.getY() - pos.getY() : 0;
    }

    public double ageDays(long now) {
      return (now - founded) / (double) Ecology.TICKS_PER_DAY;
    }

    public int traceCount() {
      return traces.size();
    }

    public List<Trace> traces() {
      return List.copyOf(traces);
    }

    public @Nullable LairSpecies dominant() {
      LairSpecies best = null;
      for (LairSpecies species : LairSpecies.VALUES) {
        if (n[species.ordinal()] >= 1.0
            && (best == null || n[species.ordinal()] > n[best.ordinal()])) {
          best = species;
        }
      }
      return best;
    }

    public boolean ridersEstablished() {
      return coexistSince >= 0 && (step - coexistSince) * Ecology.STEP_DAYS >= P.jockeyStableDays();
    }

    public LairSpecies stronger(LairSpecies a, LairSpecies b) {
      return a.strength(openness, n[a.ordinal()]) >= b.strength(openness, n[b.ordinal()]) ? a : b;
    }

    double sizeFactor() {
      return size <= 0 ? 1.0 : Math.clamp(size / 250.0, 0.5, 1.5);
    }

    double capacity(LairSpecies species) {
      return P.lairCapacity() * species.fit(openness) * sizeFactor();
    }
  }

  public double residentsPerScannedChunk() {
    double residents = 0.0;
    for (Node node : nodes.values()) {
      residents += node.mouth != null ? node.total() : 0.0;
    }
    return stats.chunks == 0 ? 0.0 : residents / stats.chunks;
  }

  public static final class Stats {
    public long chunks;
    public long entrances;
    public long lairs;
    public long skippedInhabited;
    public long nanos;
  }

  public static final Codec<Lairs> CODEC =
      Node.CODEC.listOf().xmap(Lairs::new, lairs -> List.copyOf(lairs.nodes.values()));

  public static final SavedDataType<Lairs> TYPE =
      new SavedDataType<>(
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "lairs"), Lairs::new, CODEC);

  private final Long2ObjectOpenHashMap<Node> nodes = new Long2ObjectOpenHashMap<>();

  private final LongOpenHashSet scanned = new LongOpenHashSet();

  private final Long2ObjectOpenHashMap<Entrance> entrances = new Long2ObjectOpenHashMap<>();

  private final LongOpenHashSet pending = new LongOpenHashSet();

  public final Stats stats = new Stats();

  private Lairs() {}

  private Lairs(List<Node> saved) {
    saved.forEach(node -> nodes.put(node.key, node));
  }

  public static Lairs get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public @Nullable Node node(long key) {
    return nodes.get(key);
  }

  public List<Node> nodes() {
    return List.copyOf(nodes.values());
  }

  public List<Node> nodesNear(BlockPos pos, int radius) {
    List<Node> result = new ArrayList<>();
    long radiusSqr = (long) radius * radius;
    if (radius > 256) {
      for (Node node : nodes.values()) {
        if (within(node, pos, radiusSqr)) {
          result.add(node);
        }
      }
      return result;
    }
    for (int x = (pos.getX() - radius) >> 4; x <= (pos.getX() + radius) >> 4; x++) {
      for (int z = (pos.getZ() - radius) >> 4; z <= (pos.getZ() + radius) >> 4; z++) {
        Node node = nodes.get(ChunkPos.pack(x, z));
        if (node != null && within(node, pos, radiusSqr)) {
          result.add(node);
        }
      }
    }
    return result;
  }

  private static boolean within(Node node, BlockPos pos, long radiusSqr) {
    long dx = node.entrance().getX() - pos.getX();
    long dz = node.entrance().getZ() - pos.getZ();
    return dx * dx + dz * dz <= radiusSqr;
  }

  private record Entrance(
      long key,
      BlockPos mouth,
      BlockPos outside,
      BlockPos den,
      float openness,
      int size,
      long rank) {}

  public void onChunkLoad(ServerLevel level, ChunkPos chunkPos) {
    long key = chunkPos.pack();
    Node node = nodes.get(key);
    if (node != null && node.mouth == null) {
      retire(level, node);
      node = null;
    }
    if (node != null) {
      advance(level, node);
      updateTraces(level, node, null);
    } else if (scanned.add(key)) {
      scan(level, chunkPos, key);
    }
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        long around = ChunkPos.pack(chunkPos.x() + dx, chunkPos.z() + dz);
        if (pending.contains(around)) {
          resolve(level, around);
        }
      }
    }
  }

  private void scan(ServerLevel level, ChunkPos chunkPos, long key) {
    LevelChunk chunk = level.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z());
    if (chunk == null) {
      scanned.remove(key);
      return;
    }
    if (chunk.getInhabitedTime() > P.lairInhabitedLimit()) {
      stats.skippedInhabited++;
      return;
    }
    long started = System.nanoTime();
    CaveScan.Cave cave = CaveScan.find(CaveScan.terrain(chunk));
    stats.nanos += System.nanoTime() - started;
    stats.chunks++;
    if (cave == null) {
      return;
    }
    BlockPos mouth = CaveScan.toBlock(chunkPos.x(), chunkPos.z(), cave.mouth());
    if (manMade(level, chunk, mouth)) {
      return;
    }
    stats.entrances++;
    entrances.put(
        key,
        new Entrance(
            key,
            mouth,
            CaveScan.toBlock(chunkPos.x(), chunkPos.z(), cave.outside()),
            CaveScan.toBlock(chunkPos.x(), chunkPos.z(), cave.den()),
            cave.openness(),
            cave.size(),
            GuestHash.hash(level.getSeed(), mouth.asLong(), RANK_SALT)));
    pending.add(key);
  }

  private void resolve(ServerLevel level, long key) {
    int x = ChunkPos.getX(key);
    int z = ChunkPos.getZ(key);
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        long around = ChunkPos.pack(x + dx, z + dz);
        if (!scanned.contains(around) && !nodes.containsKey(around)) {
          return;
        }
      }
    }
    LevelChunk chunk = level.getChunkSource().getChunkNow(x, z);
    if (chunk == null) {
      return;
    }
    pending.remove(key);
    Entrance entrance = entrances.get(key);
    long spacingSqr = (long) SPACING * SPACING;
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        long around = ChunkPos.pack(x + dx, z + dz);
        Node near = nodes.get(around);
        if (near != null
            && near.mouth != null
            && near.mouth.distSqr(entrance.mouth()) <= spacingSqr) {
          return;
        }
        Entrance rival = around == key ? null : entrances.get(around);
        if (rival != null
            && rival.rank() > entrance.rank()
            && rival.mouth().distSqr(entrance.mouth()) <= spacingSqr) {
          return;
        }
      }
    }
    if (GuestHash.unit(GuestHash.hash(level.getSeed(), key, LAIR_SALT))
        >= WildsConfig.LAIR_SHARE.get()) {
      return;
    }
    long now = GuestTime.gameTime(level);
    Node found =
        new Node(
            entrance.den(),
            entrance.mouth(),
            entrance.outside(),
            entrance.openness(),
            entrance.size(),
            now,
            Ecology.step(now),
            new double[SPECIES],
            new double[SPECIES],
            new int[SPECIES],
            -1L,
            List.of(),
            -1L);
    seed(level.getSeed(), found, habitat(level, chunk, found));
    nodes.put(key, found);
    stats.lairs++;
    updateTraces(level, found, null);
    setDirty();
  }

  private static boolean manMade(ServerLevel level, LevelChunk chunk, BlockPos mouth) {
    var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    for (Structure structure : chunk.getAllReferences().keySet()) {
      Identifier id = registry.getKey(structure);
      if (id != null && id.getPath().startsWith("mineshaft")) {
        continue;
      }
      if (level.structureManager().getStructureWithPieceAt(mouth, structure).isValid()) {
        return true;
      }
    }
    return false;
  }

  private static LairSpecies.Habitat habitat(ServerLevel level, LevelChunk chunk, Node node) {
    var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    boolean ruins = false;
    for (Structure structure : chunk.getAllReferences().keySet()) {
      Identifier id = registry.getKey(structure);
      if (id != null && isFormerHome(id.getPath())) {
        ruins = true;
        break;
      }
    }
    Holder<Biome> surface = level.getBiome(node.outside != null ? node.outside : node.pos);
    boolean forest =
        surface.is(BiomeTags.IS_FOREST)
            || surface.is(BiomeTags.IS_TAIGA)
            || surface.is(BiomeTags.IS_JUNGLE);
    boolean lush =
        level.getBiome(node.pos).is(Biomes.LUSH_CAVES)
            || level.getBiome(node.pos.below(8)).is(Biomes.LUSH_CAVES)
            || mossy(level, node.entrance())
            || mossy(level, node.pos);
    return new LairSpecies.Habitat(ruins, forest, lush, node.openness, node.depth());
  }

  private static boolean mossy(ServerLevel level, BlockPos center) {
    int found = 0;
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -MOSS_RADIUS; dx <= MOSS_RADIUS; dx++) {
      for (int dy = -MOSS_RADIUS; dy <= MOSS_RADIUS; dy++) {
        for (int dz = -MOSS_RADIUS; dz <= MOSS_RADIUS; dz++) {
          cursor.setWithOffset(center, dx, dy, dz);
          if (!GuestWilds.loaded(level, cursor)) {
            continue;
          }
          BlockState state = level.getBlockState(cursor);
          if (state.is(BlockTags.MOSS_BLOCKS)
              || state.is(Blocks.MOSS_CARPET)
              || state.is(BlockTags.CAVE_VINES)
              || state.is(Blocks.AZALEA)
              || state.is(Blocks.FLOWERING_AZALEA)
              || state.is(Blocks.SPORE_BLOSSOM)) {
            if (++found >= MOSSY) {
              return true;
            }
          }
        }
      }
    }
    return false;
  }

  static boolean isFormerHome(String path) {
    return path.startsWith("village")
        || path.startsWith("ruined_portal")
        || path.startsWith("mineshaft")
        || path.startsWith("trail_ruins")
        || path.equals("ancient_city")
        || path.equals("stronghold")
        || path.endsWith("_pyramid");
  }

  static void seed(long worldSeed, Node node, LairSpecies.Habitat habitat) {
    long h = GuestHash.hash(worldSeed, node.key, SEED_SALT);
    int first = LairSpecies.settle(h, habitat, -1);
    node.n[first] =
        node.capacity(LairSpecies.VALUES[first])
            * (0.5 + 0.5 * GuestHash.unit(GuestHash.hash(h, 7)));
    if (GuestHash.unit(GuestHash.hash(h, 8)) < 0.35) {
      int second = LairSpecies.settle(GuestHash.hash(h, 9), habitat, first);
      node.n[second] = 0.3 * node.capacity(LairSpecies.VALUES[second]);
    }
  }

  public void refresh(ServerLevel level, Node node) {
    advance(level, node);
    if (node.mouth != null) {
      updateTraces(level, node, null);
    }
  }

  public void advance(ServerLevel level, Node node) {
    long target = Ecology.step(GuestTime.gameTime(level));
    long steps = Ecology.stepsToRun(node.step, target);
    if (steps > 0) {
      double[] capacity = new double[SPECIES];
      double[] growth = new double[SPECIES];
      for (LairSpecies species : LairSpecies.VALUES) {
        capacity[species.ordinal()] = node.capacity(species);
        growth[species.ordinal()] = species.growth(P);
      }
      int spider = LairSpecies.SPIDER.ordinal();
      int skeleton = LairSpecies.SKELETON.ordinal();
      long first = target - steps;
      for (long s = 0; s < steps; s++) {
        boolean winter = GuestTime.season((first + s) * Ecology.STEP_TICKS) == Season.WINTER;

        double alpha = P.competition() * (winter ? P.winterCompetition() : 1.0);
        double homeless = P.lairMortalityPerDay() * (winter ? 2.0 : 1.0);
        for (int i = 0; i < SPECIES; i++) {
          node.emigrants[i] *= 1.0 - homeless * Ecology.STEP_DAYS;
        }
        Ecology.lairStep(
            node.n,
            capacity,
            growth,
            P.lairMortalityPerDay(),
            alpha,
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

      for (int i = 0; i < SPECIES; i++) {
        node.n[i] = Math.max(node.n[i], node.concrete[i]);
      }
      setDirty();
    }
    node.step = Math.max(node.step, target);
    deliverEmigrants(node);
  }

  private void deliverEmigrants(Node node) {
    for (int i = 0; i < SPECIES; i++) {
      if (node.emigrants[i] < 1.0) {
        continue;
      }
      Node best = null;
      double bestRoom = 0.5;
      for (Node target : nodesNear(node.entrance(), P.emigrantRange())) {
        if (target == node || target.mouth == null) {
          continue;
        }
        double rivals = 0.0;
        for (int j = 0; j < SPECIES; j++) {
          rivals += j == i ? 0.0 : target.n[j];
        }
        double room =
            target.capacity(LairSpecies.VALUES[i]) - target.n[i] - P.competition() * rivals;
        if (room > bestRoom) {
          bestRoom = room;
          best = target;
        }
      }
      if (best != null) {
        best.n[i] += node.emigrants[i];
        node.emigrants[i] = 0.0;
        setDirty();
      }
    }
  }

  private void updateTraces(ServerLevel level, Node node, CaveScan.@Nullable Cave known) {
    if (node.mouth == null || !isLoaded(level, node.mouth)) {
      return;
    }
    long day = GuestTime.day(GuestTime.gameTime(level));
    boolean renew = node.renewed < 0 || day - node.renewed >= P.renewDays();
    CaveScan.Cave cave = known;
    boolean changed = false;
    if (node.traces.stream().anyMatch(trace -> trace.placed().is(Blocks.TURTLE_EGG))) {
      removeTraces(level, node, LairSpecies.CREEPER.ordinal());
      renew = true;
      changed = true;
    }
    for (LairSpecies species : LairSpecies.VALUES) {
      int i = species.ordinal();
      boolean traced = node.traces.stream().anyMatch(trace -> trace.species() == i);

      if (!traced && node.n[i] >= 1.0 && (known != null || renew)) {
        if (cave == null) {
          cave = measure(level, node);
        }
        if (cave != null) {
          placeTraces(level, node, species, cave);
          changed = true;
        }
      } else if (traced && node.n[i] < 0.5) {
        removeTraces(level, node, i);
        changed = true;
      } else if (traced && renew && node.n[i] >= 1.0) {
        changed |= renewTraces(level, node, i);
      }
    }
    if (renew) {
      node.renewed = day;
      changed = true;
    }
    if (changed) {
      setDirty();
    }
  }

  private static CaveScan.@Nullable Cave measure(ServerLevel level, Node node) {
    if (node.mouth == null || node.outside == null) {
      return null;
    }
    LevelChunk chunk =
        level
            .getChunkSource()
            .getChunkNow(
                SectionPos.blockToSectionCoord(node.mouth.getX()),
                SectionPos.blockToSectionCoord(node.mouth.getZ()));
    if (chunk == null) {
      return null;
    }
    return CaveScan.measure(
        CaveScan.terrain(chunk),
        CaveScan.pack(node.outside.getX(), node.outside.getY(), node.outside.getZ()),
        CaveScan.pack(node.mouth.getX(), node.mouth.getY(), node.mouth.getZ()));
  }

  private void placeTraces(ServerLevel level, Node node, LairSpecies species, CaveScan.Cave cave) {
    int chunkX = SectionPos.blockToSectionCoord(node.entrance().getX());
    int chunkZ = SectionPos.blockToSectionCoord(node.entrance().getZ());
    int[] mouthZone = zone(cave, LairSpecies.Zone.MOUTH);
    int[] denZone = zone(cave, LairSpecies.Zone.DEN);
    Set<BlockPos> used = new HashSet<>();
    node.traces.forEach(trace -> used.add(trace.pos()));
    List<LairSpecies.TraceSpec> specs = species.traces();
    for (int s = 0; s < specs.size(); s++) {
      LairSpecies.TraceSpec spec = specs.get(s);
      int[] zone = spec.zone() == LairSpecies.Zone.MOUTH ? mouthZone : denZone;
      for (int j = 0; j < spec.count() && zone.length > 0; j++) {
        long h =
            GuestHash.hash(
                level.getSeed(), node.key, TRACE_SALT, species.ordinal() * 64L + s * 8L + j);
        int spread =
            spec.zone() == LairSpecies.Zone.MOUTH ? Math.min(MOUTH_PICK, zone.length) : zone.length;
        int start = (int) (GuestHash.unit(h) * spread);
        for (int k = 0; k < zone.length; k++) {
          BlockPos cell = CaveScan.toBlock(chunkX, chunkZ, zone[(start + k) % zone.length]);
          if (tryPlace(level, node, species, spec, cell, h, used)) {
            break;
          }
        }
      }
    }
  }

  private static int[] zone(CaveScan.Cave cave, LairSpecies.Zone zone) {
    List<Integer> cells = new ArrayList<>();
    if (zone == LairSpecies.Zone.MOUTH) {
      cells.add(cave.outside());
    }
    int den = cave.den();
    for (int c = 0; c < cave.cells().length; c++) {
      int cell = cave.cells()[c];
      boolean in =
          zone == LairSpecies.Zone.MOUTH
              ? cave.dist()[c] <= MOUTH_ZONE_STEPS
              : Math.abs(CaveScan.x(cell) - CaveScan.x(den))
                      + Math.abs(CaveScan.y(cell) - CaveScan.y(den))
                      + Math.abs(CaveScan.z(cell) - CaveScan.z(den))
                  <= DEN_ZONE_RADIUS;
      if (in) {
        cells.add(cell);
      }
    }
    return cells.stream().mapToInt(Integer::intValue).toArray();
  }

  private boolean tryPlace(
      ServerLevel level,
      Node node,
      LairSpecies species,
      LairSpecies.TraceSpec spec,
      BlockPos cell,
      long h,
      Set<BlockPos> used) {
    BlockState state = vary(spec.state(), h);
    BlockPos target =
        switch (spec.kind()) {
          case FLOOR -> {
            BlockPos floor = cell.below();
            yield level.getBlockState(cell).isAir()
                    && CaveScan.isNatural(level.getBlockState(floor))
                ? floor
                : null;
          }
          case CRATER -> {
            BlockPos floor = cell.below();

            yield level.getBlockState(cell).isAir()
                    && CaveScan.isNatural(level.getBlockState(floor))
                    && level.getBlockState(floor.below()).blocksMotion()
                ? floor
                : null;
          }
          case STAND -> {
            BlockPos below = cell.below();
            yield level.getBlockState(cell).isAir()
                    && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
                    && state.canSurvive(level, cell)
                ? cell
                : null;
          }
          case CEILING -> {
            BlockPos above = cell.above();
            yield level.getBlockState(cell).isAir()
                    && level.getBlockState(above).isFaceSturdy(level, above, Direction.DOWN)
                ? cell
                : null;
          }
          case WALL -> {
            BlockPos found = null;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
              BlockPos wall = cell.above().relative(direction);
              if (!GuestWilds.loaded(level, wall)) {
                continue;
              }
              BlockState rock = level.getBlockState(wall);
              if (!used.contains(wall) && rock.blocksMotion() && CaveScan.isNatural(rock)) {
                found = wall;
                break;
              }
            }
            yield found;
          }
        };
    if (target == null || used.contains(target)) {
      return false;
    }
    BlockState original = level.getBlockState(target);
    level.setBlock(target, state, GuestWilds.updateFlags(level, target));
    if (level.getBlockEntity(target) instanceof BrushableBlockEntity brushable) {
      brushable.setLootTable(GEAR, h);
    }
    node.traces.add(new Trace(target.immutable(), original, state, species.ordinal()));
    used.add(target.immutable());
    return true;
  }

  private static BlockState vary(BlockState state, long h) {
    int roll = (int) (GuestHash.unit(GuestHash.hash(h, 3)) * 16);
    if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
      state = state.setValue(BlockStateProperties.ROTATION_16, roll);
    }
    if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
      state =
          state.setValue(
              BlockStateProperties.HORIZONTAL_FACING, Direction.from2DDataValue(roll % 4));
    }
    return state;
  }

  private static boolean renewTraces(ServerLevel level, Node node, int species) {
    boolean changed = false;
    for (Trace trace : node.traces) {
      if (trace.species() == species
          && LairSpecies.renewable(trace.placed())
          && level.getBlockState(trace.pos()).isAir()
          && trace.placed().canSurvive(level, trace.pos())
          && (trace.placed().is(Blocks.COBWEB) || onFloor(level, trace.pos()))) {
        level.setBlock(trace.pos(), trace.placed(), GuestWilds.updateFlags(level, trace.pos()));
        changed = true;
      }
    }
    return changed;
  }

  private static boolean onFloor(ServerLevel level, BlockPos pos) {
    BlockPos below = pos.below();
    return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
  }

  private static void removeTraces(ServerLevel level, Node node, int species) {

    for (int t = node.traces.size() - 1; t >= 0; t--) {
      Trace trace = node.traces.get(t);
      if (species >= 0 && trace.species() != species) {
        continue;
      }
      if (GuestWilds.loaded(level, trace.pos())
          && level.getBlockState(trace.pos()).is(trace.placed().getBlock())) {
        level.setBlock(trace.pos(), trace.original(), GuestWilds.updateFlags(level, trace.pos()));
      }
      node.traces.remove(t);
    }
  }

  private void retire(ServerLevel level, Node node) {
    removeTraces(level, node, -1);
    nodes.remove(node.key);
    setDirty();
  }

  public void materializeNear(ServerLevel level, ServerPlayer player) {
    long now = GuestTime.gameTime(level);
    long tick = GuestTime.tickOfDay(now);

    boolean night = GuestTime.isNight(now) && tick < PRE_DAWN;
    boolean exiting = night && tick < 12_500L + EXIT_TICKS;
    for (Node node : nodesNear(player.blockPosition(), P.materializeRadius() + 16)) {
      if (node.mouth == null || !isLoaded(level, node.mouth) || !isLoaded(level, node.pos)) {
        continue;
      }
      advance(level, node);
      updateTraces(level, node, null);

      boolean storm = GuestWeather.get(level, node.mouth, now).isSevere();
      int budget = P.materializePerCheck();
      for (LairSpecies species : LairSpecies.VALUES) {
        int i = species.ordinal();
        boolean inside =
            !night || (storm && (species == LairSpecies.SPIDER || species == LairSpecies.CREEPER));
        while (budget > 0 && node.n[i] - node.concrete[i] >= 1.0) {
          int index = node.concrete[i] + i * 31;
          BlockPos at =
              inside
                  ? denSpot(level, node, index)
                  : exiting ? exitSpot(level, node) : surfacePoint(level, node, index);
          if (at == null
              || level.getNearestPlayer(
                      at.getX() + 0.5, at.getY(), at.getZ() + 0.5, UNSEEN_DISTANCE, false)
                  != null) {
            break;
          }
          Mob mob = spawn(level, node, species, at, now, index);
          if (mob == null) {
            break;
          }
          budget--;
          int skeleton = LairSpecies.SKELETON.ordinal();
          if (species == LairSpecies.SPIDER
              && node.ridersEstablished()
              && node.n[skeleton] - node.concrete[skeleton] >= 1.0) {
            Mob rider = spawn(level, node, LairSpecies.SKELETON, at, now, index);
            if (rider != null) {
              rider.startRiding(mob, true, false);
            }
          }
        }
      }
    }
  }

  private static @Nullable Mob spawn(
      ServerLevel level, Node node, LairSpecies species, BlockPos at, long now, int index) {
    long h = GuestHash.hash(level.getSeed(), node.key, FORM_SALT, index);
    EntityType<? extends Mob> type = Variants.lairForm(species, level, at, now, node);
    if (species == LairSpecies.SPIDER
        && node.ageDays(now) >= P.caveSpiderAgeDays()
        && node.depth() >= P.caveSpiderDepth()
        && GuestHash.unit(h) < P.caveSpiderShare()) {
      type = EntityType.CAVE_SPIDER;
    }

    boolean baby =
        species == LairSpecies.ZOMBIE && GuestHash.unit(GuestHash.hash(h, 1)) < P.babyZombieShare();
    return Membership.spawn(
        type,
        level,
        at,
        new Membership(Membership.Kind.LAIR, node.key, species.id()),
        mob -> {
          if (baby && mob instanceof Zombie zombie) {
            zombie.setBaby(true);
          }
        });
  }

  private static @Nullable BlockPos exitSpot(ServerLevel level, Node node) {
    BlockPos mouth = node.mouth;
    if (mouth == null) {
      return null;
    }
    List<BlockPos> spots = new ArrayList<>();
    spots.add(mouth);
    if (node.outside != null) {
      spots.add(node.outside);
    }
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      spots.add(mouth.relative(direction));
    }
    for (BlockPos spot : spots) {
      if (standable(level, spot)) {
        return spot;
      }
    }
    return null;
  }

  private static @Nullable BlockPos denSpot(ServerLevel level, Node node, int index) {
    long h = GuestHash.hash(level.getSeed(), node.key, ROAM_SALT, -1L - index);
    for (int attempt = 0; attempt < 4; attempt++) {
      long a = GuestHash.hash(h, attempt);
      BlockPos base =
          node.pos.offset(
              (int) (GuestHash.unit(a) * 5) - 2, 0, (int) (GuestHash.unit(a >>> 7) * 5) - 2);
      for (int dy = 1; dy >= -2; dy--) {
        BlockPos at = base.above(dy);
        if (standable(level, at)) {
          return at;
        }
      }
    }
    return standable(level, node.pos) ? node.pos : null;
  }

  private static boolean standable(ServerLevel level, BlockPos at) {
    BlockPos below = at.below();
    return GuestWilds.loaded(level, at)
        && level.getBrightness(LightLayer.BLOCK, at) < LIT
        && level.isEmptyBlock(at)
        && level.isEmptyBlock(at.above())
        && level.getFluidState(at).isEmpty()
        && level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
  }

  public static @Nullable BlockPos surfacePoint(ServerLevel level, Node node, int index) {
    BlockPos origin = node.outside != null ? node.outside : node.pos;
    int radius = P.roamRadius();
    for (int attempt = 0; attempt < 4; attempt++) {
      long h = GuestHash.hash(level.getSeed(), node.key, ROAM_SALT, index * 4L + attempt);
      int x = origin.getX() + (int) (GuestHash.unit(h) * (2 * radius + 1)) - radius;
      int z =
          origin.getZ() + (int) (GuestHash.unit(GuestHash.hash(h, 1)) * (2 * radius + 1)) - radius;
      if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
        continue;
      }
      BlockPos top =
          new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
      BlockPos ground = top.below();
      if (level.getFluidState(ground).isEmpty()
          && CaveScan.isNatural(level.getBlockState(ground))
          && level.getBrightness(LightLayer.BLOCK, top) < LIT) {
        return top;
      }
    }
    return null;
  }

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

  public static int rivalry(ServerLevel level, Entity self, Entity other) {
    if (!WildsConfig.LAIR_FIGHTS.get()) {
      return 0;
    }
    Membership a = Membership.of(self);
    Membership b = Membership.of(other);
    if (a == null
        || b == null
        || a.kind() != Membership.Kind.LAIR
        || b.kind() != Membership.Kind.LAIR
        || a.node() != b.node()
        || a.species().equals(b.species())) {
      return 0;
    }
    Node node = get(level).nodes.get(a.node());
    LairSpecies mine = LairSpecies.byId(a.species());
    LairSpecies theirs = LairSpecies.byId(b.species());
    if (node == null || mine == null || theirs == null) {
      return 0;
    }
    boolean riders =
        (mine == LairSpecies.SPIDER && theirs == LairSpecies.SKELETON)
            || (mine == LairSpecies.SKELETON && theirs == LairSpecies.SPIDER);
    if (riders && node.ridersEstablished()) {
      return 0;
    }
    return node.stronger(mine, theirs) == mine ? 1 : -1;
  }

  public static double hostilePressure(ServerLevel level, BlockPos pos, int radius) {
    return pressure(level, pos, radius, true);
  }

  public static double pressure(ServerLevel level, BlockPos pos, int radius, boolean catchUp) {
    if (!WildsConfig.LAIRS.get() || level.dimension() != Level.OVERWORLD) {
      return GuestWildlife.BASELINE;
    }
    Lairs lairs = get(level);
    double total = 0.0;
    int chunks = 0;
    for (int x = (pos.getX() - radius) >> 4; x <= (pos.getX() + radius) >> 4; x++) {
      for (int z = (pos.getZ() - radius) >> 4; z <= (pos.getZ() + radius) >> 4; z++) {
        long key = ChunkPos.pack(x, z);
        chunks++;
        Node node = lairs.nodes.get(key);
        if (node != null) {
          if (catchUp) {
            lairs.advance(level, node);
          }
          total += node.total();
        } else if (!lairs.scanned.contains(key)) {
          total += P.residentsPerChunk();
        }
      }
    }
    return total / (chunks * P.residentsPerChunk());
  }

  private static boolean isLoaded(ServerLevel level, BlockPos pos) {
    return level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) != null;
  }
}
