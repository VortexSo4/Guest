package com.vortexso.guest_atmosphere.weather;

import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;

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
    double[][] heat,
    double leafFall,
    double[] morningFog,
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

  public static final double[][] DEFAULT_PRECIPITATION = {
    {0.34, 0.16, 0.36, 0.24},
    {0.30, 0.28, 0.34, 0.33},
    {0.42, 0.36, 0.44, 0.30},
    {0.02, 0.01, 0.02, 0.03}
  };

  public static final double[][] DEFAULT_SEVERE = {
    {0.15, 0.30, 0.25, 0.20},
    {0.15, 0.20, 0.25, 0.50},
    {0.30, 0.35, 0.30, 0.10},
    {0.10, 0.30, 0.10, 0.20}
  };

  public static final double[][] DEFAULT_FOG = {
    {0.10, 0.03, 0.14, 0.08},
    {0.10, 0.18, 0.12, 0.06},
    {0.14, 0.12, 0.16, 0.15},
    {0.00, 0.00, 0.00, 0.00}
  };

  public static final double[] DEFAULT_SANDSTORM = {0.10, 0.25, 0.06, 0.00};

  public static final double[][] DEFAULT_HEAT = {
    {0.00, 0.10, 0.00, 0.00},
    {0.00, 0.00, 0.00, 0.00},
    {0.00, 0.08, 0.00, 0.00},
    {0.15, 0.40, 0.08, 0.00}
  };

  public static final double[] DEFAULT_MORNING_FOG = {0.45, 0.25, 0.55, 0.25};

  public static final double[] DEFAULT_WINDINESS = {1.0, 0.7, 1.3, 1.0};

  public static final double[] DEFAULT_SEASON_TEMPERATURE = {-0.10, 0.10, -0.10, -0.75};

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
          DEFAULT_MORNING_FOG,
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

  public double heat(ClimateClass climate, int season) {
    return heat[climate.ordinal()][season];
  }

  public long slotTicks() {
    return segmentTicks * WeatherModel.SEGMENTS_PER_SLOT;
  }
}
