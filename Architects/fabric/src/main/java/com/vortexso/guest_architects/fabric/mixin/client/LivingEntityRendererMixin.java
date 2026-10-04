package com.vortexso.guest_architects.fabric.mixin.client;

import com.vortexso.guest_architects.client.ArchitectsClient;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
  @Inject(
      method =
          "extractRenderState(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;F)V",
      at = @At("TAIL"))
  private void guestArchitects$curing(
      LivingEntity entity, LivingEntityRenderState state, float partialTicks, CallbackInfo ci) {
    ArchitectsClient.extractRenderState(entity, state);
  }
}
