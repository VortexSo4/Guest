package com.vortexso.guest_architects.city;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;

public final class CityLife {
  private static final long SALT_STATE = 0x53544154L;
  private static final long SALT_BASE = 0x42415345L;
  private static final long SALT_SHAPE = 0x53484150L;
  private static final long SALT_YOUNG = 0x594F554EL;
  private static final long SALT_NICHES = 0x4E494348L;

  private static final ArchitectRole[] CORE_ROLES = {
    ArchitectRole.RITUAL,
    ArchitectRole.MELODY,
    ArchitectRole.DEEPSLATE,
    ArchitectRole.WOOL,
    ArchitectRole.WATCHER,
    ArchitectRole.TEACHER,
    ArchitectRole.KEEPER
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
      int minPopulation,
      int maxPopulation,
      double fluctuationYears,
      double fluctuationAmplitude,
      double declineYears,
      int spreadMaxRadius,
      double spreadDays) {}

  public record Profile(boolean living, State state, int basePopulation, double shape) {}

  public static Profile profile(long seed, long cityId, boolean living, Params params) {
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

  public static Profile withState(Profile profile, State state) {
    if (state == null || state == profile.state) {
      return profile;
    }
    double shape = state == State.DECLINING ? 0.2 + 0.8 * profile.shape : profile.shape;
    return new Profile(profile.living, state, profile.basePopulation, shape);
  }

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

  public static double abandonedSince(Profile profile, double delayDays, Params params) {
    if (!profile.living) {
      return 0.0;
    }
    if (profile.state != State.DECLINING) {
      return Double.NaN;
    }

    return delayDays + (profile.shape - 0.5 / profile.basePopulation) * declineDays(params);
  }

  public static int spreadRadius(double days, Params params) {
    if (!(days > 0)) {
      return 0;
    }
    return (int) Math.floor(params.spreadMaxRadius * (1.0 - Math.exp(-days / params.spreadDays)));
  }

  public static int lowestPopulation(Profile profile, double day, double delayDays, Params params) {
    if (profile.state != State.FLUCTUATING) {
      return population(profile, day, delayDays, params);
    }
    double period = periodDays(params);

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

  public static ArchitectRole role(long seed, long cityId, int index, State state) {
    if (index < CORE_ROLES.length) {
      return CORE_ROLES[index];
    }
    if (state != State.DECLINING
        && GuestHash.unit(GuestHash.hash(seed, cityId, SALT_YOUNG, index)) < YOUNG_CHANCE) {
      return ArchitectRole.YOUNG;
    }
    return EXTRA_ROLES[index % EXTRA_ROLES.length];
  }

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
