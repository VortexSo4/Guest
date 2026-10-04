package com.vortexso.guest_atmosphere.fabric.mixin;

import com.vortexso.guest_atmosphere.client.ClientWeatherEffects;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
  @ModifyVariable(method = "play", at = @At("HEAD"), argsOnly = true)
  private SoundInstance guestAtmosphere$weatherSound(SoundInstance instance) {
    return ClientWeatherEffects.playSound(instance);
  }
}
