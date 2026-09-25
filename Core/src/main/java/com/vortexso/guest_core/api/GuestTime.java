package com.vortexso.guest_core.api;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;

/**
 * Shared calendar. The 8-day week is deliberately the same cycle as the moon, so weekday and moon
 * phase are one value: weekday 0 is the full moon ("Sunday"), weekday 4 is the new moon
 * ("Wednesday").
 */
public final class GuestTime {
  public static final long TICKS_PER_DAY = 24_000L;

  public static final int DAYS_PER_WEEK = 8;
  public static final int DAYS_PER_SEASON = 32;
  public static final int SEASONS_PER_YEAR = 4;
  public static final int DAYS_PER_YEAR = 128;

  private GuestTime() {}

  public static long gameTime(Level level) {
    return level.getOverworldClockTime();
  }

  public static long day(long gameTime) {
    return Math.floorDiv(gameTime, TICKS_PER_DAY);
  }

  public static long tickOfDay(long gameTime) {
    return Math.floorMod(gameTime, TICKS_PER_DAY);
  }

  public static long year(long gameTime) {
    return Math.floorDiv(day(gameTime), DAYS_PER_YEAR);
  }

  public static int dayOfYear(long gameTime) {
    return (int) Math.floorMod(day(gameTime), DAYS_PER_YEAR);
  }

  public static Season season(long gameTime) {
    return Season.values()[dayOfYear(gameTime) / DAYS_PER_SEASON];
  }

  /** 0..1 position inside the current season; lets consumers blend across season edges. */
  public static double seasonProgress(long gameTime) {
    return (dayOfYear(gameTime) % DAYS_PER_SEASON + tickOfDay(gameTime) / (double) TICKS_PER_DAY)
        / DAYS_PER_SEASON;
  }

  public static int weekOfSeason(long gameTime) {
    return (dayOfYear(gameTime) % DAYS_PER_SEASON) / DAYS_PER_WEEK;
  }

  public static int weekday(long gameTime) {
    return (int) Math.floorMod(day(gameTime), DAYS_PER_WEEK);
  }

  /** Matches the vanilla overworld moon timeline (full moon on day 0, one phase per day). */
  public static MoonPhase moonPhase(long gameTime) {
    return MoonPhase.values()[weekday(gameTime)];
  }

  /** Vanilla night: roughly from sunset to sunrise. */
  public static boolean isNight(long gameTime) {
    long tick = tickOfDay(gameTime);
    return tick >= 12_500L && tick < 23_500L;
  }
}
