package com.vortexso.guest_settlements.village;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class VillageSimulatorTest {

  private static final long SEED = 123456789L;

  private static final VillageSimulationParameters PARAMETERS =
      new VillageSimulationParameters(
          2.0, // foodPerVillagerPerDay
          2.0, // foodForBreedingVillager
          12.0, // foodYieldPerFarmer
          20.0, // childMaturationDays
          0.02, // birthRatePerEligiblePairPerDay
          0.001, // baseZombieDeathRatePerDay
          0.0 // activeVariation
          );

  private static VillageState village(
      int population, int children, int housingCapacity, double foodReserve) {
    return new VillageState(
        1L, BlockPos.ZERO, 0L, population, children, housingCapacity, foodReserve);
  }

  private static VillageDayInput input(
      int farmers, double fieldCapacity, double fertility, double zombiePressure) {
    return new VillageDayInput(farmers, fieldCapacity, fertility, zombiePressure, 0, 0);
  }

  @Test
  void deterministicSimulation() {
    VillageState initial = village(20, 4, 30, 100.0);

    VillageDayInput input = input(4, 100.0, 1.0, 0.1);

    VillageState first = VillageSimulator.simulateDay(initial, input, SEED, PARAMETERS);

    VillageState second = VillageSimulator.simulateDay(initial, input, SEED, PARAMETERS);

    assertEquals(first, second);
  }

  @Test
  void fieldCapacityLimitsProduction() {
    VillageDayInput input = input(100, 100.0, 1.0, 0.0);

    assertEquals(100.0, VillageSimulator.foodProduction(input, PARAMETERS), 1e-9);
  }

  @Test
  void additionalFarmersDoNotIncreaseProductionAfterFieldCapacity() {
    VillageDayInput fewFarmers = input(20, 100.0, 1.0, 0.0);

    VillageDayInput manyFarmers = input(1000, 100.0, 1.0, 0.0);

    assertEquals(
        VillageSimulator.foodProduction(fewFarmers, PARAMETERS),
        VillageSimulator.foodProduction(manyFarmers, PARAMETERS),
        1e-9);
  }

  @Test
  void zeroFertilityProducesNoFood() {
    VillageDayInput input = input(100, 10_000.0, 0.0, 0.0);

    assertEquals(0.0, VillageSimulator.foodProduction(input, PARAMETERS), 1e-9);
  }

  @Test
  void incomingVillagersIncreasePopulation() {
    VillageState initial = village(20, 4, 30, 100.0);

    VillageDayInput input = new VillageDayInput(0, 0.0, 0.0, 0.0, 5, 0);

    VillageState result = VillageSimulator.simulateDay(initial, input, SEED, PARAMETERS);

    assertEquals(25, result.population());
    assertEquals(4, result.children());
    assertEquals(21, result.adults());
  }

  @Test
  void outgoingVillagersAreAdults() {
    VillageState initial = village(20, 8, 30, 100.0);

    VillageDayInput input = new VillageDayInput(0, 0.0, 0.0, 0.0, 0, 5);

    VillageState result = VillageSimulator.simulateDay(initial, input, SEED, PARAMETERS);

    assertEquals(15, result.population());
    assertEquals(8, result.children());
    assertEquals(7, result.adults());
  }

  @Test
  void outgoingVillagersCannotExceedAdultPopulation() {
    VillageState initial = village(10, 7, 20, 100.0);

    VillageDayInput input = new VillageDayInput(0, 0.0, 0.0, 0.0, 0, 100);

    VillageState result = VillageSimulator.simulateDay(initial, input, SEED, PARAMETERS);

    assertEquals(7, result.population());
    assertEquals(7, result.children());
    assertEquals(0, result.adults());
  }

  @Test
  void fractionalFoodReserveIsPreserved() {
    VillageState initial = village(1, 0, 10, 1.5);

    VillageDayInput input = input(0, 0.0, 0.0, 0.0);

    VillageSimulationParameters parameters =
        new VillageSimulationParameters(0.25, 2.0, 12.0, 20.0, 0.0, 0.0, 0.0);

    VillageState result = VillageSimulator.simulateDay(initial, input, SEED, parameters);

    assertEquals(1.25, result.foodReserve(), 1e-9);
  }

  @Test
  void populationNeverBecomesNegative() {
    VillageState state = village(10, 5, 20, 0.0);

    VillageDayInput input = input(0, 0.0, 0.0, 1000.0);

    for (int day = 0; day < 100; day++) {
      state = VillageSimulator.simulateDay(state, input, SEED, PARAMETERS);

      assertTrue(state.population() >= 0);
      assertTrue(state.children() >= 0);
      assertTrue(state.children() <= state.population());
      assertTrue(state.foodReserve() >= 0.0);
    }
  }

  @Test
  void villageCanBecomeExtinct() {
    VillageState state = village(100, 20, 100, 0.0);

    VillageDayInput input = input(0, 0.0, 0.0, 1000.0);

    for (int day = 0; day < 100 && state.population() > 0; day++) {

      state = VillageSimulator.simulateDay(state, input, SEED, PARAMETERS);
    }

    assertEquals(0, state.population());
    assertEquals(0, state.children());
    assertTrue(state.isAbandoned());
  }

  @Test
  void populationCannotGrowBeyondHousingCapacity() {
    VillageState state = village(20, 4, 20, 1000.0);

    VillageDayInput input = input(20, 1000.0, 1.0, 0.0);

    for (int day = 0; day < 1000; day++) {
      state = VillageSimulator.simulateDay(state, input, SEED, PARAMETERS);

      assertTrue(state.population() <= state.housingCapacity());
    }
  }

  @Test
  void childrenCanMatureIntoAdults() {
    VillageState state = village(20, 10, 30, 1000.0);

    VillageDayInput input = input(0, 0.0, 0.0, 0.0);

    for (int day = 0; day < 100; day++) {
      state = VillageSimulator.simulateDay(state, input, SEED, PARAMETERS);
    }

    assertTrue(state.children() < 10);
    assertTrue(state.adults() > 10);
  }

  @Test
  void activeVariationRemainsDeterministic() {
    VillageSimulationParameters parameters =
        new VillageSimulationParameters(2.0, 2.0, 12.0, 20.0, 0.02, 0.001, 0.10);

    VillageDayInput input = new VillageDayInput(6, 200.0, 0.8, 0.5, 0, 0);

    VillageState first = village(30, 5, 50, 500.0);

    VillageState second = first;

    for (int day = 0; day < 1000; day++) {
      first = VillageSimulator.simulateDay(first, input, SEED, parameters);

      second = VillageSimulator.simulateDay(second, input, SEED, parameters);

      assertEquals(first, second);
    }
  }

  @Test
  void fieldHasLimitedEffectiveFarmerCapacity() {
    VillageSimulationParameters parameters =
        new VillageSimulationParameters(2.0, 2.0, 12.0, 20.0, 0.02, 0.001, 0.0);

    assertEquals(9, VillageEconomy.effectiveFarmerCapacity(100.0, parameters));
  }
}
