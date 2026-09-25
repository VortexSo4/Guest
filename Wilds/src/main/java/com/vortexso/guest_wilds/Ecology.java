package com.vortexso.guest_wilds;

/**
 * Pure aggregate formulas shared by lairs, herds and shoals. No Minecraft state, so it is unit
 * tested directly.
 *
 * <p>Nodes integrate on a fixed absolute grid of {@link #STEP_TICKS}: advancing A->C gives the same
 * result as A->B->C, whatever moment the node happened to be queried at.
 */
public final class Ecology {
  public static final long TICKS_PER_DAY = 24_000L;
  public static final long STEP_TICKS = TICKS_PER_DAY / 4;
  public static final double STEP_DAYS = STEP_TICKS / (double) TICKS_PER_DAY;

  /** 512 days: every population here reaches equilibrium far sooner, so older gaps are skipped. */
  public static final long MAX_STEPS = 2048;

  private Ecology() {}

  public static long step(long gameTime) {
    return Math.floorDiv(gameTime, STEP_TICKS);
  }

  /** Grid steps to integrate from {@code fromStep} to {@code toStep}, capped at equilibrium. */
  public static long stepsToRun(long fromStep, long toStep) {
    return Math.max(0L, Math.min(MAX_STEPS, toStep - fromStep));
  }

  public static double decay(double value, double elapsedDays, double halfLifeDays) {
    return elapsedDays <= 0.0 ? value : value * Math.pow(0.5, elapsedDays / halfLifeDays);
  }

  public static double logistic(double n0, double capacity, double rate, double days) {
    if (capacity <= 0.0) {
      return 0.0;
    }
    if (n0 <= 0.0) {
      return 0.0;
    }
    return capacity / (1.0 + (capacity - n0) / n0 * Math.exp(-rate * days));
  }

  /** Unattended vegetation recovering exponentially toward full cover. */
  public static double recover(double v0, double rate, double days) {
    return 1.0 - (1.0 - v0) * Math.exp(-rate * Math.max(0.0, days));
  }

  /**
   * One Lotka-Volterra step for species sharing a cave. Losses caused by rivals are returned in
   * {@code emigrants} instead of dying: losers relocate.
   */
  public static void lairStep(
      double[] n,
      double[] capacity,
      double[] growth,
      double mortality,
      double alpha,
      double dt,
      double[] emigrants) {
    double total = 0.0;
    for (double value : n) {
      total += value;
    }
    double[] next = new double[n.length];
    for (int i = 0; i < n.length; i++) {
      double k = Math.max(capacity[i], 0.1);
      double rivals = total - n[i];
      double own = growth[i] * n[i] * (1.0 - n[i] / k) - mortality * n[i];
      double displaced = growth[i] * n[i] * alpha * rivals / k;
      double left = Math.min(n[i], displaced * dt);
      next[i] = Math.max(0.0, n[i] + own * dt - left);
      emigrants[i] += left;
    }
    for (int i = 0; i < n.length; i++) {
      n[i] = next[i] < 0.05 ? 0.0 : next[i];
    }
  }

  public record Herd(double animals, double vegetation) {}

  /** One step of a grazing herd on its current pasture. */
  public static Herd herdStep(
      Herd herd,
      double birth,
      double deathAndPredation,
      double graze,
      double regrowth,
      double capacity,
      double dt) {
    double n = herd.animals();
    double v = herd.vegetation();
    double nextV = v + (regrowth * (1.0 - v) - graze * n * v) * dt;
    double nextN = n + (birth * n * v * (1.0 - n / capacity) - deathAndPredation * n) * dt;
    return new Herd(Math.max(0.0, nextN), Math.clamp(nextV, 0.0, 1.0));
  }

  /**
   * Salmon spawning run intensity 0..1 over the year: rising through summer, peaking in autumn when
   * salmon crowd the rivers, gone by winter.
   */
  public static double salmonRun(int dayOfYear, int daysPerYear) {
    double phase = (dayOfYear + 0.5) / daysPerYear; // spring starts at 0
    // Peak at 0.625 (mid-autumn), zero outside summer..autumn.
    double distance = Math.abs(phase - 0.625);
    return Math.max(0.0, 1.0 - distance / 0.2);
  }

  /** Extra fish on one cast: abundance beyond the multi-catch ratio plus rewarded patience. */
  public static int extraFish(
      double ratio, int waitedTicks, double multiCatchRatio, int ticksPerExtra) {
    if (ratio < multiCatchRatio) {
      return 0;
    }
    int abundance = Math.min(2, 1 + (int) ((ratio - multiCatchRatio) / 0.3));
    int patience = Math.min(2, waitedTicks / Math.max(1, ticksPerExtra));
    return abundance + patience;
  }
}
