package com.vortexso.guest_hands.station;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CraftingGridTest {
  private static final int SOUTH = 0;
  private static final int WEST = 1;
  private static final int NORTH = 2;
  private static final int EAST = 3;

  @Test
  void hitPositionMapsToThirds() {
    assertEquals(0, CraftingGrid.cellAt(0.1, 0.1));
    assertEquals(4, CraftingGrid.cellAt(0.5, 0.5));
    assertEquals(2, CraftingGrid.cellAt(0.9, 0.1));
    assertEquals(6, CraftingGrid.cellAt(0.1, 0.9));
    assertEquals(8, CraftingGrid.cellAt(1.0, 1.0));
    assertEquals(0, CraftingGrid.cellAt(-0.01, 0.0));
  }

  @Test
  void farRowIsTheScreenTopRow() {
    // Facing north the far side is -z (cz = 0); facing east it is +x (cx = 2).
    assertEquals(0, CraftingGrid.worldCell(NORTH, 0));
    assertEquals(8, CraftingGrid.worldCell(SOUTH, 0));
    assertEquals(2, CraftingGrid.worldCell(EAST, 0));
    assertEquals(6, CraftingGrid.worldCell(WEST, 0));
  }

  @Test
  void everyFacingIsAPermutation() {
    for (int facing = 0; facing < 4; facing++) {
      Set<Integer> cells = new HashSet<>();
      for (int index = 0; index < 9; index++) {
        cells.add(CraftingGrid.worldCell(facing, index));
      }
      assertEquals(9, cells.size());
      assertEquals(4, CraftingGrid.worldCell(facing, 4));
    }
  }
}
