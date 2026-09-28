package com.vortexso.guest_hands.station;

public final class CraftingGrid {

  public static final double PITCH = 3.0 / 16.0;

  public static final double CELL_SIZE = 2.0 / 16.0;

  private CraftingGrid() {}

  public static int cellAt(double localX, double localZ) {
    return nearest(localZ) * 3 + nearest(localX);
  }

  private static int nearest(double local) {
    return (int) Math.clamp(Math.round((local - 0.5) / PITCH) + 1, 0, 2);
  }

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

  public static double cellCenter(int cellCoordinate) {
    return 0.5 + (cellCoordinate - 1) * PITCH;
  }
}
