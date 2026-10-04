package com.vortexso.guest_architects.fabric.mixin;

import com.vortexso.guest_architects.ArchitectsEvents;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Zombie.class)
public abstract class ZombieMixin {
  @Inject(method = "finalizeSpawn", at = @At("HEAD"))
  private void guestArchitects$finalizeSpawn(
      ServerLevelAccessor level,
      DifficultyInstance difficulty,
      EntitySpawnReason spawnReason,
      SpawnGroupData groupData,
      CallbackInfoReturnable<SpawnGroupData> cir) {
    ArchitectsEvents.onFinalizeSpawn((Mob) (Object) this, level.getLevel(), spawnReason);
  }
}
