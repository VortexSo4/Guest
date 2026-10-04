package com.vortexso.guest_settlements.fabric.mixin;

import com.vortexso.guest_settlements.village.VillageWorldEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FarmlandBlock.class)
public abstract class FarmlandBlockMixin {
  @Inject(method = "turnToDirt", at = @At("HEAD"))
  private static void guestSettlements$trampled(
      @Nullable Entity source, BlockState state, Level level, BlockPos pos, CallbackInfo ci) {
    VillageWorldEvents.blockChanged(level, pos);
  }
}
