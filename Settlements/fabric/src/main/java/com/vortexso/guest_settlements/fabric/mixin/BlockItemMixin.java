package com.vortexso.guest_settlements.fabric.mixin;

import com.vortexso.guest_settlements.life.Rites;
import com.vortexso.guest_settlements.village.VillageWorldEvents;
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
  private void guestSettlements$placed(
      BlockPlaceContext context, BlockState state, CallbackInfoReturnable<Boolean> cir) {
    if (cir.getReturnValueZ()) {
      VillageWorldEvents.blockChanged(context.getLevel(), context.getClickedPos());
      Rites.onPlace(context.getLevel(), context.getPlayer(), context.getClickedPos(), state);
    }
  }
}
