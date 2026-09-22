package com.vortexso.guest_settlements.village;

import net.minecraft.core.BlockPos;

public record VillageRecord(
        long id,
        BlockPos origin,
        long initializedDay,
        int initialPopulation,
        int initialChildren,
        int initialHousingCapacity,
        double initialFoodReserve
) {
    public VillageRecord {
        if (initialPopulation < 0) {
            throw new IllegalArgumentException("initialPopulation must be >= 0");
        }
        if (initialChildren < 0 || initialChildren > initialPopulation) {
            throw new IllegalArgumentException("initialChildren must be in [0, initialPopulation]");
        }
        if (initialHousingCapacity < 0) {
            throw new IllegalArgumentException("initialHousingCapacity must be >= 0");
        }
        if (!Double.isFinite(initialFoodReserve) || initialFoodReserve < 0.0) {
            throw new IllegalArgumentException("initialFoodReserve must be finite and >= 0");
        }
    }
}