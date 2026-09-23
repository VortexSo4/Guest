package com.vortexso.guest_core.api.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

public final class GuestWeather {
  private static WeatherProvider provider = GuestWeather::getVanillaWeather;

  private GuestWeather() {}

  public static WeatherState get(Level level, BlockPos pos, long day) {
    return provider.get(level, pos, day);
  }

  public static void register(WeatherProvider provider) {
    GuestWeather.provider = provider;
  }

  private static WeatherState getVanillaWeather(Level level, BlockPos pos, long day) {
    if (!level.isRainingAt(pos)) {
      return new WeatherState(WeatherCategory.CLEAR);
    }

    if (level.isThundering()) {
      return new WeatherState(WeatherCategory.THUNDER);
    }

    return new WeatherState(WeatherCategory.RAIN);
  }
}
