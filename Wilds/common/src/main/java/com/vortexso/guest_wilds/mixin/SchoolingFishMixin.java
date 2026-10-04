package com.vortexso.guest_wilds.mixin;

import com.vortexso.guest_wilds.WildsConfig;
import net.minecraft.world.entity.animal.fish.AbstractSchoolingFish;
import net.minecraft.world.entity.animal.fish.Salmon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin({AbstractSchoolingFish.class, Salmon.class})
abstract class SchoolingFishMixin {
  @Inject(method = "getMaxSchoolSize", at = @At("RETURN"), cancellable = true)
  private void guestWilds$schoolSize(CallbackInfoReturnable<Integer> cir) {
    if (WildsConfig.SPEC.isLoaded() && WildsConfig.FISH_SHOALS.get()) {
      cir.setReturnValue(Math.max(cir.getReturnValueI(), WildsConfig.FISH_SCHOOL_SIZE.get()));
    }
  }
}
