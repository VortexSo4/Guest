package com.vortexso.guest_hands.fabric.mixin;

import com.vortexso.guest_hands.client.GrappleClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
  @Inject(method = "tick", at = @At("HEAD"))
  private void guestHands$grappleTick(CallbackInfo ci) {
    GrappleClient.tick((LocalPlayer) (Object) this);
  }
}
