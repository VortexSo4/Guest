package com.vortexso.guest_atmosphere;

import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_atmosphere.weather.WeatherParameters;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

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
  public static final ModConfigSpec.DoubleValue MORNING_FOG_MULTIPLIER;
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

  public static final ModConfigSpec.DoubleValue SNOW_MELT_OFFSET;
  public static final ModConfigSpec.DoubleValue SNOW_MELT_RATE;
  public static final ModConfigSpec.DoubleValue RAIN_SNOW_MELT;
  public static final ModConfigSpec.BooleanValue SNOWY_PLANTS;
  public static final ModConfigSpec.BooleanValue FOOTPRINTS;
  public static final ModConfigSpec.DoubleValue FOOTPRINT_CHANCE;
  public static final ModConfigSpec.BooleanValue ICICLES;
  public static final ModConfigSpec.DoubleValue ICICLE_CHANCE;
  public static final ModConfigSpec.BooleanValue FROST;
  public static final ModConfigSpec.DoubleValue FROST_CHANCE;
  public static final ModConfigSpec.BooleanValue THIN_ICE;
  public static final ModConfigSpec.BooleanValue THIN_ICE_CRACKS;
  public static final ModConfigSpec.BooleanValue PERMAFROST;
  public static final ModConfigSpec.DoubleValue PERMAFROST_DAYS;
  public static final ModConfigSpec.BooleanValue COATED_BLOCKS;
  public static final ModConfigSpec.DoubleValue COAT_CHANCE;
  public static final ModConfigSpec.BooleanValue SAND_PILES;
  public static final ModConfigSpec.BooleanValue SILT;
  public static final ModConfigSpec.DoubleValue SILT_CHANCE;
  public static final ModConfigSpec.DoubleValue SILT_GRASS_DAYS;
  public static final ModConfigSpec.BooleanValue LEAF_LITTER;
  public static final ModConfigSpec.DoubleValue LEAF_LITTER_PER_DAY;
  public static final ModConfigSpec.BooleanValue GROWTH;
  public static final ModConfigSpec.DoubleValue MOSS_RATE;
  public static final ModConfigSpec.DoubleValue LICHEN_RATE;
  public static final ModConfigSpec.BooleanValue IVY;
  public static final ModConfigSpec.DoubleValue IVY_RATE;
  public static final ModConfigSpec.DoubleValue ABANDONED_GROWTH;
  public static final ModConfigSpec.BooleanValue CRACKING;
  public static final ModConfigSpec.DoubleValue CRACK_RATE;
  public static final ModConfigSpec.BooleanValue LIGHTNING_BURNS_TREES;
  public static final ModConfigSpec.IntValue TREE_BURN_MAX_BLOCKS;
  public static final ModConfigSpec.DoubleValue REGROWTH_DAYS;
  public static final ModConfigSpec.BooleanValue FULGURITE;
  public static final ModConfigSpec.BooleanValue FIRE_CHARS_WOOD;
  public static final ModConfigSpec.DoubleValue FIRE_CHAR_CHANCE;
  public static final ModConfigSpec.BooleanValue FIRE_ASH;
  public static final ModConfigSpec.DoubleValue FIRE_ASH_CHANCE;
  public static final ModConfigSpec.BooleanValue SOOT;
  public static final ModConfigSpec.DoubleValue SOOT_CHANCE;
  public static final ModConfigSpec.BooleanValue MINESHAFT_AGING;
  public static final ModConfigSpec.DoubleValue MINESHAFT_DECAY_DAYS;

  public static final ModConfigSpec.DoubleValue FOG_STRENGTH;
  public static final ModConfigSpec.BooleanValue WEATHER_PARTICLES;
  public static final ModConfigSpec.DoubleValue PARTICLE_DENSITY;
  public static final ModConfigSpec.BooleanValue AMBIENT_SOUNDS;
  public static final ModConfigSpec.BooleanValue AURORA_VISUALS;
  public static final ModConfigSpec.BooleanValue GROUND_MIST;
  public static final ModConfigSpec.IntValue GROUND_MIST_DISTANCE;
  public static final ModConfigSpec.BooleanValue VEGETATION_TINT;
  public static final ModConfigSpec.DoubleValue WIND_DRIFT;

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
    MORNING_FOG_MULTIPLIER =
        b.comment("Multiplies the chance of dawn mist over rivers, swamps and low ground.")
            .defineInRange("morningFogMultiplier", 1.0, 0.0, 3.0);

    b.comment(
            "Per-climate tables [spring, summer, autumn, winter]: chance of a precipitation event per slot, and share of those that are severe.")
        .push("seasons");
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
        b.comment(
                "Weather leaves traces in blocks: snow, mud, ice, sand, frost, ash, moss... See FEATURES-atm-blocks.md.")
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
    MUD_CHANCE = b.defineInRange("mudChance", 0.08, 0.0, 1.0);
    MUD_DRYING_DAYS =
        b.comment("Days without real rain (drizzle does not count) after which mud dries back.")
            .defineInRange("mudDryingDays", 1.0, 0.1, 8.0);
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

    b.push("winter");
    SNOW_MELT_OFFSET =
        b.comment(
                "Lying snow melts only this far above the snowfall temperature (0.15), so a mild winter day keeps its snow.")
            .defineInRange("snowMeltOffset", 0.1, 0.0, 0.5);

    SNOW_MELT_RATE =
        b.comment("Layers of snow melted per day for every 0.1 of temperature above melting.")
            .defineInRange("snowMeltRate", 1.0, 0.0, 16.0);
    RAIN_SNOW_MELT =
        b.comment("Extra layers melted per day while it rains on snow.")
            .defineInRange("rainSnowMelt", 1.0, 0.0, 16.0);
    SNOWY_PLANTS =
        b.comment("Snow covers grass and flowers without destroying them (Bedrock-style).")
            .define("snowyPlants", true);
    FOOTPRINTS =
        b.comment("Walking through deep snow, sand or ash packs it down into visible trails.")
            .define("footprints", true);
    FOOTPRINT_CHANCE =
        b.comment("Chance per tick of walking on a layer that it sinks by one.")
            .defineInRange("footprintChance", 0.05, 0.0, 1.0);
    ICICLES = b.comment("Icicles under snowy eaves and branches.").define("icicles", true);
    ICICLE_CHANCE = b.defineInRange("icicleChance", 0.25, 0.0, 1.0);
    FROST =
        b.comment("Hoarfrost on the ground, grass and windows on clear freezing nights.")
            .define("frost", true);
    FROST_CHANCE = b.defineInRange("frostChance", 0.5, 0.0, 1.0);
    THIN_ICE =
        b.comment("Shore water freezes into thin ice (replaces the old full ice).")
            .define("thinIce", true);
    THIN_ICE_CRACKS =
        b.comment("Thin ice cracks under walking creatures and breaks on the third crack.")
            .define("thinIceCracks", true);
    PERMAFROST =
        b.comment("Long frosts freeze the topsoil of cold regions (permafrost, cryosol).")
            .define("permafrost", true);
    PERMAFROST_DAYS =
        b.comment("Days of continuous frost before the ground freezes.")
            .defineInRange("permafrostDays", 3.0, 0.5, 16.0);
    b.pop();

    b.push("surfaces");
    COATED_BLOCKS =
        b.comment(
                "Snow and sand lodge in the cracks of cobblestone, bricks, planks, fences and walls.")
            .define("coatedBlocks", true);
    COAT_CHANCE = b.defineInRange("coatChance", 0.3, 0.0, 1.0);
    SAND_PILES =
        b.comment("Sandstorms pile sand layers against walls and in corners.")
            .define("sandPiles", true);
    SILT =
        b.comment("Downpours leave silt on low banks next to water; grass takes it over.")
            .define("silt", true);
    SILT_CHANCE = b.defineInRange("siltChance", 0.2, 0.0, 1.0);
    SILT_GRASS_DAYS =
        b.comment("Days without heavy rain before silt turns to grass.")
            .defineInRange("siltGrassDays", 2.0, 0.1, 16.0);
    LEAF_LITTER =
        b.comment("Autumn drops leaf litter under broadleaf trees; winter clears it.")
            .define("leafLitter", true);
    LEAF_LITTER_PER_DAY =
        b.comment("Leaf litter segments per autumn day (three times as many in wind).")
            .defineInRange("leafLitterPerDay", 0.12, 0.0, 4.0);
    b.pop();

    b.push("growth");
    GROWTH =
        b.comment("Damp shaded surfaces slowly age: moss, lichen, ivy, cracks. Permanent.")
            .define("growthEnabled", true);
    MOSS_RATE =
        b.comment("Chance per damp day that an exposed cobblestone or stone brick turns mossy.")
            .defineInRange("mossRate", 0.004, 0.0, 1.0);
    LICHEN_RATE = b.defineInRange("lichenRate", 0.003, 0.0, 1.0);
    IVY = b.define("ivy", true);
    IVY_RATE =
        b.comment("Chance per damp day that ivy takes hold at a wall foot; it climbs 10x faster.")
            .defineInRange("ivyRate", 0.002, 0.0, 1.0);
    ABANDONED_GROWTH =
        b.comment("Growth speed-up where nobody lives (no bed within 32 blocks).")
            .defineInRange("abandonedGrowth", 3.0, 1.0, 16.0);
    CRACKING =
        b.comment("Freeze-thaw cycles crack stone bricks, bricks and deepslate bricks.")
            .define("cracking", true);
    CRACK_RATE =
        b.comment("Chance per freeze-thaw cycle that an exposed brick block cracks.")
            .defineInRange("crackRate", 0.002, 0.0, 1.0);
    b.pop();

    b.push("fire");
    LIGHTNING_BURNS_TREES =
        b.comment("Lightning burns the whole struck tree: charred trunk, burnt crown, regrowth.")
            .define("lightningBurnsTrees", true);
    TREE_BURN_MAX_BLOCKS = b.defineInRange("treeBurnMaxBlocks", 400, 16, 4_096);
    REGROWTH_DAYS =
        b.comment("Days after a tree burnt until saplings and bushes come up around it.")
            .defineInRange("regrowthDays", 5.0, 0.5, 64.0);
    FULGURITE =
        b.comment("Lightning into sand leaves vitrified sand and fulgurites.")
            .define("fulgurite", true);
    FIRE_CHARS_WOOD =
        b.comment("Burning wood may be left as charred wood instead of vanishing.")
            .define("fireCharsWood", true);
    FIRE_CHAR_CHANCE = b.defineInRange("fireCharChance", 0.5, 0.0, 1.0);
    FIRE_ASH = b.comment("A fire that goes out may leave a layer of ash.").define("fireAsh", true);
    FIRE_ASH_CHANCE = b.defineInRange("fireAshChance", 0.35, 0.0, 1.0);
    SOOT =
        b.comment("Smoke from lit campfires blackens chimney walls and ceilings.")
            .define("soot", true);
    SOOT_CHANCE =
        b.comment("Chance per column update above a lit campfire that soot spreads.")
            .defineInRange("sootChance", 0.05, 0.0, 1.0);
    b.pop();

    b.push("mineshafts");
    MINESHAFT_AGING =
        b.comment(
                "Abandoned mineshafts decay with world age: rotten supports, gravel falls, flooded bottom, vines.")
            .define("mineshaftAging", true);
    MINESHAFT_DECAY_DAYS =
        b.comment("World age in days by which about 63% of the final decay has happened.")
            .defineInRange("mineshaftDecayDays", 256.0, 8.0, 100_000.0);
    b.pop();
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
    GROUND_MIST =
        c.comment("Low translucent mist patches over swamps (and rivers/ponds at dawn).")
            .define("groundMist", true);
    GROUND_MIST_DISTANCE =
        c.comment("How far away ground mist is drawn, in blocks.")
            .defineInRange("groundMistDistance", 96, 32, 192);
    VEGETATION_TINT =
        c.comment("Leaves and grass whiten while it snows and yellow in droughts.")
            .define("vegetationTint", true);
    WIND_DRIFT =
        c.comment("How strongly wind pushes falling snow sideways (0 = vanilla).")
            .defineInRange("windDrift", 1.0, 0.0, 2.0);
    CLIENT_SPEC = c.build();
  }

  private AtmosphereConfig() {}

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
    double[][] heat = new double[classes][];
    for (int i = 0; i < classes; i++) {
      heat[i] = scale(WeatherParameters.DEFAULT_HEAT[i], HEAT_MULTIPLIER.get());
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
        heat,
        LEAF_FALL_CHANCE.get(),
        scale(WeatherParameters.DEFAULT_MORNING_FOG, MORNING_FOG_MULTIPLIER.get()),
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
