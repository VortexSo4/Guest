package com.vortexso.guest_settlements.village;

import net.minecraft.core.BlockPos;

public record VillageRecord(
    long id,
    BlockPos origin,
    long initializedDay,
    VillagePopulation initialPopulation,
    int initialHousingCapacity,
    double initialFoodReserve) {
  public VillageRecord {
    if (initialPopulation == null) {
      throw new IllegalArgumentException("initialPopulation must not be null");
    }

    if (initialHousingCapacity < 0) {
      throw new IllegalArgumentException("initialHousingCapacity must be >= 0");
    }

    if (!Double.isFinite(initialFoodReserve) || initialFoodReserve < 0.0) {
      throw new IllegalArgumentException("initialFoodReserve must be finite and >= 0");
    }
  }
}
