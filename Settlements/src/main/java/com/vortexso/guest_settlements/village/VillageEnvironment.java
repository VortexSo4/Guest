package com.vortexso.guest_settlements.village;

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

    double severeFraction(long fromDay, long toDay);

    double importedFood(long fromDay, long toDay);
  }
}
