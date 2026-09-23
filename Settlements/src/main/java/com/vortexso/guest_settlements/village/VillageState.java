package com.vortexso.guest_settlements.village;

import net.minecraft.core.BlockPos;

public record VillageState(
        long id,
        BlockPos center,
        long day,
        VillagePopulation villagePopulation,
        int housingCapacity,
        double foodReserve
) {
    public VillageState {
        if (housingCapacity < 0) {
            throw new IllegalArgumentException(
                    "housingCapacity must be >= 0"
            );
        }

        if (!Double.isFinite(foodReserve) || foodReserve < 0.0) {
            throw new IllegalArgumentException(
                    "foodReserve must be finite and >= 0"
            );
        }

        if (villagePopulation == null) {
            throw new IllegalArgumentException(
                    "population must not be null"
            );
        }
    }

    public int adults() {
        return villagePopulation.adults();
    }

    public int children() {
        return villagePopulation.children();
    }

    public int population() {
        return villagePopulation.population();
    }

    public int freeHousing() {
        return Math.max(
                0,
                housingCapacity - population()
        );
    }

    public boolean isAbandoned() {
        return population() == 0;
    }
}