package com.vortexso.guest_settlements.village;

/** Tunable demographic/economic parameters. */
public record VillageSimulationParameters(
    double foodPerVillagerPerDay,
    double foodForBreedingVillager,
    double foodYieldPerFarmer,
    double childMaturationDays,
    double birthRatePerEligiblePairPerDay,
    double baseZombieDeathRatePerDay,
    double activeVariation) {
  public VillageSimulationParameters {
    if (!(foodPerVillagerPerDay > 0.0)) {
      throw new IllegalArgumentException("foodPerVillagerPerDay must be > 0");
    }
    if (!(foodForBreedingVillager >= 0.0)) {
      throw new IllegalArgumentException("foodForBreedingVillager must be >= 0");
    }
    if (!(foodYieldPerFarmer >= 0.0)) {
      throw new IllegalArgumentException("foodYieldPerFarmer must be >= 0");
    }
    if (!(childMaturationDays > 0.0)) {
      throw new IllegalArgumentException("childMaturationDays must be > 0");
    }
    if (!(birthRatePerEligiblePairPerDay >= 0.0 && birthRatePerEligiblePairPerDay <= 1.0)) {
      throw new IllegalArgumentException("birthRatePerEligiblePairPerDay must be in [0, 1]");
    }
    if (!(baseZombieDeathRatePerDay >= 0.0 && baseZombieDeathRatePerDay <= 1.0)) {
      throw new IllegalArgumentException("baseZombieDeathRatePerDay must be in [0, 1]");
    }
    if (!(activeVariation >= 0.0 && activeVariation <= 1.0)) {
      throw new IllegalArgumentException("activeVariation must be in [0, 1]");
    }
  }

  public static VillageSimulationParameters defaults() {
    return new VillageSimulationParameters(2.0, 2.0, 12.0, 20.0, 0.02, 0.001, 0.0);
  }
}
