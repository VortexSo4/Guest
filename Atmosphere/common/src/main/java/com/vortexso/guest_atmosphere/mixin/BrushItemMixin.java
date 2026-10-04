package com.vortexso.guest_atmosphere.mixin;

import com.vortexso.guest_atmosphere.trace.TraceInteractions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BrushItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BrushItem.class)
public abstract class BrushItemMixin {
  @Inject(method = "onUseTick", at = @At("HEAD"))
  private void guestAtmosphere$brushTraces(
      Level level, LivingEntity entity, ItemStack stack, int ticksRemaining, CallbackInfo ci) {
    TraceInteractions.brushTick(entity, stack, ticksRemaining);
  }
}
