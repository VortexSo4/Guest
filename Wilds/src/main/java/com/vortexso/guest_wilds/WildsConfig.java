package com.vortexso.guest_wilds;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class WildsConfig {
  private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

  public static final ModConfigSpec.BooleanValue PATHS = bool("paths", true);
  public static final ModConfigSpec.DoubleValue WEAR_MULTIPLIER =
      BUILDER.translation(key("wearMultiplier")).defineInRange("wearMultiplier", 1.0, 0.0, 10.0);
  public static final ModConfigSpec.BooleanValue LAIRS = bool("lairs", true);
  public static final ModConfigSpec.DoubleValue LAIR_SHARE =
      BUILDER.translation(key("lairShare")).defineInRange("lairShare", 0.5, 0.0, 1.0);
  public static final ModConfigSpec.DoubleValue SURFACE_SPAWN_SHARE =
      BUILDER
          .translation(key("surfaceSpawnShare"))
          .defineInRange("surfaceSpawnShare", 0.25, 0.0, 1.0);
  public static final ModConfigSpec.BooleanValue SUPPRESS_RANDOM_JOCKEYS =
      bool("suppressRandomJockeys", true);
  public static final ModConfigSpec.BooleanValue UNDEAD_DAILY_CYCLE =
      bool("undeadDailyCycle", true);
  public static final ModConfigSpec.BooleanValue FOG_SHIELDS_UNDEAD =
      bool("fogShieldsUndead", true);
  public static final ModConfigSpec.BooleanValue LAIR_FIGHTS = bool("lairFights", true);
  public static final ModConfigSpec.BooleanValue HERDS = bool("herds", true);
  public static final ModConfigSpec.BooleanValue HERD_RELOCATION = bool("herdRelocation", true);
  public static final ModConfigSpec.BooleanValue GRAZERS_EAT_GRASS = bool("grazersEatGrass", true);
  public static final ModConfigSpec.BooleanValue WEATHER_SHELTER = bool("weatherShelter", true);
  public static final ModConfigSpec.BooleanValue SEASONAL_VARIANTS = bool("seasonalVariants", true);
  public static final ModConfigSpec.BooleanValue FISH_SHOALS = bool("fishShoals", true);
  public static final ModConfigSpec.IntValue FISH_SCHOOL_SIZE =
      BUILDER.translation(key("fishSchoolSize")).defineInRange("fishSchoolSize", 20, 1, 64);
  public static final ModConfigSpec.BooleanValue FISHING_BY_PLACE = bool("fishingByPlace", true);
  public static final ModConfigSpec.BooleanValue FOREST_REGROWTH = bool("forestRegrowth", true);
  public static final ModConfigSpec.BooleanValue FOREST_SPREAD = bool("forestSpread", true);
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
