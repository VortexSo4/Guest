package com.vortexso.guest_core.api.world;

import com.vortexso.guest_core.api.GuestTime;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

public final class GuestWeather {
  private static volatile WeatherProvider provider = GuestWeather::vanillaWeather;

  private GuestWeather() {}

  public static WeatherState get(Level level, BlockPos pos, long gameTime) {
    return provider.get(level, pos, gameTime);
  }

  public static void register(WeatherProvider provider) {
    GuestWeather.provider = provider;
  }

  private static WeatherState vanillaWeather(Level level, BlockPos pos, long gameTime) {
    if (GuestTime.day(gameTime) != GuestTime.day(GuestTime.gameTime(level)) || !level.isRaining()) {
      return WeatherState.CLEAR;
    }

    Biome.Precipitation precipitation =
        level.getBiome(pos).value().getPrecipitationAt(pos, level.getSeaLevel());
    boolean thunder = level.isThundering();

    return switch (precipitation) {
      case NONE -> WeatherState.CLEAR;
      case SNOW ->
          new WeatherState(
              thunder ? WeatherType.BLIZZARD : WeatherType.SNOWFALL,
              level.getRainLevel(1.0F),
              thunder ? 0.8F : 0.2F,
              false);
      case RAIN ->
          new WeatherState(
              thunder ? WeatherType.THUNDERSTORM : WeatherType.RAIN,
              level.getRainLevel(1.0F),
              thunder ? 0.7F : 0.2F,
              false);
    };
  }
}
