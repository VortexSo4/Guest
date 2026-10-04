package com.vortexso.guest_architects.mixin;

import com.vortexso.guest_architects.city.CityManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Warden.class)
abstract class WardenMixin {
  @Inject(method = "canTargetEntity", at = @At("HEAD"), cancellable = true)
  private void guest_architects$silence(
      @Nullable Entity entity, CallbackInfoReturnable<Boolean> result) {
    if (entity instanceof Player player
        && player.level() instanceof ServerLevel level
        && CityManager.get(level).isSilenced(player)) {
      result.setReturnValue(false);
    }
  }
}
