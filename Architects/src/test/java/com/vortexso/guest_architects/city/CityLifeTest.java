package com.vortexso.guest_architects.city;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CityLifeTest {
  private static final CityLife.Params PARAMS =
      new CityLife.Params(0.35, 20, 30, 6.0, 0.2, 20.0, 10, 128.0);

  private static CityLife.Profile declining(double shape) {
    return new CityLife.Profile(true, CityLife.State.DECLINING, 25, shape);
  }

  @Test
  void profileIsDeterministicAndInRange() {
    int living = 0;
    for (long id = 0; id < 2000; id++) {
      CityLife.Profile a = CityLife.profile(42L, id, PARAMS);
      assertEquals(a, CityLife.profile(42L, id, PARAMS));
      assertTrue(a.basePopulation() >= 20 && a.basePopulation() <= 30);
      living += a.living() ? 1 : 0;
    }
    assertTrue(Math.abs(living / 2000.0 - 0.35) < 0.05, "living fraction " + living);
  }

  @Test
  void declineIsMonotonicAndReachesZeroWhenPredicted() {
    CityLife.Profile profile = declining(0.5);
    int previous = Integer.MAX_VALUE;
    for (double day = 0; day < 2000; day += 3.7) {
      int population = CityLife.population(profile, day, 0, PARAMS);
      assertTrue(population <= previous);
      previous = population;
    }
    double zero = CityLife.abandonedSince(profile, 0, PARAMS);
    assertEquals(0, CityLife.population(profile, zero + 0.01, 0, PARAMS));
    assertTrue(CityLife.population(profile, zero - 1, 0, PARAMS) > 0);
  }

  @Test
  void assistanceDelaysButNeverReverses() {
    CityLife.Profile profile = declining(0.8);
    double delay = 0;
    double lastGrant = 0;
    int previous = Integer.MAX_VALUE;
    // A tireless helper asking for a full day of delay every hour of game time.
    for (double day = 0; day < 3000; day += 1.0 / 24) {
      double granted = CityLife.grantableDelay(1.0, day, lastGrant, delay, 256);
      if (granted > 0) {
        delay += granted;
        lastGrant = day;
      }
      int population = CityLife.population(profile, day, delay, PARAMS);
      assertTrue(population <= previous, "population rose at day " + day);
      previous = population;
    }
    assertTrue(delay <= 256.0 + 1e-9);
    assertTrue(
        CityLife.abandonedSince(profile, delay, PARAMS)
            > CityLife.abandonedSince(profile, 0, PARAMS));
  }

  @Test
  void stableCityNeverEmptiesAndFluctuationStaysBounded() {
    CityLife.Profile stable = new CityLife.Profile(true, CityLife.State.STABLE, 24, 0.3);
    CityLife.Profile fluctuating = new CityLife.Profile(true, CityLife.State.FLUCTUATING, 24, 0.3);
    for (double day = 0; day < 5000; day += 11) {
      assertEquals(24, CityLife.population(stable, day, 0, PARAMS));
      int f = CityLife.population(fluctuating, day, 0, PARAMS);
      assertTrue(f >= 19 && f <= 29, "fluctuating " + f);
    }
    assertTrue(Double.isNaN(CityLife.abandonedSince(stable, 0, PARAMS)));
  }

  @Test
  void memoryOnlyGrows() {
    CityLife.Profile fluctuating = new CityLife.Profile(true, CityLife.State.FLUCTUATING, 24, 0.1);
    int previous = 0;
    for (double day = 0; day < 3000; day += 5) {
      int niches = CityLife.memorialNiches(1L, 2L, fluctuating, day, 0, PARAMS);
      assertTrue(niches >= previous);
      previous = niches;
    }
  }

  @Test
  void spreadGrowsTowardMaximum() {
    assertEquals(0, CityLife.spreadRadius(0, PARAMS));
    assertTrue(CityLife.spreadRadius(64, PARAMS) < CityLife.spreadRadius(512, PARAMS));
    assertTrue(CityLife.spreadRadius(1e6, PARAMS) <= 10);
  }

  @Test
  void coreRolesAlwaysPresent() {
    assertEquals(ArchitectRole.RITUAL, CityLife.role(1L, 2L, 0));
    assertEquals(ArchitectRole.MELODY, CityLife.role(1L, 2L, 1));
    assertEquals(ArchitectRole.TEACHER, CityLife.role(1L, 2L, 5));
  }
}
