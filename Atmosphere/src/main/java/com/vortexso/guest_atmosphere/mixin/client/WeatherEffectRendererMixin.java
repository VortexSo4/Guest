package com.vortexso.guest_atmosphere.mixin.client;

import com.vortexso.guest_atmosphere.weather.AtmosphereWeather;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.world.GuestWeather;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Rain/snow columns follow the local player's regional weather instead of the biome temperature, so
 * plains show snow in winter and a desert can see its rare snowfall.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererMixin {
  @Inject(method = "getPrecipitationAt", at = @At("HEAD"), cancellable = true)
  private void guestAtmosphere$regionalPrecipitation(
      Level level, BlockPos pos, CallbackInfoReturnable<Biome.Precipitation> cir) {
    if (level.dimension() != Level.OVERWORLD || !AtmosphereWeather.driving(level)) {
      return;
    }
    if (!level
        .getChunkSource()
        .hasChunk(
            SectionPos.blockToSectionCoord(pos.getX()),
            SectionPos.blockToSectionCoord(pos.getZ()))) {
      cir.setReturnValue(Biome.Precipitation.NONE);
      return;
    }
    cir.setReturnValue(
        AtmosphereWeather.precipitation(
            GuestWeather.get(level, pos, GuestTime.gameTime(level)).type()));
  }
}
