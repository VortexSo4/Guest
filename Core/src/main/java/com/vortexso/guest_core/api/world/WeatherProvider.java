package com.vortexso.guest_core.api.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

@FunctionalInterface
public interface WeatherProvider {
    WeatherState get(Level level, BlockPos pos, long day);
}