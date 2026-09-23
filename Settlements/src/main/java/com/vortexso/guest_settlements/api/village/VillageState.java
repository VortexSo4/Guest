package com.vortexso.guest_settlements.api.village;

import net.minecraft.core.BlockPos;

public record VillageState(
    long id,
    BlockPos center,
    long day,
    int population,
    int children,
    int unemployed,
    int idle,
    int housingCapacity,
    int foodReserve) {}
