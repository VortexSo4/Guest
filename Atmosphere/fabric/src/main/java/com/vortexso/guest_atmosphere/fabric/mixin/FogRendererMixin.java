package com.vortexso.guest_atmosphere.fabric.mixin;

import com.vortexso.guest_atmosphere.client.WeatherFog;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.fog.FogRenderer;
import net.minecraft.world.level.material.FogType;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
  @Inject(method = "computeFogColor", at = @At("TAIL"))
  private void guestAtmosphere$fogColor(
      Camera camera,
      float partialTicks,
      ClientLevel level,
      int renderDistance,
      float darkenWorldAmount,
      Vector4f dest,
      CallbackInfo ci) {
    WeatherFog.fogColor(camera, dest);
  }

  @Inject(method = "setupFog", at = @At("RETURN"))
  private void guestAtmosphere$renderFog(
      Camera camera,
      int renderDistanceInChunks,
      DeltaTracker deltaTracker,
      float darkenWorldAmount,
      ClientLevel level,
      CallbackInfoReturnable<FogData> cir) {
    FogType type = camera.getFluidInCamera();
    WeatherFog.renderFog(type == FogType.NONE ? FogType.ATMOSPHERIC : type, cir.getReturnValue());
  }
}
