package com.vortexso.guest_wilds.herd;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_wilds.Ecology;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.Membership;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.WildsParameters;
import com.vortexso.guest_wilds.lair.Lairs;
import com.vortexso.guest_wilds.path.PathWear;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

/**
 * Wild herds: aggregate populations that graze a 64x64 pasture cell, lower its vegetation and move
 * on when it runs thin, preferring pastures with fewer predators. Concrete animals persist as in
 * vanilla; when their chunk reloads after the herd moved (or lost animals) the aggregate wins and
 * the animal is folded back into the herd's pool, to reappear where the herd now is.
 */
public final class Herds extends SavedData {
  public static final int CELL_SHIFT = 6;
  private static final WildsParameters P = WildsParameters.DEFAULT;
  private static final long MIGRATION_SALT = 0x6865726473L;
  private static final long SPAWN_SALT = 0x6865726474L;
  private static final int OBSERVE_RADIUS = 64;
  private static final int SPAWN_MIN_PLAYER_DISTANCE = 24;

  /** A cow is ~2 player-widths squared of trampling; a herd's route wear per animal. */
  private static final double WEAR_PER_ANIMAL = 2.0;

  private static final int PEN_RADIUS = 6;

  public static final Set<EntityType<?>> SPECIES =
      Set.of(
          EntityType.COW,
          EntityType.SHEEP,
          EntityType.PIG,
          EntityType.HORSE,
          EntityType.DONKEY,
          EntityType.GOAT,
          EntityType.LLAMA,
          EntityType.MOOSHROOM);

  public static final class Herd {
    public final long id;
    public final String species;
    double n;
    int concrete;
    int x;
    int z;
    int prevX;
    int prevZ;
    long step;

    static final Codec<Herd> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("id").forGetter(h -> h.id),
                        Codec.STRING.fieldOf("species").forGetter(h -> h.species),
                        Codec.DOUBLE.fieldOf("n").forGetter(h -> h.n),
                        Codec.INT.fieldOf("concrete").forGetter(h -> h.concrete),
                        Codec.INT.fieldOf("x").forGetter(h -> h.x),
                        Codec.INT.fieldOf("z").forGetter(h -> h.z),
                        Codec.INT.fieldOf("prev_x").forGetter(h -> h.prevX),
                        Codec.INT.fieldOf("prev_z").forGetter(h -> h.prevZ),
                        Codec.LONG.fieldOf("step").forGetter(h -> h.step))
                    .apply(i, Herd::new));

    Herd(
        long id,
        String species,
        double n,
        int concrete,
        int x,
        int z,
        int prevX,
        int prevZ,
        long step) {
      this.id = id;
      this.species = species;
      this.n = n;
      this.concrete = concrete;
      this.x = x;
      this.z = z;
      this.prevX = prevX;
      this.prevZ = prevZ;
      this.step = step;
    }

    public double animals() {
      return n;
    }

    public int concrete() {
      return concrete;
    }

    public BlockPos center() {
      return new BlockPos(x, 0, z);
    }

    public BlockPos previous() {
      return new BlockPos(prevX, 0, prevZ);
    }

    long cell() {
      return ChunkPos.pack(x >> CELL_SHIFT, z >> CELL_SHIFT);
    }
  }

  private record Pasture(long cell, float value, long time) {
    static final Codec<Pasture> CODEC =
        RecordCodecBuilder.create(
            i ->
                i.group(
                        Codec.LONG.fieldOf("cell").forGetter(Pasture::cell),
                        Codec.FLOAT.fieldOf("value").forGetter(Pasture::value),
                        Codec.LONG.fieldOf("time").forGetter(Pasture::time))
                    .apply(i, Pasture::new));
  }

  public static final Codec<Herds> CODEC =
      RecordCodecBuilder.create(
          i ->
              i.group(
                      Herd.CODEC
                          .listOf()
                          .fieldOf("herds")
                          .forGetter(h -> List.copyOf(h.herds.values())),
                      Pasture.CODEC
                          .listOf()
                          .fieldOf("pastures")
                          .forGetter(h -> List.copyOf(h.pastures.values())),
                      Codec.LONG.fieldOf("next_id").forGetter(h -> h.nextId))
                  .apply(i, Herds::new));

  public static final SavedDataType<Herds> TYPE =
      new SavedDataType<>(
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "herds"), Herds::new, CODEC);

  private final Long2ObjectOpenHashMap<Herd> herds = new Long2ObjectOpenHashMap<>();

  /** Only grazed pastures are stored; an absent cell is full grown. */
  private final Long2ObjectOpenHashMap<Pasture> pastures = new Long2ObjectOpenHashMap<>();

  private long nextId;

  private Herds() {}

  private Herds(List<Herd> herds, List<Pasture> pastures, long nextId) {
    herds.forEach(herd -> this.herds.put(herd.id, herd));
    pastures.forEach(pasture -> this.pastures.put(pasture.cell(), pasture));
    this.nextId = nextId;
  }

  public static Herds get(ServerLevel level) {
    return level.getDataStorage().computeIfAbsent(TYPE);
  }

  public @Nullable Herd herd(long id) {
    return herds.get(id);
  }

  public List<Herd> herds() {
    return List.copyOf(herds.values());
  }

  public double vegetation(long cell, long time) {
    Pasture pasture = pastures.get(cell);
    return pasture == null
        ? 1.0
        : Ecology.recover(
            pasture.value(),
            P.vegetationRegrowthPerDay(),
            (time - pasture.time()) / (double) Ecology.TICKS_PER_DAY);
  }

  public double vegetationAt(BlockPos pos, long time) {
    return vegetation(ChunkPos.pack(pos.getX() >> CELL_SHIFT, pos.getZ() >> CELL_SHIFT), time);
  }

  private void setVegetation(long cell, double value, long time) {
    if (value > 0.99) {
      pastures.remove(cell);
    } else {
      pastures.put(cell, new Pasture(cell, (float) value, time));
    }
  }

  /**
   * Wild = spawned by the world (chunk generation / natural spawning) and not claimed by anyone.
   */
  public static boolean isWildCandidate(Mob mob) {
    EntitySpawnReason reason = mob.getSpawnType();
    return SPECIES.contains(mob.getType())
        && (reason == EntitySpawnReason.NATURAL || reason == EntitySpawnReason.CHUNK_GENERATION)
        && unclaimed(mob);
  }

  private static boolean unclaimed(Mob mob) {
    return !mob.hasCustomName()
        && !mob.isLeashed()
        && !mob.isPassenger()
        && !(mob instanceof AbstractHorse horse && horse.isTamed());
  }

  /**
   * Fences or walls right around an animal mean someone penned it (a player leading it with wheat
   * leaves no other trace). ponytail: block scan heuristic, only run when a member reloads.
   */
  private static boolean penned(ServerLevel level, BlockPos pos) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -PEN_RADIUS; dx <= PEN_RADIUS; dx++) {
      for (int dz = -PEN_RADIUS; dz <= PEN_RADIUS; dz++) {
        for (int dy = -1; dy <= 1; dy++) {
          BlockState state = level.getBlockState(cursor.setWithOffset(pos, dx, dy, dz));
          if (state.is(BlockTags.FENCES)
              || state.is(BlockTags.WALLS)
              || state.is(BlockTags.FENCE_GATES)) {
            return true;
          }
        }
      }
    }
    return false;
  }

  public void assign(ServerLevel level, Mob mob) {
    // Village livestock belongs to Settlements.
    if (level.isCloseToVillage(mob.blockPosition(), 3)) {
      return;
    }
    String species = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
    Herd herd = null;
    long radiusSqr = (long) P.herdJoinRadius() * P.herdJoinRadius();
    // ponytail: linear scan of all herds; index by cell if herd counts reach thousands.
    for (Herd candidate : herds.values()) {
      long dx = candidate.x - mob.getBlockX();
      long dz = candidate.z - mob.getBlockZ();
      if (candidate.species.equals(species) && dx * dx + dz * dz <= radiusSqr) {
        herd = candidate;
        break;
      }
    }
    long now = GuestTime.gameTime(level);
    if (herd == null) {
      herd =
          new Herd(
              nextId++,
              species,
              0.0,
              0,
              mob.getBlockX(),
              mob.getBlockZ(),
              mob.getBlockX(),
              mob.getBlockZ(),
              Ecology.step(now));
      herds.put(herd.id, herd);
    } else {
      advance(level, herd);
    }
    herd.n += 1.0;
    herd.concrete++;
    mob.setData(GuestWilds.MEMBERSHIP, new Membership(Membership.Kind.HERD, herd.id, species));
    setDirty();
  }

  public void advance(ServerLevel level, Herd herd) {
    long target = Ecology.step(GuestTime.gameTime(level));
    long steps = Ecology.stepsToRun(herd.step, target);
    herd.step = Math.max(herd.step, target);
    if (steps == 0) {
      return;
    }
    double pressure = Lairs.hostilePressure(level, herd.center(), 48);
    double death = P.herdMortalityPerDay() + P.predationPerDayAtBaseline() * pressure;
    long first = target - steps;
    for (long s = 1; s <= steps; s++) {
      long time = (first + s) * Ecology.STEP_TICKS;
      long cell = herd.cell();
      Ecology.Herd next =
          Ecology.herdStep(
              new Ecology.Herd(herd.n, vegetation(cell, time)),
              P.herdBirthPerDay(),
              death,
              P.grazePerAnimalDay(),
              P.vegetationRegrowthPerDay(),
              P.herdCapacity(),
              Ecology.STEP_DAYS);
      // Loaded members are simulated by vanilla; the aggregate only speaks for the unseen rest.
      herd.n = Math.max(next.animals(), herd.concrete);
      setVegetation(cell, next.vegetation(), time);
      if (next.vegetation() < P.migrateBelowVegetation() && herd.n >= 0.5) {
        migrate(level, herd, next.vegetation(), time);
      }
    }
    setDirty();
  }

  /**
   * Moves the herd to the best neighbouring pasture: more forage, fewer lair predators. The walk
   * itself is aggregate route traffic for surface wear.
   */
  private void migrate(ServerLevel level, Herd herd, double here, long time) {
    int cellX = herd.x >> CELL_SHIFT;
    int cellZ = herd.z >> CELL_SHIFT;
    BlockPos best = null;
    double bestScore = Double.NEGATIVE_INFINITY;
    double bestVegetation = 0.0;
    for (int dx = -1; dx <= 1; dx++) {
      for (int dz = -1; dz <= 1; dz++) {
        if (dx == 0 && dz == 0) {
          continue;
        }
        long key = ChunkPos.pack(cellX + dx, cellZ + dz);
        BlockPos center =
            new BlockPos(
                ((cellX + dx) << CELL_SHIFT) + 32,
                level.getSeaLevel(),
                ((cellZ + dz) << CELL_SHIFT) + 32);
        Holder<Biome> biome = level.getBiome(center);
        if (biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER)) {
          continue;
        }
        double vegetation = vegetation(key, time);
        double score =
            vegetation
                - P.predatorAvoidance() * Lairs.hostilePressure(level, center, 32)
                + 0.05
                    * GuestHash.unit(
                        GuestHash.hash(level.getSeed(), herd.id, key, time ^ MIGRATION_SALT));
        if (score > bestScore) {
          bestScore = score;
          best = center;
          bestVegetation = vegetation;
        }
      }
    }
    // Moving only pays when the new pasture is clearly better; otherwise the herd stays and
    // starves.
    if (best == null || bestVegetation < here + 0.2) {
      return;
    }
    PathWear.get(level)
        .addRoute(
            level,
            new BlockPos(herd.x, 0, herd.z),
            new BlockPos(best.getX(), 0, best.getZ()),
            herd.n * WEAR_PER_ANIMAL,
            time);
    herd.prevX = herd.x;
    herd.prevZ = herd.z;
    herd.x = best.getX();
    herd.z = best.getZ();
  }

  // ---- membership ----

  public boolean onMemberJoin(ServerLevel level, Mob mob, Membership membership, boolean fromDisk) {
    Herd herd = herds.get(membership.node());
    if (herd == null) {
      mob.removeData(GuestWilds.MEMBERSHIP);
      return true;
    }
    if (!fromDisk) {
      herd.concrete++;
      setDirty();
      return true;
    }
    advance(level, herd);
    if (!unclaimed(mob) || penned(level, mob.blockPosition())) {
      // Someone tamed, named, leashed or fenced it in: it left the wild population.
      herd.n = Math.max(0.0, herd.n - 1.0);
      herd.concrete = Math.max(0, herd.concrete - 1);
      mob.removeData(GuestWilds.MEMBERSHIP);
      setDirty();
      return true;
    }
    long dx = mob.getBlockX() - herd.x;
    long dz = mob.getBlockZ() - herd.z;
    double far = P.herdJoinRadius() * 1.5;
    boolean died = herd.n - herd.concrete <= -1.0;
    boolean left = WildsConfig.HERD_RELOCATION.get() && dx * dx + dz * dz > far * far;
    if (died || left) {
      herd.concrete = Math.max(0, herd.concrete - 1);
      setDirty();
      return false;
    }
    return true;
  }

  public void onMemberLeave(ServerLevel level, Membership membership, boolean killed) {
    Herd herd = herds.get(membership.node());
    if (herd == null) {
      return;
    }
    if (killed) {
      advance(level, herd);
      herd.n = Math.max(0.0, herd.n - 1.0);
    }
    herd.concrete = Math.max(0, herd.concrete - 1);
    if (herd.n < 0.5 && herd.concrete == 0) {
      herds.remove(herd.id);
    }
    setDirty();
  }

  // ---- observation ----

  public void materializeNear(ServerLevel level, ServerPlayer player) {
    long radiusSqr = (long) OBSERVE_RADIUS * OBSERVE_RADIUS;
    for (Herd herd : herds()) {
      long dx = herd.x - player.getBlockX();
      long dz = herd.z - player.getBlockZ();
      if (dx * dx + dz * dz > radiusSqr) {
        continue;
      }
      advance(level, herd);
      if (herd.n < 0.5 && herd.concrete == 0) {
        herds.remove(herd.id);
        continue;
      }
      EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(herd.species));
      for (int j = 0; j < 2 && herd.n - herd.concrete >= 1.0; j++) {
        BlockPos at = spawnPoint(level, herd, herd.concrete * 7 + j);
        if (at == null) {
          break;
        }
        @SuppressWarnings("unchecked") // SPECIES only holds Mob types
        Mob mob =
            Membership.spawn(
                (EntityType<? extends Mob>) type,
                level,
                at,
                new Membership(Membership.Kind.HERD, herd.id, herd.species));
        if (mob == null) {
          break;
        }
      }
    }
  }

  private static @Nullable BlockPos spawnPoint(ServerLevel level, Herd herd, int index) {
    long h = GuestHash.hash(level.getSeed(), herd.id, SPAWN_SALT, index);
    int x = herd.x + (int) (GuestHash.unit(h) * 17) - 8;
    int z = herd.z + (int) (GuestHash.unit(GuestHash.hash(h, 1)) * 17) - 8;
    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
      return null;
    }
    BlockPos pos =
        new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    boolean seen =
        level.getNearestPlayer(x + 0.5, pos.getY(), z + 0.5, SPAWN_MIN_PLAYER_DISTANCE, false)
            != null;
    return seen || !level.getFluidState(pos.below()).isEmpty() ? null : pos;
  }
}
