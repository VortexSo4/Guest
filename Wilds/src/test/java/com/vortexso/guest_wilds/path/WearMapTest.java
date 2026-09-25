package com.vortexso.guest_wilds.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.vortexso.guest_wilds.Ecology;
import com.vortexso.guest_wilds.WildsParameters;
import org.junit.jupiter.api.Test;

class WearMapTest {
  private static final WildsParameters P = WildsParameters.DEFAULT;
  private static final long DAY = Ecology.TICKS_PER_DAY;

  @Test
  void stagesClimbWithUseAndFallBackWhenAbandoned() {
    WearMap map = new WearMap(P);
    WearMap.Column column = map.add(-5, 17, P.pathWear(), 0L);
    assertEquals(3, map.targetStage(column, 0L));
    column.stage = 3;
    // Just below the path threshold keeps the path (hysteresis): no flicker on busy routes.
    assertEquals(3, map.targetStage(column, (long) (P.wearHalfLifeDays() * 0.5 * DAY)));
    assertEquals(0, map.targetStage(column, 60 * DAY));
  }

  @Test
  void hysteresisOnlyDropsWellBelowThreshold() {
    double[] t = {0.0, 8.0, 32.0, 96.0};
    assertEquals(2, WearMap.targetStage(20.0, 2, t, 0.5));
    assertEquals(1, WearMap.targetStage(15.0, 2, t, 0.5));
    assertEquals(3, WearMap.targetStage(100.0, 1, t, 0.5));
  }

  @Test
  void lateAggregateWearArrivesDecayed() {
    WearMap map = new WearMap(P);
    WearMap.Column column = map.add(3, 3, 10.0, 10 * DAY);
    long halfLife = (long) (P.wearHalfLifeDays() * DAY);
    map.add(3, 3, 8.0, 10 * DAY - halfLife);
    assertEquals(14.0, map.wearAt(column, 10 * DAY), 1e-3);
  }

  @Test
  void removedColumnsFreeTheirChunk() {
    WearMap map = new WearMap(P);
    WearMap.Column column = map.add(100, -100, 1.0, 0L);
    map.remove(column);
    assertNull(map.get(100, -100));
    assertEquals(0, map.size());
    assertEquals(0, map.chunkKeys().length);
  }
}
