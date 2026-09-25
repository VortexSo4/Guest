package com.vortexso.guest_wilds;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vortexso.guest_wilds.lair.LairSpecies;
import org.junit.jupiter.api.Test;

class EcologyTest {
  private static final WildsParameters P = WildsParameters.DEFAULT;

  @Test
  void decayHalvesPerHalfLife() {
    assertEquals(4.0, Ecology.decay(8.0, 4.0, 4.0), 1e-9);
    assertEquals(8.0, Ecology.decay(8.0, 0.0, 4.0), 1e-9);
  }

  @Test
  void logisticComposesAcrossIntervals() {
    double direct = Ecology.logistic(2.0, 40.0, 0.3, 10.0);
    double split = Ecology.logistic(Ecology.logistic(2.0, 40.0, 0.3, 4.0), 40.0, 0.3, 6.0);
    assertEquals(direct, split, 1e-9);
    assertEquals(40.0, Ecology.logistic(2.0, 40.0, 0.3, 1000.0), 1e-6);
  }

  @Test
  void gridStepsDoNotDependOnQueryMoments() {
    long a = Ecology.step(1_000L);
    long b = Ecology.step(37_123L);
    long c = Ecology.step(250_000L);
    assertEquals(Ecology.stepsToRun(a, c), Ecology.stepsToRun(a, b) + Ecology.stepsToRun(b, c));
    assertEquals(Ecology.MAX_STEPS, Ecology.stepsToRun(0, 1_000_000));
  }

  @Test
  void singleSpeciesSettlesBelowCapacity() {
    double[] n = run(0.5, new double[] {1.0, 0.0, 0.0, 0.0}, 400);
    double k = P.lairCapacity() * LairSpecies.ZOMBIE.fit(0.5);
    double expected = k * (1.0 - P.lairMortalityPerDay() / P.zombieGrowthPerDay());
    assertEquals(expected, n[0], 0.05);
  }

  @Test
  void wideChamberDrivesSpidersOutAsEmigrants() {
    double[] emigrants = new double[4];
    double[] n = run(1.0, new double[] {0.0, 6.0, 6.0, 0.0}, 800, emigrants);
    int skeleton = LairSpecies.SKELETON.ordinal();
    int spider = LairSpecies.SPIDER.ordinal();
    assertTrue(n[skeleton] > 5.0, "skeletons hold the chamber");
    assertTrue(n[spider] < 0.5, "spiders lose it");
    assertTrue(emigrants[spider] > 1.0, "losers relocate instead of vanishing");
  }

  @Test
  void moderatelyOpenCaveLetsSpidersAndSkeletonsCoexist() {
    double[] n = run(0.6, new double[] {0.0, 3.0, 3.0, 0.0}, 800);
    assertTrue(n[LairSpecies.SKELETON.ordinal()] >= 2.0);
    assertTrue(n[LairSpecies.SPIDER.ordinal()] >= 2.0);
  }

  @Test
  void grazingDepletesPastureAndRestRestoresIt() {
    Ecology.Herd herd = new Ecology.Herd(12.0, 1.0);
    for (int i = 0; i < 200; i++) {
      herd =
          Ecology.herdStep(
              herd,
              P.herdBirthPerDay(),
              P.herdMortalityPerDay(),
              P.grazePerAnimalDay(),
              P.vegetationRegrowthPerDay(),
              P.herdCapacity(),
              Ecology.STEP_DAYS);
    }
    assertTrue(herd.vegetation() < P.migrateBelowVegetation(), "a full herd exhausts a pasture");
    assertTrue(Ecology.recover(herd.vegetation(), P.vegetationRegrowthPerDay(), 120.0) > 0.99);
  }

  @Test
  void salmonRunPeaksInAutumnAndIsAbsentInWinterAndSpring() {
    assertEquals(1.0, Ecology.salmonRun(79, 128), 0.05); // mid-autumn
    assertEquals(0.0, Ecology.salmonRun(10, 128), 1e-9); // spring
    assertEquals(0.0, Ecology.salmonRun(110, 128), 1e-9); // winter
  }

  @Test
  void bigShoalsAndPatienceGiveMoreFish() {
    assertEquals(0, Ecology.extraFish(0.5, 5_000, 0.6, 1200));
    assertEquals(1, Ecology.extraFish(0.65, 0, 0.6, 1200));
    assertEquals(4, Ecology.extraFish(1.0, 5_000, 0.6, 1200));
  }

  private static double[] run(double openness, double[] start, int steps) {
    return run(openness, start, steps, new double[4]);
  }

  private static double[] run(double openness, double[] start, int steps, double[] emigrants) {
    double[] n = start.clone();
    double[] capacity = new double[4];
    double[] growth = new double[4];
    for (LairSpecies species : LairSpecies.VALUES) {
      capacity[species.ordinal()] = P.lairCapacity() * species.fit(openness);
      growth[species.ordinal()] = species.growth(P);
    }
    for (int i = 0; i < steps; i++) {
      Ecology.lairStep(
          n,
          capacity,
          growth,
          P.lairMortalityPerDay(),
          P.competition(),
          Ecology.STEP_DAYS,
          emigrants);
    }
    return n;
  }
}
