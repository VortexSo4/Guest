package com.vortexso.guest_atmosphere.trace;

import com.vortexso.guest_atmosphere.AtmosphereConfig;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.WeatherType;

public final class History {
  public static final long NEVER = ChunkTraces.NEVER;

  public record Params(
      double visitsPerDay,
      double snowTemperature,
      double meltTemperature,
      double freezeTemperature,
      double meltPerTenth,
      double rainMeltPerDay,
      int snowfallCap,
      int stormCap,
      double mudChance,
      double sandChance,
      double dryChance,
      double iceChance,
      double floodChance,
      double coatChance,
      double litterPerDay,
      long mudDryingTicks) {

    static Params current() {
      double snow = AtmosphereConfig.weather().snowTemperature();
      return new Params(
          AtmosphereConfig.COLUMN_VISIT_RATE.get() / 256.0 * GuestTime.TICKS_PER_DAY,
          snow,
          snow + AtmosphereConfig.SNOW_MELT_OFFSET.get(),
          AtmosphereConfig.FREEZE_TEMPERATURE.get(),
          AtmosphereConfig.SNOW_MELT_RATE.get(),
          AtmosphereConfig.RAIN_SNOW_MELT.get(),
          AtmosphereConfig.SNOWFALL_MAX_LAYERS.get(),
          AtmosphereConfig.STORM_MAX_LAYERS.get(),
          AtmosphereConfig.MUD_CHANCE.get(),
          AtmosphereConfig.SAND_CHANCE.get(),
          AtmosphereConfig.DRY_CHANCE.get(),
          AtmosphereConfig.ICE_CHANCE.get(),
          AtmosphereConfig.SILT_CHANCE.get(),
          AtmosphereConfig.COAT_CHANCE.get(),
          AtmosphereConfig.LEAF_LITTER_PER_DAY.get(),
          (long) (AtmosphereConfig.MUD_DRYING_DAYS.get() * GuestTime.TICKS_PER_DAY));
    }

    public double meltPerDay(double temperature, boolean raining) {
      return Math.max(0.0, temperature - meltTemperature) * 10.0 * meltPerTenth
          + (raining ? rainMeltPerDay : 0.0);
    }

    public double snowPerDay(WeatherType type, double intensity) {
      return visitsPerDay * intensity * (type == WeatherType.SNOWFALL ? 0.6 : 1.0);
    }
  }

  public final Params params;
  public final long from;
  public final double initialSnow;

  public final long growthFrom;

  public long now;
  public double snowDepth;
  public boolean stormSnow;
  public long lastSnow = NEVER;
  public long lastThaw = NEVER;
  public long lastRain = NEVER;
  public long lastRealRain = NEVER;
  public long lastHeavyRain = NEVER;
  public long lastSandstorm = NEVER;
  public long lastWind = NEVER;
  public long lastHeat = NEVER;
  public long coldSince = NEVER;
  public long warmSince = NEVER;
  public double snowHits;
  public double mudHits;
  public double sandHits;
  public double dryHits;
  public double iceHits;
  public double floodHits;
  public double dampDays;
  public double litterDays;
  public int freezeThawCycles;
  public boolean winterSeen;

  private boolean wasFreezing;
  private boolean started;

  public History(Params params, long from, double initialSnow, long growthFrom) {
    this.params = params;
    this.from = from;
    this.growthFrom = growthFrom;
    this.now = from;
    this.initialSnow = initialSnow;
    this.snowDepth = initialSnow;
    this.stormSnow = initialSnow > params.snowfallCap();
  }

  public void add(
      long time,
      long ticks,
      WeatherType type,
      double intensity,
      double temperature,
      double wind,
      boolean wetClimate,
      boolean forested) {
    Params p = params;
    double days = ticks / (double) GuestTime.TICKS_PER_DAY;
    double visits = p.visitsPerDay() * days;
    now = time;

    boolean snowing = type.isSnow();
    boolean rain = type.isRain();
    boolean realRain = rain && type != WeatherType.DRIZZLE;
    boolean heavy = type == WeatherType.DOWNPOUR || type == WeatherType.THUNDERSTORM;

    if (snowing) {
      lastSnow = time;
      boolean storm = type != WeatherType.SNOWFALL;
      stormSnow |= storm;
      double cap =
          Math.max(storm ? p.stormCap() : p.snowfallCap(), Math.min(snowDepth, p.stormCap()));
      snowDepth =
          Math.max(snowDepth, Math.min(cap, snowDepth + p.snowPerDay(type, intensity) * days));
      snowHits += visits * intensity * p.coatChance();
    } else {

      double melt = Math.min(visits, p.meltPerDay(temperature, rain) * days);
      snowDepth = Math.max(0.0, snowDepth - melt);
      if (snowDepth == 0.0) {
        stormSnow = false;
      }
      if (temperature > p.meltTemperature()) {
        lastThaw = time;
      }
    }

    if (rain) {
      lastRain = time;
      sandHits = 0.0;
      dryHits = 0.0;
    }
    if (realRain && temperature >= p.snowTemperature()) {
      lastRealRain = time;
      mudHits += visits * p.mudChance() * intensity;
    } else if (lastRealRain != NEVER && time - lastRealRain > p.mudDryingTicks()) {
      mudHits = 0.0;
    }
    if (heavy && temperature >= p.snowTemperature()) {
      lastHeavyRain = time;
      floodHits += visits * p.floodChance() * intensity;
    }
    if (type == WeatherType.SANDSTORM) {
      lastSandstorm = time;
      sandHits += visits * p.sandChance() * intensity;
    }
    if (type == WeatherType.WIND) {
      lastWind = time;
    }
    if (type == WeatherType.HEAT) {
      lastHeat = time;
      dryHits += visits * p.dryChance() * intensity;
    }

    boolean freezing = temperature < p.freezeTemperature();
    if (freezing) {
      iceHits += visits * p.iceChance();
      if (coldSince == NEVER) {
        coldSince = time - ticks;
      }
    } else {
      iceHits = 0.0;
      coldSince = NEVER;
    }
    if (temperature > p.meltTemperature()) {
      if (warmSince == NEVER) {
        warmSince = time - ticks;
      }
    } else {
      warmSince = NEVER;
    }
    boolean counts = time > growthFrom;
    if (started && freezing != wasFreezing && counts) {
      freezeThawCycles++;
    }
    wasFreezing = freezing;
    started = true;

    if (counts && (rain || snowing || type == WeatherType.FOG)) {
      dampDays += days;
    } else if (counts && wetClimate && type != WeatherType.HEAT) {
      dampDays += days * 0.3;
    }

    Season season = GuestTime.season(time);
    if (season == Season.AUTUMN && forested) {
      litterDays += days * (type == WeatherType.LEAF_FALL || type == WeatherType.WIND ? 3.0 : 1.0);
    } else if (season == Season.WINTER) {
      winterSeen = true;
      litterDays = 0.0;
    }
  }

  public boolean rainedWithin(long ticks) {
    return lastRealRain != NEVER && now - lastRealRain <= ticks;
  }

  public boolean heavyRainWithin(long ticks) {
    return lastHeavyRain != NEVER && now - lastHeavyRain <= ticks;
  }

  public long coldFor() {
    return coldSince == NEVER ? 0L : now - coldSince;
  }

  public long warmFor() {
    return warmSince == NEVER ? 0L : now - warmSince;
  }

  public boolean clearedSince(long event) {
    return event == NEVER || lastRain > event || lastWind > event;
  }

  public int snowLayers(double variation, int cap) {
    if (snowDepth < 0.5) {
      return 0;
    }
    double drift = cap > params.stormCap() ? 1.5 : 1.0;
    return (int) Math.min(cap, Math.round(snowDepth * (0.75 + 0.5 * variation) * drift));
  }

  public static double coverage(double hits) {
    return hits <= 0.0 ? 0.0 : 1.0 - Math.exp(-hits);
  }
}
