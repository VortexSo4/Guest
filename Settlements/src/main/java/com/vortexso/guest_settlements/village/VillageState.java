package com.vortexso.guest_settlements.village;

import net.minecraft.core.BlockPos;

public record VillageState(
        long id,
        BlockPos center,
        long day,
        int population,
        int children,
        int housingCapacity,
        double foodReserve
) {
    public VillageState {
        if (population < 0) {
            throw new IllegalArgumentException("population must be >= 0");
        }
        if (children < 0 || children > population) {
            throw new IllegalArgumentException("children must be in [0, population]");
        }
        if (housingCapacity < 0) {
            throw new IllegalArgumentException("housingCapacity must be >= 0");
        }
        if (!Double.isFinite(foodReserve) || foodReserve < 0.0) {
            throw new IllegalArgumentException("foodReserve must be finite and >= 0");
        }
    }

    public int adults() {
        return population - children;
    }

    public int freeHousing() {
        return Math.max(0, housingCapacity - population);
    }

    public boolean isAbandoned() {
        return population == 0;
    }
}