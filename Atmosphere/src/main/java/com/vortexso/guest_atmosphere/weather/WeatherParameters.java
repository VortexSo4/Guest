package com.vortexso.guest_atmosphere.weather;

import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;

/**
 * Every tuning value of the weather model in one place. Tables are indexed {@code
 * [ClimateClass.ordinal()][Season.ordinal()]} (spring, summer, autumn, winter) and hold the chance
 * that one region cell gets an event of that kind in one event slot (one slot = {@link
 * WeatherModel#SEGMENTS_PER_SLOT} segments = one day with the default segment length).
 *
 * <p>Vanilla basis: vanilla rain lasts 12 000–24 000 ticks after a 12 000–180 000 tick pause, i.e.
 * it rains ~16% of the time. An event here lasts 2–4 segments (12 000–24 000 ticks by default, the
 * vanilla rain duration) inside a 24 000 tick slot; with events spilling into the next slot and
 * border blending the measured time share is roughly equal to the chance (see WeatherModelTest).
 * Temperate {@code 0.18–0.24} therefore rains 17–23% of the time, slightly more than vanilla
 * because drizzle now counts as rain.
 *
 * <p>Arrays are never mutated after construction.
 */
public record WeatherParameters(
    long segmentTicks,
    int cellSize,
    double blendFraction,
    double driftBlocksPerDay,
    double minEventSegments,
    double maxEventSegments,
    double rampTicks,
    double[][] precipitation,
    double[][] severe,
    double[][] fog,
    double[] sandstorm,
    double[] heat,
    double leafFall,
    double[] windiness,
    double[] seasonTemperature,
    double anomalyAmplitude,
    double diurnalAmplitude,
    double desertNightChill,
    double snowTemperature,
    double minimumField,
    double severeThreshold,
    double auroraChance,
    double auroraMaxTemperature) {

  /** Rows: TEMPERATE, BOREAL, WET, DRY. Columns: spring, summer, autumn, winter. */
  public static final double[][] DEFAULT_PRECIPITATION = {
    {0.24, 0.18, 0.24, 0.22},
    // taiga: rainy cool summer, snowy winter
    {0.24, 0.30, 0.27, 0.33},
    {0.36, 0.40, 0.36, 0.28},
    // desert/savanna/badlands: winter precipitation is snow, rare enough to be memorable
    {0.02, 0.01, 0.02, 0.03}
  };

  /** Share of precipitation events that become thunderstorms / blizzards. */
  public static final double[][] DEFAULT_SEVERE = {
    {0.15, 0.30, 0.15, 0.20},
    // taiga: frequent winter blizzards
    {0.15, 0.20, 0.20, 0.50},
    {0.30, 0.35, 0.25, 0.10},
    {0.10, 0.30, 0.10, 0.20}
  };

  public static final double[][] DEFAULT_FOG = {
    {0.06, 0.03, 0.12, 0.09},
    // taiga: foggy summer
    {0.10, 0.18, 0.12, 0.06},
    {0.12, 0.12, 0.15, 0.15},
    {0.00, 0.00, 0.00, 0.00}
  };

  /** Sandy biomes only (desert, badlands); summer is the sandstorm season. */
  public static final double[] DEFAULT_SANDSTORM = {0.10, 0.25, 0.06, 0.00};

  /** Hot dry biomes only; heat is a daytime-only event. */
  public static final double[] DEFAULT_HEAT = {0.15, 0.40, 0.08, 0.00};

  public static final double[] DEFAULT_WINDINESS = {1.0, 0.7, 1.1, 1.0};

  /**
   * Added to the biome temperature (vanilla scale: snow below 0.15). Plains (0.8) in winter sits at
   * 0.1, so it snows on average and the daily anomaly produces thaws; taiga (0.25) summer is 0.35,
   * cool rain.
   */
  public static final double[] DEFAULT_SEASON_TEMPERATURE = {-0.10, 0.10, -0.10, -0.70};

  public static final WeatherParameters DEFAULT =
      new WeatherParameters(
          6_000L,
          768,
          0.3,
          256.0,
          2.0,
          4.0,
          600.0,
          DEFAULT_PRECIPITATION,
          DEFAULT_SEVERE,
          DEFAULT_FOG,
          DEFAULT_SANDSTORM,
          DEFAULT_HEAT,
          0.35,
          DEFAULT_WINDINESS,
          DEFAULT_SEASON_TEMPERATURE,
          0.25,
          0.05,
          0.8,
          0.15,
          0.12,
          0.4,
          0.08,
          0.35);

  public double precipitation(ClimateClass climate, int season) {
    return precipitation[climate.ordinal()][season];
  }

  public double severe(ClimateClass climate, int season) {
    return severe[climate.ordinal()][season];
  }

  public double fog(ClimateClass climate, int season) {
    return fog[climate.ordinal()][season];
  }

  public long slotTicks() {
    return segmentTicks * WeatherModel.SEGMENTS_PER_SLOT;
  }
}
