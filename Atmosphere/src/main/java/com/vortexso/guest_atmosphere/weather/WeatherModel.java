package com.vortexso.guest_atmosphere.weather;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import com.vortexso.guest_core.api.world.WeatherState;
import com.vortexso.guest_core.api.world.WeatherType;

/**
 * Pure regional weather: {@code (seed, time, position, climate) -> weather}, O(1) for any time.
 *
 * <p>The world is split into square region cells that slowly drift east (fronts move). Every cell
 * draws one event per slot (a day with default settings): its kind, strength, severity, start and
 * duration come from a hash of {@code (seed, cell, slot)}. An event may run past its slot end, so
 * the previous slot is also checked; that is what lets storms last longer than a day. Cells get a
 * hashed phase so they do not all change at the same moment.
 *
 * <p>The event kind is resolved with the climate at the queried position, not the cell centre, so
 * biome decides what is possible (a cell's rain roll does not rain on desert that has no rain). The
 * four cells around a position are blended near their borders, so weather fades between regions
 * instead of switching at a line.
 */
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

  private static final long DAY_START = 1_000L;
  private static final long DAY_END = 12_000L;

  private WeatherModel() {}

  public enum ClimateClass {
    TEMPERATE,
    BOREAL,
    WET,
    DRY
  }

  /**
   * Biome/terrain facts at a position.
   *
   * @param temperature vanilla-scale biome temperature, already adjusted for altitude
   * @param open mountain or open country; turns blizzards into snowstorms
   */
  public record Climate(
      ClimateClass climateClass, float temperature, boolean sandy, boolean forested, boolean open) {
    public boolean hot() {
      return climateClass == ClimateClass.DRY;
    }
  }

  /**
   * @param temperature effective air temperature on the vanilla biome scale (snow below 0.15)
   * @param windAngle wind direction in radians (0 = towards +X)
   */
  public record Sample(WeatherState state, float temperature, float windAngle) {}

  public static Sample sample(
      long seed, long time, int x, int z, Climate climate, WeatherParameters parameters) {
    int season = GuestTime.season(time).ordinal();
    long tickOfDay = GuestTime.tickOfDay(time);
    boolean day = tickOfDay >= DAY_START && tickOfDay < DAY_END;

    double size = parameters.cellSize();
    double fx = (x - drift(time, parameters)) / size - 0.5;
    double fz = z / size - 0.5;
    long cx = (long) Math.floor(fx);
    long cz = (long) Math.floor(fz);
    double wx = blendWeight(fx - cx, parameters.blendFraction());
    double wz = blendWeight(fz - cz, parameters.blendFraction());

    Accumulator acc = new Accumulator();
    acc.add(seed, cx, cz, (1 - wx) * (1 - wz), time, climate, season, day, parameters);
    acc.add(seed, cx + 1, cz, wx * (1 - wz), time, climate, season, day, parameters);
    acc.add(seed, cx, cz + 1, (1 - wx) * wz, time, climate, season, day, parameters);
    acc.add(seed, cx + 1, cz + 1, wx * wz, time, climate, season, day, parameters);

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

    double wind = acc.wind * parameters.windiness()[season];
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

  /** How far the cell grid has drifted east at this time; cell i spans [i*size+drift, ...). */
  public static double drift(long time, WeatherParameters parameters) {
    return time * parameters.driftBlocksPerDay() / GuestTime.TICKS_PER_DAY;
  }

  /** Seasonal temperature offset, blended into the next season over the last quarter. */
  static double seasonTemperature(long time, WeatherParameters parameters) {
    int season = GuestTime.season(time).ordinal();
    double[] offsets = parameters.seasonTemperature();
    double blend = smoothstep((GuestTime.seasonProgress(time) - 0.75) / 0.25);
    return offsets[season] + (offsets[(season + 1) % offsets.length] - offsets[season]) * blend;
  }

  /** Weight of the next cell; 0 in the inner part of a cell, smooth across the border band. */
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

  /** 16-bit uniform from one quarter of a hash; plenty of resolution for probabilities. */
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
    if (climate.hot() && roll < (threshold += p.heat()[season])) {
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

    void add(
        long seed,
        long cx,
        long cz,
        double weight,
        long time,
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

      int bestKind = -1;
      double bestAmount = 0.0;
      boolean bestSevere = false;
      long currentEvent = 0L;
      long currentExtra = 0L;
      for (long s = slot; s >= slot - 1; s--) {
        long event = GuestHash.hash(seed ^ SALT_EVENT, cx, cz, s);
        long extra = GuestHash.hash(event, SALT_EXTRA);
        if (s == slot) {
          currentEvent = event;
          currentExtra = extra;
        }
        double start = s * (double) slotTicks + unit16(event, 3) * (slotTicks - p.segmentTicks());
        double duration =
            p.segmentTicks()
                * (p.minEventSegments()
                    + unit16(extra, 0) * (p.maxEventSegments() - p.minEventSegments()));
        double elapsed = local - start;
        if (elapsed < 0.0 || elapsed >= duration) {
          continue;
        }
        int kind = kind(unit16(event, 0), climate, season, p);
        if (kind < 0 || (kind == HEAT && !day)) {
          continue;
        }
        boolean severe =
            (kind == PRECIPITATION || kind == SAND)
                && unit16(event, 2) < p.severe(climate.climateClass(), season);
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

      // Wind and the temperature anomaly glide from this slot's value to the next one.
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
