package com.vortexso.guest_settlements;

import com.vortexso.guest_settlements.village.Caravans;
import com.vortexso.guest_settlements.village.VillageSimulationParameters;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class SettlementsConfig {
  public static final ModConfigSpec SPEC;

  public static final ModConfigSpec.BooleanValue CEREMONIES;
  public static final ModConfigSpec.BooleanValue STORM_BELL;
  public static final ModConfigSpec.BooleanValue TRADE_SCENES;
  public static final ModConfigSpec.DoubleValue TRADES_PER_PAIR;
  public static final ModConfigSpec.BooleanValue HELPERS;

  public static final ModConfigSpec.BooleanValue STORM_SHELTER;
  public static final ModConfigSpec.BooleanValue AURORA;
  public static final ModConfigSpec.BooleanValue PROFESSION_DAYS;
  public static final ModConfigSpec.BooleanValue HELD_TOOLS;
  public static final ModConfigSpec.BooleanValue FARMER_TILLING;
  public static final ModConfigSpec.DoubleValue TILL_PER_FARMER;
  public static final ModConfigSpec.BooleanValue LIVESTOCK_CULLING;
  public static final ModConfigSpec.BooleanValue SNOW_CLEARING;
  public static final ModConfigSpec.BooleanValue CARTOGRAPHER_TRIPS;
  public static final ModConfigSpec.BooleanValue CONSTRUCTION;
  public static final ModConfigSpec.DoubleValue BLOCKS_PER_BUILDER;
  public static final ModConfigSpec.DoubleValue FENCE_PRESSURE;
  public static final ModConfigSpec.BooleanValue CARAVANS;
  public static final ModConfigSpec.DoubleValue CARAVAN_DEPARTURE;
  public static final ModConfigSpec.DoubleValue CARAVAN_SPEED;
  public static final ModConfigSpec.DoubleValue CARAVAN_LOSS;
  public static final ModConfigSpec.DoubleValue CARAVAN_SEVERE_LOSS;
  public static final ModConfigSpec.DoubleValue CARAVAN_CARGO;
  public static final ModConfigSpec.DoubleValue COMMUTE_TRIPS;
  public static final ModConfigSpec.BooleanValue SEASONAL_PATROLS;
  public static final ModConfigSpec.DoubleValue PATROL_SPRING;
  public static final ModConfigSpec.DoubleValue PATROL_SUMMER;
  public static final ModConfigSpec.DoubleValue PATROL_AUTUMN;
  public static final ModConfigSpec.DoubleValue PATROL_WINTER;
  public static final ModConfigSpec.DoubleValue PATROL_NEW_MOON;
  public static final ModConfigSpec.BooleanValue STORM_HOLDS_RAIDS;
  public static final ModConfigSpec.BooleanValue ILLAGER_RESPECT;
  public static final ModConfigSpec.IntValue RESPECT_KILLS;
  public static final ModConfigSpec.IntValue MEMORY_GENERATION_DAYS;
  public static final ModConfigSpec.IntValue MEMORY_FORGET_DAYS;
  public static final ModConfigSpec.BooleanValue FALLEN_VILLAGES;

  public static final ModConfigSpec.IntValue ROAD_WIDTH;
  public static final ModConfigSpec.BooleanValue VISIBLE_CARAVANS;
  public static final ModConfigSpec.BooleanValue CARAVAN_WRECKS;
  public static final ModConfigSpec.IntValue WRECK_DAYS;
  public static final ModConfigSpec.BooleanValue EMIGRATION;
  public static final ModConfigSpec.DoubleValue EMIGRATION_CHANCE;
  public static final ModConfigSpec.BooleanValue CAMP_LIFE;
  public static final ModConfigSpec.BooleanValue CAMP_PENS;
  public static final ModConfigSpec.BooleanValue RITUALS;
  public static final ModConfigSpec.DoubleValue RITUAL_CHANCE;
  public static final ModConfigSpec.BooleanValue ILLAGER_TENTS;
  public static final ModConfigSpec.DoubleValue RECOGNITION_CHANCE;
  public static final ModConfigSpec.BooleanValue DEFECTION;
  public static final ModConfigSpec.IntValue DEFECTION_TICKS;
  public static final ModConfigSpec.BooleanValue MEMORY_DISTORTION;

  private static final ModConfigSpec.DoubleValue HARVESTS;
  private static final ModConfigSpec.DoubleValue FOOD_PER_HARVEST;
  private static final ModConfigSpec.IntValue FARMLAND_PER_FARMER;
  private static final ModConfigSpec.DoubleValue FOOD_PER_VILLAGER;
  private static final ModConfigSpec.DoubleValue FOOD_PER_BIRTH;
  private static final ModConfigSpec.DoubleValue BREED_CHANCE;
  private static final ModConfigSpec.DoubleValue MATURATION_DAYS;
  private static final ModConfigSpec.DoubleValue NIGHT_LOSS;
  private static final ModConfigSpec.DoubleValue SEVERE_NIGHT_LOSS;
  private static final ModConfigSpec.DoubleValue SEVERE_WORK;
  private static final ModConfigSpec.DoubleValue GROWTH_SPRING;
  private static final ModConfigSpec.DoubleValue GROWTH_SUMMER;
  private static final ModConfigSpec.DoubleValue GROWTH_AUTUMN;
  private static final ModConfigSpec.DoubleValue GROWTH_WINTER;
  private static final ModConfigSpec.DoubleValue STORAGE;
  private static final ModConfigSpec.DoubleValue CONVERSION;

  static {
    ModConfigSpec.Builder b = new ModConfigSpec.Builder();
    VillageSimulationParameters d = VillageSimulationParameters.defaults();

    b.push("life");
    CEREMONIES =
        b.comment("Full-moon rites and new-moon mourning at the village bell.")
            .define("ceremonies", true);
    STORM_BELL =
        b.comment("Ring the bell as a landmark while someone is outside in severe weather.")
            .define("stormBell", true);
    TRADE_SCENES =
        b.comment("Villagers visibly carry goods to each other.").define("tradeScenes", true);
    TRADES_PER_PAIR =
        b.comment("Visible exchanges per supplier/consumer pair per working day at full supply.")
            .defineInRange("tradesPerPair", 2.0, 0.0, 20.0);
    HELPERS =
        b.comment("Unemployed adults follow skilled workers; nitwits keep children company.")
            .define("helpers", true);
    STORM_SHELTER =
        b.comment("Villagers read the weather ahead and hurry home before severe weather.")
            .define("stormShelter", true);
    AURORA =
        b.comment(
                "On aurora nights villagers wake, gather at the bell and fall silent for a minute.")
            .define("auroraGathering", true);
    PROFESSION_DAYS =
        b.comment(
                "Visible profession days: fishing trips, bait, tilling, herding, shearing, feeding,"
                    + " herbs, repairs, the smiths' rest day, goods carried between villagers.")
            .define("professionDays", true);
    HELD_TOOLS =
        b.comment("Workers hold their trade's tool between the sleeves at the workstation.")
            .define("heldTools", true);
    FARMER_TILLING =
        b.comment(
                "Farmers re-till trampled soil in their fields and extend fields when food runs short.")
            .define("farmerTilling", true);
    TILL_PER_FARMER =
        b.comment("New farmland a farmer adds per day while the village needs more fields.")
            .defineInRange("tillPerFarmerPerDay", 2.0, 0.0, 64.0);
    LIVESTOCK_CULLING =
        b.comment("The butcher culls an animal every few days when the herd outgrows the village.")
            .define("livestockCulling", true);
    SNOW_CLEARING =
        b.comment("Villagers shovel snow and sand layers off the square and their doorsteps.")
            .define("snowClearing", true);
    CARTOGRAPHER_TRIPS =
        b.comment("Cartographers leave for days and come back with a map they sell.")
            .define("cartographerTrips", true);
    b.pop();

    b.push("construction");
    CONSTRUCTION =
        b.comment("Villages build houses, farms, bridges, market stalls and fences for themselves.")
            .define("selfBuilding", true);
    BLOCKS_PER_BUILDER =
        b.comment("Blocks one builder places per day (also the rate while nobody watches).")
            .defineInRange("blocksPerBuilderPerDay", 2.0, 0.0, 1000.0);
    FENCE_PRESSURE =
        b.comment(
                "Hostile pressure (1.0 = vanilla night) at which a comfortable village fences itself.")
            .defineInRange("fencePressure", 1.3, 0.0, 100.0);
    b.pop();

    b.push("roads");
    CARAVANS = b.comment("Caravans travel between connected villages.").define("caravans", true);
    CARAVAN_DEPARTURE =
        b.comment("Chance per road per day that a caravan sets out.")
            .defineInRange("caravanDepartureChance", 0.1, 0.0, 1.0);
    CARAVAN_SPEED =
        b.comment("Blocks a caravan covers per day (a walking trader, daylight only).")
            .defineInRange("caravanBlocksPerDay", 1000.0, 50.0, 100000.0);
    CARAVAN_LOSS = b.defineInRange("caravanLossChance", 0.05, 0.0, 1.0);
    CARAVAN_SEVERE_LOSS =
        b.comment("Extra loss chance when severe weather catches a caravan on the road.")
            .defineInRange("caravanSevereLossChance", 0.3, 0.0, 1.0);
    CARAVAN_CARGO =
        b.comment("Food points a caravan delivers (24 = one vanilla breeding).")
            .defineInRange("caravanCargoFood", 24.0, 0.0, 10000.0);
    COMMUTE_TRIPS =
        b.comment("One-way trips per farmer per day between the bell and the fields (road wear).")
            .defineInRange("commuteTripsPerFarmer", 2.0, 0.0, 100.0);
    b.pop();

    b.push("illagers");
    SEASONAL_PATROLS =
        b.comment("Scale vanilla patrol spawning by season and moon.")
            .define("seasonalPatrols", true);
    PATROL_SPRING = b.defineInRange("patrolSpring", 1.5, 0.0, 10.0);
    PATROL_SUMMER = b.defineInRange("patrolSummer", 1.0, 0.0, 10.0);
    PATROL_AUTUMN = b.defineInRange("patrolAutumn", 0.6, 0.0, 10.0);
    PATROL_WINTER = b.defineInRange("patrolWinter", 0.3, 0.0, 10.0);
    PATROL_NEW_MOON = b.defineInRange("patrolNewMoon", 2.0, 0.0, 10.0);
    STORM_HOLDS_RAIDS =
        b.comment("No patrols spawn and pending raids wait while a blizzard/snowstorm lasts.")
            .define("stormHoldsRaids", true);
    ILLAGER_RESPECT =
        b.comment(
                "Illagers who heard of a player killing many of them may lower their weapons (or seek revenge).")
            .define("illagerRespect", true);
    RESPECT_KILLS = b.defineInRange("respectKills", 10, 1, 1000);
    b.pop();

    b.push("memory");
    MEMORY_GENERATION_DAYS =
        b.comment("Days per generation; inherited stories halve with each one.")
            .defineInRange("generationDays", 128, 1, 100000);
    MEMORY_FORGET_DAYS =
        b.comment("Village memories older than this are forgotten.")
            .defineInRange("forgetAfterDays", 640, 1, 1000000);
    FALLEN_VILLAGES =
        b.comment("Villages emptied by monsters are left to infected families.")
            .define("fallenVillages", true);
    b.pop();

    b.push("simulation");
    HARVESTS =
        b.defineInRange("harvestsPerFarmlandPerDay", d.harvestsPerFarmlandPerDay(), 0.0, 24.0);
    FOOD_PER_HARVEST = b.defineInRange("foodPerHarvest", d.foodPerHarvest(), 0.0, 64.0);
    FARMLAND_PER_FARMER = b.defineInRange("farmlandPerFarmer", d.farmlandPerFarmer(), 1, 10000);
    FOOD_PER_VILLAGER =
        b.defineInRange("foodPerVillagerPerDay", d.foodPerVillagerPerDay(), 0.0, 100.0);
    FOOD_PER_BIRTH = b.defineInRange("foodPerBirth", d.foodPerBirth(), 0.0, 1000.0);
    BREED_CHANCE =
        b.defineInRange("breedChancePerPairPerDay", d.breedChancePerPairPerDay(), 0.0, 1.0);
    MATURATION_DAYS = b.defineInRange("childMaturationDays", d.childMaturationDays(), 0.01, 1000.0);
    NIGHT_LOSS = b.defineInRange("nightLossPerVillager", d.nightLossPerVillager(), 0.0, 1.0);
    SEVERE_NIGHT_LOSS =
        b.defineInRange("severeNightLossMultiplier", d.severeNightLossMultiplier(), 0.0, 100.0);
    SEVERE_WORK = b.defineInRange("severeWorkFactor", d.severeWorkFactor(), 0.0, 1.0);
    GROWTH_SPRING = b.defineInRange("growthSpring", d.springGrowth(), 0.0, 10.0);
    GROWTH_SUMMER = b.defineInRange("growthSummer", d.summerGrowth(), 0.0, 10.0);
    GROWTH_AUTUMN = b.defineInRange("growthAutumn", d.autumnGrowth(), 0.0, 10.0);
    GROWTH_WINTER = b.defineInRange("growthWinter", d.winterGrowth(), 0.0, 10.0);
    STORAGE = b.defineInRange("storagePerVillager", d.storagePerVillager(), 0.0, 100000.0);
    CONVERSION = b.defineInRange("zombieConversionChance", d.zombieConversionChance(), 0.0, 1.0);
    b.pop();

    b.push("society");
    ROAD_WIDTH =
        b.comment("Width in blocks of the strip caravans wear between villages.")
            .defineInRange("roadWidth", 3, 1, 7);
    VISIBLE_CARAVANS =
        b.comment("Caravans near a player appear as a wandering trader with pack llamas.")
            .define("visibleCaravans", true);
    CARAVAN_WRECKS =
        b.comment("Lost caravans leave a broken cart with cargo on the road.")
            .define("caravanWrecks", true);
    WRECK_DAYS = b.defineInRange("wreckDays", 40, 1, 10000);
    EMIGRATION =
        b.comment("Unemployed adults move to a neighbouring village along the road.")
            .define("emigration", true);
    EMIGRATION_CHANCE =
        b.comment("Chance per day for each unemployed adult beyond the first to leave.")
            .defineInRange("emigrationChance", 0.03, 0.0, 1.0);
    CAMP_LIFE =
        b.comment("Illagers at outposts and mansions work, train, guard and keep animals.")
            .define("campLife", true);
    CAMP_PENS =
        b.comment("Place an animal pen at an observed illager camp (changes the world).")
            .define("campPens", true);
    RITUALS =
        b.comment("Evokers practise magic outside camps and turn illagers into ravagers.")
            .define("rituals", true);
    RITUAL_CHANCE =
        b.comment("Chance per day that a camp's evoker performs the ravager ritual.")
            .defineInRange("ritualChance", 0.0625, 0.0, 1.0);
    ILLAGER_TENTS =
        b.comment("A raid held back by a snowstorm waits in a hidden tent near the village.")
            .define("illagerTents", true);
    RECOGNITION_CHANCE =
        b.comment("Chance that an illager who heard of a player recognizes them.")
            .defineInRange("recognitionChance", 0.8, 0.0, 1.0);
    DEFECTION =
        b.comment("A lone illager near a village may settle there as a villager.")
            .define("defection", true);
    DEFECTION_TICKS = b.defineInRange("defectionTicks", 6000, 20, 1000000);
    MEMORY_DISTORTION =
        b.comment("Retold stories grow, contradict each other and may not be tied to you.")
            .define("memoryDistortion", true);
    b.pop();

    SPEC = b.build();
  }

  private SettlementsConfig() {}

  public static boolean loaded() {
    return SPEC.isLoaded();
  }

  public static boolean enabled(ModConfigSpec.BooleanValue value) {
    return loaded() ? value.getAsBoolean() : value.getDefault();
  }

  public static double value(ModConfigSpec.DoubleValue value) {
    return loaded() ? value.getAsDouble() : value.getDefault();
  }

  public static int value(ModConfigSpec.IntValue value) {
    return loaded() ? value.getAsInt() : value.getDefault();
  }

  public static VillageSimulationParameters parameters() {
    return new VillageSimulationParameters(
        value(HARVESTS),
        value(FOOD_PER_HARVEST),
        value(FARMLAND_PER_FARMER),
        value(FOOD_PER_VILLAGER),
        value(FOOD_PER_BIRTH),
        value(BREED_CHANCE),
        value(MATURATION_DAYS),
        value(NIGHT_LOSS),
        value(SEVERE_NIGHT_LOSS),
        value(SEVERE_WORK),
        value(GROWTH_SPRING),
        value(GROWTH_SUMMER),
        value(GROWTH_AUTUMN),
        value(GROWTH_WINTER),
        value(STORAGE),
        value(CONVERSION));
  }

  public static Caravans.Parameters caravans() {
    return new Caravans.Parameters(
        enabled(CARAVANS) ? value(CARAVAN_DEPARTURE) : 0.0,
        value(CARAVAN_SPEED),
        value(CARAVAN_LOSS),
        value(CARAVAN_SEVERE_LOSS),
        value(CARAVAN_CARGO));
  }
}
