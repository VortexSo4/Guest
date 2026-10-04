package com.vortexso.guest_settlements.illager;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.history.GuestHistory;
import com.vortexso.guest_core.api.history.HistoryRecord;
import com.vortexso.guest_core.api.world.GuestWeather;
import com.vortexso.guest_settlements.SettlementsConfig;
import com.vortexso.guest_settlements.memory.VillageMemory;
import com.vortexso.guest_settlements.mixin.MobAccessor;
import com.vortexso.guest_settlements.society.SocietyData;
import com.vortexso.guest_settlements.society.SocietyData.Camp;
import com.vortexso.guest_settlements.society.SocietyData.CampKind;
import com.vortexso.guest_settlements.society.Traces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.goal.MoveTowardsRestrictionGoal;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class IllagerCamps {
  public static final String MATERIALIZED_TAG = "guest_settlements.materialized";
  public static final String CREW_TAG = "guest_settlements.crew";
  public static final String LEADER_TAG = "guest_settlements.leader";
  public static final String RAVAGER_TAG = "guest_settlements.camp_ravager:";

  public static final int PEN_CAPACITY = 2;
  private static final int PEN_RETRY = 200;
  private static final int WATCH = 80;
  private static final int UNWATCH = 112;
  private static final int CAMP_MARGIN = 24;
  private static final int INTERVAL = 20;
  private static final int JOB_INTERVAL = 40;
  private static final int CREW_REGROWTH_DAYS = 8;
  private static final int MAX_CATCH_UP_DAYS = 1024;
  private static final long WORK_START = 1000;
  private static final long WORK_END = 11000;
  private static final long DUSK_END = 12500;
  private static final long EVENT_RITUAL = 0x2170A1L;
  private static final long EVENT_RANK = 0x2A4BL;
  private static final long EVENT_ILLUSIONER = 0x111D5L;
  private static final long EVENT_SITE = 0x517EL;

  private static final Identifier OUTPOST = Identifier.withDefaultNamespace("pillager_outpost");
  private static final Identifier MANSION = Identifier.withDefaultNamespace("mansion");

  public enum JobKind {
    LEAD,
    CHOP,
    TRAIN,
    GUARD,
    SHEAR,
    FEED,
    PRACTICE,
    RITUAL,
    SUBJECT,
    RETURN
  }

  public record Job(JobKind kind, long camp, BlockPos site, @Nullable UUID target) {}

  record Features(List<BlockPos> targets, List<BoundingBox> cages) {
    static final Features NONE = new Features(List.of(), List.of());
  }

  static final class Live {
    final List<UUID> materialized = new ArrayList<>();
    @Nullable UUID ritualist;
    @Nullable BlockPos home;
    @Nullable UUID subject;
    @Nullable BlockPos circle;
    long circleDay = Long.MIN_VALUE;
    long ritualStarted = -1;
    long nextPractice;
    long poseUntil;
  }

  private static final Map<ServerLevel, Map<Long, Live>> LIVE = new WeakHashMap<>();
  private static final Map<ServerLevel, Map<Long, Features>> FEATURES = new WeakHashMap<>();
  private static final Map<UUID, Job> JOBS = new ConcurrentHashMap<>();
  private static final Set<Long> QUEUED = ConcurrentHashMap.newKeySet();
  private static final Map<Raid, Boolean> RAIDS = new WeakHashMap<>();

  private static final Map<Long, Long> FORCED = new ConcurrentHashMap<>();

  private IllagerCamps() {}

  public static @Nullable Job job(Entity entity) {
    return JOBS.get(entity.getUUID());
  }

  public static Map<UUID, Job> jobs() {
    return JOBS;
  }

  public static void onChunkLoad(ServerLevel level, LevelChunk chunk, boolean newChunk) {
    {
      ChunkPos pos = chunk.getPos();
      if (QUEUED.add(pos.pack())) {
        level
            .getServer()
            .execute(
                () -> {
                  QUEUED.remove(pos.pack());
                  register(level, pos);
                });
      }
    }
  }

  private static void register(ServerLevel level, ChunkPos chunk) {
    for (StructureStart start :
        level
            .structureManager()
            .startsForStructure(chunk, structure -> kind(level, structure) != null)) {
      CampKind kind = kind(level, start.getStructure());
      BoundingBox box = start.getBoundingBox();
      long id = box.getCenter().asLong();
      SocietyData data = SocietyData.get(level);
      if (!data.camps.containsKey(id)) {
        data.putCamp(
            new Camp(
                id,
                kind,
                box.getCenter(),
                box,
                kind == CampKind.MANSION ? 2 : 1,
                0,
                0,
                crewSize(kind),
                today(level) - 1,
                Optional.empty()));
      }
      FEATURES.computeIfAbsent(level, ignored -> new HashMap<>()).put(id, features(start));
    }
  }

  private static @Nullable CampKind kind(ServerLevel level, Structure structure) {
    Identifier key = level.registryAccess().lookupOrThrow(Registries.STRUCTURE).getKey(structure);
    return OUTPOST.equals(key) ? CampKind.OUTPOST : MANSION.equals(key) ? CampKind.MANSION : null;
  }

  private static int crewSize(CampKind kind) {
    return kind == CampKind.MANSION ? 3 : 0;
  }

  private static Features features(StructureStart start) {
    List<BlockPos> targets = new ArrayList<>();
    List<BoundingBox> cages = new ArrayList<>();
    for (StructurePiece piece : start.getPieces()) {
      if (piece instanceof PoolElementStructurePiece pool
          && pool.getElement() instanceof SinglePoolElement single) {
        String name = single.getTemplateLocation().getPath();
        if (name.contains("feature_targets")) {
          targets.add(piece.getBoundingBox().getCenter());
        } else if (name.contains("feature_cage")) {
          cages.add(piece.getBoundingBox());
        }
      }
    }
    return new Features(List.copyOf(targets), List.copyOf(cages));
  }

  public static @Nullable Camp campAt(ServerLevel level, BlockPos pos) {
    for (Camp camp : SocietyData.get(level).camps.values()) {
      if (area(camp).contains(Vec3.atCenterOf(pos))) {
        return camp;
      }
    }
    return null;
  }

  static AABB area(Camp camp) {
    return AABB.of(camp.box()).inflate(CAMP_MARGIN, 16, CAMP_MARGIN);
  }

  public static int prestige(ServerLevel level, Camp camp) {
    int prestige = 0;
    for (HistoryRecord record : raidHistory(level, camp)) {
      prestige += record.kind().equals(VillageMemory.RAID_WON) ? 1 : -1;
    }
    return prestige;
  }

  private static List<HistoryRecord> raidHistory(ServerLevel level, Camp camp) {
    return GuestHistory.get(level)
        .near(
            camp.center(),
            96,
            r ->
                r.kind().equals(VillageMemory.RAID_WON)
                    || r.kind().equals(VillageMemory.RAID_FAILED));
  }

  public static long leaderEpoch(ServerLevel level, Camp camp) {
    return raidHistory(level, camp).stream()
        .filter(r -> r.kind().equals(VillageMemory.RAID_FAILED))
        .count();
  }

  public static void forceRitual(ServerLevel level, Camp camp) {
    FORCED.put(camp.id(), today(level));
  }

  public static boolean ritualDue(ServerLevel level, Camp camp, long day, int prestige) {
    if (!SettlementsConfig.enabled(SettlementsConfig.RITUALS)
        || camp.evokers() <= 0
        || illusionist(level, camp)) {
      return false;
    }
    if (FORCED.getOrDefault(camp.id(), Long.MIN_VALUE) == day) {
      return true;
    }
    if (camp.ravagers() + camp.pending() >= PEN_CAPACITY) {
      return false;
    }
    double factor = Math.clamp(1.0 + 0.25 * prestige, 0.5, 3.0);
    double chance = SettlementsConfig.value(SettlementsConfig.RITUAL_CHANCE) * factor;
    return GuestHash.unit(GuestHash.hash(level.getSeed(), camp.id(), day, EVENT_RITUAL)) < chance;
  }

  public static boolean illusionist(ServerLevel level, Camp camp) {
    return camp.kind() == CampKind.MANSION
        && GuestHash.unit(GuestHash.hash(level.getSeed(), camp.id(), EVENT_ILLUSIONER)) < 0.25;
  }

  static Camp settle(ServerLevel level, Camp camp, long today) {
    if (camp.day() >= today - 1) {
      return camp;
    }
    int prestige = prestige(level, camp);
    int pending = camp.pending();
    int crew = camp.crew();
    for (long day = Math.max(camp.day() + 1, today - MAX_CATCH_UP_DAYS); day < today; day++) {
      Camp current = camp.withDay(day - 1, pending);
      if (ritualDue(level, current, day, prestige)) {
        pending++;
      }
      if (Math.floorMod(day, CREW_REGROWTH_DAYS) == 0) {
        crew = Math.min(crewSize(camp.kind()), crew + 1);
      }
    }
    return camp.withCrew(crew).withDay(today - 1, pending);
  }

  public static void onLevelTick(ServerLevel level) {
    if (level.getGameTime() % INTERVAL != 0) {
      return;
    }
    trackRaids(level);
    SocietyData data = SocietyData.get(level);
    Map<Long, Live> lives = LIVE.computeIfAbsent(level, ignored -> new HashMap<>());
    boolean enabled = SettlementsConfig.enabled(SettlementsConfig.CAMP_LIFE);
    for (Camp stored : List.copyOf(data.camps.values())) {
      Live live = lives.get(stored.id());
      boolean watched = enabled && watchedWithin(level, stored, live == null ? WATCH : UNWATCH);
      if (!watched) {
        if (live != null) {
          stop(level, stored, live);
          lives.remove(stored.id());
        }
        continue;
      }
      Camp camp = settle(level, stored, today(level));
      if (!camp.equals(stored)) {
        data.putCamp(camp);
      }
      if (live == null) {
        live = new Live();
        lives.put(camp.id(), live);
      }
      work(level, data, camp, live);
    }
  }

  private static boolean watchedWithin(ServerLevel level, Camp camp, int radius) {
    if (!level.isPositionEntityTicking(camp.center())) {
      return false;
    }
    for (Player player : level.players()) {
      double dx = player.getX() - camp.center().getX();
      double dz = player.getZ() - camp.center().getZ();
      if (dx * dx + dz * dz < (double) radius * radius) {
        return true;
      }
    }
    return false;
  }

  private static void stop(ServerLevel level, Camp camp, Live live) {
    for (UUID id : live.materialized) {
      Entity entity = level.getEntity(id);
      if (entity != null) {
        entity.discard();
      }
      JOBS.remove(id);
    }
    JOBS.values().removeIf(job -> job.camp() == camp.id());
  }

  private static void work(ServerLevel level, SocietyData data, Camp camp, Live live) {
    long now = GuestTime.gameTime(level);
    long tickOfDay = GuestTime.tickOfDay(now);
    long today = GuestTime.day(now);
    boolean severe = GuestWeather.get(level, camp.center(), now).isSevere();
    boolean day = tickOfDay >= WORK_START && tickOfDay < WORK_END && !severe;
    boolean dusk = tickOfDay >= WORK_END && tickOfDay < DUSK_END && !severe;
    live.materialized.removeIf(
        id -> !(level.getEntity(id) instanceof LivingEntity entity) || !entity.isAlive());

    if (camp.pen().isEmpty()
        && SettlementsConfig.enabled(SettlementsConfig.CAMP_PENS)
        && level.getGameTime() % PEN_RETRY == 0) {
      camp = placePen(level, data, camp);
    }
    boolean penReady =
        camp.pen().isPresent() || !SettlementsConfig.enabled(SettlementsConfig.CAMP_PENS);
    if (camp.pending() > 0 && penReady && level.isPositionEntityTicking(penCenter(camp))) {
      for (int i = 0; i < camp.pending(); i++) {
        spawnRavager(level, camp, surface(level, penCenter(camp)));
      }
      camp = camp.withRavagers(camp.ravagers() + camp.pending(), 0);
      data.putCamp(camp);
    }

    boolean ritualToday =
        camp.day() < today && ritualDue(level, camp, today, prestige(level, camp));
    boolean ritualist = camp.evokers() > 0 && SettlementsConfig.enabled(SettlementsConfig.RITUALS);
    if (ritualist && (day || (dusk && (ritualToday || live.ritualStarted >= 0)))) {
      ensureRitualist(level, camp, live);
    } else if (live.ritualist != null) {
      returnHome(level, camp, live);
    }
    if (camp.kind() == CampKind.MANSION && day) {
      ensureCrew(level, camp, live);
    }
    if (level.getGameTime() % JOB_INTERVAL == 0) {
      assignJobs(level, camp, live, day, dusk && ritualToday);
    }
    if (live.ritualist != null && dusk && ritualToday) {
      camp = CampWork.ritual(level, data, camp, live);
    } else if (live.ritualist != null && day) {
      CampWork.practice(level, camp, live, today);
    }
  }

  private static Camp placePen(ServerLevel level, SocietyData data, Camp camp) {
    long seed = GuestHash.hash(level.getSeed(), camp.id(), EVENT_SITE);
    BlockPos site = null;

    for (int k = 0; k < 8 && site == null; k++) {
      BlockPos around = outside(camp, seed + k * 0x9E3779B97F4A7C15L, 10);
      if (level.isLoaded(around)) {
        site = Traces.findSite(level, Traces.ILLAGER_PEN, around, 8, seed + k, true);
      }
    }
    if (site == null) {
      return camp;
    }
    long id = GuestHash.hash(camp.id(), EVENT_SITE);

    Traces.placeTemplate(
        level, id, Traces.ILLAGER_PEN, site, Rotation.NONE, true, GuestTime.gameTime(level), -1L);
    Camp placed = camp.withPen(site);
    data.putCamp(placed);

    int sheep = camp.kind() == CampKind.OUTPOST ? 2 : 1;
    for (int i = 0; i < sheep; i++) {
      Sheep animal =
          EntityType.SHEEP.spawn(level, penCenter(placed).above(), EntitySpawnReason.EVENT);
      if (animal != null) {
        animal.setPersistenceRequired();
        animal.setHomeTo(penCenter(placed), 3);
      }
    }
    return placed;
  }

  static BlockPos penCenter(Camp camp) {
    return camp.pen()
        .map(pen -> pen.offset(4, 0, 4))
        .orElseGet(() -> outside(camp, camp.id() ^ EVENT_SITE, 8));
  }

  static BlockPos outside(Camp camp, long seed, int distance) {
    double angle = GuestHash.unit(seed) * Math.PI * 2.0;
    BoundingBox box = camp.box();
    double reach = Math.hypot(box.getXSpan(), box.getZSpan()) / 2.0 + distance;
    if (camp.kind() == CampKind.OUTPOST) {

      reach = Math.min(reach, 20.0 + distance);
    }
    return BlockPos.containing(
        camp.center().getX() + Math.cos(angle) * reach,
        camp.center().getY(),
        camp.center().getZ() + Math.sin(angle) * reach);
  }

  static BlockPos surface(ServerLevel level, BlockPos pos) {
    return new BlockPos(
        pos.getX(),
        level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()),
        pos.getZ());
  }

  static BlockPos circle(ServerLevel level, Camp camp, Live live) {
    if (live.circle == null) {
      long seed = GuestHash.hash(level.getSeed(), camp.id(), EVENT_SITE + 1);
      live.circle = surface(level, outside(camp, seed, camp.kind() == CampKind.MANSION ? 12 : 16));
    }
    return live.circle;
  }

  private static void ensureRitualist(ServerLevel level, Camp camp, Live live) {
    if (live.ritualist != null
        && level.getEntity(live.ritualist) instanceof SpellcasterIllager caster
        && caster.isAlive()) {
      return;
    }

    long seed = GuestHash.hash(level.getSeed(), camp.id(), EVENT_SITE + 1);
    BlockPos home = surface(level, outside(camp, seed, 0));
    if (!level.isPositionEntityTicking(home)
        || !level.isPositionEntityTicking(circle(level, camp, live))) {
      return;
    }
    EntityType<? extends SpellcasterIllager> type =
        illusionist(level, camp) ? EntityType.ILLUSIONER : EntityType.EVOKER;
    SpellcasterIllager caster = type.spawn(level, home, EntitySpawnReason.EVENT);
    if (caster != null) {
      materialize(caster, live);
      live.ritualist = caster.getUUID();
      live.home = home;
    }
  }

  private static void returnHome(ServerLevel level, Camp camp, Live live) {
    Entity caster = level.getEntity(live.ritualist);
    BlockPos home = live.home != null ? live.home : camp.center();
    if (caster == null || caster.position().distanceTo(Vec3.atBottomCenterOf(home)) < 3.0) {
      if (caster != null) {
        caster.discard();
      }
      JOBS.remove(live.ritualist);
      live.ritualist = null;
      return;
    }
    JOBS.put(live.ritualist, new Job(JobKind.RETURN, camp.id(), home, null));
  }

  private static void ensureCrew(ServerLevel level, Camp camp, Live live) {
    long crew =
        live.materialized.stream()
            .filter(
                id -> level.getEntity(id) instanceof Entity e && e.entityTags().contains(CREW_TAG))
            .count();
    for (long i = crew; i < camp.crew(); i++) {
      BlockPos at =
          surface(
              level,
              outside(camp, GuestHash.hash(level.getSeed(), camp.id(), EVENT_SITE + 10 + i), 6));
      if (!level.isPositionEntityTicking(at)) {
        continue;
      }
      EntityType<? extends AbstractIllager> type =
          i == 0 ? EntityType.PILLAGER : EntityType.VINDICATOR;
      AbstractIllager member = type.spawn(level, at, EntitySpawnReason.EVENT);
      if (member != null) {
        member.addTag(CREW_TAG);
        materialize(member, live);
      }
    }
  }

  private static void materialize(AbstractIllager illager, Live live) {
    illager.addTag(MATERIALIZED_TAG);
    illager.setCanJoinRaid(false);
    live.materialized.add(illager.getUUID());
  }

  static void spawnRavager(ServerLevel level, Camp camp, BlockPos at) {
    Ravager ravager = EntityType.RAVAGER.create(level, EntitySpawnReason.EVENT);
    if (ravager != null) {
      ravager.setPos(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
      keep(ravager, camp);
      level.addFreshEntity(ravager);
    }
  }

  static void keep(Ravager ravager, Camp camp) {
    ravager.addTag(RAVAGER_TAG + camp.id());
    ravager.setPersistenceRequired();
    ravager.setCanJoinRaid(false);
    ravager.setHomeTo(penCenter(camp), 3);
  }

  private static void assignJobs(
      ServerLevel level, Camp camp, Live live, boolean day, boolean ritual) {
    JOBS.values().removeIf(job -> job.camp() == camp.id() && job.kind() != JobKind.RETURN);
    if (live.ritualist != null && (day || ritual)) {
      JobKind kind = ritual ? JobKind.RITUAL : JobKind.PRACTICE;
      JOBS.put(live.ritualist, new Job(kind, camp.id(), circle(level, camp, live), live.subject));
    }
    List<AbstractIllager> members =
        new ArrayList<>(
            level.getEntitiesOfClass(
                AbstractIllager.class,
                area(camp),
                illager ->
                    (illager instanceof Pillager || illager instanceof Vindicator)
                        && !illager.hasActiveRaid()
                        && (camp.kind() == CampKind.OUTPOST
                            || !AABB.of(camp.box()).contains(illager.position()))));
    members.sort(Comparator.comparing(AbstractIllager::getUUID));
    long epoch = leaderEpoch(level, camp);
    AbstractIllager leader =
        members.stream()
            .filter(illager -> !illager.getUUID().equals(live.subject))
            .max(
                Comparator.comparingLong(
                    illager ->
                        GuestHash.hash(
                            level.getSeed(),
                            illager.getUUID().getLeastSignificantBits(),
                            epoch,
                            EVENT_RANK)))
            .orElse(null);
    for (AbstractIllager member : members) {
      boolean isLeader = member == leader;
      crown(level, member, isLeader);
      if (isLeader) {
        JOBS.put(member.getUUID(), new Job(JobKind.LEAD, camp.id(), camp.center(), null));
      }
    }
    if (!day && !ritual) {
      return;
    }
    List<Job> work = openJobs(level, camp);
    int next = 0;
    for (AbstractIllager member : members) {
      if (member == leader || member.getUUID().equals(live.subject) || work.isEmpty()) {
        continue;
      }
      Job job = null;
      if (member instanceof Vindicator) {
        job = work.stream().filter(j -> j.kind() == JobKind.CHOP).findFirst().orElse(null);
      }
      if (job == null) {
        job = work.get(next++ % work.size());
      }
      JOBS.put(member.getUUID(), job);
    }
    if (ritual && live.subject == null) {
      live.subject =
          members.stream()
              .filter(member -> member != leader && !member.entityTags().contains(LEADER_TAG))
              .map(AbstractIllager::getUUID)
              .findFirst()
              .orElse(null);
    }
    if (live.subject != null) {
      JOBS.put(
          live.subject,
          new Job(JobKind.SUBJECT, camp.id(), circle(level, camp, live), live.ritualist));
    }
  }

  private static List<Job> openJobs(ServerLevel level, Camp camp) {
    List<Job> jobs = new ArrayList<>();
    Features features =
        FEATURES.getOrDefault(level, Map.of()).getOrDefault(camp.id(), Features.NONE);
    for (BlockPos target : features.targets()) {
      BlockPos mark =
          findNear(
              level,
              target,
              4,
              state -> state.is(Blocks.CARVED_PUMPKIN) || state.is(Blocks.TARGET));
      if (mark != null) {
        jobs.add(new Job(JobKind.TRAIN, camp.id(), mark, null));
      }
    }
    for (LivingEntity prisoner :
        level.getEntitiesOfClass(
            LivingEntity.class, area(camp), e -> e instanceof IronGolem || e instanceof Allay)) {
      jobs.add(new Job(JobKind.GUARD, camp.id(), prisoner.blockPosition(), prisoner.getUUID()));
    }
    AABB pen = new AABB(penCenter(camp)).inflate(6.0, 3.0, 6.0);
    for (Sheep sheep : level.getEntitiesOfClass(Sheep.class, pen, Sheep::readyForShearing)) {
      jobs.add(new Job(JobKind.SHEAR, camp.id(), sheep.blockPosition(), sheep.getUUID()));
    }
    for (Ravager ravager : level.getEntitiesOfClass(Ravager.class, pen)) {
      jobs.add(new Job(JobKind.FEED, camp.id(), ravager.blockPosition(), ravager.getUUID()));
    }
    BlockPos log =
        findNear(
            level,
            surface(level, outside(camp, camp.id(), 4)),
            12,
            state -> state.is(net.minecraft.tags.BlockTags.LOGS));
    if (log != null) {
      jobs.add(new Job(JobKind.CHOP, camp.id(), log, null));
    }
    return jobs;
  }

  private static @Nullable BlockPos findNear(
      ServerLevel level, BlockPos center, int radius, Predicate<BlockState> test) {
    if (!level.isLoaded(center)) {
      return null;
    }
    return BlockPos.findClosestMatch(
            center,
            radius,
            radius,
            pos -> level.isLoaded(pos) && test.test(level.getBlockState(pos)))
        .orElse(null);
  }

  private static void crown(ServerLevel level, AbstractIllager member, boolean leader) {
    boolean crowned = member.entityTags().contains(LEADER_TAG);
    if (leader && !crowned) {
      member.setItemSlot(
          EquipmentSlot.HEAD,
          Raid.getOminousBannerInstance(
              level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)));
      member.setDropChance(EquipmentSlot.HEAD, 0.0F);
      member.addTag(LEADER_TAG);
    } else if (!leader && crowned) {
      member.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
      member.removeTag(LEADER_TAG);
    }
  }

  private static void trackRaids(ServerLevel level) {
    if (level.getGameTime() % 100 != 0) {
      return;
    }
    for (Player player : level.players()) {
      Raid raid = level.getRaids().getNearbyRaid(player.blockPosition(), 128 * 128);
      if (raid != null) {
        RAIDS.putIfAbsent(raid, false);
      }
    }
    for (Map.Entry<Raid, Boolean> entry : RAIDS.entrySet()) {
      Raid raid = entry.getKey();
      if (entry.getValue() || !(raid.isVictory() || raid.isLoss())) {
        continue;
      }
      entry.setValue(true);
      Camp camp = nearestCamp(level, raid.getCenter(), 1536);
      BlockPos at = camp == null ? raid.getCenter() : camp.center();
      if (raid.isLoss()) {
        VillageMemory.recordIds(
            level, at, VillageMemory.RAID_WON, null, null, raid.getGroupsSpawned());
        continue;
      }
      Set<UUID> heroes = new HashSet<>();
      for (Player player :
          level.getEntitiesOfClass(Player.class, new AABB(raid.getCenter()).inflate(96.0))) {
        if (player.hasEffect(MobEffects.HERO_OF_THE_VILLAGE)) {
          heroes.add(player.getUUID());
        }
      }
      if (heroes.isEmpty()) {
        VillageMemory.recordIds(
            level, at, VillageMemory.RAID_FAILED, null, null, raid.getGroupsSpawned());
      }
      for (UUID hero : heroes) {
        VillageMemory.recordIds(
            level, at, VillageMemory.RAID_FAILED, hero, null, raid.getGroupsSpawned());
      }
    }
  }

  static @Nullable Camp nearestCamp(ServerLevel level, BlockPos pos, int radius) {
    return SocietyData.get(level).camps.values().stream()
        .filter(camp -> camp.center().distSqr(pos) < (double) radius * radius)
        .min(Comparator.comparingDouble(camp -> camp.center().distSqr(pos)))
        .orElse(null);
  }

  public static void onDeath(LivingEntity dead, DamageSource source) {
    if (!(dead.level() instanceof ServerLevel level)) {
      return;
    }
    SocietyData data = SocietyData.get(level);
    if (dead instanceof Ravager) {
      for (String tag : dead.entityTags()) {
        if (tag.startsWith(RAVAGER_TAG)) {
          Camp camp = data.camps.get(Long.parseLong(tag.substring(RAVAGER_TAG.length())));
          if (camp != null) {
            data.putCamp(camp.withRavagers(Math.max(0, camp.ravagers() - 1), camp.pending()));
          }
        }
      }
      return;
    }
    if (!(dead instanceof Raider)) {
      return;
    }
    Camp camp = campAt(level, dead.blockPosition());
    if (camp == null) {
      return;
    }
    if (dead instanceof SpellcasterIllager) {
      data.putCamp(camp.withEvokers(Math.max(0, camp.evokers() - 1)));
    } else if (dead.entityTags().contains(CREW_TAG)) {
      data.putCamp(camp.withCrew(Math.max(0, camp.crew() - 1)));
    }
  }

  public static boolean onJoin(Entity entity, ServerLevel level, boolean fromDisk) {
    if (fromDisk && entity.entityTags().contains(MATERIALIZED_TAG)) {
      return false;
    }
    if (entity instanceof AbstractIllager illager) {
      CampWork.restoreTool(illager);
      ((MobAccessor) illager).guestSettlements$goalSelector().addGoal(5, new CampWork(illager));
    } else if (entity instanceof Ravager ravager
        && ravager.entityTags().stream().anyMatch(tag -> tag.startsWith(RAVAGER_TAG))) {

      ((MobAccessor) ravager)
          .guestSettlements$goalSelector()
          .addGoal(5, new MoveTowardsRestrictionGoal(ravager, 0.6));
    }
    return true;
  }

  public static boolean allowSpawn(
      EntityType<?> type, ServerLevelAccessor accessor, EntitySpawnReason reason, BlockPos pos) {
    if (type != EntityType.PILLAGER
        || reason != EntitySpawnReason.NATURAL
        || !(accessor instanceof ServerLevel level)) {
      return true;
    }
    Camp camp = campAt(level, pos);
    if (camp == null || camp.kind() != CampKind.OUTPOST) {
      return true;
    }
    double cap =
        MobCategory.MONSTER.getMaxInstancesPerChunk()
            * SettlementsConfig.value(SettlementsConfig.OUTPOST_PILLAGERS);
    return level.getEntitiesOfClass(Pillager.class, area(camp)).size() < cap;
  }

  static long today(ServerLevel level) {
    return GuestTime.day(GuestTime.gameTime(level));
  }
}
