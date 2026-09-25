package com.vortexso.guest_settlements.village;

/**
 * Everything outside the village that the aggregate model reads. Keeps the simulator free of world
 * access; the world manager backs {@link Timeline} with weather and caravan queries.
 *
 * @param hostilePressure relative night pressure, 1.0 = ordinary vanilla night
 */
public record VillageEnvironment(double hostilePressure, Timeline timeline) {
  public static final Timeline CALM =
      new Timeline() {
        @Override
        public double severeFraction(long fromDay, long toDay) {
          return 0.0;
        }

        @Override
        public double importedFood(long fromDay, long toDay) {
          return 0.0;
        }
      };

  public VillageEnvironment {
    if (!(hostilePressure >= 0.0) || !Double.isFinite(hostilePressure)) {
      throw new IllegalArgumentException("hostilePressure must be finite and >= 0");
    }
  }

  public static VillageEnvironment calm(double hostilePressure) {
    return new VillageEnvironment(hostilePressure, CALM);
  }

  public interface Timeline {
    /** Share of days in {@code [fromDay, toDay)} with severe weather over the village, 0..1. */
    double severeFraction(long fromDay, long toDay);

    /** Food delivered by caravans arriving in {@code [fromDay, toDay)}. */
    double importedFood(long fromDay, long toDay);
  }
}
