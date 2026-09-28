package com.vortexso.guest_architects.city;

import com.vortexso.guest_architects.ArchitectsConfig;
import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.entity.Architect;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.history.GuestHistory;
import com.vortexso.guest_core.api.history.HistoryRecord;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

public final class CityRelations {
  static final double ATTACK_POINTS = 2.0;
  static final double BLOCK_POINTS = 1.0;
  static final double SHRIEK_POINTS = 1.0;

  private static final double QUIET_DAYS_PER_MINUTE = 0.02;

  private static final double LONELY_DAYS_PER_MINUTE = 0.1;
  private static final double FLOOR_DAYS = 0.25;
  private static final double LIGHT_DAYS = 0.25;

  private static final double MAX_PLAYER_ASSISTANCE = 4.0;

  private static final double HELPER_ASSISTANCE = 0.5;
  private static final long QUIET_TICKS = 1200;
  private static final int WITNESS_RADIUS = 32;
  private static final int NOTICE_RADIUS = 24;
  private static final int ATTENTION_RADIUS = 24;

  private static final int DARK_LIGHT = 8;

  static final Identifier REMOVAL_KIND =
      Identifier.fromNamespaceAndPath(GuestArchitects.MODID, "forced_removal");

  private CityRelations() {}

  static boolean isSculkFamily(BlockState state) {
    return state.is(Blocks.SCULK)
        || state.is(Blocks.SCULK_VEIN)
        || state.is(Blocks.SCULK_CATALYST)
        || state.is(Blocks.SCULK_SHRIEKER)
        || state.is(Blocks.SCULK_SENSOR)
        || state.is(Blocks.CALIBRATED_SCULK_SENSOR);
  }

  static boolean isCritical(ServerLevel level, CitySite site, BlockPos pos, BlockState state) {
    return state.is(BlockTags.WOOL)
        || state.is(BlockTags.CANDLES)
        || isVital(level, site, pos, state);
  }

  static boolean isVital(ServerLevel level, CitySite site, BlockPos pos, BlockState state) {
    if (isSculkFamily(state) || state.is(Blocks.REINFORCED_DEEPSLATE) || site.inMemoryPlace(pos)) {
      return true;
    }
    for (Direction direction : Direction.values()) {
      if (level.getBlockState(pos.relative(direction)).is(Blocks.REINFORCED_DEEPSLATE)) {
        return true;
      }
    }
    return false;
  }

  public static void onBlockBroken(
      ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
    CityManager manager = CityManager.get(level);
    CitySite site = manager.siteAt(pos);
    if (site == null
        || !site.living()
        || player.isCreative()
        || !isCritical(level, site, pos, state)) {
      return;
    }
    site.record.addDamage(pos, state);
    ArchitectsData.get(level).setDirty();
    double floor =
        isVital(level, site, pos, state) ? ArchitectsConfig.EQUIPMENT_THRESHOLD.get() : 0.0;
    interfere(manager, site, player, BLOCK_POINTS, floor);
  }

  public static void onShriek(ServerLevel level, ServerPlayer player, BlockPos pos) {
    CityManager manager = CityManager.get(level);
    CitySite site = manager.siteAt(pos);
    if (site != null && site.living()) {
      interfere(manager, site, player, SHRIEK_POINTS, 0.0);
    }
  }

  public static boolean provoke(
      ServerLevel level, CitySite site, ServerPlayer player, double points) {
    CityManager manager = CityManager.get(level);
    if (!site.living() || nearestArchitect(site, player.position(), WITNESS_RADIUS) == null) {
      return false;
    }
    interfere(manager, site, player, points, 0.0);
    return true;
  }

  static void interfere(
      CityManager manager, CitySite site, ServerPlayer player, double points, double floor) {
    if (manager.escalation(player) != null) {
      return;
    }
    ServerLevel level = manager.level();
    Architect witness = nearestArchitect(site, player.position(), WITNESS_RADIUS);
    if (witness == null) {
      return;
    }
    long now = GuestTime.gameTime(level);
    CityRecord.Relation old = site.record.relation(player.getUUID());
    double score =
        Math.max(
            floor, old.pointsAt(now, ArchitectsConfig.INTERFERENCE_DECAY_PER_DAY.get()) + points);

    boolean portal = ArchitectsConfig.PORTAL_REMOVAL.get();
    Escalation.Kind kind = null;
    if (portal && old.removed()) {
      kind = Escalation.Kind.QUICK_REMOVE;
    } else if (portal && score >= ArchitectsConfig.REMOVAL_THRESHOLD.get()) {
      kind = Escalation.Kind.REMOVE;
    } else if (score >= ArchitectsConfig.EQUIPMENT_THRESHOLD.get()) {
      kind = Escalation.Kind.UNMAKE;
    } else if (score >= ArchitectsConfig.IMMOBILIZE_THRESHOLD.get()) {
      kind = Escalation.Kind.HOLD;
    }
    boolean removed =
        old.removed() || kind == Escalation.Kind.REMOVE || kind == Escalation.Kind.QUICK_REMOVE;
    site.record.relations.put(
        player.getUUID(), new CityRecord.Relation(score, now, old.assistance(), removed));
    ArchitectsData.get(level).setDirty();

    if (score >= ArchitectsConfig.NOTICE_THRESHOLD.get()) {
      for (Architect architect : site.architects) {
        if (architect.distanceToSqr(player) < NOTICE_RADIUS * NOTICE_RADIUS) {
          architect.watch(player, 100, false);
        }
      }
    }
    if (kind != null) {
      manager.startEscalation(site, player, witness, kind);
    }
  }

  static void history(ServerLevel level, CitySite site, Player player, Identifier kind) {
    GuestHistory.get(level)
        .record(
            level,
            new HistoryRecord(
                GuestTime.gameTime(level),
                site.center(),
                kind,
                Optional.of(player.getUUID()),
                Optional.empty(),
                1));
  }

  public static void onAdvancedAction(ServerLevel level, Player player, BlockPos pos) {
    CitySite site = CityManager.get(level).siteAt(pos);
    if (site == null || !site.living()) {
      return;
    }
    Architect nearest = nearestArchitect(site, player.position(), ATTENTION_RADIUS);
    if (nearest != null && !nearest.busy()) {
      nearest.watch(player, 80, false);
    }
  }

  public static void onBlockPlaced(
      ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
    CitySite site = CityManager.get(level).siteAt(pos);
    if (site == null || !site.living()) {
      return;
    }
    if (state.is(BlockTags.WOOL) && fillsFloorGap(level, site, pos)) {
      site.record.damage.removeIf(d -> d.pos().equals(pos));
      assist(level, site, player, FLOOR_DAYS);
    } else if (state.getLightEmission() > 0
        && !isSculkFamily(state)
        && site.ritualBox.inflatedBy(6).isInside(pos)
        && level.getBrightness(LightLayer.BLOCK, pos) < DARK_LIGHT) {
      assist(level, site, player, LIGHT_DAYS);
    }
  }

  private static boolean fillsFloorGap(ServerLevel level, CitySite site, BlockPos pos) {
    for (CityRecord.Damage damage : site.record.damage) {
      if (damage.pos().equals(pos) && damage.state().is(BlockTags.WOOL)) {
        return true;
      }
    }
    int woolNeighbours = 0;
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      if (level.getBlockState(pos.relative(direction)).is(BlockTags.WOOL)) {
        woolNeighbours++;
      }
    }
    return woolNeighbours >= 2;
  }

  static void tickPresence(CityManager manager, CitySite site) {
    ServerLevel level = manager.level();
    for (ServerPlayer player : level.players()) {
      if (player.isSpectator() || !site.ritualBox.inflatedBy(8).isInside(player.blockPosition())) {
        continue;
      }
      boolean ritualInProgress =
          site.architects.stream()
              .anyMatch(
                  a ->
                      a.role() == ArchitectRole.RITUAL
                          && a.working()
                          && a.distanceToSqr(player) < 16 * 16);
      if (!ritualInProgress) {
        continue;
      }
      long lastNoise = manager.lastNoise.getOrDefault(player.getUUID(), Long.MIN_VALUE / 2);
      double perMinute = 0.0;
      if (level.getGameTime() - lastNoise >= QUIET_TICKS) {
        perMinute += QUIET_DAYS_PER_MINUTE;
      }
      if (site.population <= CityManager.LONELY_POPULATION) {
        perMinute += LONELY_DAYS_PER_MINUTE;
      }
      if (perMinute > 0) {
        assist(level, site, player, perMinute / 60.0);
      }
    }
  }

  static void assist(ServerLevel level, CitySite site, ServerPlayer player, double days) {
    long now = GuestTime.gameTime(level);
    double day = CityLife.day(now);
    CityRecord record = site.record;
    double granted =
        CityLife.grantableDelay(
            days,
            day,
            record.lastGrantDay,
            record.delayDays,
            ArchitectsConfig.MAX_ASSISTANCE_DAYS.get());
    if (granted > 0) {
      record.delayDays += granted;
      record.lastGrantDay = day;
    }
    UUID id = player.getUUID();
    CityRecord.Relation old = record.relation(id);
    record.relations.put(
        id,
        new CityRecord.Relation(
            old.pointsAt(now, ArchitectsConfig.INTERFERENCE_DECAY_PER_DAY.get()),
            now,
            Math.min(MAX_PLAYER_ASSISTANCE, old.assistance() + days),
            old.removed()));
    ArchitectsData.get(level).setDirty();
  }

  static boolean isHelper(CitySite site, Player player) {
    CityRecord.Relation relation = site.record.relations.get(player.getUUID());
    return relation != null && relation.assistance() >= HELPER_ASSISTANCE;
  }

  static void shareWithHelper(CityManager manager, CitySite site, Architect speaker) {
    Player helper = manager.level().getNearestPlayer(speaker, 8.0);
    if (helper == null || !isHelper(site, helper)) {
      return;
    }
    CityManager.glyphs(manager.level(), speaker.getEyePosition(), helper.getEyePosition(), 8);
    Vec3 between = speaker.getEyePosition().add(helper.getEyePosition()).scale(0.5);
    manager
        .level()
        .sendParticles(ParticleTypes.GLOW, between.x, between.y, between.z, 3, 0.4, 0.3, 0.4, 0.0);
  }

  private static @Nullable Architect nearestArchitect(CitySite site, Vec3 pos, double radius) {
    Architect best = null;
    double bestDistance = radius * radius;
    for (Architect architect : site.architects) {
      double d = architect.distanceToSqr(pos);
      if (!architect.isRemoved() && d < bestDistance) {
        bestDistance = d;
        best = architect;
      }
    }
    return best;
  }
}
