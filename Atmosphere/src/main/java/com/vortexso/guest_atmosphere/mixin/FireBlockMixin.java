package com.vortexso.guest_atmosphere.mixin;

import com.vortexso.guest_atmosphere.trace.FireTraces;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
  @Inject(
      method = "checkBurnOut",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/level/Level;removeBlock(Lnet/minecraft/core/BlockPos;Z)Z"),
      cancellable = true)
  private void guestAtmosphere$charInsteadOfBurning(
      Level level,
      BlockPos pos,
      int chance,
      RandomSource random,
      int age,
      Direction face,
      CallbackInfo ci) {
    if (FireTraces.charInsteadOfBurning(level, pos)) {
      ci.cancel();
    }
  }

  @Inject(method = "tick", at = @At("RETURN"))
  private void guestAtmosphere$ashAfterFire(
      BlockState state, ServerLevel level, BlockPos pos, RandomSource random, CallbackInfo ci) {
    FireTraces.afterFireTick(level, pos);
  }
}
