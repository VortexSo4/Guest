package com.vortexso.guest_atmosphere.fabric.mixin;

import com.vortexso.guest_atmosphere.client.AuroraRenderer;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public abstract class SkyRendererMixin {
  @Inject(method = "renderSunMoonAndStars", at = @At("TAIL"))
  private void guestAtmosphere$aurora(CallbackInfo ci) {
    AuroraRenderer.renderSky();
  }
}
