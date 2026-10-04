package com.vortexso.guest_wilds.fabric.mixin;

import com.vortexso.guest_wilds.fabric.SpawnHooks;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.monster.spider.Spider;
import net.minecraft.world.level.ServerLevelAccessor;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Spider.class)
abstract class SpiderMixin {
  @Inject(method = "finalizeSpawn", at = @At("HEAD"), cancellable = true)
  private void guestWilds$finalizeSpiderSpawn(
      ServerLevelAccessor level,
      DifficultyInstance difficulty,
      EntitySpawnReason reason,
      @Nullable SpawnGroupData groupData,
      CallbackInfoReturnable<SpawnGroupData> cir) {
    if (SpawnHooks.finalizeSpawn((Mob) (Object) this, level, reason)) {
      cir.setReturnValue(null);
    }
  }
}
