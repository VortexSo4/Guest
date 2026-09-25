package com.vortexso.guest_atmosphere;

import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_atmosphere.weather.WeatherParameters;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server config holds the simulation (shared by every player); client config only presentation.
 * Translation keys follow NeoForge's {@code <modid>.configuration.<name>} scheme.
 */
public final class AtmosphereConfig {
  public static final ModConfigSpec SERVER_SPEC;
  public static final ModConfigSpec CLIENT_SPEC;

  public static final ModConfigSpec.BooleanValue DRIVE_VANILLA_WEATHER;
  public static final ModConfigSpec.LongValue SEGMENT_TICKS;
  public static final ModConfigSpec.IntValue CELL_SIZE;
  public static final ModConfigSpec.DoubleValue BLEND_FRACTION;
  public static final ModConfigSpec.DoubleValue DRIFT_BLOCKS_PER_DAY;
  public static final ModConfigSpec.DoubleValue MIN_EVENT_SEGMENTS;
  public static final ModConfigSpec.DoubleValue MAX_EVENT_SEGMENTS;
  public static final ModConfigSpec.DoubleValue PRECIPITATION_MULTIPLIER;
  public static final ModConfigSpec.DoubleValue STORM_MULTIPLIER;
  public static final ModConfigSpec.DoubleValue FOG_MULTIPLIER;
  public static final ModConfigSpec.DoubleValue SANDSTORM_MULTIPLIER;
  public static final ModConfigSpec.DoubleValue HEAT_MULTIPLIER;
  public static final ModConfigSpec.DoubleValue LEAF_FALL_CHANCE;
  public static final ModConfigSpec.DoubleValue AURORA_CHANCE;
  private static final ModConfigSpec.ConfigValue<List<? extends Double>>[] PRECIPITATION_TABLE;
  private static final ModConfigSpec.ConfigValue<List<? extends Double>>[] SEVERE_TABLE;

  public static final ModConfigSpec.BooleanValue SLOWDOWN_ENABLED;
  public static final ModConfigSpec.DoubleValue BLIZZARD_SLOWDOWN;
  public static final ModConfigSpec.DoubleValue SNOWSTORM_SLOWDOWN;
  public static final ModConfigSpec.DoubleValue WARM_CLOTHING_MITIGATION;

  public static final ModConfigSpec.BooleanValue TRACES_ENABLED;
  public static final ModConfigSpec.IntValue TRACE_CHUNK_RADIUS;
  public static final ModConfigSpec.DoubleValue COLUMN_VISIT_RATE;
  public static final ModConfigSpec.IntValue SNOWFALL_MAX_LAYERS;
  public static final ModConfigSpec.IntValue STORM_MAX_LAYERS;
  public static final ModConfigSpec.IntValue DRIFT_MAX_LAYERS;
  public static final ModConfigSpec.DoubleValue MELT_CHANCE;
  public static final ModConfigSpec.DoubleValue MUD_CHANCE;
  public static final ModConfigSpec.DoubleValue MUD_DRYING_DAYS;
  public static final ModConfigSpec.DoubleValue ICE_CHANCE;
  public static final ModConfigSpec.DoubleValue FREEZE_TEMPERATURE;
  public static final ModConfigSpec.DoubleValue SAND_CHANCE;
  public static final ModConfigSpec.DoubleValue SAND_LIFETIME_DAYS;
  public static final ModConfigSpec.DoubleValue DRY_CHANCE;
  public static final ModConfigSpec.DoubleValue DRY_LIFETIME_DAYS;
  public static final ModConfigSpec.BooleanValue SCORCH_ENABLED;
  public static final ModConfigSpec.DoubleValue SCORCH_LIFETIME_DAYS;
  public static final ModConfigSpec.IntValue MAX_TRACES_PER_CHUNK;
  public static final ModConfigSpec.IntValue CATCH_UP_MAX_DAYS;
  public static final ModConfigSpec.IntValue CATCH_UP_CHUNKS_PER_TICK;

  public static final ModConfigSpec.DoubleValue FOG_STRENGTH;
  public static final ModConfigSpec.BooleanValue WEATHER_PARTICLES;
  public static final ModConfigSpec.DoubleValue PARTICLE_DENSITY;
  public static final ModConfigSpec.BooleanValue AMBIENT_SOUNDS;
  public static final ModConfigSpec.BooleanValue AURORA_VISUALS;

  private static volatile WeatherParameters weather = WeatherParameters.DEFAULT;

  static {
    ModConfigSpec.Builder b = new ModConfigSpec.Builder();

    b.push("weather");
    DRIVE_VANILLA_WEATHER =
        b.comment(
                "Replace the vanilla global rain/thunder cycle with the regional model. Off leaves vanilla weather alone (the model still answers queries from other Guest addons).")
            .define("driveVanillaWeather", true);
    SEGMENT_TICKS =
        b.comment("Length of one weather segment in ticks; one event slot is 4 segments.")
            .defineInRange("segmentTicks", 6_000L, 1_200L, 24_000L);
    CELL_SIZE =
        b.comment("Size of a weather region cell in blocks.")
            .defineInRange("cellSize", 768, 128, 4_096);
    BLEND_FRACTION =
        b.comment("Share of a cell used to fade into the neighbour cell (0 = hard borders).")
            .defineInRange("blendFraction", 0.3, 0.0, 1.0);
    DRIFT_BLOCKS_PER_DAY =
        b.comment("How far weather fronts travel east per day.")
            .defineInRange("driftBlocksPerDay", 256.0, 0.0, 4_096.0);
    MIN_EVENT_SEGMENTS =
        b.comment("Shortest weather event in segments (vanilla rain: 12000 ticks = 2 segments).")
            .defineInRange("minEventSegments", 2.0, 1.0, 8.0);
    MAX_EVENT_SEGMENTS =
        b.comment("Longest weather event in segments (vanilla rain: 24000 ticks = 4 segments).")
            .defineInRange("maxEventSegments", 4.0, 1.0, 8.0);
    PRECIPITATION_MULTIPLIER =
        b.comment("Multiplies every precipitation chance.")
            .defineInRange("precipitationMultiplier", 1.0, 0.0, 3.0);
    STORM_MULTIPLIER =
        b.comment("Multiplies the share of precipitation that turns into storms/blizzards.")
            .defineInRange("stormMultiplier", 1.0, 0.0, 3.0);
    FOG_MULTIPLIER = b.defineInRange("fogMultiplier", 1.0, 0.0, 3.0);
    SANDSTORM_MULTIPLIER = b.defineInRange("sandstormMultiplier", 1.0, 0.0, 3.0);
    HEAT_MULTIPLIER = b.defineInRange("heatMultiplier", 1.0, 0.0, 3.0);
    LEAF_FALL_CHANCE =
        b.comment("Chance of a leaf-fall day in autumn forests.")
            .defineInRange("leafFallChance", 0.35, 0.0, 1.0);
    AURORA_CHANCE =
        b.comment("Chance of an aurora on a clear winter night in a cold biome.")
            .defineInRange("auroraChance", 0.08, 0.0, 1.0);
    b.comment(
            "Per-climate tables [spring, summer, autumn, winter]: chance of a precipitation event per slot, and share of those that are severe.")
        .push("tables");
    @SuppressWarnings("unchecked")
    ModConfigSpec.ConfigValue<List<? extends Double>>[] precipitation =
        new ModConfigSpec.ConfigValue[ClimateClass.values().length];
    @SuppressWarnings("unchecked")
    ModConfigSpec.ConfigValue<List<? extends Double>>[] severe =
        new ModConfigSpec.ConfigValue[ClimateClass.values().length];
    for (ClimateClass climate : ClimateClass.values()) {
      String name = climate.name().toLowerCase(Locale.ROOT);
      precipitation[climate.ordinal()] =
          b.defineList(
              "precipitation_" + name,
              boxed(WeatherParameters.DEFAULT_PRECIPITATION[climate.ordinal()]),
              () -> 0.0,
              AtmosphereConfig::isProbability);
      severe[climate.ordinal()] =
          b.defineList(
              "severe_" + name,
              boxed(WeatherParameters.DEFAULT_SEVERE[climate.ordinal()]),
              () -> 0.0,
              AtmosphereConfig::isProbability);
    }
    PRECIPITATION_TABLE = precipitation;
    SEVERE_TABLE = severe;
    b.pop();
    b.pop();

    b.push("effects");
    SLOWDOWN_ENABLED =
        b.comment("Blizzards and snowstorms slow players who are outside.")
            .define("slowdownEnabled", true);
    BLIZZARD_SLOWDOWN = b.defineInRange("blizzardSlowdown", 0.15, 0.0, 0.9);
    SNOWSTORM_SLOWDOWN = b.defineInRange("snowstormSlowdown", 0.25, 0.0, 0.9);
    WARM_CLOTHING_MITIGATION =
        b.comment("Share of the slowdown removed by a full set of leather armour.")
            .defineInRange("warmClothingMitigation", 1.0, 0.0, 1.0);
    b.pop();

    b.push("traces");
    TRACES_ENABLED =
        b.comment("Weather leaves reversible block traces: snow, mud, ice, sand, dry ground.")
            .define("tracesEnabled", true);
    TRACE_CHUNK_RADIUS = b.defineInRange("traceChunkRadius", 6, 1, 16);
    COLUMN_VISIT_RATE =
        b.comment(
                "Chance per chunk per tick that one column is updated. Vanilla precipitation: randomTickSpeed 3 x 1/48 = 1/16.")
            .defineInRange("columnVisitRate", 0.0625, 0.0, 1.0);
    SNOWFALL_MAX_LAYERS = b.defineInRange("snowfallMaxLayers", 3, 1, 8);
    STORM_MAX_LAYERS = b.defineInRange("stormMaxLayers", 5, 1, 8);
    DRIFT_MAX_LAYERS =
        b.comment("Depth of storm drifts against walls and doors (8 = full block).")
            .defineInRange("driftMaxLayers", 8, 1, 8);
    MELT_CHANCE = b.defineInRange("meltChance", 0.5, 0.0, 1.0);
    MUD_CHANCE = b.defineInRange("mudChance", 0.08, 0.0, 1.0);
    MUD_DRYING_DAYS = b.defineInRange("mudDryingDays", 1.5, 0.1, 32.0);
    ICE_CHANCE = b.defineInRange("iceChance", 0.5, 0.0, 1.0);
    FREEZE_TEMPERATURE =
        b.comment("Air temperature (vanilla biome scale) below which still water ices over.")
            .defineInRange("freezeTemperature", 0.0, -2.0, 0.15);
    SAND_CHANCE = b.defineInRange("sandChance", 0.3, 0.0, 1.0);
    SAND_LIFETIME_DAYS = b.defineInRange("sandLifetimeDays", 4.0, 0.1, 64.0);
    DRY_CHANCE = b.defineInRange("dryChance", 0.03, 0.0, 1.0);
    DRY_LIFETIME_DAYS = b.defineInRange("dryLifetimeDays", 8.0, 0.1, 64.0);
    SCORCH_ENABLED = b.define("scorchEnabled", true);
    SCORCH_LIFETIME_DAYS = b.defineInRange("scorchLifetimeDays", 3.0, 0.1, 64.0);
    MAX_TRACES_PER_CHUNK = b.defineInRange("maxTracesPerChunk", 512, 16, 4_096);
    CATCH_UP_MAX_DAYS =
        b.comment("How much missed weather history a chunk replays when it is seen again.")
            .defineInRange("catchUpMaxDays", 16, 1, 128);
    CATCH_UP_CHUNKS_PER_TICK = b.defineInRange("catchUpChunksPerTick", 2, 1, 64);
    b.pop();

    SERVER_SPEC = b.build();

    ModConfigSpec.Builder c = new ModConfigSpec.Builder();
    FOG_STRENGTH =
        c.comment("How strongly fog, blizzards and sandstorms reduce visibility.")
            .defineInRange("fogStrength", 1.0, 0.0, 1.0);
    WEATHER_PARTICLES = c.define("weatherParticles", true);
    PARTICLE_DENSITY = c.defineInRange("particleDensity", 1.0, 0.0, 3.0);
    AMBIENT_SOUNDS = c.define("ambientSounds", true);
    AURORA_VISUALS = c.define("auroraVisuals", true);
    CLIENT_SPEC = c.build();
  }

  private AtmosphereConfig() {}

  /** Snapshot used by the pure model; rebuilt whenever the server config (re)loads. */
  public static WeatherParameters weather() {
    return weather;
  }

  static void onConfig(ModConfigEvent event) {
    if (event.getConfig().getSpec() == SERVER_SPEC
        && !(event instanceof ModConfigEvent.Unloading)) {
      weather = buildWeather();
    }
  }

  private static WeatherParameters buildWeather() {
    WeatherParameters d = WeatherParameters.DEFAULT;
    int classes = ClimateClass.values().length;
    double[][] precipitation = new double[classes][];
    double[][] severe = new double[classes][];
    double[][] fog = new double[classes][];
    for (int i = 0; i < classes; i++) {
      precipitation[i] =
          scale(
              table(PRECIPITATION_TABLE[i].get(), WeatherParameters.DEFAULT_PRECIPITATION[i]),
              PRECIPITATION_MULTIPLIER.get());
      severe[i] =
          scale(
              table(SEVERE_TABLE[i].get(), WeatherParameters.DEFAULT_SEVERE[i]),
              STORM_MULTIPLIER.get());
      fog[i] = scale(WeatherParameters.DEFAULT_FOG[i], FOG_MULTIPLIER.get());
    }
    double minSegments = MIN_EVENT_SEGMENTS.get();
    return new WeatherParameters(
        SEGMENT_TICKS.get(),
        CELL_SIZE.get(),
        BLEND_FRACTION.get(),
        DRIFT_BLOCKS_PER_DAY.get(),
        minSegments,
        Math.max(minSegments, MAX_EVENT_SEGMENTS.get()),
        d.rampTicks(),
        precipitation,
        severe,
        fog,
        scale(WeatherParameters.DEFAULT_SANDSTORM, SANDSTORM_MULTIPLIER.get()),
        scale(WeatherParameters.DEFAULT_HEAT, HEAT_MULTIPLIER.get()),
        LEAF_FALL_CHANCE.get(),
        d.windiness(),
        d.seasonTemperature(),
        d.anomalyAmplitude(),
        d.diurnalAmplitude(),
        d.desertNightChill(),
        d.snowTemperature(),
        d.minimumField(),
        d.severeThreshold(),
        AURORA_CHANCE.get(),
        d.auroraMaxTemperature());
  }

  private static double[] table(List<? extends Double> values, double[] fallback) {
    if (values.size() != fallback.length) {
      GuestAtmosphere.LOGGER.warn("Weather table needs {} values, using defaults", fallback.length);
      return fallback;
    }
    // TOML may hand back integers for values like "0", so read through Number.
    return values.stream().mapToDouble(value -> ((Number) (Object) value).doubleValue()).toArray();
  }

  private static double[] scale(double[] values, double factor) {
    return Arrays.stream(values).map(value -> Math.min(1.0, value * factor)).toArray();
  }

  private static List<Double> boxed(double[] values) {
    return Arrays.stream(values).boxed().toList();
  }

  private static boolean isProbability(Object value) {
    return value instanceof Number number
        && number.doubleValue() >= 0.0
        && number.doubleValue() <= 1.0;
  }
}
