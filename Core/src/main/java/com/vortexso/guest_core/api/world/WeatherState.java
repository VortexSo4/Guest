package com.vortexso.guest_core.api.world;

/**
 * Weather as a value. Simulation lives in Atmosphere; everyone else only reads this.
 *
 * @param intensity 0..1 strength of the weather type
 * @param wind 0..1 wind strength, independent of type (a clear day can be windy)
 * @param aurora aurora visible tonight; only meaningful at night in cold regions
 */
public record WeatherState(WeatherType type, float intensity, float wind, boolean aurora) {
  public static final WeatherState CLEAR = new WeatherState(WeatherType.CLEAR, 0.0F, 0.0F, false);

  public WeatherState {
    intensity = clamp(intensity);
    wind = clamp(wind);
  }

  public boolean isSevere() {
    return type.isSevere() && intensity >= 0.5F;
  }

  private static float clamp(float value) {
    return Math.max(0.0F, Math.min(1.0F, value));
  }
}
