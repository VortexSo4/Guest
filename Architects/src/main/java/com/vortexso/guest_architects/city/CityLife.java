package com.vortexso.guest_architects.city;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;

/**
 * Closed-form city lifecycle. A city is {@code seed + id -> profile}, and its population at any day
 * is a function of that profile and time, so years of absence cost nothing and no individual
 * Architect is simulated while unobserved.
 */
public final class CityLife {
  private static final long SALT_LIVING = 0x4C49564EL;
  private static final long SALT_STATE = 0x53544154L;
  private static final long SALT_BASE = 0x42415345L;
  private static final long SALT_SHAPE = 0x53484150L;
  private static final long SALT_YOUNG = 0x594F554EL;
  private static final long SALT_NICHES = 0x4E494348L;

  /** Index below which roles are fixed, so every living city has each visible task covered. */
  private static final ArchitectRole[] CORE_ROLES = {
    ArchitectRole.RITUAL,
    ArchitectRole.MELODY,
    ArchitectRole.DEEPSLATE,
    ArchitectRole.WOOL,
    ArchitectRole.WATCHER,
    ArchitectRole.TEACHER
  };

  private static final ArchitectRole[] EXTRA_ROLES = {
    ArchitectRole.RITUAL, ArchitectRole.DEEPSLATE, ArchitectRole.WOOL, ArchitectRole.WATCHER
  };

  private static final double YOUNG_CHANCE = 0.25;

  private CityLife() {}

  public enum State {
    STABLE,
    FLUCTUATING,
    DECLINING
  }

  public record Params(
      double livingFraction,
      int minPopulation,
      int maxPopulation,
      double fluctuationYears,
      double fluctuationAmplitude,
      double declineYears,
      int spreadMaxRadius,
      double spreadDays) {}

  /**
   * @param shape fluctuation phase (0..1) or, for declining cities, the fraction of the base
   *     population still alive on world day 0
   */
  public record Profile(boolean living, State state, int basePopulation, double shape) {}

  public static Profile profile(long seed, long cityId, Params params) {
    boolean living =
        GuestHash.unit(GuestHash.hash(seed, cityId, SALT_LIVING)) < params.livingFraction;
    State state =
        State.values()[(int) (GuestHash.unit(GuestHash.hash(seed, cityId, SALT_STATE)) * 3)];
    int span = params.maxPopulation - params.minPopulation + 1;
    int base =
        params.minPopulation
            + (int) (GuestHash.unit(GuestHash.hash(seed, cityId, SALT_BASE)) * span);
    double u = GuestHash.unit(GuestHash.hash(seed, cityId, SALT_SHAPE));
    double shape = state == State.DECLINING ? 0.2 + 0.8 * u : u;
    return new Profile(living, state, base, shape);
  }

  public static Profile withOverrides(Profile profile, Boolean living, State state) {
    return new Profile(
        living != null ? living : profile.living,
        state != null ? state : profile.state,
        profile.basePopulation,
        state == State.DECLINING && profile.state != State.DECLINING
            ? 0.2 + 0.8 * profile.shape
            : profile.shape);
  }

  /**
   * @param day fractional world day
   * @param delayDays assistance delay; only declining cities use it, since help postpones the
   *     symptoms of decline but cannot create births
   */
  public static int population(Profile profile, double day, double delayDays, Params params) {
    if (!profile.living) {
      return 0;
    }
    double base = profile.basePopulation;
    double value =
        switch (profile.state) {
          case STABLE -> base;
          case FLUCTUATING ->
              base
                  * (1.0
                      + params.fluctuationAmplitude
                          * Math.sin(2.0 * Math.PI * (day / periodDays(params) + profile.shape)));
          case DECLINING ->
              base * (profile.shape - Math.max(0.0, day - delayDays) / declineDays(params));
        };
    return (int) Math.max(0, Math.min(Math.round(value), Math.round(base * 2)));
  }

  /** First fractional day with zero population, or {@code NaN} if the city never empties. */
  public static double abandonedSince(Profile profile, double delayDays, Params params) {
    if (!profile.living) {
      return 0.0;
    }
    if (profile.state != State.DECLINING) {
      return Double.NaN;
    }
    // round(base * (shape - t / L)) == 0  <=>  base * (shape - t / L) < 0.5
    return delayDays + (profile.shape - 0.5 / profile.basePopulation) * declineDays(params);
  }

  /** Radius of uncontrolled sculk around each catalyst after {@code days} without maintenance. */
  public static int spreadRadius(double days, Params params) {
    if (!(days > 0)) {
      return 0;
    }
    return (int) Math.floor(params.spreadMaxRadius * (1.0 - Math.exp(-days / params.spreadDays)));
  }

  /**
   * Lowest population ever reached up to {@code day}: everyone above it has died at least once, so
   * this is what the memory space must remember. Births afterwards do not erase the dead.
   */
  public static int lowestPopulation(Profile profile, double day, double delayDays, Params params) {
    if (profile.state != State.FLUCTUATING) {
      return population(profile, day, delayDays, params);
    }
    double period = periodDays(params);
    // Trough of sin at phase 3/4: day = (k + 0.75 - shape) * period.
    double firstTrough = (0.75 - profile.shape) * period;
    while (firstTrough < 0) {
      firstTrough += period;
    }
    double lowest =
        Math.min(
            population(profile, 0.0, delayDays, params),
            population(profile, day, delayDays, params));
    if (firstTrough <= day) {
      lowest = Math.min(lowest, population(profile, firstTrough, delayDays, params));
    }
    return (int) lowest;
  }

  /** Memorial niches the city has: a few ancient ones plus one per individual lost since. */
  public static int memorialNiches(
      long seed, long cityId, Profile profile, double day, double delayDays, Params params) {
    if (!profile.living) {
      return 0;
    }
    int ancient = 1 + (int) (GuestHash.unit(GuestHash.hash(seed, cityId, SALT_NICHES)) * 3);
    int lost =
        Math.max(0, profile.basePopulation - lowestPopulation(profile, day, delayDays, params));
    return ancient + lost;
  }

  public static ArchitectRole role(long seed, long cityId, int index) {
    if (index < CORE_ROLES.length) {
      return CORE_ROLES[index];
    }
    if (GuestHash.unit(GuestHash.hash(seed, cityId, SALT_YOUNG, index)) < YOUNG_CHANCE) {
      return ArchitectRole.YOUNG;
    }
    return EXTRA_ROLES[index % EXTRA_ROLES.length];
  }

  /**
   * Caps how fast assistance can accumulate so that effective city time never runs backwards: help
   * slows the decline down, it never brings the dead back.
   *
   * @return the credit actually granted
   */
  public static double grantableDelay(
      double requested, double nowDay, double lastGrantDay, double current, double maxTotal) {
    double byRate = Math.max(0.0, (nowDay - lastGrantDay) * 0.5);
    return Math.max(0.0, Math.min(requested, Math.min(byRate, maxTotal - current)));
  }

  public static double day(long gameTime) {
    return gameTime / (double) GuestTime.TICKS_PER_DAY;
  }

  private static double periodDays(Params params) {
    return params.fluctuationYears * GuestTime.DAYS_PER_YEAR;
  }

  private static double declineDays(Params params) {
    return params.declineYears * GuestTime.DAYS_PER_YEAR;
  }
}
