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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Guest/Architect relationship: interference escalates a per-player, per-city score, ordinary help
 * delays decline and raises tolerance. Architects only react to what one of them witnessed.
 */
public final class CityRelations {
  static final double ATTACK_POINTS = 2.0;
  static final double BLOCK_POINTS = 1.0;
  static final double SHRIEK_POINTS = 1.0;

  /**
   * Help, measured in days of decline delay. Tuning values without a vanilla basis; they only need
   * to make an evening of careful company worth about a day, and one filled gap a few hours.
   */
  private static final double QUIET_DAYS_PER_MINUTE = 0.02;

  private static final double LONELY_DAYS_PER_MINUTE = 0.1;
  private static final double FLOOR_DAYS = 0.25;
  private static final double LIGHT_DAYS = 0.25;

  /** Tolerance cap: at most five times faster forgetting of interference. */
  private static final double MAX_PLAYER_ASSISTANCE = 4.0;

  private static final double HELPER_ASSISTANCE = 0.5;
  private static final int LONELY_POPULATION = 3;
  private static final long QUIET_TICKS = 1200;
  private static final int WITNESS_RADIUS = 32;
  private static final int NOTICE_RADIUS = 24;
  private static final int ATTENTION_RADIUS = 24;
  private static final int IMMOBILIZE_TICKS = 200;

  /** Block light below this counts as a dark ritual that ordinary light helps. */
  private static final int DARK_LIGHT = 8;

  private static final Identifier REMOVAL_KIND =
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

  /** Blocks whose destruction the city treats as damage to itself. */
  static boolean isCritical(ServerLevel level, BlockPos pos, BlockState state) {
    if (isSculkFamily(state) || state.is(BlockTags.WOOL) || state.is(BlockTags.CANDLES)) {
      return true;
    }
    for (Direction direction : Direction.values()) {
      if (level.getBlockState(pos.relative(direction)).is(Blocks.REINFORCED_DEEPSLATE)) {
        return true;
      }
    }
    return false;
  }

  // ---- interference -------------------------------------------------------------------------

  public static void onBlockBroken(
      ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
    CityManager manager = CityManager.get(level);
    CitySite site = manager.siteAt(pos);
    if (site == null || !site.living() || player.isCreative() || !isCritical(level, pos, state)) {
      return;
    }
    site.record.addDamage(pos, state);
    ArchitectsData.get(level).setDirty();
    interfere(manager, site, player, BLOCK_POINTS);
  }

  public static void onShriek(ServerLevel level, ServerPlayer player, BlockPos pos) {
    CityManager manager = CityManager.get(level);
    CitySite site = manager.siteAt(pos);
    if (site != null && site.living()) {
      interfere(manager, site, player, SHRIEK_POINTS);
    }
  }

  static void interfere(CityManager manager, CitySite site, ServerPlayer player, double points) {
    ServerLevel level = manager.level();
    Architect witness = nearestArchitect(site, player.position(), WITNESS_RADIUS);
    if (witness == null) {
      return;
    }
    long now = GuestTime.gameTime(level);
    CityRecord.Relation old = site.record.relation(player.getUUID());
    double score = old.pointsAt(now, ArchitectsConfig.INTERFERENCE_DECAY_PER_DAY.get()) + points;
    site.record.relations.put(
        player.getUUID(), new CityRecord.Relation(score, now, old.assistance()));
    ArchitectsData.get(level).setDirty();

    if (score >= ArchitectsConfig.NOTICE_THRESHOLD.get()) {
      for (Architect architect : site.architects) {
        if (architect.distanceToSqr(player) < NOTICE_RADIUS * NOTICE_RADIUS) {
          architect.watch(player, 100, false);
        }
      }
    }
    if (score >= ArchitectsConfig.REMOVAL_THRESHOLD.get()
        && ArchitectsConfig.PORTAL_REMOVAL.get()) {
      remove(level, site, player);
    } else if (score >= ArchitectsConfig.EQUIPMENT_THRESHOLD.get()) {
      immobilize(level, witness, player);
      takeTool(level, player);
    } else if (score >= ArchitectsConfig.IMMOBILIZE_THRESHOLD.get()) {
      immobilize(level, witness, player);
    }
  }

  private static void immobilize(ServerLevel level, Architect witness, ServerPlayer player) {
    player.addEffect(
        new MobEffectInstance(MobEffects.SLOWNESS, IMMOBILIZE_TICKS, 6, false, false, true));
    player.addEffect(
        new MobEffectInstance(MobEffects.MINING_FATIGUE, IMMOBILIZE_TICKS, 2, false, false, true));
    CityManager.glyphs(level, witness.getEyePosition(), player.getEyePosition(), 12);
  }

  private static void takeTool(ServerLevel level, ServerPlayer player) {
    ItemStack held = player.getMainHandItem();
    if (held.isEmpty()) {
      return;
    }
    if (ArchitectsConfig.DESTROY_EQUIPMENT.get()) {
      level.playSound(
          null,
          player.getX(),
          player.getY(),
          player.getZ(),
          SoundEvents.ITEM_BREAK,
          SoundSource.PLAYERS,
          1.0F,
          0.6F);
      level.sendParticles(
          ParticleTypes.SMOKE,
          player.getX(),
          player.getY() + 1.0,
          player.getZ(),
          10,
          0.3,
          0.3,
          0.3,
          0.01);
    } else {
      player.drop(held.copy(), true, false);
    }
    player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
  }

  /** Forced removal through an Architect portal to the surface straight above. */
  private static void remove(ServerLevel level, CitySite site, ServerPlayer player) {
    Vec3 from = player.position();
    BlockPos top =
        level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, player.blockPosition());
    portalEffect(level, from);
    player.teleportTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5);
    portalEffect(level, player.position());
    history(level, site, player, REMOVAL_KIND);
  }

  private static void portalEffect(ServerLevel level, Vec3 at) {
    level.sendParticles(
        ParticleTypes.REVERSE_PORTAL, at.x, at.y + 1.0, at.z, 60, 0.5, 1.0, 0.5, 0.05);
    level.sendParticles(ParticleTypes.ENCHANT, at.x, at.y + 1.0, at.z, 40, 0.6, 1.0, 0.6, 0.5);
    level.playSound(
        null, at.x, at.y, at.z, GuestArchitects.PORTAL.get(), SoundSource.HOSTILE, 1.0F, 1.0F);
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

  // ---- attention ----------------------------------------------------------------------------

  /** Precise work (enchanting, complex redstone) makes the nearest Architect stop and watch. */
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

  // ---- assistance ---------------------------------------------------------------------------

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
        // Light updates are deferred, so this still reads the darkness before the placement.
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

  /** Per-second presence effects: quiet company near the ritual and not leaving it lonely. */
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
      if (site.population <= LONELY_POPULATION) {
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
            Math.min(MAX_PLAYER_ASSISTANCE, old.assistance() + days)));
    ArchitectsData.get(level).setDirty();
  }

  /** Subtle feedback for helpers: they are included in the silent conversation. */
  static void shareWithHelper(CityManager manager, CitySite site, Architect speaker) {
    Player helper = manager.level().getNearestPlayer(speaker, 8.0);
    if (helper == null) {
      return;
    }
    CityRecord.Relation relation = site.record.relations.get(helper.getUUID());
    if (relation != null && relation.assistance() >= HELPER_ASSISTANCE) {
      CityManager.glyphs(manager.level(), speaker.getEyePosition(), helper.getEyePosition(), 8);
    }
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
