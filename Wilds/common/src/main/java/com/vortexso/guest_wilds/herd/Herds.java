package com.vortexso.guest_wilds.herd;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_wilds.Ecology;
import com.vortexso.guest_wilds.GuestWilds;
import com.vortexso.guest_wilds.Membership;
import com.vortexso.guest_wilds.WildsConfig;
import com.vortexso.guest_wilds.WildsParameters;
import com.vortexso.guest_wilds.behavior.WildBehavior;
import com.vortexso.guest_wilds.lair.CaveScan;
import com.vortexso.guest_wilds.lair.Lairs;
import com.vortexso.guest_wilds.path.PathWear;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;

public final class Herds extends SavedData {
  public static final int CELL_SHIFT = 6;
  private static final WildsParameters P = WildsParameters.DEFAULT;
  private static final long MIGRATION_SALT = 0x6865726473L;
  private static final long SPAWN_SALT = 0x6865726474L;
  private static final long GRAZE_SALT = 0x6865726475L;
  private static final int OBSERVE_RADIUS = 96;
  private static final int SPAWN_MIN_PLAYER_DISTANCE = 24;
  private static final int SPAWN_SPREAD = 6;
  private static final int JOIN_VISIBLE_RADIUS = 48;
  private static final int PEN_RADIUS = 6;
  private static final int WATER_SEARCH = 40;
  private static final int STRAY = 24;
  private static final int PREY_RANGE = 64;
  private static final int VILLAGE_RANGE = 128;
  private static final int WOLF_RANGE = 64;
  private static final String WOLF = "minecraft:wolf";

  private static final long DRINK_UNTIL = 2_500L;

  private static final long GRAZE_UNTIL = 12_000L;
  private static final long GRAZE_SPOT_TICKS = 2_400L;

  public static final Set<EntityType<?>> SPECIES =
      Set.of(
          EntityType.COW,
          EntityType.SHEEP,
          EntityType.PIG,
          EntityType.HORSE,
          EntityType.DONKEY,
          EntityType.GOAT,
          EntityType.LLAMA,
          EntityType.MOOSHROOM,
          EntityType.WOLF);

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
    @Nullable BlockPos water;

    @Nullable UUID leader;

    boolean waterSearched;

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
                        Codec.LONG.fieldOf("step").forGetter(h -> h.step),
                        BlockPos.CODEC
                            .optionalFieldOf("water")
                            .forGetter(h -> Optional.ofNullable(h.water)))
                    .apply(
                        i,
                        (id, species, n, concrete, x, z, prevX, prevZ, step, water) ->
                            new Herd(
                                id,
                                species,
                                n,
                                concrete,
                                x,
                                z,
                                prevX,
                                prevZ,
                                step,
                                water.orElse(null))));

    Herd(
        long id,
        String species,
        double n,
        int concrete,
        int x,
        int z,
        int prevX,
        int prevZ,
        long step,
        @Nullable BlockPos water) {
      this.id = id;
      this.species = species;
      this.n = n;
      this.concrete = concrete;
      this.x = x;
      this.z = z;
      this.prevX = prevX;
      this.prevZ = prevZ;
      this.step = step;
      this.water = water;
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

    public @Nullable BlockPos water() {
      return water;
    }

    public @Nullable UUID leaderId() {
      return leader;
    }

    public boolean isPack() {
      return WOLF.equals(species);
    }

    double capacity() {
      return isPack() ? P.packCapacity() : P.herdCapacity();
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
          Identifier.fromNamespaceAndPath(GuestWilds.MODID, "herds"), Herds::new, CODEC, null);

  private final Long2ObjectOpenHashMap<Herd> herds = new Long2ObjectOpenHashMap<>();

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

  public static boolean isWildCandidate(Mob mob) {
    EntitySpawnReason reason = GuestWilds.spawnReason.apply(mob);
    return SPECIES.contains(mob.getType())
        && (reason == EntitySpawnReason.NATURAL || reason == EntitySpawnReason.CHUNK_GENERATION)
        && unclaimed(mob);
  }

  private static boolean unclaimed(Mob mob) {
    return !mob.hasCustomName()
        && !mob.isLeashed()
        && !mob.isPassenger()
        && !(mob instanceof AbstractHorse horse && horse.isTamed())
        && !(mob instanceof TamableAnimal tamable && tamable.isTame());
  }

  private static boolean penned(ServerLevel level, BlockPos pos) {
    BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    for (int dx = -PEN_RADIUS; dx <= PEN_RADIUS; dx++) {
      for (int dz = -PEN_RADIUS; dz <= PEN_RADIUS; dz++) {
        for (int dy = -1; dy <= 1; dy++) {
          cursor.setWithOffset(pos, dx, dy, dz);
          if (!GuestWilds.loaded(level, cursor)) {
            continue;
          }
          BlockState state = level.getBlockState(cursor);
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

  private static boolean inVillage(ServerLevel level, BlockPos pos) {
    if (level.isCloseToVillage(pos, 3)) {
      return true;
    }
    var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
    for (Structure structure : level.structureManager().getAllStructuresAt(pos).keySet()) {
      Identifier id = registry.getKey(structure);
      if (id != null && id.getPath().startsWith("village")) {
        return true;
      }
    }
    return false;
  }

  public List<Herd> herdsNear(BlockPos pos, int radius) {
    long radiusSqr = (long) radius * radius;
    return herds.values().stream()
        .filter(h -> sqr(h.x - pos.getX()) + sqr(h.z - pos.getZ()) <= radiusSqr)
        .sorted(
            java.util.Comparator.comparingLong(h -> sqr(h.x - pos.getX()) + sqr(h.z - pos.getZ())))
        .toList();
  }

  private static long sqr(long value) {
    return value * value;
  }

  private @Nullable Herd nearest(String species, int x, int z, int radius) {
    Herd best = null;
    long bestSqr = (long) radius * radius;

    for (Herd candidate : herds.values()) {
      long dx = candidate.x - x;
      long dz = candidate.z - z;
      long sqr = dx * dx + dz * dz;
      if (candidate.species.equals(species) && sqr <= bestSqr) {
        best = candidate;
        bestSqr = sqr;
      }
    }
    return best;
  }

  public boolean assign(ServerLevel level, Mob mob, boolean fromDisk) {

    if (inVillage(level, mob.blockPosition())) {
      return true;
    }
    String species = BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString();
    long now = GuestTime.gameTime(level);
    Herd herd = nearest(species, mob.getBlockX(), mob.getBlockZ(), P.herdGatherRadius());
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
              Ecology.step(now),
              null);
      herds.put(herd.id, herd);
    } else {
      advance(level, herd);
    }

    Mob head = leader(level, herd, null);
    double hx = head != null ? head.getX() : herd.x;
    double hz = head != null ? head.getZ() : herd.z;
    double dx = mob.getX() - hx;
    double dz = mob.getZ() - hz;
    boolean visible =
        fromDisk
            || herd.concrete == 0
            || dx * dx + dz * dz <= (double) JOIN_VISIBLE_RADIUS * JOIN_VISIBLE_RADIUS;
    setDirty();
    if (!visible) {

      herd.n = Math.min(herd.capacity(), herd.n + 1.0);
      return false;
    }
    herd.n += 1.0;
    herd.concrete++;
    GuestWilds.MEMBERSHIP.set(mob, new Membership(Membership.Kind.HERD, herd.id, species));
    return true;
  }

  public void advance(ServerLevel level, Herd herd) {
    long target = Ecology.step(GuestTime.gameTime(level));
    long steps = Ecology.stepsToRun(herd.step, target);
    herd.step = Math.max(herd.step, target);
    if (steps == 0) {
      return;
    }
    double predators =
        herd.isPack()
            ? 0.0
            : Lairs.hostilePressure(level, herd.center(), 48) + wolves(herd.center(), WOLF_RANGE);
    double death = P.herdMortalityPerDay() + P.predationPerDayAtBaseline() * predators;

    double graze = herd.isPack() ? 0.0 : P.grazePerAnimalDay();
    long first = target - steps;
    for (long s = 1; s <= steps; s++) {
      long time = (first + s) * Ecology.STEP_TICKS;
      long cell = herd.cell();
      Ecology.Herd next =
          Ecology.herdStep(
              new Ecology.Herd(herd.n, vegetation(cell, time)),
              P.herdBirthPerDay(),
              death,
              graze,
              P.vegetationRegrowthPerDay(),
              herd.capacity(),
              Ecology.STEP_DAYS);

      herd.n = Math.max(next.animals(), herd.concrete);
      if (!herd.isPack()) {
        setVegetation(cell, next.vegetation(), time);
        if (next.vegetation() < P.migrateBelowVegetation() && herd.n >= 0.5) {
          migrate(level, herd, next.vegetation(), time);
        }
      }
    }
    if (herd.concrete == 0 && herd.water != null && !herd.isPack()) {

      double daily = 2.0 * herd.n * P.trailWearPerAnimal();
      PathWear.get(level)
          .addRoute(
              level,
              herd.center(),
              herd.water,
              Ecology.accumulated(daily, steps * Ecology.STEP_DAYS, P.wearHalfLifeDays()),
              GuestTime.gameTime(level));
    }
    setDirty();
  }

  public double wolves(BlockPos pos, int radius) {
    double wolves = 0.0;
    long radiusSqr = (long) radius * radius;
    for (Herd pack : herds.values()) {
      long dx = pack.x - pos.getX();
      long dz = pack.z - pos.getZ();
      if (pack.isPack() && dx * dx + dz * dz <= radiusSqr) {
        wolves += pack.n;
      }
    }
    return wolves / P.wolvesPerPredatorUnit();
  }

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
                - P.predatorAvoidance()
                    * (Lairs.hostilePressure(level, center, 32) + wolves(center, WOLF_RANGE))
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

    if (best == null || bestVegetation < here + 0.2) {
      return;
    }
    PathWear.get(level)
        .addRoute(
            level,
            new BlockPos(herd.x, 0, herd.z),
            new BlockPos(best.getX(), 0, best.getZ()),
            herd.n * P.trailWearPerAnimal(),
            time);
    herd.prevX = herd.x;
    herd.prevZ = herd.z;
    herd.x = best.getX();
    herd.z = best.getZ();
    herd.water = null;
    herd.waterSearched = false;
  }

  public @Nullable Mob leader(ServerLevel level, Herd herd, @Nullable Mob candidate) {
    if (herd.leader != null
        && level.getEntity(herd.leader) instanceof Mob head
        && head.isAlive()
        && Membership.of(head) instanceof Membership m
        && m.kind() == Membership.Kind.HERD
        && m.node() == herd.id) {
      return head;
    }
    if (candidate == null) {
      return null;
    }
    herd.leader = candidate.getUUID();
    return candidate;
  }

  public @Nullable BlockPos destination(ServerLevel level, Herd herd, Mob leader) {
    long now = GuestTime.gameTime(level);
    BlockPos at = leader.blockPosition();
    WeatherState weather = GuestWeather.get(level, at, now);
    if (herd.isPack()) {
      if (weather.type().isSnow() && weather.isSevere()) {
        BlockPos bell = village(level, at);
        if (bell != null) {
          return bell;
        }
      }
      return prey(level, herd);
    }
    if (WildsConfig.WEATHER_SHELTER.get()
        && level.canSeeSky(at)
        && WildBehavior.seeksShelter(weather)) {
      return WildBehavior.hide(level, leader);
    }
    long dx = at.getX() - herd.x;
    long dz = at.getZ() - herd.z;
    if (dx * dx + dz * dz > (long) STRAY * STRAY) {
      return surface(level, herd.x, herd.z);
    }
    findWater(level, herd);
    long tick = GuestTime.tickOfDay(now);
    if (tick < DRINK_UNTIL && herd.water != null) {
      return herd.water;
    }
    if (tick < GRAZE_UNTIL) {
      long spot = GuestTime.day(now) * 8 + (tick - DRINK_UNTIL) / GRAZE_SPOT_TICKS;
      long h = GuestHash.hash(level.getSeed(), herd.id, GRAZE_SALT, spot);
      return surface(
          level,
          herd.x + (int) (GuestHash.unit(h) * 29) - 14,
          herd.z + (int) (GuestHash.unit(GuestHash.hash(h, 1)) * 29) - 14);
    }
    return null;
  }

  private static @Nullable BlockPos village(ServerLevel level, BlockPos at) {
    return level
        .getPoiManager()
        .findClosest(
            holder -> holder.is(PoiTypes.MEETING), at, VILLAGE_RANGE, PoiManager.Occupancy.ANY)
        .orElse(null);
  }

  private @Nullable BlockPos prey(ServerLevel level, Herd pack) {
    Herd best = null;
    long bestSqr = (long) PREY_RANGE * PREY_RANGE;
    for (Herd herd : herds.values()) {
      long dx = herd.x - pack.x;
      long dz = herd.z - pack.z;
      long sqr = dx * dx + dz * dz;
      if (!herd.isPack() && herd.n >= 1.0 && sqr <= bestSqr) {
        best = herd;
        bestSqr = sqr;
      }
    }
    return best == null ? null : surface(level, best.x, best.z);
  }

  private void findWater(ServerLevel level, Herd herd) {
    if (herd.waterSearched) {
      return;
    }
    herd.waterSearched = true;
    herd.water = null;
    setDirty();
    for (int r = 4; r <= WATER_SEARCH; r += 4) {
      int bestSlope = Integer.MAX_VALUE;
      for (int i = -r; i <= r; i += 4) {
        for (int[] offset : new int[][] {{i, -r}, {i, r}, {-r, i}, {r, i}}) {
          int x = herd.x + offset[0];
          int z = herd.z + offset[1];
          if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
            continue;
          }
          BlockPos top = new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z), z);
          if (!level.getFluidState(top.below()).is(FluidTags.WATER)) {
            continue;
          }
          double length = Math.max(1.0, Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1]));
          double stepX = offset[0] / length;
          double stepZ = offset[1] / length;
          for (int back = 1; back <= 3; back++) {
            BlockPos shore =
                surface(
                    level, x - (int) Math.round(stepX * back), z - (int) Math.round(stepZ * back));
            if (shore == null) {
              continue;
            }
            BlockPos exit =
                surface(
                    level,
                    x - (int) Math.round(stepX * (back + 1)),
                    z - (int) Math.round(stepZ * (back + 1)));
            int drop = shore.getY() - top.getY();
            int slope =
                exit == null ? Integer.MAX_VALUE : drop + Math.abs(exit.getY() - shore.getY());
            if (drop >= 0 && drop <= 1 && slope <= 2 && slope < bestSlope) {
              bestSlope = slope;
              herd.water = shore;
            }
            break;
          }
        }
      }
      if (herd.water != null) {
        return;
      }
    }
  }

  private static @Nullable BlockPos surface(ServerLevel level, int x, int z) {
    if (level.getChunkSource().getChunkNow(x >> 4, z >> 4) == null) {
      return null;
    }
    BlockPos top =
        new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    BlockPos ground = top.below();
    return level.getFluidState(ground).isEmpty() && CaveScan.isNatural(level.getBlockState(ground))
        ? top
        : null;
  }

  public boolean onMemberJoin(ServerLevel level, Mob mob, Membership membership, boolean fromDisk) {
    Herd herd = herds.get(membership.node());
    if (herd == null) {
      GuestWilds.MEMBERSHIP.set(mob, Membership.NONE);
      return true;
    }
    if (!fromDisk) {
      herd.concrete++;
      setDirty();
      return true;
    }
    advance(level, herd);
    if (!unclaimed(mob) || penned(level, mob.blockPosition())) {

      herd.n = Math.max(0.0, herd.n - 1.0);
      herd.concrete = Math.max(0, herd.concrete - 1);
      GuestWilds.MEMBERSHIP.set(mob, Membership.NONE);
      setDirty();
      return true;
    }
    long dx = mob.getBlockX() - herd.x;
    long dz = mob.getBlockZ() - herd.z;
    double far = P.herdGatherRadius() * 1.5;
    boolean died = herd.n - herd.concrete <= -1.0;
    boolean left = WildsConfig.HERD_RELOCATION.get() && dx * dx + dz * dz > far * far;
    if (died || left) {
      herd.concrete = Math.max(0, herd.concrete - 1);
      setDirty();
      return false;
    }
    return true;
  }

  public void onMemberLeave(
      ServerLevel level, Entity entity, Membership membership, boolean killed) {
    Herd herd = herds.get(membership.node());
    if (herd == null) {
      return;
    }
    if (killed) {
      advance(level, herd);
      herd.n = Math.max(0.0, herd.n - 1.0);
    }
    herd.concrete = Math.max(0, herd.concrete - 1);
    if (entity.getUUID().equals(herd.leader)) {
      herd.leader = null;
    }
    if (herd.n < 0.5 && herd.concrete == 0) {
      herds.remove(herd.id);
    }
    setDirty();
  }

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
        setDirty();
        continue;
      }
      Mob head = leader(level, herd, null);
      if (herd.isPack()
          && head != null
          && head.distanceToSqr(herd.x, head.getY(), herd.z) > 16 * 16) {

        herd.x = head.getBlockX();
        herd.z = head.getBlockZ();
        setDirty();
      }
      int unseen = (int) Math.floor(herd.n - herd.concrete + 1e-6);
      if (unseen < 1) {
        continue;
      }
      BlockPos anchor = head != null ? head.blockPosition() : surface(level, herd.x, herd.z);
      if (anchor == null
          || player.distanceToSqr(anchor.getCenter())
              < SPAWN_MIN_PLAYER_DISTANCE * SPAWN_MIN_PLAYER_DISTANCE) {
        continue;
      }
      EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse(herd.species));
      int batch = Math.min(unseen, P.herdBatch());
      for (int j = 0; j < batch; j++) {
        BlockPos at = spawnPoint(level, herd, anchor, herd.concrete * 7 + j);
        if (at == null) {
          continue;
        }
        @SuppressWarnings("unchecked")
        Mob mob =
            Membership.spawn(
                (EntityType<? extends Mob>) type,
                level,
                at,
                new Membership(Membership.Kind.HERD, herd.id, herd.species));
        if (mob != null && leader(level, herd, null) == null) {
          herd.leader = mob.getUUID();
        }
      }
    }
  }

  private static @Nullable BlockPos spawnPoint(
      ServerLevel level, Herd herd, BlockPos anchor, int index) {
    long h = GuestHash.hash(level.getSeed(), herd.id, SPAWN_SALT, index);
    int spread = 2 * SPAWN_SPREAD + 1;
    BlockPos pos =
        surface(
            level,
            anchor.getX() + (int) (GuestHash.unit(h) * spread) - SPAWN_SPREAD,
            anchor.getZ() + (int) (GuestHash.unit(GuestHash.hash(h, 1)) * spread) - SPAWN_SPREAD);
    if (pos == null) {
      return null;
    }
    boolean seen =
        level.getNearestPlayer(
                pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, SPAWN_MIN_PLAYER_DISTANCE, false)
            != null;
    return seen ? null : pos;
  }
}
