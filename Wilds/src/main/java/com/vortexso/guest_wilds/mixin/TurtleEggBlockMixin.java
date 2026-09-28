package com.vortexso.guest_wilds.mixin;

import com.vortexso.guest_wilds.lair.Lairs;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TurtleEggBlock.class)
abstract class TurtleEggBlockMixin {
  @Inject(method = "decreaseEggs", at = @At("HEAD"), cancellable = true)
  private void guestWilds$clutch(Level level, BlockPos pos, BlockState state, CallbackInfo ci) {
    if (level instanceof ServerLevel server && Lairs.burstClutch(server, pos, state)) {
      ci.cancel();
    }
  }
}
