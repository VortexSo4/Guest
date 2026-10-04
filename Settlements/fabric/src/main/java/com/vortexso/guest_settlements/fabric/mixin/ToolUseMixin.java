package com.vortexso.guest_settlements.fabric.mixin;

import com.vortexso.guest_settlements.village.VillageWorldEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.context.UseOnContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({HoeItem.class, ShovelItem.class})
public abstract class ToolUseMixin {
  @Inject(method = "useOn", at = @At("RETURN"))
  private void guestSettlements$modified(
      UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
    if (cir.getReturnValue().consumesAction()) {
      VillageWorldEvents.blockChanged(context.getLevel(), context.getClickedPos());
    }
  }
}
