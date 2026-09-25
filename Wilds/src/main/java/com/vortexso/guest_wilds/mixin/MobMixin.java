package com.vortexso.guest_wilds.mixin;

import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_wilds.behavior.WildBehavior;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Fog keeps sun-sensitive undead from burning, so they can hunt through a foggy day. No event
 * covers the daylight burn check; injecting at RETURN only consults the weather when vanilla
 * already decided to burn.
 */
@Mixin(Mob.class)
abstract class MobMixin {
  @Inject(method = "isSunBurnTick", at = @At("RETURN"), cancellable = true)
  private void guestWilds$fogShield(CallbackInfoReturnable<Boolean> cir) {
    if (cir.getReturnValueZ()
        && ((Object) this) instanceof Mob mob
        && mob.level() instanceof ServerLevel level
        && WildBehavior.fogShields(level, mob.blockPosition(), GuestTime.gameTime(level))) {
      cir.setReturnValue(false);
    }
  }
}
