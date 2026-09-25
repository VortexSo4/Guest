package com.vortexso.guest_core.api.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

@FunctionalInterface
public interface WeatherProvider {
  /**
   * Must be deterministic for (world, region of pos, gameTime) and cheap for any past/future time.
   */
  WeatherState get(Level level, BlockPos pos, long gameTime);
}
