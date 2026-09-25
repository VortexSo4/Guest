package com.vortexso.guest_settlements.village;

/**
 * Every tunable of the aggregate village model in one place. Defaults are derived from vanilla
 * numbers where one exists; the basis is noted next to each value in {@link #defaults()}. The
 * server config overrides them (see {@code SettlementsConfig.parameters()}).
 */
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
        // Wiki: crops need ~31 min on average to fully grow; a day is 20 min -> 20/31.
        0.645,
        // Villager food points (Villager.FOOD_POINTS): bread 4 per 3 wheat, carrot/potato ~2.7
        // per harvest, beetroot 1 -> ~1.9 food points per harvested block on a mixed farm.
        1.9,
        // One farmer keeps roughly a large vanilla village farm (2 x 13x9 minus water) tended.
        40,
        // Vanilla villagers never starve; this is the bread they eat on top of breeding.
        0.5,
        // Villager.BREEDING_FOOD_THRESHOLD: both parents need 12 food points.
        24.0,
        // Breeding cooldown is 6000 ticks, but pairs rarely meet with food and a free bed.
        0.5,
        // Baby villagers grow up after 24000 ticks.
        1.0,
        // Unprotected villages lose roughly one villager per hundred per night to zombies.
        0.01,
        // Thunderstorms let monsters spawn and hide them in the dark.
        1.5,
        // Outdoor work on a severe-weather day.
        0.3,
        1.0,
        1.0,
        0.75,
        // Winter: crops practically stop growing.
        0.05,
        // Roughly a chest of bread per villager before surplus spoils or is traded away.
        64.0,
        // Normal difficulty: a villager killed by a zombie converts with 50% chance.
        0.5);
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
