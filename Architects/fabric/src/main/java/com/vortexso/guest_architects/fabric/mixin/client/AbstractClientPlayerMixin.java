package com.vortexso.guest_architects.fabric.mixin.client;

import com.vortexso.guest_architects.client.ArchitectsClient;
import net.minecraft.client.player.AbstractClientPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerMixin {
  @Inject(method = "getFieldOfViewModifier", at = @At("RETURN"), cancellable = true)
  private void guestArchitects$steadyFov(
      boolean firstPerson, float effectScale, CallbackInfoReturnable<Float> cir) {
    if (ArchitectsClient.steadyFov((AbstractClientPlayer) (Object) this)) {
      cir.setReturnValue(1.0F);
    }
  }
}
