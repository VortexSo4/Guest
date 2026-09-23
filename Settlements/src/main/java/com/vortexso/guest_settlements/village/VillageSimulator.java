package com.vortexso.guest_settlements.village;

import com.vortexso.guest_core.api.GuestHash;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;

/** Pure aggregate village simulation. Does not access Minecraft world state. */
public final class VillageSimulator {
  private static final Identifier FARMER = Identifier.fromNamespaceAndPath("minecraft", "farmer");

  private static final Identifier NONE = Identifier.fromNamespaceAndPath("minecraft", "none");

  private static final long EVENT_BIRTH = 0x42A11L;
  private static final long EVENT_MATURATION = 0x6D47L;
  private static final long EVENT_DEATH = 0xD34DL;
  private static final long EVENT_MIGRATION = 0xA91CL;

  private VillageSimulator() {}

  public static VillageState initialize(VillageRecord record) {
    return new VillageState(
        record.id(),
        record.origin(),
        record.initializedDay(),
        record.initialPopulation(),
        record.initialHousingCapacity(),
        record.initialFoodReserve());
  }

  public static VillageState simulateDay(
      VillageState state,
      VillageDayInput input,
      long worldSeed,
      VillageSimulationParameters parameters) {
    long nextDay = state.day() + 1;

    Map<Identifier, Integer> professions = new LinkedHashMap<>(state.population());

    int adultsBefore = state.adults();

    /*
     * Migration currently concerns adults only.
     * Outgoing villagers are removed proportionally from professions.
     * Incoming villagers arrive unemployed.
     */
    int outgoing = Math.min(input.outgoingVillagers(), adultsBefore);

    professions =
        removeAdultsProportionally(
            professions, outgoing, GuestHash.hash(worldSeed, state.id(), nextDay, EVENT_MIGRATION));

    addProfession(professions, NONE, input.incomingVillagers());

    int adultsAfterMigration = adultCount(professions);

    int populationAfterMigration = state.children() + adultsAfterMigration;

    /*
     * Food is produced before the village consumes it.
     */
    int farmers = professions.getOrDefault(FARMER, 0);

    double production =
        VillageEconomy.foodProduction(
            farmers, input.fieldCapacity(), input.fertility(), parameters);

    double consumption = populationAfterMigration * parameters.foodPerVillagerPerDay();

    double foodReserve = Math.max(0.0, state.foodReserve() + production - consumption);

    /*
     * Zombies may kill both adults and children.
     */
    double deathRate =
        effectiveRate(
            parameters.baseZombieDeathRatePerDay() * input.zombiePressure(),
            worldSeed,
            state.id(),
            nextDay,
            EVENT_DEATH,
            parameters.activeVariation());

    int deaths =
        deterministicCount(
            populationAfterMigration * deathRate,
            GuestHash.hash(worldSeed, state.id(), nextDay, EVENT_DEATH));

    deaths = Math.min(deaths, populationAfterMigration);

    int childrenAfterDeaths =
        removeChildrenProportionally(
            state.children(),
            populationAfterMigration,
            deaths,
            GuestHash.hash(worldSeed, state.id(), nextDay, EVENT_DEATH ^ 0xBEEFL));

    int childDeaths = state.children() - childrenAfterDeaths;

    int adultDeaths = deaths - childDeaths;

    professions =
        removeAdultsProportionally(
            professions,
            adultDeaths,
            GuestHash.hash(worldSeed, state.id(), nextDay, EVENT_DEATH ^ 0xCAFEFL));

    int adultsAfterDeaths = adultCount(professions);

    /*
     * A child has a probability of maturing each day based
     * on the configured average maturation time.
     */
    double maturationRate = 1.0 / parameters.childMaturationDays();

    maturationRate =
        effectiveRate(
            maturationRate,
            worldSeed,
            state.id(),
            nextDay,
            EVENT_MATURATION,
            parameters.activeVariation());

    int matured =
        deterministicCount(
            childrenAfterDeaths * maturationRate,
            GuestHash.hash(worldSeed, state.id(), nextDay, EVENT_MATURATION));

    matured = Math.min(matured, childrenAfterDeaths);

    int childrenAfterMaturation = childrenAfterDeaths - matured;

    /*
     * A matured villager enters the adult population as unemployed.
     */
    addProfession(professions, NONE, matured);

    int adultsAfterMaturation = adultsAfterDeaths + matured;

    /*
     * Breeding requires both available housing and enough food
     * for one eligible pair.
     */
    int populationAfterDeaths = childrenAfterDeaths + adultsAfterDeaths;

    int freeHousing = Math.max(0, state.housingCapacity() - populationAfterDeaths);

    int eligiblePairs = Math.min(adultsAfterMaturation / 2, freeHousing);

    boolean breedingFoodAvailable = foodReserve >= 2.0 * parameters.foodForBreedingVillager();

    int births = 0;

    if (breedingFoodAvailable && eligiblePairs > 0) {
      double birthRate =
          effectiveRate(
              parameters.birthRatePerEligiblePairPerDay(),
              worldSeed,
              state.id(),
              nextDay,
              EVENT_BIRTH,
              parameters.activeVariation());

      births =
          deterministicCount(
              eligiblePairs * birthRate,
              GuestHash.hash(worldSeed, state.id(), nextDay, EVENT_BIRTH));

      births = Math.min(births, freeHousing);

      foodReserve =
          Math.max(0.0, foodReserve - births * 2.0 * parameters.foodForBreedingVillager());
    }

    /*
     * Newborns are not assigned a profession.
     */
    return new VillageState(
        state.id(),
        state.center(),
        nextDay,
        new VillagePopulation(childrenAfterMaturation + births, professions),
        state.housingCapacity(),
        foodReserve);
  }

  // TODO replace with formula instead of
  public static VillageState simulateDays(
      VillageState state,
      VillageDayInput input,
      long worldSeed,
      VillageSimulationParameters parameters,
      long days) {
    if (days <= 0) {
      return state;
    }

    VillageState result = state;

    for (long i = 0; i < days; i++) {
      result = simulateDay(result, input, worldSeed, parameters);
    }

    return result;
  }

  private static int adultCount(Map<Identifier, Integer> professions) {
    long total = 0;

    for (int amount : professions.values()) {
      total += amount;
    }

    if (total > Integer.MAX_VALUE) {
      throw new IllegalStateException("adult population exceeds Integer.MAX_VALUE");
    }

    return (int) total;
  }

  private static void addProfession(
      Map<Identifier, Integer> professions, Identifier profession, int amount) {
    if (amount <= 0) {
      return;
    }

    professions.merge(profession, amount, Math::addExact);
  }

  private static Map<Identifier, Integer> removeAdultsProportionally(
      Map<Identifier, Integer> source, int amount, long randomSeed) {
    if (amount <= 0 || source.isEmpty()) {
      return Map.copyOf(source);
    }

    int adults = adultCount(source);
    int target = Math.min(amount, adults);

    if (target == 0) {
      return Map.copyOf(source);
    }

    List<Allocation> allocations = new ArrayList<>();

    int assigned = 0;

    List<Map.Entry<Identifier, Integer>> entries =
        source.entrySet().stream()
            .sorted(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)))
            .toList();

    for (Map.Entry<Identifier, Integer> entry : entries) {
      double expected = (double) entry.getValue() * target / adults;

      int base = (int) Math.floor(expected);

      allocations.add(new Allocation(entry.getKey(), entry.getValue(), base, expected - base));

      assigned += base;
    }

    int remaining = target - assigned;

    allocations.sort(
        Comparator.comparingDouble(Allocation::fraction)
            .reversed()
            .thenComparing(allocation -> allocation.profession().toString()));

    for (int i = 0; i < remaining; i++) {
      Allocation allocation = allocations.get(i);

      allocation.removed++;
    }

    Map<Identifier, Integer> result = new LinkedHashMap<>();

    allocations.sort(Comparator.comparing(allocation -> allocation.profession().toString()));

    for (Allocation allocation : allocations) {
      int remainingAmount = allocation.amount() - allocation.removed;

      if (remainingAmount > 0) {
        result.put(allocation.profession(), remainingAmount);
      }
    }

    return Map.copyOf(result);
  }

  private static double effectiveRate(
      double baseRate, long seed, long villageId, long day, long event, double variation) {
    if (variation == 0.0) {
      return Math.min(1.0, Math.max(0.0, baseRate));
    }

    double centered = GuestHash.unit(GuestHash.hash(seed, villageId, day, event)) * 2.0 - 1.0;

    return Math.min(1.0, Math.max(0.0, baseRate * (1.0 + centered * variation)));
  }

  private static int deterministicCount(double expected, long randomSeed) {
    if (!(expected > 0.0)) {
      return 0;
    }

    long whole = (long) Math.floor(expected);

    double fractional = expected - whole;

    if (fractional > 0.0 && GuestHash.unit(randomSeed) < fractional) {
      whole++;
    }

    return saturatingToInt(whole);
  }

  private static int removeChildrenProportionally(
      int children, int population, int deaths, long randomSeed) {
    if (children == 0 || deaths == 0 || population == 0) {
      return children;
    }

    double expected = (double) children * deaths / population;

    int childDeaths = deterministicCount(expected, randomSeed);

    childDeaths = Math.min(childDeaths, children);

    return children - childDeaths;
  }

  private static int saturatingToInt(long value) {
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, value));
  }

  private static final class Allocation {
    private final Identifier profession;
    private final int amount;
    private final double fraction;
    private int removed;

    private Allocation(Identifier profession, int amount, int removed, double fraction) {
      this.profession = profession;
      this.amount = amount;
      this.removed = removed;
      this.fraction = fraction;
    }

    public Identifier profession() {
      return profession;
    }

    public int amount() {
      return amount;
    }

    public double fraction() {
      return fraction;
    }
  }
}
