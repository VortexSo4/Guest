package com.vortexso.guest_wilds;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Player-facing switches. Aggregate rates live in {@link WildsParameters}. */
public final class WildsConfig {
  private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

  public static final ModConfigSpec.BooleanValue PATHS = bool("paths", true);
  public static final ModConfigSpec.DoubleValue WEAR_MULTIPLIER =
      BUILDER.translation(key("wearMultiplier")).defineInRange("wearMultiplier", 1.0, 0.0, 10.0);
  public static final ModConfigSpec.BooleanValue LAIRS = bool("lairs", true);
  public static final ModConfigSpec.BooleanValue SUPPRESS_SURFACE_SPAWNS =
      bool("suppressSurfaceNightSpawns", true);
  public static final ModConfigSpec.BooleanValue SUPPRESS_RANDOM_JOCKEYS =
      bool("suppressRandomJockeys", true);
  public static final ModConfigSpec.BooleanValue UNDEAD_DAILY_CYCLE =
      bool("undeadDailyCycle", true);
  public static final ModConfigSpec.BooleanValue FOG_SHIELDS_UNDEAD =
      bool("fogShieldsUndead", true);
  public static final ModConfigSpec.BooleanValue HERDS = bool("herds", true);
  public static final ModConfigSpec.BooleanValue HERD_RELOCATION = bool("herdRelocation", true);
  public static final ModConfigSpec.BooleanValue GRAZERS_EAT_GRASS = bool("grazersEatGrass", true);
  public static final ModConfigSpec.BooleanValue WEATHER_SHELTER = bool("weatherShelter", true);
  public static final ModConfigSpec.BooleanValue SEASONAL_VARIANTS = bool("seasonalVariants", true);
  public static final ModConfigSpec.BooleanValue FISH_SHOALS = bool("fishShoals", true);
  public static final ModConfigSpec.BooleanValue ENDERMEN_PLACES_OF_POWER =
      bool("endermenPlacesOfPower", true);
  public static final ModConfigSpec.DoubleValue ENDERMEN_ELSEWHERE_CHANCE =
      BUILDER
          .translation(key("endermenElsewhereChance"))
          .defineInRange("endermenElsewhereChance", 0.1, 0.0, 1.0);

  public static final ModConfigSpec SPEC = BUILDER.build();

  private WildsConfig() {}

  private static ModConfigSpec.BooleanValue bool(String name, boolean defaultValue) {
    return BUILDER.translation(key(name)).define(name, defaultValue);
  }

  private static String key(String name) {
    return GuestWilds.MODID + ".configuration." + name;
  }
}
