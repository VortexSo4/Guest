package com.vortexso.guest_hands.fabric.mixin;

import com.vortexso.guest_hands.client.GrappleClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
  @Inject(
      method = "startUseItem",
      at =
          @At(
              value = "INVOKE",
              target =
                  "Lnet/minecraft/world/InteractionHand;values()[Lnet/minecraft/world/InteractionHand;"),
      cancellable = true)
  private void guestHands$grapple(CallbackInfo ci) {
    if (GrappleClient.onUse()) {
      ci.cancel();
    }
  }
}
