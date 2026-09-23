package com.vortexso.guest_core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.vortexso.guest_core.api.GuestTime;
import org.junit.jupiter.api.Test;

class GuestTimeTest {

  @Test
  void calendarRepeatsEvery128Days() {
    long day = GuestTime.TICKS_PER_DAY;
    assertEquals(0, GuestTime.season(0));
    assertEquals(1, GuestTime.season(32 * day));
    assertEquals(2, GuestTime.season(64 * day));
    assertEquals(3, GuestTime.season(96 * day));
    assertEquals(0, GuestTime.season(128 * day));
  }

  @Test
  void weekRepeatsEvery8Days() {
    assertEquals(0, GuestTime.weekday(0));
    assertEquals(7, GuestTime.weekday(7 * GuestTime.TICKS_PER_DAY));
    assertEquals(0, GuestTime.weekday(8 * GuestTime.TICKS_PER_DAY));
  }

  @Test
  void dayAndTickOfDayAreConsistent() {
    long time = 12345;

    assertEquals(0, GuestTime.day(time));
    assertEquals(12345, GuestTime.tickOfDay(time));

    long later = 3 * GuestTime.TICKS_PER_DAY + 42;

    assertEquals(3, GuestTime.day(later));
    assertEquals(42, GuestTime.tickOfDay(later));
  }

  @Test
  void lunarPhaseRepeatsEveryEightDays() {
    assertEquals(0, GuestTime.lunarPhase(0));
    assertEquals(7, GuestTime.lunarPhase(7 * GuestTime.TICKS_PER_DAY));
    assertEquals(0, GuestTime.lunarPhase(8 * GuestTime.TICKS_PER_DAY));
  }
}
