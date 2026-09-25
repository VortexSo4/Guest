package com.vortexso.guest_architects;

import com.vortexso.guest_architects.city.CityLife;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Server config. Every gameplay rule a player could want to tune or disable lives here. */
public final class ArchitectsConfig {
  public static final ModConfigSpec SPEC;

  public static final ModConfigSpec.DoubleValue LIVING_CITY_FRACTION;
  public static final ModConfigSpec.IntValue MIN_POPULATION;
  public static final ModConfigSpec.IntValue MAX_POPULATION;
  public static final ModConfigSpec.DoubleValue FLUCTUATION_YEARS;
  public static final ModConfigSpec.DoubleValue FLUCTUATION_AMPLITUDE;
  public static final ModConfigSpec.DoubleValue DECLINE_YEARS;
  public static final ModConfigSpec.BooleanValue ABANDONED_SPREAD;
  public static final ModConfigSpec.IntValue SPREAD_MAX_RADIUS;
  public static final ModConfigSpec.DoubleValue SPREAD_DAYS;
  public static final ModConfigSpec.DoubleValue MAX_ASSISTANCE_DAYS;

  public static final ModConfigSpec.IntValue NOTICE_THRESHOLD;
  public static final ModConfigSpec.IntValue IMMOBILIZE_THRESHOLD;
  public static final ModConfigSpec.IntValue EQUIPMENT_THRESHOLD;
  public static final ModConfigSpec.IntValue REMOVAL_THRESHOLD;
  public static final ModConfigSpec.BooleanValue DESTROY_EQUIPMENT;
  public static final ModConfigSpec.BooleanValue PORTAL_REMOVAL;
  public static final ModConfigSpec.DoubleValue INTERFERENCE_DECAY_PER_DAY;

  public static final ModConfigSpec.IntValue PIGLIN_ZOMBIFICATION_SECONDS;
  public static final ModConfigSpec.DoubleValue ARCHITECT_EQUIPMENT_ZOMBIE_CHANCE;

  static {
    ModConfigSpec.Builder b = new ModConfigSpec.Builder();

    LIVING_CITY_FRACTION =
        b.translation(key("living_city_fraction"))
            .comment("Fraction of ancient cities that are still inhabited.")
            .defineInRange("living_city_fraction", 0.35, 0.0, 1.0);
    MIN_POPULATION =
        b.translation(key("min_population"))
            .comment("Smallest base population of a living city.")
            .defineInRange("min_population", 20, 1, 64);
    MAX_POPULATION =
        b.translation(key("max_population"))
            .comment("Largest base population of a living city.")
            .defineInRange("max_population", 30, 1, 64);
    FLUCTUATION_YEARS =
        b.translation(key("fluctuation_years"))
            .comment("Period, in Guest years (128 days), of a fluctuating city's population cycle.")
            .defineInRange("fluctuation_years", 6.0, 0.5, 1000.0);
    FLUCTUATION_AMPLITUDE =
        b.translation(key("fluctuation_amplitude"))
            .comment("Relative amplitude of a fluctuating city's population cycle.")
            .defineInRange("fluctuation_amplitude", 0.2, 0.0, 0.9);
    DECLINE_YEARS =
        b.translation(key("decline_years"))
            .comment("Years a declining city needs to fall from full base population to zero.")
            .defineInRange("decline_years", 20.0, 0.1, 10000.0);
    ABANDONED_SPREAD =
        b.translation(key("abandoned_spread"))
            .comment("Whether sculk keeps spreading through abandoned cities over time.")
            .define("abandoned_spread", true);
    SPREAD_MAX_RADIUS =
        b.translation(key("spread_max_radius"))
            .comment(
                "Maximum radius of the sculk spread around each catalyst of an abandoned city.")
            .defineInRange("spread_max_radius", 10, 0, 12);
    SPREAD_DAYS =
        b.translation(key("spread_days"))
            .comment("Days after abandonment for sculk to reach ~63% of its maximum radius.")
            .defineInRange("spread_days", 128.0, 1.0, 100000.0);
    MAX_ASSISTANCE_DAYS =
        b.translation(key("max_assistance_days"))
            .comment("Maximum total delay (days) player assistance can add to a city's decline.")
            .defineInRange("max_assistance_days", 256.0, 0.0, 100000.0);

    NOTICE_THRESHOLD =
        b.translation(key("notice_threshold"))
            .comment("Interference points at which Architects stop and watch the player.")
            .defineInRange("notice_threshold", 2, 1, 1000);
    IMMOBILIZE_THRESHOLD =
        b.translation(key("immobilize_threshold"))
            .comment("Interference points at which the player is immobilized.")
            .defineInRange("immobilize_threshold", 4, 1, 1000);
    EQUIPMENT_THRESHOLD =
        b.translation(key("equipment_threshold"))
            .comment("Interference points at which the held tool is taken as a warning.")
            .defineInRange("equipment_threshold", 6, 1, 1000);
    REMOVAL_THRESHOLD =
        b.translation(key("removal_threshold"))
            .comment("Interference points at which the player is removed from the city.")
            .defineInRange("removal_threshold", 8, 1, 1000);
    DESTROY_EQUIPMENT =
        b.translation(key("destroy_equipment"))
            .comment("Whether the warning destroys the held item (false: it is dropped instead).")
            .define("destroy_equipment", true);
    PORTAL_REMOVAL =
        b.translation(key("portal_removal"))
            .comment("Whether Architects remove persistent intruders to the surface.")
            .define("portal_removal", true);
    INTERFERENCE_DECAY_PER_DAY =
        b.translation(key("interference_decay_per_day"))
            .comment("Interference points forgotten per day (faster for players who helped).")
            .defineInRange("interference_decay_per_day", 2.0, 0.0, 1000.0);

    PIGLIN_ZOMBIFICATION_SECONDS =
        b.translation(key("piglin_zombification_seconds"))
            .comment("Seconds of Overworld exposure before piglins/hoglins turn (vanilla: 15).")
            .defineInRange("piglin_zombification_seconds", 30, 15, 3600);
    ARCHITECT_EQUIPMENT_ZOMBIE_CHANCE =
        b.translation(key("architect_equipment_zombie_chance"))
            .comment("Chance that a naturally spawned zombie keeps old Architect armor.")
            .defineInRange("architect_equipment_zombie_chance", 0.005, 0.0, 1.0);

    SPEC = b.build();
  }

  private ArchitectsConfig() {}

  private static String key(String name) {
    return GuestArchitects.MODID + ".configuration." + name;
  }

  public static CityLife.Params lifeParams() {
    int min = MIN_POPULATION.get();
    return new CityLife.Params(
        LIVING_CITY_FRACTION.get(),
        min,
        Math.max(min, MAX_POPULATION.get()),
        FLUCTUATION_YEARS.get(),
        FLUCTUATION_AMPLITUDE.get(),
        DECLINE_YEARS.get(),
        SPREAD_MAX_RADIUS.get(),
        SPREAD_DAYS.get());
  }
}
