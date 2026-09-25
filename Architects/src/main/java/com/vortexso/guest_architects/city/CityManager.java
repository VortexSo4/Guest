package com.vortexso.guest_architects.city;

import com.vortexso.guest_architects.ArchitectsConfig;
import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.entity.Architect;
import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.debug.GuestDebug;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Unit;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.BuiltinStructures;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Per-dimension runtime for ancient cities: discovers them, advances nothing while unobserved, and
 * on observation materializes the continuation of the closed-form city state.
 */
public final class CityManager {
  private static final Map<ServerLevel, CityManager> INSTANCES = new WeakHashMap<>();
  private static final Identifier ANCIENT_CITY = BuiltinStructures.ANCIENT_CITY.identifier();

  private static final int OBSERVE_RADIUS = 112;

  /** Seconds of observation before materializing, so saved entities have time to load first. */
  private static final int SETTLE_SECONDS = 3;

  /** Covers the largest abandoned spread radius, so a revived city can clean it up. */
  private static final int CONTAIN_HORIZONTAL = 12;

  private static final int CONTAIN_VERTICAL = 4;
  private static final int STRAY_SCAN_SECONDS = 15;
  private static final int WARDEN_CONCERN_RADIUS = 24;
  private static final int CHAT_RADIUS = 6;
  private static final float CHAT_CHANCE = 0.3F;
  private static final int RECOGNITION_RADIUS = 10;
  private static final int RECOGNITION_TICKS = 240;

  private static final Identifier RECOGNITION_KIND =
      Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "recognition");

  private final ServerLevel level;
  private final Map<Long, CitySite> sites = new HashMap<>();
  private final Set<Long> queuedChunks = new HashSet<>();

  /** Last game tick each player caused a vibration; runtime only, like the vibration itself. */
  final Map<UUID, Long> lastNoise = new HashMap<>();

  private volatile DebugSnapshot debug = new DebugSnapshot(List.of(), List.of());

  private CityManager(ServerLevel level) {
    this.level = level;
  }

  public static synchronized CityManager get(ServerLevel level) {
    return INSTANCES.computeIfAbsent(level, CityManager::new);
  }

  public ServerLevel level() {
    return level;
  }

  public Collection<CitySite> sites() {
    return sites.values();
  }

  public @Nullable CitySite siteAt(BlockPos pos) {
    for (CitySite site : sites.values()) {
      if (site.contains(pos)) {
        return site;
      }
    }
    return null;
  }

  public @Nullable CitySite nearest(BlockPos pos) {
    return sites.values().stream()
        .min(Comparator.comparingDouble(site -> site.center().distSqr(pos)))
        .orElse(null);
  }

  public void noiseBy(ServerPlayer player, long gameTick) {
    lastNoise.put(player.getUUID(), gameTick);
  }

  public DebugSnapshot debugSnapshot() {
    return debug;
  }

  // ---- discovery ----------------------------------------------------------------------------

  public void queueChunk(ChunkPos pos) {
    long key = pos.pack();
    if (queuedChunks.add(key)) {
      level
          .getServer()
          .execute(
              () -> {
                queuedChunks.remove(key);
                discover(pos);
              });
    }
  }

  private void discover(ChunkPos pos) {
    if (level.getChunkSource().getChunkNow(pos.x(), pos.z()) == null) {
      return;
    }
    for (StructureStart start :
        level.structureManager().startsForStructure(pos, this::isAncientCity)) {
      if (!start.isValid()) {
        continue;
      }
      long id = start.getChunkPos().pack();
      if (!sites.containsKey(id)) {
        CityRecord record = ArchitectsData.get(level).city(id, start.getBoundingBox().getCenter());
        sites.put(id, CitySite.from(id, start, record));
      }
    }
  }

  private boolean isAncientCity(Structure structure) {
    Identifier key = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
    return ANCIENT_CITY.equals(key);
  }

  // ---- per-second update --------------------------------------------------------------------

  public void tick() {
    if (level.getGameTime() % 20 != 0) {
      return;
    }
    CityLife.Params params = ArchitectsConfig.lifeParams();
    double day = CityLife.day(GuestTime.gameTime(level));
    for (CitySite site : sites.values()) {
      site.profile = profile(site, params);
      site.population = CityLife.population(site.profile, day, site.record.delayDays, params);
      if (!observed(site)) {
        site.observedSeconds = 0;
        site.activated = false;
        continue;
      }
      site.observedSeconds++;
      if (!site.activated) {
        if (site.observedSeconds >= SETTLE_SECONDS && loaded(site)) {
          activate(site, params, day);
        }
        continue;
      }
      tickObserved(site, params, day);
    }
    debug = GuestDebug.isEnabled(GuestArchitects.DEBUG_CHANNEL) ? buildDebug() : debug;
  }

  public CityLife.Profile profile(CitySite site, CityLife.Params params) {
    return CityLife.withOverrides(
        CityLife.profile(level.getSeed(), site.id, params),
        site.record.livingOverride.orElse(null),
        site.record.stateOverride.orElse(null));
  }

  private boolean observed(CitySite site) {
    BlockPos c = site.center();
    for (ServerPlayer player : level.players()) {
      double dx = player.getX() - c.getX();
      double dz = player.getZ() - c.getZ();
      if (dx * dx + dz * dz < OBSERVE_RADIUS * OBSERVE_RADIUS
          && player.getY() < site.box.maxY() + 48) {
        return true;
      }
    }
    return false;
  }

  /** Blocks and saved entities must both be present, or materializing would duplicate people. */
  private boolean loaded(CitySite site) {
    for (BlockPos origin : site.origins) {
      if (!loaded(origin)) {
        return false;
      }
    }
    return loaded(site.portalBox.getCenter()) && loaded(site.ritualBox.getCenter());
  }

  private boolean loaded(BlockPos pos) {
    return level.isLoaded(pos)
        && level.areEntitiesLoaded(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
  }

  /** Forces the next observed second to re-materialize, e.g. after a debug override. */
  public void invalidate(CitySite site) {
    site.activated = false;
    site.strays.clear();
  }

  /**
   * Catches the city up to "now" in one step: the aggregate result of maintenance or neglect, the
   * memorial niches it has accumulated, and the Architects alive today.
   */
  private void activate(CitySite site, CityLife.Params params, double day) {
    if (site.living()) {
      for (BlockPos origin : site.origins) {
        scanForStrays(site, origin, true);
      }
    } else if (ArchitectsConfig.ABANDONED_SPREAD.get()) {
      double since = CityLife.abandonedSince(site.profile, site.record.delayDays, params);
      spread(site, CityLife.spreadRadius(day - since, params));
    }
    placeNiches(site, params, day);
    materialize(site);
    site.activated = true;
  }

  private void tickObserved(CitySite site, CityLife.Params params, double day) {
    List<Architect> architects = gatherArchitects(site);
    site.architects = architects;
    // Slow demographic change can also happen in front of the player; births are checked less
    // often because a failed spawn search is not free.
    if (architects.size() > site.population
        || (architects.size() < site.population && site.observedSeconds % 60 == 0)) {
      placeNiches(site, params, day);
      materialize(site);
      site.architects = architects = gatherArchitects(site);
    }
    if (!site.living() || architects.isEmpty()) {
      return;
    }
    if (site.observedSeconds % STRAY_SCAN_SECONDS == 0 && !site.origins.isEmpty()) {
      scanForStrays(site, site.origins.get(site.scanCursor++ % site.origins.size()), false);
    }
    communicate(site, architects);
    recognition(site, architects);
    wardens(site, architects);
    CityRelations.tickPresence(this, site);
  }

  /** Current Architects of the city; duplicates of one identity (a reloaded copy) are removed. */
  private List<Architect> gatherArchitects(CitySite site) {
    List<Architect> found =
        level.getEntitiesOfClass(
            Architect.class, site.aabb(), a -> a.hasCity() && a.cityId() == site.id);
    found.sort(Comparator.comparingInt(Architect::index).thenComparing(a -> -a.tickCount));
    List<Architect> result = new ArrayList<>(found.size());
    int lastIndex = -1;
    for (Architect architect : found) {
      if (architect.index() == lastIndex) {
        architect.discard();
      } else {
        result.add(architect);
        lastIndex = architect.index();
      }
    }
    return result;
  }

  // ---- materialization ----------------------------------------------------------------------

  public void materialize(CitySite site) {
    List<Architect> existing = gatherArchitects(site);
    boolean[] present = new boolean[Math.max(site.population, 1)];
    for (Architect architect : existing) {
      if (architect.index() >= site.population) {
        // Gone; the memorial niches remember them.
        level.sendParticles(
            ParticleTypes.SCULK_SOUL,
            architect.getX(),
            architect.getY() + 1.0,
            architect.getZ(),
            12,
            0.3,
            0.8,
            0.3,
            0.02);
        architect.discard();
      } else {
        present[architect.index()] = true;
      }
    }
    for (int index = 0; index < site.population; index++) {
      if (!present[index]) {
        spawn(site, index);
      }
    }
  }

  private void spawn(CitySite site, int index) {
    ArchitectRole role = CityLife.role(level.getSeed(), site.id, index);
    BlockPos anchor = anchorFor(site, role, index);
    BlockPos stand = findStand(anchor);
    if (stand == null) {
      return;
    }
    Architect architect =
        GuestArchitects.ARCHITECT.get().create(level, EntitySpawnReason.STRUCTURE);
    if (architect == null) {
      return;
    }
    architect.assign(site.id, index, role);
    float yaw = (float) (GuestHash.unit(GuestHash.hash(level.getSeed(), site.id, index)) * 360.0);
    architect.snapTo(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, yaw, 0.0F);
    level.addFreshEntity(architect);
  }

  private BlockPos anchorFor(CitySite site, ArchitectRole role, int index) {
    return switch (role) {
      case RITUAL, TEACHER, YOUNG -> site.ritualBox.getCenter();
      case DEEPSLATE, MELODY -> site.portalBox.getCenter();
      case WOOL, WATCHER -> {
        if (site.origins.isEmpty()) {
          yield site.portalBox.getCenter();
        }
        long h = GuestHash.hash(level.getSeed(), site.id, index, 0x57415443L);
        yield site.origins.get((int) Math.floorMod(h, (long) site.origins.size()));
      }
    };
  }

  /** Nearest spot (deterministic scan order) with a floor and three blocks of headroom. */
  private @Nullable BlockPos findStand(BlockPos anchor) {
    for (int radius = 0; radius <= 8; radius++) {
      for (int dy = 0; dy <= 6; dy = dy <= 0 ? 1 - dy : -dy) {
        for (int dx = -radius; dx <= radius; dx++) {
          for (int dz = -radius; dz <= radius; dz++) {
            if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
              continue;
            }
            BlockPos pos = anchor.offset(dx, dy, dz);
            if (standable(pos)) {
              return pos;
            }
          }
        }
      }
    }
    return null;
  }

  private boolean standable(BlockPos pos) {
    if (!level.isLoaded(pos)) {
      return false;
    }
    BlockPos below = pos.below();
    return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)
        && level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()
        && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
        && level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty()
        && level.getFluidState(pos).isEmpty();
  }

  // ---- sculk --------------------------------------------------------------------------------

  /**
   * Finds sculk that escaped the ritual area around one catalyst origin. On activation the result
   * is applied at once (maintenance happened while nobody watched); while observed it becomes
   * visible work for the ritual maintainers.
   */
  private void scanForStrays(CitySite site, BlockPos origin, boolean immediate) {
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (int dx = -CONTAIN_HORIZONTAL; dx <= CONTAIN_HORIZONTAL; dx++) {
      for (int dz = -CONTAIN_HORIZONTAL; dz <= CONTAIN_HORIZONTAL; dz++) {
        for (int dy = -CONTAIN_VERTICAL; dy <= CONTAIN_VERTICAL; dy++) {
          pos.setWithOffset(origin, dx, dy, dz);
          if (site.inRitualArea(pos) || !level.isLoaded(pos)) {
            continue;
          }
          BlockState state = level.getBlockState(pos);
          if (!state.is(Blocks.SCULK) && !state.is(Blocks.SCULK_VEIN)) {
            continue;
          }
          if (immediate) {
            revertSculk(pos.immutable());
          } else {
            site.strays.add(pos.immutable());
          }
        }
      }
    }
  }

  private void revertSculk(BlockPos pos) {
    BlockState state = level.getBlockState(pos);
    if (state.is(Blocks.SCULK)) {
      level.setBlock(pos, Blocks.DEEPSLATE_TILES.defaultBlockState(), 3);
    } else if (state.is(Blocks.SCULK_VEIN)) {
      level.removeBlock(pos, false);
    }
  }

  /**
   * Uncontrolled growth after abandonment, as a deterministic pattern of the current radius: the
   * same world always ends up with the same patches, however the time was skipped.
   */
  private void spread(CitySite site, int radius) {
    if (radius <= site.record.spreadApplied) {
      return;
    }
    long seed = level.getSeed();
    BlockState vein =
        Blocks.SCULK_VEIN
            .defaultBlockState()
            .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    for (BlockPos origin : site.origins) {
      for (int dx = -radius; dx <= radius; dx++) {
        for (int dz = -radius; dz <= radius; dz++) {
          for (int dy = -3; dy <= 3; dy++) {
            double distance = Math.sqrt(dx * dx + dz * dz + 4.0 * dy * dy);
            if (distance > radius) {
              continue;
            }
            pos.setWithOffset(origin, dx, dy, dz);
            double density = 0.9 * (1.0 - distance / (radius + 1.0));
            double u = GuestHash.unit(GuestHash.hash(seed, site.id, pos.asLong()));
            if (u >= density) {
              continue;
            }
            BlockState state = level.getBlockState(pos);
            if (state.is(BlockTags.SCULK_REPLACEABLE_WORLD_GEN)
                && level.getBlockState(pos.above()).isAir()) {
              level.setBlock(pos, Blocks.SCULK.defaultBlockState(), 2);
            } else if (state.isAir() && u < density * 0.3) {
              BlockPos below = pos.below();
              if (level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) {
                level.setBlock(pos, vein, 2);
              }
            }
          }
        }
      }
    }
    site.record.spreadApplied = radius;
    ArchitectsData.get(level).setDirty();
  }

  // ---- memory -------------------------------------------------------------------------------

  /** One lit candle per remembered individual, at deterministic floor spots of the city center. */
  private void placeNiches(CitySite site, CityLife.Params params, double day) {
    int wanted =
        CityLife.memorialNiches(
            level.getSeed(), site.id, site.profile, day, site.record.delayDays, params);
    if (site.record.nichesPlaced >= wanted) {
      return;
    }
    BlockState candle = Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.LIT, true);
    BoundingBox box = site.portalBox;
    while (site.record.nichesPlaced < wanted) {
      int niche = site.record.nichesPlaced++;
      for (int attempt = 0; attempt < 64; attempt++) {
        long h = GuestHash.hash(level.getSeed(), site.id, niche, attempt);
        BlockPos pos =
            new BlockPos(
                box.minX() + (int) Math.floorMod(h, (long) box.getXSpan()),
                box.minY() + (int) Math.floorMod(h >>> 20, (long) box.getYSpan()),
                box.minZ() + (int) Math.floorMod(h >>> 40, (long) box.getZSpan()));
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (level.getBlockState(pos).isAir()
            && floor.isFaceSturdy(level, below, Direction.UP)
            && !floor.is(BlockTags.WOOL)
            && !floor.is(Blocks.REINFORCED_DEEPSLATE)) {
          level.setBlock(pos, candle, 3);
          break;
        }
      }
    }
    ArchitectsData.get(level).setDirty();
  }

  // ---- tasks --------------------------------------------------------------------------------

  public @Nullable Task nextTask(Architect architect) {
    CitySite site = sites.get(architect.cityId());
    if (site == null || !site.living()) {
      return null;
    }
    RandomSource random = architect.getRandom();
    return switch (architect.role()) {
      case RITUAL -> {
        BlockPos stray = nearestStray(site, architect.blockPosition());
        if (stray != null) {
          yield new Task(Task.Kind.CONTAIN, stray);
        }
        Task repair = repair(site, CityRelations::isSculkFamily);
        yield repair != null
            ? repair
            : new Task(
                Task.Kind.TEND, sample(site.ritualBox, CityRelations::isSculkFamily, random));
      }
      case DEEPSLATE -> {
        Task repair =
            repair(
                site,
                s ->
                    !CityRelations.isSculkFamily(s)
                        && !s.is(BlockTags.WOOL)
                        && !s.is(BlockTags.CANDLES));
        yield repair != null
            ? repair
            : new Task(
                Task.Kind.TEND,
                sample(site.portalBox, s -> s.is(Blocks.REINFORCED_DEEPSLATE), random));
      }
      case WOOL -> {
        Task repair = repair(site, s -> s.is(BlockTags.WOOL) || s.is(BlockTags.CANDLES));
        yield repair != null
            ? repair
            : new Task(Task.Kind.TEND, sample(site.portalBox, s -> s.is(BlockTags.WOOL), random));
      }
      case TEACHER ->
          new Task(Task.Kind.TEND, sample(site.ritualBox, CityRelations::isSculkFamily, random));
      case WATCHER -> {
        BlockPos post =
            site.origins.isEmpty()
                ? site.portalBox.getCenter()
                : site.origins.get(random.nextInt(site.origins.size()));
        yield new Task(Task.Kind.WATCH, post);
      }
      case MELODY -> new Task(Task.Kind.WATCH, site.portalBox.getCenter());
      case YOUNG -> {
        Architect teacher =
            site.architects.stream()
                .filter(a -> a.role() == ArchitectRole.TEACHER && !a.isRemoved())
                .min(Comparator.comparingDouble(a -> a.distanceToSqr(architect)))
                .orElse(null);
        yield teacher != null
            ? new Task(Task.Kind.FOLLOW, teacher.blockPosition())
            : new Task(
                Task.Kind.TEND, sample(site.ritualBox, CityRelations::isSculkFamily, random));
      }
    };
  }

  private @Nullable BlockPos nearestStray(CitySite site, BlockPos from) {
    BlockPos best = null;
    for (BlockPos pos : site.strays) {
      if (best == null || pos.distSqr(from) < best.distSqr(from)) {
        best = pos;
      }
    }
    if (best != null) {
      site.strays.remove(best);
    }
    return best;
  }

  private @Nullable Task repair(CitySite site, Predicate<BlockState> kind) {
    for (CityRecord.Damage damage : site.record.damage) {
      if (kind.test(damage.state())) {
        return new Task(Task.Kind.REPAIR, damage.pos(), damage);
      }
    }
    return null;
  }

  /** Random matching block inside the box; the box center if none is found quickly. */
  private BlockPos sample(BoundingBox box, Predicate<BlockState> match, RandomSource random) {
    for (int attempt = 0; attempt < 32; attempt++) {
      BlockPos pos =
          new BlockPos(
              box.minX() + random.nextInt(box.getXSpan()),
              box.minY() + random.nextInt(box.getYSpan()),
              box.minZ() + random.nextInt(box.getZSpan()));
      if (level.isLoaded(pos) && match.test(level.getBlockState(pos))) {
        return pos;
      }
    }
    return box.getCenter();
  }

  public void complete(Architect architect, Task task) {
    CitySite site = sites.get(architect.cityId());
    if (site == null) {
      return;
    }
    Vec3 at = task.pos().getCenter();
    switch (task.kind()) {
      case CONTAIN -> {
        revertSculk(task.pos());
        level.sendParticles(
            ParticleTypes.SCULK_SOUL, at.x, at.y + 0.5, at.z, 3, 0.2, 0.1, 0.2, 0.02);
      }
      case REPAIR -> {
        CityRecord.Damage damage = task.damage();
        if (damage != null && site.record.damage.remove(damage)) {
          if (level.getBlockState(task.pos()).canBeReplaced()) {
            level.setBlock(task.pos(), damage.state(), 3);
          }
          ArchitectsData.get(level).setDirty();
        }
      }
      default -> {}
    }
  }

  // ---- communication, recognition, Wardens --------------------------------------------------

  /** Silent exchange of enchanting-table glyphs between nearby Architects. */
  private void communicate(CitySite site, List<Architect> architects) {
    RandomSource random = level.getRandom();
    for (Architect speaker : architects) {
      if (speaker.busy() || random.nextFloat() > CHAT_CHANCE) {
        continue;
      }
      Architect listener = null;
      double best = CHAT_RADIUS * CHAT_RADIUS;
      for (Architect other : architects) {
        double d = other.distanceToSqr(speaker);
        // Teacher and young talk more: prefer that pair when both are near.
        if (other.role() == ArchitectRole.YOUNG && speaker.role() == ArchitectRole.TEACHER) {
          d *= 0.25;
        }
        if (other != speaker && d < best) {
          best = d;
          listener = other;
        }
      }
      if (listener != null) {
        glyphs(level, speaker.getEyePosition(), listener.getEyePosition(), 5);
      }
      CityRelations.shareWithHelper(this, site, speaker);
    }
  }

  private void recognition(CitySite site, List<Architect> architects) {
    if (site.record.recognitionUsed) {
      return;
    }
    Architect observant =
        architects.stream()
            .filter(a -> a.role() == ArchitectRole.WATCHER)
            .findFirst()
            .orElse(architects.getFirst());
    if (observant.busy() || observant.isBaby()) {
      return;
    }
    for (ServerPlayer player : level.players()) {
      if (!player.isSpectator()
          && observant.distanceToSqr(player) < RECOGNITION_RADIUS * RECOGNITION_RADIUS
          && observant.hasLineOfSight(player)) {
        observant.watch(player, RECOGNITION_TICKS, true);
        site.record.recognitionUsed = true;
        ArchitectsData.get(level).setDirty();
        CityRelations.history(level, site, player, RECOGNITION_KIND);
        return;
      }
    }
  }

  /**
   * An emerged Warden close to Architects gets the melody; one hunting elsewhere does not. The
   * player gets no protection that the Architects do not need themselves.
   */
  private void wardens(CitySite site, List<Architect> architects) {
    for (Warden warden : level.getEntitiesOfClass(Warden.class, site.aabb())) {
      if (warden.hasPose(Pose.EMERGING) || warden.hasPose(Pose.DIGGING)) {
        continue;
      }
      boolean concerned = false;
      boolean handled = false;
      for (Architect architect : architects) {
        concerned |=
            architect.distanceToSqr(warden) < WARDEN_CONCERN_RADIUS * WARDEN_CONCERN_RADIUS;
        handled |= architect.melodyTarget() == warden;
      }
      if (!concerned || handled) {
        continue;
      }
      architects.stream()
          .filter(a -> !a.isBaby())
          .min(
              Comparator.comparing((Architect a) -> a.role() != ArchitectRole.MELODY)
                  .thenComparingDouble(a -> a.distanceToSqr(warden)))
          .ifPresent(a -> a.playMelodyFor(warden));
    }
  }

  /** The melody's effect: anger is forgotten and the Warden may dig back down. */
  public static void calm(ServerLevel level, Warden warden) {
    for (LivingEntity entity :
        level.getEntitiesOfClass(LivingEntity.class, warden.getBoundingBox().inflate(64))) {
      warden.clearAnger(entity);
    }
    Brain<Warden> brain = warden.getBrain();
    brain.eraseMemory(MemoryModuleType.ATTACK_TARGET);
    brain.eraseMemory(MemoryModuleType.ROAR_TARGET);
    brain.eraseMemory(MemoryModuleType.DISTURBANCE_LOCATION);
    brain.eraseMemory(MemoryModuleType.WALK_TARGET);
    // A short cooldown instead of none: the Warden settles for a moment before digging.
    brain.setMemoryWithExpiry(MemoryModuleType.DIG_COOLDOWN, Unit.INSTANCE, 60L);
    level.sendParticles(
        ParticleTypes.SCULK_SOUL,
        warden.getX(),
        warden.getY() + 2.0,
        warden.getZ(),
        8,
        0.5,
        0.5,
        0.5,
        0.02);
  }

  /** Enchanting-table glyphs that start at {@code from} and settle at {@code to}. */
  public static void glyphs(ServerLevel level, Vec3 from, Vec3 to, int count) {
    RandomSource random = level.getRandom();
    for (int i = 0; i < count; i++) {
      double jx = (random.nextDouble() - 0.5) * 0.4;
      double jy = (random.nextDouble() - 0.5) * 0.4;
      double jz = (random.nextDouble() - 0.5) * 0.4;
      level.sendParticles(
          ParticleTypes.ENCHANT,
          to.x,
          to.y,
          to.z,
          0,
          from.x - to.x + jx,
          from.y - to.y + jy + 0.3,
          from.z - to.z + jz,
          1.0);
    }
  }

  public void onArchitectAttacked(Architect architect, ServerPlayer player) {
    CitySite site = sites.get(architect.cityId());
    if (site != null && site.living()) {
      CityRelations.interfere(this, site, player, CityRelations.ATTACK_POINTS);
    }
  }

  // ---- debug --------------------------------------------------------------------------------

  private DebugSnapshot buildDebug() {
    long now = GuestTime.gameTime(level);
    double decay = ArchitectsConfig.INTERFERENCE_DECAY_PER_DAY.get();
    List<DebugCity> cities = new ArrayList<>();
    List<DebugArchitect> architects = new ArrayList<>();
    for (CitySite site : sites.values()) {
      List<String> relations = new ArrayList<>();
      site.record.relations.forEach(
          (uuid, relation) -> {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(uuid);
            String name =
                player == null ? uuid.toString().substring(0, 8) : player.getGameProfile().name();
            relations.add(
                String.format(
                    java.util.Locale.ROOT,
                    "%s: %.1f / %.2f",
                    name,
                    relation.pointsAt(now, decay),
                    relation.assistance()));
          });
      cities.add(
          new DebugCity(
              site.id,
              site.box,
              site.ritualBox,
              site.portalBox,
              site.profile == null ? null : site.profile.state(),
              site.profile != null && site.profile.living(),
              site.population,
              site.record.delayDays,
              site.record.recognitionUsed,
              site.record.spreadApplied,
              site.strays.size(),
              site.record.damage.size(),
              List.copyOf(relations),
              List.copyOf(site.origins)));
      for (Architect architect : site.architects) {
        if (!architect.isRemoved()) {
          architects.add(
              new DebugArchitect(
                  architect.position(),
                  architect.getBbHeight(),
                  site.id,
                  architect.index(),
                  architect.role(),
                  architect.activityKey(),
                  architect.debugTarget()));
        }
      }
    }
    return new DebugSnapshot(List.copyOf(cities), List.copyOf(architects));
  }

  public record DebugSnapshot(List<DebugCity> cities, List<DebugArchitect> architects) {}

  public record DebugCity(
      long id,
      BoundingBox box,
      BoundingBox ritualBox,
      BoundingBox portalBox,
      CityLife.@Nullable State state,
      boolean living,
      int population,
      double delayDays,
      boolean recognized,
      int spreadRadius,
      int strays,
      int damage,
      List<String> relations,
      List<BlockPos> origins) {}

  public record DebugArchitect(
      Vec3 position,
      float height,
      long cityId,
      int index,
      ArchitectRole role,
      String activity,
      @Nullable Vec3 target) {}
}
