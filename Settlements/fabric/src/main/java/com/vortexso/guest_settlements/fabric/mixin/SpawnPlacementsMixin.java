package com.vortexso.guest_settlements.fabric.mixin;

import com.vortexso.guest_settlements.illager.IllagerCamps;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.level.ServerLevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SpawnPlacements.class)
public abstract class SpawnPlacementsMixin {
  @Inject(method = "checkSpawnRules", at = @At("RETURN"), cancellable = true)
  private static void guestSettlements$outpostCap(
      EntityType<?> type,
      ServerLevelAccessor level,
      EntitySpawnReason reason,
      BlockPos pos,
      RandomSource random,
      CallbackInfoReturnable<Boolean> cir) {
    if (cir.getReturnValueZ() && !IllagerCamps.allowSpawn(type, level, reason, pos)) {
      cir.setReturnValue(false);
    }
  }
}
