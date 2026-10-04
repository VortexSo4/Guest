package com.vortexso.guest_atmosphere.weather;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;
import java.util.function.LongFunction;

public final class WeatherModel {
  public static final int SEGMENTS_PER_SLOT = 4;

  private static final int PRECIPITATION = 0;
  private static final int SAND = 1;
  private static final int FOG = 2;
  private static final int HEAT = 3;
  private static final int LEAF = 4;
  private static final int KINDS = 5;

  private static final long SALT_PHASE = 0x6A09E667F3BCC909L;
  private static final long SALT_EVENT = 0xBB67AE8584CAA73BL;
  private static final long SALT_EXTRA = 0x3C6EF372FE94F82BL;
  private static final long SALT_AURORA = 0xA54FF53A5F1D36F1L;
  private static final long SALT_MORNING = 0x5BE0CD19137E2179L;

  private static final long DAY_START = 1_000L;
  private static final long DAY_END = 12_000L;

  private static final long DAWN_START = 22_500L;

  private static final long DAWN_FULL = 23_500L;
  private static final long DAWN_FADE = 1_000L;
  private static final long DAWN_END = 2_500L;

  private static final double CALM_SEGMENTS = 2.0;

  private static final int SNOW_STEPS = 32;

  private static final long SNOW_STEP_TICKS = 1_200L;

  private static final int DRY_STEPS = 32;

  private WeatherModel() {}

  public enum ClimateClass {
    TEMPERATE,
    BOREAL,
    WET,
    DRY
  }

  public record Climate(
      ClimateClass climateClass,
      float temperature,
      boolean sandy,
      boolean forested,
      boolean open,
      float dampness) {
    public Climate(
        ClimateClass climateClass,
        float temperature,
        boolean sandy,
        boolean forested,
        boolean open) {
      this(climateClass, temperature, sandy, forested, open, 0.0F);
    }

    public boolean hot() {
      return climateClass == ClimateClass.DRY;
    }
  }

  public record Sample(WeatherState state, float temperature, float windAngle) {}

  public record Cover(float snow, float dryness) {
    public static final Cover NONE = new Cover(0.0F, 0.0F);
  }

  public static Sample sample(
      long seed, long time, int x, int z, Climate climate, WeatherParameters parameters) {
    int season = GuestTime.season(time).ordinal();
    long tickOfDay = GuestTime.tickOfDay(time);
    boolean day = tickOfDay >= DAY_START && tickOfDay < DAY_END;

    long dawnDay = GuestTime.day(time + GuestTime.TICKS_PER_DAY - DAWN_START);

    double size = parameters.cellSize();
    double fx = (x - drift(time, parameters)) / size - 0.5;
    double fz = z / size - 0.5;
    long cx = (long) Math.floor(fx);
    long cz = (long) Math.floor(fz);
    double wx = blendWeight(fx - cx, parameters.blendFraction());
    double wz = blendWeight(fz - cz, parameters.blendFraction());

    Accumulator acc = new Accumulator();
    acc.add(seed, cx, cz, (1 - wx) * (1 - wz), time, dawnDay, climate, season, day, parameters);
    acc.add(seed, cx + 1, cz, wx * (1 - wz), time, dawnDay, climate, season, day, parameters);
    acc.add(seed, cx, cz + 1, (1 - wx) * wz, time, dawnDay, climate, season, day, parameters);
    acc.add(seed, cx + 1, cz + 1, wx * wz, time, dawnDay, climate, season, day, parameters);

    double temperature =
        climate.temperature()
            + seasonTemperature(time, parameters)
            + acc.anomaly
            + (day ? parameters.diurnalAmplitude() : -parameters.diurnalAmplitude());
    boolean dryWinter =
        climate.climateClass() == ClimateClass.DRY && season == Season.WINTER.ordinal();
    if (dryWinter && !day) {
      temperature -= parameters.desertNightChill();
    }

    int best = -1;
    double bestField = parameters.minimumField();
    for (int kind = 0; kind < KINDS; kind++) {
      if (acc.fields[kind] > bestField) {
        best = kind;
        bestField = acc.fields[kind];
      }
    }

    double calm = Math.min(1.0, 2.0 * acc.calm);
    double wind = acc.wind * (1.0 - 0.8 * calm) * parameters.windiness()[season];

    if (best < 0 || best == FOG) {
      double mist =
          acc.morning
              * dawn(tickOfDay)
              * climate.dampness()
              * (1.0 - smoothstep((wind - 0.3) / 0.3));
      if (mist > bestField) {
        best = FOG;
        bestField = mist;
      }
    }

    WeatherType type;
    double intensity = bestField;
    switch (best) {
      case PRECIPITATION -> {
        boolean severe = acc.severe > parameters.severeThreshold() * bestField;
        boolean snow = temperature < parameters.snowTemperature() || dryWinter;
        if (snow) {
          type =
              severe
                  ? (climate.open() ? WeatherType.SNOWSTORM : WeatherType.BLIZZARD)
                  : WeatherType.SNOWFALL;
          wind += severe ? 0.5 * bestField + (climate.open() ? 0.2 : 0.0) : 0.1;
        } else if (severe && season == Season.AUTUMN.ordinal()) {

          type = WeatherType.DOWNPOUR;
          wind += 0.3 * bestField;
        } else if (severe && season != Season.WINTER.ordinal()) {
          type = WeatherType.THUNDERSTORM;
          wind += 0.4 * bestField;
        } else if (bestField >= 0.7) {
          type = WeatherType.DOWNPOUR;
        } else if (bestField >= 0.35) {
          type = WeatherType.RAIN;
        } else {
          type = WeatherType.DRIZZLE;
        }
      }
      case SAND -> {
        type = WeatherType.SANDSTORM;
        wind += 0.6 * bestField;
      }
      case FOG -> {
        type = WeatherType.FOG;
        wind *= 0.3;
      }
      case HEAT -> {
        type = WeatherType.HEAT;
        wind *= 0.5;
      }
      case LEAF -> {
        type = WeatherType.LEAF_FALL;
        wind += 0.2;
      }
      default -> {
        type = wind >= 0.65 ? WeatherType.WIND : WeatherType.CLEAR;
        intensity = type == WeatherType.WIND ? wind : 0.0;
      }
    }

    boolean aurora =
        (type == WeatherType.CLEAR || type == WeatherType.WIND)
            && season == Season.WINTER.ordinal()
            && GuestTime.isNight(time)
            && climate.temperature() <= parameters.auroraMaxTemperature()
            && GuestHash.unit(
                    GuestHash.hash(
                        seed ^ SALT_AURORA,
                        (long) Math.floor(fx + 0.5),
                        (long) Math.floor(fz + 0.5),
                        GuestTime.day(time)))
                < parameters.auroraChance();

    return new Sample(
        new WeatherState(type, (float) intensity, (float) wind, aurora),
        (float) temperature,
        (float) acc.angle);
  }

  public static Cover cover(long time, LongFunction<Sample> weather, WeatherParameters parameters) {
    double snow = 0.0;
    for (int i = SNOW_STEPS; i >= 0; i--) {
      Sample sample = weather.apply(time - i * SNOW_STEP_TICKS);
      WeatherType type = sample.state().type();
      if (type.isSnow()) {
        snow += (1.0 - snow) * (0.1 + 0.2 * sample.state().intensity());
      } else if (type.isRain()) {
        snow *= 0.6;
      } else if (sample.temperature() > parameters.snowTemperature()) {
        snow *= 0.85;
      } else {
        snow *= 0.97;
      }
    }
    double dry = 0.0;
    for (int i = DRY_STEPS; i >= 0; i--) {
      Sample sample = weather.apply(time - i * parameters.segmentTicks());
      WeatherType type = sample.state().type();
      if (type == WeatherType.HEAT) {
        dry += (1.0 - dry) * 0.25 * sample.state().intensity();
      } else if (type.isPrecipitation()) {
        dry *= 0.5;
      } else {
        dry *= 0.95;
      }
    }
    return new Cover((float) snow, (float) dry);
  }

  public static double drift(long time, WeatherParameters parameters) {
    return time * parameters.driftBlocksPerDay() / GuestTime.TICKS_PER_DAY;
  }

  static double seasonTemperature(long time, WeatherParameters parameters) {
    int season = GuestTime.season(time).ordinal();
    double[] offsets = parameters.seasonTemperature();
    double blend = smoothstep((GuestTime.seasonProgress(time) - 0.75) / 0.25);
    return offsets[season] + (offsets[(season + 1) % offsets.length] - offsets[season]) * blend;
  }

  public static double dawn(long tickOfDay) {
    if (tickOfDay >= DAWN_START) {
      return smoothstep((tickOfDay - DAWN_START) / (double) (DAWN_FULL - DAWN_START));
    }
    if (tickOfDay < DAWN_FADE) {
      return 1.0;
    }
    return 1.0 - smoothstep((tickOfDay - DAWN_FADE) / (double) (DAWN_END - DAWN_FADE));
  }

  private static double blendWeight(double t, double blendFraction) {
    if (blendFraction <= 0.0) {
      return t < 0.5 ? 0.0 : 1.0;
    }
    return smoothstep((t - 0.5) / blendFraction + 0.5);
  }

  private static double smoothstep(double t) {
    double c = Math.max(0.0, Math.min(1.0, t));
    return c * c * (3.0 - 2.0 * c);
  }

  private static double unit16(long hash, int index) {
    return ((hash >>> (index * 16)) & 0xFFFFL) / 65_536.0;
  }

  private static int kind(double roll, Climate climate, int season, WeatherParameters p) {
    double threshold = p.precipitation(climate.climateClass(), season);
    if (roll < threshold) {
      return PRECIPITATION;
    }
    if (climate.sandy() && roll < (threshold += p.sandstorm()[season])) {
      return SAND;
    }
    if (roll < (threshold += p.heat(climate.climateClass(), season))) {
      return HEAT;
    }
    if (roll < (threshold += p.fog(climate.climateClass(), season))) {
      return FOG;
    }
    if (climate.forested()
        && season == Season.AUTUMN.ordinal()
        && roll < threshold + p.leafFall()) {
      return LEAF;
    }
    return -1;
  }

  private static final class Accumulator {
    final double[] fields = new double[KINDS];
    double severe;
    double wind;
    double anomaly;
    double angle;
    double calm;
    double morning;

    void add(
        long seed,
        long cx,
        long cz,
        double weight,
        long time,
        long dawnDay,
        Climate climate,
        int season,
        boolean day,
        WeatherParameters p) {
      if (weight <= 0.0) {
        return;
      }
      long slotTicks = p.slotTicks();
      long local =
          time + (long) (GuestHash.unit(GuestHash.hash(seed ^ SALT_PHASE, cx, cz)) * slotTicks);
      long slot = Math.floorDiv(local, slotTicks);
      double calmTicks = CALM_SEGMENTS * p.segmentTicks();

      int bestKind = -1;
      double bestAmount = 0.0;
      boolean bestSevere = false;
      double calmHere = 0.0;
      long currentEvent = 0L;
      long currentExtra = 0L;
      for (long s = slot; s >= slot - 1; s--) {
        long event = GuestHash.hash(seed ^ SALT_EVENT, cx, cz, s);
        long extra = GuestHash.hash(event, SALT_EXTRA);
        if (s == slot) {
          currentEvent = event;
          currentExtra = extra;
        }
        int kind = kind(unit16(event, 0), climate, season, p);
        boolean severe =
            (kind == PRECIPITATION || kind == SAND)
                && unit16(event, 2) < p.severe(climate.climateClass(), season);
        double start = s * (double) slotTicks + unit16(event, 3) * (slotTicks - p.segmentTicks());
        double duration =
            p.segmentTicks()
                * (p.minEventSegments()
                    + unit16(extra, 0) * (p.maxEventSegments() - p.minEventSegments()));
        if (severe && kind == PRECIPITATION && season == Season.SUMMER.ordinal()) {
          duration *= 0.5;
        }
        double elapsed = local - start;

        if (severe && elapsed >= duration - p.rampTicks() && elapsed < duration + calmTicks) {
          calmHere = Math.max(calmHere, Math.min(1.0, 1.0 - (elapsed - duration) / calmTicks));
        }
        if (kind < 0 || elapsed < 0.0 || elapsed >= duration || (kind == HEAT && !day)) {
          continue;
        }
        double roll = unit16(event, 1);
        double strength =
            kind == PRECIPITATION
                ? (severe ? 0.6 + 0.4 * roll : 0.2 + 0.8 * roll * roll)
                : 0.4 + 0.6 * roll;
        double ramp = Math.min(1.0, Math.min(elapsed, duration - elapsed) / p.rampTicks());
        double amount = ramp * strength;
        if (amount > bestAmount) {
          bestKind = kind;
          bestAmount = amount;
          bestSevere = severe;
        }
      }
      if (bestKind >= 0) {
        fields[bestKind] += weight * bestAmount;
        if (bestSevere) {
          severe += weight * bestAmount;
        }
      }
      calm += weight * calmHere;

      double mistRoll = GuestHash.unit(GuestHash.hash(seed ^ SALT_MORNING, cx, cz, dawnDay));
      double mistChance = p.morningFog()[season];
      if (mistRoll < mistChance) {
        morning += weight * (1.0 - 0.5 * mistRoll / mistChance);
      }

      long nextExtra =
          GuestHash.hash(GuestHash.hash(seed ^ SALT_EVENT, cx, cz, slot + 1), SALT_EXTRA);
      double progress = (local - slot * (double) slotTicks) / slotTicks;
      double windNow = unit16(currentExtra, 1);
      double windNext = unit16(nextExtra, 1);
      wind += weight * lerp(windNow * windNow, windNext * windNext, progress);
      anomaly +=
          weight
              * (lerp(unit16(currentExtra, 2), unit16(nextExtra, 2), progress) - 0.5)
              * 2.0
              * p.anomalyAmplitude();
      angle += weight * (unit16(currentEvent ^ currentExtra, 3) - 0.5) * (Math.PI / 2.0);
    }

    private static double lerp(double a, double b, double t) {
      return a + (b - a) * t;
    }
  }
}
