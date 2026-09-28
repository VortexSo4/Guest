package com.vortexso.guest_settlements.illager;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_settlements.GuestSettlements;
import com.vortexso.guest_settlements.illager.IllagerCamps.Job;
import com.vortexso.guest_settlements.illager.IllagerCamps.Live;
import com.vortexso.guest_settlements.society.SocietyData;
import com.vortexso.guest_settlements.society.SocietyData.Camp;
import com.vortexso.guest_settlements.society.Traces;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.illager.AbstractIllager;
import net.minecraft.world.entity.monster.illager.Illusioner;
import net.minecraft.world.entity.monster.illager.Pillager;
import net.minecraft.world.entity.monster.illager.SpellcasterIllager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.EvokerFangs;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

final class CampWork extends Goal {
  private static final String TOOL_TAG = "guest_settlements.tool";
  private static final double SPEED = 0.6;
  private static final long CIRCLE_LIFETIME = 3 * GuestTime.TICKS_PER_DAY;
  private static final int CIRCLE_RADIUS = 4;
  private static final int RITUAL_TICKS = 120;
  private static final long EVENT_PRACTICE = 0x9AC7L;

  private static final int SPELL_NONE = 0;

  private static final int SPELL_SUMMON = 1;
  private static final int SPELL_FANGS = 2;
  private static final int SPELL_WOLOLO = 3;
  private static final int SPELL_DISAPPEAR = 4;

  private static final @Nullable Method SET_SPELL;
  private static final Object[] SPELLS;

  static {
    Method method = null;
    Object[] spells = new Object[0];
    try {

      Class<?> type = Class.forName(SpellcasterIllager.class.getName() + "$IllagerSpell");
      method = SpellcasterIllager.class.getMethod("setIsCastingSpell", type);
      spells = type.getEnumConstants();
    } catch (ReflectiveOperationException | RuntimeException exception) {
      GuestSettlements.LOGGER.warn("Evoker casting pose unavailable: {}", exception.toString());
    }
    SET_SPELL = method;
    SPELLS = spells;
  }

  private final AbstractIllager mob;
  private @Nullable Job job;
  private int timer;

  CampWork(AbstractIllager mob) {
    this.mob = mob;
    setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
  }

  @Override
  public boolean canUse() {
    job = IllagerCamps.job(mob);
    return job != null && mob.getTarget() == null && !mob.hasActiveRaid();
  }

  @Override
  public boolean canContinueToUse() {
    return job != null && job.equals(IllagerCamps.job(mob)) && mob.getTarget() == null;
  }

  @Override
  public void start() {
    timer = 0;
  }

  @Override
  public void stop() {
    if (job != null
        && job.kind() == IllagerCamps.JobKind.CHOP
        && mob.level() instanceof ServerLevel level) {
      level.destroyBlockProgress(mob.getId(), job.site(), -1);
    }
    restoreTool(mob);
    if (mob instanceof Pillager pillager) {
      pillager.setChargingCrossbow(false);
    }
    job = null;
  }

  @Override
  public boolean requiresUpdateEveryTick() {
    return true;
  }

  @Override
  public void tick() {
    if (job == null || !(mob.level() instanceof ServerLevel level)) {
      return;
    }
    timer++;
    Entity target = job.target() == null ? null : level.getEntity(job.target());
    switch (job.kind()) {
      case LEAD -> lead(level);
      case CHOP -> chop(level, job.site());
      case TRAIN -> train(level, job.site());
      case GUARD -> tend(level, target, 4.0, 300, true);
      case SHEAR -> shear(level, target);
      case FEED -> tend(level, target, 3.0, 100, false);
      case PRACTICE, RETURN -> reach(Vec3.atBottomCenterOf(job.site()), 3.0);
      case RITUAL -> reach(Vec3.atBottomCenterOf(job.site()).add(CIRCLE_RADIUS - 1, 0.0, 0.0), 1.5);
      case SUBJECT -> {
        if (reach(Vec3.atBottomCenterOf(job.site()), 1.0) && target != null) {
          mob.getLookControl().setLookAt(target);
        }
      }
    }
  }

  private boolean reach(Vec3 at, double distance) {
    if (mob.position().distanceToSqr(at) <= distance * distance) {
      if (!mob.getNavigation().isDone()) {
        mob.getNavigation().stop();
      }
      return true;
    }
    if (timer % 20 == 1 || mob.getNavigation().isDone()) {
      mob.getNavigation().moveTo(at.x, at.y, at.z, SPEED);
    }
    return false;
  }

  private void lead(ServerLevel level) {
    if (reach(Vec3.atBottomCenterOf(job.site()), 4.0)) {
      Player player = level.getNearestPlayer(mob, 16.0);
      if (player != null) {
        mob.getLookControl().setLookAt(player);
      } else if (timer % 100 == 0) {
        mob.setYRot(mob.getYRot() + 60.0F);
      }
      if (timer % 400 == 200) {
        mob.playAmbientSound();
      }
    }
  }

  private void chop(ServerLevel level, BlockPos log) {
    if (!reach(Vec3.atBottomCenterOf(log), 2.5)) {
      return;
    }
    mob.getLookControl().setLookAt(Vec3.atCenterOf(log));
    holdTool(mob);
    int cycle = timer % 160;
    if (cycle < 100 && cycle % 10 == 0) {
      mob.swing(InteractionHand.MAIN_HAND);
      level.destroyBlockProgress(mob.getId(), log, cycle / 10);
      BlockState state = level.getBlockState(log);
      level.playSound(
          null, log, state.getSoundType().getHitSound(), SoundSource.HOSTILE, 0.6F, 0.8F);
    } else if (cycle == 100) {
      level.destroyBlockProgress(mob.getId(), log, -1);
    }
  }

  private void train(ServerLevel level, BlockPos mark) {
    Vec3 target = Vec3.atCenterOf(mark);
    double distance = mob.position().distanceTo(target);
    if (distance > 14.0) {
      reach(target, 12.0);
      return;
    }
    if (distance < 8.0) {
      Vec3 away = target.add(mob.position().subtract(target).normalize().scale(10.0));
      mob.getNavigation().moveTo(away.x, away.y, away.z, SPEED);
      return;
    }
    mob.getLookControl().setLookAt(target);
    if (!(mob instanceof Pillager pillager)) {
      return;
    }
    int cycle = timer % 80;
    if (cycle == 20) {
      pillager.setChargingCrossbow(true);
      level.playSound(
          null, mob, SoundEvents.CROSSBOW_LOADING_END.value(), SoundSource.HOSTILE, 0.8F, 1.0F);
    } else if (cycle == 45) {
      pillager.setChargingCrossbow(false);
      Arrow arrow = new Arrow(level, mob, new ItemStack(Items.ARROW), mob.getMainHandItem());
      arrow.pickup = AbstractArrow.Pickup.DISALLOWED;
      Vec3 aim = target.subtract(mob.getEyePosition());
      arrow.shoot(aim.x, aim.y + aim.horizontalDistance() * 0.08, aim.z, 1.6F, 4.0F);
      level.addFreshEntity(arrow);
      level.playSound(null, mob, SoundEvents.CROSSBOW_SHOOT, SoundSource.HOSTILE, 1.0F, 1.0F);
    }
  }

  private void tend(
      ServerLevel level, @Nullable Entity animal, double distance, int interval, boolean guard) {
    if (animal == null) {
      return;
    }
    boolean feeding = timer % interval > interval - 60;
    if (!reach(animal.position(), feeding ? 2.0 : distance)) {
      return;
    }
    mob.getLookControl().setLookAt(animal);
    if (feeding && timer % interval == interval - 20) {
      mob.swing(InteractionHand.MAIN_HAND);
      level.sendParticles(
          guard ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.HEART,
          animal.getX(),
          animal.getEyeY() + 0.3,
          animal.getZ(),
          4,
          0.3,
          0.2,
          0.3,
          0.0);
      level.playSound(
          null, animal, SoundEvents.GENERIC_EAT.value(), SoundSource.NEUTRAL, 0.7F, 1.0F);
    }
  }

  private void shear(ServerLevel level, @Nullable Entity target) {
    if (!(target instanceof Sheep sheep) || !reach(sheep.position(), 1.8)) {
      return;
    }
    mob.getLookControl().setLookAt(sheep);
    if (timer % 40 == 0 && sheep.readyForShearing()) {
      mob.swing(InteractionHand.MAIN_HAND);

      sheep.setSheared(true);
      level.playSound(null, sheep, SoundEvents.SHEEP_SHEAR, SoundSource.HOSTILE, 1.0F, 1.0F);
    }
  }

  private static void holdTool(AbstractIllager mob) {
    if (mob instanceof Pillager && !mob.entityTags().contains(TOOL_TAG)) {
      mob.addTag(TOOL_TAG);
      mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
      mob.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }
  }

  static void restoreTool(AbstractIllager mob) {
    if (mob.entityTags().contains(TOOL_TAG)) {
      mob.removeTag(TOOL_TAG);
      mob.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.CROSSBOW));

      mob.setDropChance(EquipmentSlot.MAINHAND, 0.085F);
    }
  }

  static void practice(ServerLevel level, Camp camp, Live live, long today) {
    if (live.ritualist == null
        || !(level.getEntity(live.ritualist) instanceof SpellcasterIllager caster)
        || caster.getTarget() != null) {
      return;
    }
    BlockPos circle = IllagerCamps.circle(level, camp, live);
    if (caster.position().distanceTo(Vec3.atBottomCenterOf(circle)) > 4.0) {
      return;
    }
    long now = GuestTime.gameTime(level);
    if (now >= live.poseUntil) {
      pose(caster, SPELL_NONE);
    }
    if (live.circleDay != today) {
      live.circleDay = today;
      drawCircle(level, circle, GuestHash.hash(camp.id(), today), now);
      pose(caster, SPELL_WOLOLO);
      live.poseUntil = now + 60;
      level.playSound(
          null, caster, SoundEvents.EVOKER_PREPARE_SUMMON, SoundSource.HOSTILE, 1.0F, 0.8F);
      live.nextPractice = now + 100;
      return;
    }
    if (now < live.nextPractice) {
      return;
    }
    live.poseUntil = now + 40;
    live.nextPractice =
        now + 160 + (long) (GuestHash.unit(GuestHash.hash(camp.id(), now, EVENT_PRACTICE)) * 120);
    if (caster instanceof Illusioner) {

      pose(caster, SPELL_DISAPPEAR);
      caster.addEffect(new MobEffectInstance(MobEffects.INVISIBILITY, 60));
      level.playSound(
          null, caster, SoundEvents.ILLUSIONER_PREPARE_MIRROR, SoundSource.HOSTILE, 1.0F, 1.0F);
      return;
    }
    pose(caster, SPELL_FANGS);
    double angle = GuestHash.unit(GuestHash.hash(camp.id(), now)) * Math.PI * 2.0;
    for (int i = 1; i <= 6; i++) {
      double x = caster.getX() + Math.cos(angle) * (1.0 + i * 1.25);
      double z = caster.getZ() + Math.sin(angle) * (1.0 + i * 1.25);
      level.addFreshEntity(
          new EvokerFangs(level, x, caster.getY(), z, (float) angle, i * 2, caster));
    }
    level.playSound(null, caster, SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.0F, 1.0F);
  }

  static Camp ritual(ServerLevel level, SocietyData data, Camp camp, Live live) {
    if (live.ritualist == null
        || !(level.getEntity(live.ritualist) instanceof SpellcasterIllager caster)
        || live.subject == null
        || !(level.getEntity(live.subject) instanceof Mob subject)
        || !subject.isAlive()) {
      live.subject = null;
      live.ritualStarted = -1;
      return camp;
    }
    BlockPos circle = IllagerCamps.circle(level, camp, live);
    Vec3 center = Vec3.atBottomCenterOf(circle);
    long now = GuestTime.gameTime(level);
    if (live.ritualStarted < 0) {
      if (caster.position().distanceTo(center) > CIRCLE_RADIUS + 2
          || subject.position().distanceTo(center) > 2.5) {
        return camp;
      }
      live.ritualStarted = now;
      if (live.circleDay != IllagerCamps.today(level)) {
        live.circleDay = IllagerCamps.today(level);
        drawCircle(level, circle, GuestHash.hash(camp.id(), live.circleDay), now);
      }
      level.playSound(
          null, caster, SoundEvents.EVOKER_PREPARE_WOLOLO, SoundSource.HOSTILE, 1.5F, 0.7F);
    }
    long elapsed = now - live.ritualStarted;
    caster.getLookControl().setLookAt(subject);
    pose(caster, SPELL_SUMMON);
    for (int i = 0; i < 12; i++) {
      double angle = i * Math.PI / 6.0 + elapsed * 0.1;
      level.sendParticles(
          ParticleTypes.WITCH,
          center.x + Math.cos(angle) * (CIRCLE_RADIUS - elapsed / 40.0),
          center.y + 0.3 + elapsed / 60.0,
          center.z + Math.sin(angle) * (CIRCLE_RADIUS - elapsed / 40.0),
          1,
          0.0,
          0.0,
          0.0,
          0.0);
    }
    if (elapsed % 40 == 0) {
      level.playSound(null, caster, SoundEvents.EVOKER_CAST_SPELL, SoundSource.HOSTILE, 1.2F, 0.6F);
    }
    if (elapsed < RITUAL_TICKS) {
      return camp;
    }
    int crew =
        subject.entityTags().contains(IllagerCamps.CREW_TAG)
            ? Math.max(0, camp.crew() - 1)
            : camp.crew();
    Camp settled =
        camp.withRavagers(camp.ravagers() + 1, camp.pending())
            .withCrew(crew)
            .withDay(IllagerCamps.today(level), camp.pending());
    Ravager ravager =
        subject.convertTo(
            EntityType.RAVAGER,
            ConversionParams.single(subject, false, false),
            beast -> IllagerCamps.keep(beast, settled));
    level.sendParticles(
        ParticleTypes.SCULK_SOUL, center.x, center.y + 1.0, center.z, 30, 0.8, 0.8, 0.8, 0.05);
    if (ravager != null) {
      level.playSound(null, ravager, SoundEvents.RAVAGER_ROAR, SoundSource.HOSTILE, 1.5F, 1.0F);
    }
    pose(caster, SPELL_NONE);
    IllagerCamps.jobs().remove(live.subject);
    live.subject = null;
    live.ritualStarted = -1;
    data.putCamp(settled);
    GuestSettlements.LOGGER.debug(
        "Ravager ritual at camp {} (ravagers {})", camp.center(), settled.ravagers());
    return settled;
  }

  static void drawCircle(ServerLevel level, BlockPos center, long id, long now) {
    BlockState vein =
        Blocks.SCULK_VEIN
            .defaultBlockState()
            .setValue(MultifaceBlock.getFaceProperty(Direction.DOWN), true);
    Map<BlockPos, BlockState> marks = new HashMap<>();
    for (int step = 0; step < 32; step++) {
      double angle = step * Math.PI / 16.0;
      mark(
          level,
          center.offset(
              (int) Math.round(Math.cos(angle) * CIRCLE_RADIUS),
              0,
              (int) Math.round(Math.sin(angle) * CIRCLE_RADIUS)),
          vein,
          marks);
    }
    for (Direction direction : Direction.Plane.HORIZONTAL) {
      mark(level, center.relative(direction, 2), vein, marks);
    }
    Traces.placeBlocks(level, id, "magic_circle", marks, now, CIRCLE_LIFETIME);
    for (BlockPos pos : marks.keySet()) {
      level.sendParticles(
          ParticleTypes.ENCHANT,
          pos.getX() + 0.5,
          pos.getY() + 0.3,
          pos.getZ() + 0.5,
          4,
          0.2,
          0.2,
          0.2,
          0.3);
    }
  }

  private static void mark(
      ServerLevel level, BlockPos column, BlockState vein, Map<BlockPos, BlockState> marks) {
    BlockPos top = IllagerCamps.surface(level, column);
    BlockState here = level.getBlockState(top);
    if ((here.isAir() || here.canBeReplaced())
        && here.getFluidState().isEmpty()
        && vein.canSurvive(level, top)) {
      marks.put(top, vein);
    }
  }

  private static void pose(SpellcasterIllager caster, int spell) {
    if (SET_SPELL != null && spell < SPELLS.length) {
      try {
        SET_SPELL.invoke(caster, SPELLS[spell]);
      } catch (ReflectiveOperationException ignored) {

      }
    }
  }
}
