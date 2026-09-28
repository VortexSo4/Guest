package com.vortexso.guest_settlements.village;

public record VillageSimulationParameters(
    double harvestsPerFarmlandPerDay,
    double foodPerHarvest,
    int farmlandPerFarmer,
    double foodPerVillagerPerDay,
    double foodPerBirth,
    double breedChancePerPairPerDay,
    double childMaturationDays,
    double nightLossPerVillager,
    double severeNightLossMultiplier,
    double severeWorkFactor,
    double springGrowth,
    double summerGrowth,
    double autumnGrowth,
    double winterGrowth,
    double storagePerVillager,
    double zombieConversionChance) {

  public VillageSimulationParameters {
    requireNonNegative(harvestsPerFarmlandPerDay, "harvestsPerFarmlandPerDay");
    requireNonNegative(foodPerHarvest, "foodPerHarvest");
    if (farmlandPerFarmer <= 0) {
      throw new IllegalArgumentException("farmlandPerFarmer must be > 0");
    }
    requireNonNegative(foodPerVillagerPerDay, "foodPerVillagerPerDay");
    requireNonNegative(foodPerBirth, "foodPerBirth");
    requireUnit(breedChancePerPairPerDay, "breedChancePerPairPerDay");
    if (!(childMaturationDays > 0.0)) {
      throw new IllegalArgumentException("childMaturationDays must be > 0");
    }
    requireUnit(nightLossPerVillager, "nightLossPerVillager");
    requireNonNegative(severeNightLossMultiplier, "severeNightLossMultiplier");
    requireUnit(severeWorkFactor, "severeWorkFactor");
    requireNonNegative(springGrowth, "springGrowth");
    requireNonNegative(summerGrowth, "summerGrowth");
    requireNonNegative(autumnGrowth, "autumnGrowth");
    requireNonNegative(winterGrowth, "winterGrowth");
    requireNonNegative(storagePerVillager, "storagePerVillager");
    requireUnit(zombieConversionChance, "zombieConversionChance");
  }

  public static VillageSimulationParameters defaults() {
    return new VillageSimulationParameters(
        0.645, 1.9, 40, 0.5, 24.0, 0.5, 1.0, 0.01, 1.5, 0.3, 1.0, 1.0, 0.75, 0.05, 64.0, 0.5);
  }

  private static void requireNonNegative(double value, String name) {
    if (!(value >= 0.0) || !Double.isFinite(value)) {
      throw new IllegalArgumentException(name + " must be finite and >= 0");
    }
  }

  private static void requireUnit(double value, String name) {
    if (!(value >= 0.0 && value <= 1.0)) {
      throw new IllegalArgumentException(name + " must be in [0, 1]");
    }
  }
}
