package com.vortexso.guest_architects.entity;

import com.vortexso.guest_architects.ArchitectsConfig;
import com.vortexso.guest_architects.GuestArchitects;
import java.util.Map;
import java.util.function.BiConsumer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ConversionParams;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.LevelEvent;

public final class Cure {
  private static final Map<EntityType<?>, EntityType<? extends Mob>> CURED =
      Map.of(
          EntityType.ZOMBIFIED_PIGLIN, EntityType.PIGLIN,
          EntityType.ZOGLIN, EntityType.HOGLIN,
          EntityType.ZOMBIE_HORSE, EntityType.HORSE,
          EntityType.ZOMBIE_NAUTILUS, EntityType.NAUTILUS);

  public static BiConsumer<Mob, Mob> converted = (from, to) -> {};

  private static final int MIN_TICKS = 3600;

  private static final int SPREAD_TICKS = 2401;

  private Cure() {}

  public static boolean isCuring(Entity entity) {
    Integer ticks = GuestArchitects.CURING.getExisting(entity);
    return ticks != null && ticks > 0;
  }

  public static InteractionResult interact(Player player, Entity target, ItemStack stack) {
    if (!(target instanceof Mob mob)
        || !CURED.containsKey(mob.getType())
        || !stack.is(Items.GOLDEN_APPLE)
        || !ArchitectsConfig.CURE_INFECTED.get()) {
      return InteractionResult.PASS;
    }
    if (!mob.hasEffect(MobEffects.WEAKNESS) || isCuring(mob)) {
      return InteractionResult.CONSUME;
    }
    if (mob.level() instanceof ServerLevel level) {
      stack.consume(1, player);
      int ticks = MIN_TICKS + mob.getRandom().nextInt(SPREAD_TICKS);
      GuestArchitects.CURING.set(mob, ticks);
      mob.setPersistenceRequired();
      mob.removeEffect(MobEffects.WEAKNESS);
      mob.addEffect(new MobEffectInstance(MobEffects.STRENGTH, ticks, 0));
      level.playSound(
          null,
          mob.getX(),
          mob.getEyeY(),
          mob.getZ(),
          SoundEvents.ZOMBIE_VILLAGER_CURE,
          mob.getSoundSource(),
          1.0F + mob.getRandom().nextFloat(),
          mob.getRandom().nextFloat() * 0.7F + 0.3F);
    }
    return InteractionResult.SUCCESS_SERVER;
  }

  public static void tick(Mob mob) {
    if (!(mob.level() instanceof ServerLevel level) || !mob.isAlive()) {
      return;
    }
    int left = GuestArchitects.CURING.get(mob) - 1;
    if (left > 0) {
      GuestArchitects.CURING.set(mob, left);
      if (left % 40 == 0) {
        level.sendParticles(
            ParticleTypes.HAPPY_VILLAGER,
            mob.getX(),
            mob.getY() + mob.getBbHeight() * 0.6,
            mob.getZ(),
            2,
            mob.getBbWidth() * 0.4,
            mob.getBbHeight() * 0.3,
            mob.getBbWidth() * 0.4,
            0.0);
      }
      return;
    }
    EntityType<? extends Mob> target = CURED.get(mob.getType());
    if (target == null) {
      GuestArchitects.CURING.set(mob, 0);
      return;
    }
    convert(level, mob, target);
  }

  private static <T extends Mob> void convert(ServerLevel level, Mob mob, EntityType<T> target) {
    mob.convertTo(
        target,
        ConversionParams.single(mob, true, false),
        cured -> {
          switch (cured) {
            case AbstractPiglin piglin -> piglin.setImmuneToZombification(true);
            case Hoglin hoglin -> hoglin.setImmuneToZombification(true);
            case AbstractHorse horse ->
                horse.finalizeSpawn(
                    level,
                    level.getCurrentDifficultyAt(horse.blockPosition()),
                    EntitySpawnReason.CONVERSION,
                    null);
            default -> {}
          }
          if (GuestArchitects.CURING.has(cured)) {
            GuestArchitects.CURING.set(cured, 0);
          }
          cured.addEffect(new MobEffectInstance(MobEffects.NAUSEA, 200, 0));
          if (!mob.isSilent()) {
            level.levelEvent(null, LevelEvent.SOUND_ZOMBIE_CONVERTED, mob.blockPosition(), 0);
          }
          converted.accept(mob, cured);
        });
  }
}
