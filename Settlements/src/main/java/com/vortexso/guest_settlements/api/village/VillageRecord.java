package com.vortexso.guest_settlements.api.village;

import net.minecraft.core.BlockPos;

public record VillageRecord(
        long id,
        BlockPos origin,
        long initializedDay,
        int initialPopulation,
        int initialChildren,
        int initialHousingCapacity,
        int initialFoodReserve
) {
}