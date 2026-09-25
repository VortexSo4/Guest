package com.vortexso.guest_hands.station;

/**
 * Pure geometry of the physical 3x3 grid. World cells are indexed {@code cz * 3 + cx} on the
 * table's top face; recipe (GUI) slots are {@code row * 3 + col} as seen by the crafting player, so
 * the far row is the GUI's top row and the arrangement reads the same as in the vanilla screen from
 * wherever the player stands.
 */
public final class CraftingGrid {
  private CraftingGrid() {}

  /** World cell under a hit on the top face; local coordinates are 0..1 within the block. */
  public static int cellAt(double localX, double localZ) {
    return third(localZ) * 3 + third(localX);
  }

  private static int third(double local) {
    return Math.clamp((int) Math.floor(local * 3.0), 0, 2);
  }

  /**
   * World cell holding GUI slot {@code gridIndex} for a player facing a horizontal direction given
   * as vanilla 2D data value (0 south, 1 west, 2 north, 3 east).
   */
  public static int worldCell(int facing2d, int gridIndex) {
    int col = gridIndex % 3;
    int row = gridIndex / 3;
    int cx;
    int cz;
    switch (facing2d) {
      case 0 -> {
        cx = 2 - col;
        cz = 2 - row;
      }
      case 1 -> {
        cx = row;
        cz = 2 - col;
      }
      case 3 -> {
        cx = 2 - row;
        cz = col;
      }
      default -> {
        cx = col;
        cz = row;
      }
    }
    return cz * 3 + cx;
  }

  /** Centre of a world cell in block-local coordinates (x, z). */
  public static double cellCenter(int cellCoordinate) {
    return (cellCoordinate + 0.5) / 3.0;
  }
}
