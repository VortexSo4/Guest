package com.vortexso.guest_settlements.fabric.mixin;

import com.vortexso.guest_settlements.GuestSettlements;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mob.class)
public abstract class MobMixin {
  @Inject(method = "setTarget", at = @At("HEAD"), cancellable = true)
  private void guestSettlements$allowTarget(@Nullable LivingEntity target, CallbackInfo ci) {
    if (!GuestSettlements.allowTarget((Mob) (Object) this, target)) {
      ci.cancel();
    }
  }
}
