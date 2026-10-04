package com.vortexso.guest_core.api;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.MoonPhase;

public final class GuestTime {
  public static final long TICKS_PER_DAY = 24_000L;

  public static final int DAYS_PER_WEEK = 8;
  public static final int DAYS_PER_SEASON = 32;
  public static final int SEASONS_PER_YEAR = 4;
  public static final int DAYS_PER_YEAR = 128;

  public static final int START_DAY_OFFSET = 40;

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
    return Math.floorDiv(day(gameTime) + START_DAY_OFFSET, DAYS_PER_YEAR);
  }

  public static int dayOfYear(long gameTime) {
    return (int) Math.floorMod(day(gameTime) + START_DAY_OFFSET, DAYS_PER_YEAR);
  }

  public static Season season(long gameTime) {
    return Season.values()[dayOfYear(gameTime) / DAYS_PER_SEASON];
  }

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

  public static MoonPhase moonPhase(long gameTime) {
    return MoonPhase.values()[weekday(gameTime)];
  }

  public static boolean isNight(long gameTime) {
    long tick = tickOfDay(gameTime);
    return tick >= 12_500L && tick < 23_500L;
  }
}
