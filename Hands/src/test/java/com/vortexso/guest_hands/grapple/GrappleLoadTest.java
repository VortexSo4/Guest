package com.vortexso.guest_hands.grapple;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GrappleLoadTest {

  @Test
  void oneBlockClimbedCostsOneDurability() {
    assertEquals(1, new GrappleLoad().carry(1.0, 1.0));
  }

  @Test
  void fiveBlockFallCostsFiveLikeFiveClimbs() {
    GrappleLoad fall = new GrappleLoad();
    GrappleLoad climbs = new GrappleLoad();
    int climbed = 0;
    for (int i = 0; i < 5; i++) {
      climbed += climbs.carry(1.0, 1.0);
    }
    assertEquals(5, fall.carry(5.0, 1.0));
    assertEquals(5, climbed);
  }

  @Test
  void fractionsCarryOverInsteadOfRounding() {
    GrappleLoad load = new GrappleLoad();
    int total = 0;
    for (int tick = 0; tick < 100; tick++) {
      total += load.carry(0.07, 1.0);
    }
    assertEquals(7, total);
  }

  @Test
  void rateScalesCost() {
    assertEquals(2, new GrappleLoad().carry(1.0, 2.0));
    assertEquals(0, new GrappleLoad().carry(3.0, 0.0));
  }
}
