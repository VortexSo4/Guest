package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.GuestHash;
import com.vortexso.guest_core.api.GuestTime;
import com.vortexso.guest_core.api.Season;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/**
 * Pure aggregate village model. Does not touch the world.
 *
 * <p>Time is advanced in steps of at most one 8-day week, aligned to absolute week boundaries, so
 * long absences cost O(days / 8) and the result does not depend on how often the village was polled
 * inside a week. Within a step every rate is constant and converted to an expected count with the
 * saturating form {@code n * (1 - (1 - p)^days)}; counts are rounded deterministically from {@code
 * seed + village + step day}.
 */
public final class VillageSimulator {
  public static final Identifier FARMER = Identifier.withDefaultNamespace("farmer");
  public static final Identifier NONE = Identifier.withDefaultNamespace("none");

  private static final long EVENT_DEATH = 0xD34DL;
  private static final long EVENT_CHILD_DEATH = 0xC41DL;
  private static final long EVENT_MATURATION = 0x6D47L;
  private static final long EVENT_BIRTH = 0x42A11L;

  private VillageSimulator() {}

  /** Expected per-day flows for the current state; also what the debug overlay shows. */
  public record Rates(
      double production, double consumption, double births, double deaths, double maturations) {}

  public static Rates rates(
      VillageState state,
      double severeFraction,
      double hostilePressure,
      long day,
      VillageSimulationParameters parameters) {
    int farmers = state.villagePopulation().professionCount(FARMER);
    double tended = Math.min(state.farmland(), (double) farmers * parameters.farmlandPerFarmer());
    double production =
        tended
            * parameters.harvestsPerFarmlandPerDay()
            * parameters.foodPerHarvest()
            * growth(GuestTime.season(day * GuestTime.TICKS_PER_DAY), parameters)
            * (1.0 - severeFraction * (1.0 - parameters.severeWorkFactor()));

    int population = state.population();
    double lossChance = nightLossChance(severeFraction, hostilePressure, parameters);
    double births =
        Math.min(state.adults() / 2, state.freeHousing()) * parameters.breedChancePerPairPerDay();

    return new Rates(
        production,
        population * parameters.foodPerVillagerPerDay(),
        births,
        population * lossChance,
        Math.min(state.children(), state.children() / parameters.childMaturationDays()));
  }

  public static VillageState simulateDays(
      VillageState state,
      VillageEnvironment environment,
      long worldSeed,
      VillageSimulationParameters parameters,
      long days) {
    return simulate(state, environment, worldSeed, parameters, state.day() + days, true);
  }

  /**
   * Advances to {@code targetDay}. With {@code demographics == false} only the economy moves: used
   * while the village is loaded, where vanilla already breeds and kills the real villagers.
   */
  public static VillageState simulate(
      VillageState state,
      VillageEnvironment environment,
      long worldSeed,
      VillageSimulationParameters parameters,
      long targetDay,
      boolean demographics) {
    VillageState result = state;
    while (result.day() < targetDay) {
      long weekEnd =
          Math.floorDiv(result.day(), GuestTime.DAYS_PER_WEEK) * GuestTime.DAYS_PER_WEEK
              + GuestTime.DAYS_PER_WEEK;
      result =
          step(
              result,
              environment,
              worldSeed,
              parameters,
              Math.min(targetDay, weekEnd),
              demographics);
    }
    return result;
  }

  private static VillageState step(
      VillageState state,
      VillageEnvironment environment,
      long seed,
      VillageSimulationParameters parameters,
      long toDay,
      boolean demographics) {
    long fromDay = state.day();
    int days = (int) (toDay - fromDay);
    VillageEnvironment.Timeline timeline = environment.timeline();
    double severe = clampUnit(timeline.severeFraction(fromDay, toDay));
    Rates rates = rates(state, severe, environment.hostilePressure(), fromDay, parameters);

    int populationBefore = state.population();
    double capacity =
        Math.max(
            2.0 * parameters.foodPerBirth(), populationBefore * parameters.storagePerVillager());
    double food =
        clamp(
            state.foodReserve()
                + (rates.production() - rates.consumption()) * days
                + timeline.importedFood(fromDay, toDay),
            0.0,
            capacity);

    if (!demographics || populationBefore == 0) {
      return new VillageState(
          state.id(),
          toDay,
          state.villagePopulation(),
          state.beds(),
          state.farmland(),
          food,
          state.fallen(),
          state.infected(),
          state.lastDeathDay());
    }

    long id = state.id();
    Map<Identifier, Integer> professions = new HashMap<>(state.professions());
    int children = state.children();

    double lossChance = nightLossChance(severe, environment.hostilePressure(), parameters);
    int deaths =
        Math.min(
            populationBefore,
            round(
                populationBefore * saturate(lossChance, days),
                GuestHash.hash(seed, id, fromDay, EVENT_DEATH)));
    int childDeaths =
        Math.min(
            children,
            round(
                (double) children * deaths / populationBefore,
                GuestHash.hash(seed, id, fromDay, EVENT_CHILD_DEATH)));
    int adultDeaths = deaths - childDeaths;
    int adults = state.adults();
    if (adultDeaths > adults) {
      childDeaths += adultDeaths - adults;
      adultDeaths = adults;
    }
    removeProportionally(professions, adultDeaths);
    children -= childDeaths;

    int matured =
        Math.min(
            children,
            round(
                children * (1.0 - Math.exp(-days / parameters.childMaturationDays())),
                GuestHash.hash(seed, id, fromDay, EVENT_MATURATION)));
    children -= matured;
    add(professions, NONE, matured);
    employFarmers(professions, state.farmland(), parameters);

    int adultsNow = count(professions);
    int free = Math.max(0, state.beds() - adultsNow - children);
    int births =
        round(
            Math.min(adultsNow / 2, free) * saturate(parameters.breedChancePerPairPerDay(), days),
            GuestHash.hash(seed, id, fromDay, EVENT_BIRTH));
    births = Math.min(births, free);
    if (parameters.foodPerBirth() > 0.0) {
      births = Math.min(births, (int) (food / parameters.foodPerBirth()));
      food -= births * parameters.foodPerBirth();
    }
    children += births;

    boolean fallen = state.fallen();
    int infected = state.infected();
    if (deaths > 0 && adultsNow + children == 0) {
      // Emptied by hostile losses: the last families were infected rather than just gone.
      fallen = true;
      infected = Math.max(1, (int) Math.round(deaths * parameters.zombieConversionChance()));
    }

    return new VillageState(
        id,
        toDay,
        new VillagePopulation(children, professions),
        state.beds(),
        state.farmland(),
        food,
        fallen,
        infected,
        deaths > 0 ? toDay - 1 : state.lastDeathDay());
  }

  public static double growth(Season season, VillageSimulationParameters parameters) {
    return switch (season) {
      case SPRING -> parameters.springGrowth();
      case SUMMER -> parameters.summerGrowth();
      case AUTUMN -> parameters.autumnGrowth();
      case WINTER -> parameters.winterGrowth();
    };
  }

  private static double nightLossChance(
      double severeFraction, double hostilePressure, VillageSimulationParameters parameters) {
    return clampUnit(
        parameters.nightLossPerVillager()
            * hostilePressure
            * (1.0 + severeFraction * (parameters.severeNightLossMultiplier() - 1.0)));
  }

  /**
   * Unemployed adults take the first needed role; untended farmland is the only need the aggregate
   * can see without a workstation census.
   */
  private static void employFarmers(
      Map<Identifier, Integer> professions, int farmland, VillageSimulationParameters parameters) {
    int needed =
        (farmland + parameters.farmlandPerFarmer() - 1) / parameters.farmlandPerFarmer()
            - professions.getOrDefault(FARMER, 0);
    int hired = Math.min(Math.max(0, needed), professions.getOrDefault(NONE, 0));
    add(professions, NONE, -hired);
    add(professions, FARMER, hired);
  }

  /** Largest-remainder removal so every profession loses its fair share, deterministically. */
  private static void removeProportionally(Map<Identifier, Integer> professions, int amount) {
    int total = count(professions);
    if (amount <= 0 || total == 0) {
      return;
    }
    amount = Math.min(amount, total);
    List<Identifier> keys = new ArrayList<>(professions.keySet());
    keys.sort(Comparator.comparing(Identifier::toString));
    Map<Identifier, Double> remainders = new HashMap<>();
    int removed = 0;
    for (Identifier key : keys) {
      double share = (double) professions.get(key) * amount / total;
      int whole = (int) share;
      add(professions, key, -whole);
      remainders.put(key, share - whole);
      removed += whole;
    }
    keys.sort(Comparator.comparing((Identifier key) -> -remainders.get(key)));
    for (int i = 0; removed < amount && i < keys.size(); i++) {
      if (professions.getOrDefault(keys.get(i), 0) > 0) {
        add(professions, keys.get(i), -1);
        removed++;
      }
    }
  }

  private static void add(Map<Identifier, Integer> professions, Identifier key, int amount) {
    int value = professions.getOrDefault(key, 0) + amount;
    if (value > 0) {
      professions.put(key, value);
    } else {
      professions.remove(key);
    }
  }

  private static int count(Map<Identifier, Integer> professions) {
    int total = 0;
    for (int amount : professions.values()) {
      total += amount;
    }
    return total;
  }

  private static double saturate(double chancePerDay, int days) {
    return 1.0 - Math.pow(1.0 - clampUnit(chancePerDay), days);
  }

  /** Deterministic stochastic rounding: the fraction becomes one more with matching odds. */
  static int round(double expected, long hash) {
    if (!(expected > 0.0)) {
      return 0;
    }
    double whole = Math.floor(expected);
    int result = (int) Math.min(Integer.MAX_VALUE, whole);
    return GuestHash.unit(hash) < expected - whole ? result + 1 : result;
  }

  private static double clampUnit(double value) {
    return clamp(value, 0.0, 1.0);
  }

  private static double clamp(double value, double min, double max) {
    return Math.max(min, Math.min(max, value));
  }
}
