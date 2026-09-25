package com.vortexso.guest_architects.entity;

import com.vortexso.guest_architects.GuestArchitects;
import com.vortexso.guest_architects.city.ArchitectRole;
import com.vortexso.guest_architects.city.CityManager;
import com.vortexso.guest_architects.city.Task;
import com.vortexso.guest_core.api.GuestHash;
import java.util.UUID;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * A living-city inhabitant. Identity is {@code (city, index)}: role and age follow from it, so the
 * same individual reappears whenever the city is observed. Behaviour is deliberately a small
 * work/rest loop driven by tasks the {@link CityManager} hands out, not a villager brain.
 */
public class Architect extends PathfinderMob {
  private static final EntityDataAccessor<Boolean> YOUNG =
      SynchedEntityData.defineId(Architect.class, EntityDataSerializers.BOOLEAN);

  /** Movement attribute stays low and paths use a further modifier: slow and deliberate. */
  private static final double WALK_SPEED = 0.6;

  private static final double REACH_SQR = 3.2 * 3.2;
  private static final int GIVE_UP_TICKS = 600;

  /** Paths are recomputed at most this often; failed paths would otherwise retry every tick. */
  private static final int REPATH_TICKS = 20;

  private static final int MELODY_NOTES = 8;
  private static final int MELODY_NOTE_TICKS = 8;
  private static final double MELODY_DISTANCE = 7.0;

  private long cityId;
  private int index = -1;
  private ArchitectRole role = ArchitectRole.WATCHER;

  private @Nullable Task task;
  private int taskTicks;
  private int restTicks;

  private @Nullable UUID attention;
  private int attentionTicks;
  private boolean recognizing;

  private @Nullable Warden melodyTarget;
  private int melodyTicks;

  public Architect(EntityType<? extends Architect> type, Level level) {
    super(type, level);
    // Invulnerability is also what makes Wardens ignore them (Warden#canTargetEntity).
    setInvulnerable(true);
    setPersistenceRequired();
    setDropChance(EquipmentSlot.MAINHAND, 0.0F);
  }

  public static AttributeSupplier.Builder createAttributes() {
    return PathfinderMob.createMobAttributes()
        .add(Attributes.MAX_HEALTH, 40.0)
        .add(Attributes.MOVEMENT_SPEED, 0.25)
        .add(Attributes.FOLLOW_RANGE, 48.0);
  }

  public void assign(long cityId, int index, ArchitectRole role) {
    this.cityId = cityId;
    this.index = index;
    this.role = role;
    entityData.set(YOUNG, role == ArchitectRole.YOUNG);
    setItemSlot(EquipmentSlot.MAINHAND, toolFor(role));
    task = null;
  }

  private static ItemStack toolFor(ArchitectRole role) {
    return switch (role) {
      case RITUAL -> new ItemStack(Items.BRUSH);
      case DEEPSLATE -> new ItemStack(Items.IRON_PICKAXE);
      case WOOL -> new ItemStack(Items.SHEARS);
      case WATCHER -> new ItemStack(Items.SPYGLASS);
      case TEACHER -> new ItemStack(Items.BOOK);
      case MELODY, YOUNG -> ItemStack.EMPTY;
    };
  }

  public long cityId() {
    return cityId;
  }

  public int index() {
    return index;
  }

  public ArchitectRole role() {
    return role;
  }

  public @Nullable Task task() {
    return task;
  }

  public @Nullable Warden melodyTarget() {
    return melodyTarget;
  }

  public boolean hasCity() {
    return index >= 0;
  }

  public boolean busy() {
    return attentionTicks > 0 || melodyTarget != null;
  }

  public boolean working() {
    return task != null && restTicks <= 0 && !busy();
  }

  /** Human-readable current activity for debug rendering. */
  public String activityKey() {
    if (melodyTarget != null) {
      return "melody";
    }
    if (attentionTicks > 0) {
      return recognizing ? "recognition" : "attention";
    }
    if (restTicks > 0) {
      return "rest";
    }
    return task == null ? "idle" : task.kind().name().toLowerCase(java.util.Locale.ROOT);
  }

  public @Nullable Vec3 debugTarget() {
    if (melodyTarget != null) {
      return melodyTarget.position();
    }
    return task == null ? null : task.pos().getCenter();
  }

  /** Stop and watch a player; the recognition variant also comes close and studies them. */
  public void watch(Player player, int ticks, boolean recognition) {
    if (melodyTarget != null) {
      return;
    }
    attention = player.getUUID();
    attentionTicks = Math.max(attentionTicks, ticks);
    recognizing = recognition;
  }

  public void playMelodyFor(Warden warden) {
    if (melodyTarget == null) {
      melodyTarget = warden;
      melodyTicks = 0;
      attentionTicks = 0;
    }
  }

  @Override
  protected void registerGoals() {
    goalSelector.addGoal(0, new FloatGoal(this));
  }

  @Override
  protected void customServerAiStep(ServerLevel level) {
    super.customServerAiStep(level);
    if (melodyTarget != null) {
      tickMelody(level);
    } else if (attentionTicks > 0) {
      tickAttention(level);
    } else if (restTicks > 0) {
      restTicks--;
    } else if (hasCity()) {
      tickWork(level);
    }
  }

  private void tickWork(ServerLevel level) {
    CityManager manager = CityManager.get(level);
    if (task == null) {
      task = manager.nextTask(this);
      taskTicks = 0;
      if (task == null) {
        restTicks = 100;
        return;
      }
    }
    taskTicks++;
    Vec3 target = task.pos().getCenter();
    if (distanceToSqr(target) > REACH_SQR) {
      if (taskTicks > GIVE_UP_TICKS) {
        task = null;
        restTicks = 60;
      } else if (taskTicks % REPATH_TICKS == 1 && getNavigation().isDone()) {
        getNavigation().moveTo(target.x, task.pos().getY(), target.z, 1, WALK_SPEED);
      }
      return;
    }
    getNavigation().stop();
    getLookControl().setLookAt(target);
    if (taskTicks % 20 == 0) {
      swing(InteractionHand.MAIN_HAND);
      level.sendParticles(
          ParticleTypes.ENCHANT, target.x, target.y + 0.6, target.z, 4, 0.3, 0.2, 0.3, 0.2);
    }
    if (taskTicks >= task.kind().workTicks()) {
      manager.complete(this, task);
      task = null;
      restTicks = restTicks(level);
    }
  }

  /** Rest length varies per individual but is stable for it. */
  private int restTicks(ServerLevel level) {
    return 60 + (int) (GuestHash.unit(GuestHash.hash(level.getSeed(), cityId, index)) * 160);
  }

  private void tickAttention(ServerLevel level) {
    attentionTicks--;
    Player player = attention == null ? null : level.getPlayerByUUID(attention);
    if (player == null || distanceToSqr(player) > 48 * 48) {
      attentionTicks = 0;
      return;
    }
    if (recognizing && distanceToSqr(player) > 2.5 * 2.5) {
      if (attentionTicks % REPATH_TICKS == 0) {
        getNavigation().moveTo(player, WALK_SPEED);
      }
    } else {
      getNavigation().stop();
    }
    Vec3 look = player.getEyePosition();
    if (recognizing) {
      // Face, then hands, then feet: studying how the guest is built and moves.
      switch ((attentionTicks / 40) % 3) {
        case 1 -> look = player.position().add(player.getLookAngle().scale(0.4)).add(0, 0.9, 0);
        case 2 -> look = player.position();
        default -> {}
      }
    }
    getLookControl().setLookAt(look.x, look.y, look.z, 10.0F, 10.0F);
  }

  private void tickMelody(ServerLevel level) {
    Warden warden = melodyTarget;
    if (warden.isRemoved() || !warden.isAlive()) {
      melodyTarget = null;
      return;
    }
    if (distanceToSqr(warden) > MELODY_DISTANCE * MELODY_DISTANCE && melodyTicks == 0) {
      if (tickCount % REPATH_TICKS == 0) {
        getNavigation().moveTo(warden, WALK_SPEED * 1.4);
      }
      return;
    }
    getNavigation().stop();
    getLookControl().setLookAt(warden);
    melodyTicks++;
    if (melodyTicks % MELODY_NOTE_TICKS == 0) {
      int note = melodyTicks / MELODY_NOTE_TICKS - 1;
      // Each city keeps its own melody: note-block semitones derived from the city identity.
      int semitone = (int) (GuestHash.unit(GuestHash.hash(level.getSeed(), cityId, note)) * 25);
      float pitch = (float) Math.pow(2.0, (semitone - 12) / 12.0);
      level.playSound(
          null,
          getX(),
          getY(),
          getZ(),
          GuestArchitects.MELODY.get(),
          SoundSource.NEUTRAL,
          1.5F,
          pitch);
      level.sendParticles(
          ParticleTypes.NOTE, getX(), getEyeY() + 0.5, getZ(), 0, semitone / 24.0, 0, 0, 1.0);
    }
    if (melodyTicks >= MELODY_NOTES * MELODY_NOTE_TICKS) {
      CityManager.calm(level, warden);
      melodyTarget = null;
      restTicks = 100;
    }
  }

  @Override
  public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
    if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
      return super.hurtServer(level, source, damage);
    }
    Entity attacker = source.getEntity();
    if (attacker instanceof ServerPlayer player && !player.isCreative()) {
      CityManager.get(level).onArchitectAttacked(this, player);
    }
    return false;
  }

  @Override
  protected boolean shouldDropLoot(ServerLevel level) {
    return false;
  }

  @Override
  public boolean shouldDropExperience() {
    return false;
  }

  @Override
  protected Entity.MovementEmission getMovementEmission() {
    // Silent steps: no sound and, more importantly, no vibration for sculk or Wardens.
    return Entity.MovementEmission.NONE;
  }

  @Override
  public boolean canBeLeashed() {
    return false;
  }

  @Override
  public boolean removeWhenFarAway(double distSqr) {
    return false;
  }

  @Override
  public boolean isBaby() {
    return entityData.get(YOUNG);
  }

  @Override
  public float getAgeScale() {
    return isBaby() ? 0.6F : 1.0F;
  }

  @Override
  protected void defineSynchedData(SynchedEntityData.Builder builder) {
    super.defineSynchedData(builder);
    builder.define(YOUNG, false);
  }

  @Override
  public void onSyncedDataUpdated(EntityDataAccessor<?> accessor) {
    if (YOUNG.equals(accessor)) {
      refreshDimensions();
    }
    super.onSyncedDataUpdated(accessor);
  }

  @Override
  protected void addAdditionalSaveData(ValueOutput output) {
    super.addAdditionalSaveData(output);
    output.putLong("City", cityId);
    output.putInt("Index", index);
    output.putString("Role", role.name());
  }

  @Override
  protected void readAdditionalSaveData(ValueInput input) {
    super.readAdditionalSaveData(input);
    cityId = input.getLongOr("City", 0L);
    index = input.getIntOr("Index", -1);
    try {
      role = ArchitectRole.valueOf(input.getStringOr("Role", ArchitectRole.WATCHER.name()));
    } catch (IllegalArgumentException ignored) {
      role = ArchitectRole.WATCHER;
    }
    entityData.set(YOUNG, role == ArchitectRole.YOUNG);
  }
}
