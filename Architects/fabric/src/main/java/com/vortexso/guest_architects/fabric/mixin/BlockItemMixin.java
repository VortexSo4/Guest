package com.vortexso.guest_architects.fabric.mixin;

import com.vortexso.guest_architects.ArchitectsEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
  @Inject(method = "placeBlock", at = @At("RETURN"))
  private void guestArchitects$placed(
      BlockPlaceContext context, BlockState placementState, CallbackInfoReturnable<Boolean> cir) {
    if (cir.getReturnValueZ()
        && context.getLevel() instanceof ServerLevel level
        && context.getPlayer() instanceof ServerPlayer player) {
      ArchitectsEvents.onPlace(
          level, player, context.getClickedPos(), level.getBlockState(context.getClickedPos()));
    }
  }
}
