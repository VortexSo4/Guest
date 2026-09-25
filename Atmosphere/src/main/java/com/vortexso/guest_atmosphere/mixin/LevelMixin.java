package com.vortexso.guest_atmosphere.mixin;

import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Regional precipitation for vanilla mechanics (entities getting wet, fire going out, lightning
 * targets): vanilla asks one global "is it raining" flag, the model answers per position.
 */
@Mixin(Level.class)
public abstract class LevelMixin {
  @Inject(method = "precipitationAt", at = @At("HEAD"), cancellable = true)
  private void guestAtmosphere$regionalPrecipitation(
      BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
    Biome.Precipitation precipitation =
        AtmosphereWeather.precipitationAt((Level) (Object) this, pos);
    if (precipitation != null) {
      cir.setReturnValue(precipitation);
    }
  }
}
