package com.vortexso.guest_atmosphere.client;

import java.util.function.DoubleSupplier;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;

/** Non-positional weather loop whose volume glides toward a target and stops once silent. */
final class WeatherLoopSound extends AbstractTickableSoundInstance {
  private static final float FADE_STEP = 0.02F;

  private final DoubleSupplier target;

  WeatherLoopSound(SoundEvent event, DoubleSupplier target) {
    super(event, SoundSource.WEATHER, SoundInstance.createUnseededRandom());
    this.target = target;
    this.looping = true;
    this.delay = 0;
    // Starting audible avoids the sound manager skipping a silent instance.
    this.volume = FADE_STEP;
    this.relative = true;
    this.attenuation = SoundInstance.Attenuation.NONE;
  }

  @Override
  public void tick() {
    float goal = (float) target.getAsDouble();
    volume += Mth.clamp(goal - volume, -FADE_STEP, FADE_STEP);
    if (volume <= 0.0F && goal <= 0.0F) {
      stop();
    }
  }
}
