package com.vortexso.guest_core.api.world;

import java.util.Locale;
import net.minecraft.network.chat.Component;

public enum WeatherType {
  CLEAR,
  FOG,
  WIND,
  LEAF_FALL,
  HEAT,
  DRIZZLE,
  RAIN,
  DOWNPOUR,
  THUNDERSTORM,
  SNOWFALL,
  BLIZZARD,
  /** Mountain/open-country variant of the blizzard: stronger wind, lower visibility. */
  SNOWSTORM,
  SANDSTORM;

  public boolean isRain() {
    return this == DRIZZLE || this == RAIN || this == DOWNPOUR || this == THUNDERSTORM;
  }

  public boolean isSnow() {
    return this == SNOWFALL || this == BLIZZARD || this == SNOWSTORM;
  }

  public boolean isPrecipitation() {
    return isRain() || isSnow();
  }

  /** Weather that stops ordinary outdoor activity (travel, raids, herding). */
  public boolean isSevere() {
    return this == BLIZZARD
        || this == SNOWSTORM
        || this == SANDSTORM
        || this == THUNDERSTORM
        || this == DOWNPOUR;
  }

  public Component displayName() {
    return Component.translatable("guest_core.weather." + name().toLowerCase(Locale.ROOT));
  }
}
