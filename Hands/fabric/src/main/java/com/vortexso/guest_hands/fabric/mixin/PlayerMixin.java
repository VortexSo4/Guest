package com.vortexso.guest_hands.fabric.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.vortexso.guest_hands.sleep.SleepPass;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Player.class)
public abstract class PlayerMixin {
  @WrapWithCondition(
      method = "tick",
      at =
          @At(
              value = "INVOKE",
              target = "Lnet/minecraft/world/entity/player/Player;stopSleepInBed(ZZ)V"))
  private boolean guestHands$keepSleeping(Player player, boolean wakeImmediately, boolean update) {
    return !(player instanceof ServerPlayer server && SleepPass.keepSleeping(server));
  }
}
