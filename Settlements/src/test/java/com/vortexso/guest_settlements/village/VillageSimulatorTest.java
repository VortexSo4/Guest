package com.vortexso.guest_settlements.village;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vortexso.guest_core.api.GuestTime;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

class VillageSimulatorTest {
  private static final long SEED = 123456789L;
  private static final Identifier LIBRARIAN = Identifier.withDefaultNamespace("librarian");
  private static final VillageSimulationParameters PARAMETERS =
      VillageSimulationParameters.defaults();
  private static final VillageEnvironment CALM = VillageEnvironment.calm(1.0);

  private static VillageState village(
      int farmers, int librarians, int children, int beds, int farmland, double food) {
    return new VillageState(
        1L,
        0L,
        new VillagePopulation(
            children, Map.of(VillageSimulator.FARMER, farmers, LIBRARIAN, librarians)),
        beds,
        farmland,
        food,
        false,
        0,
        Long.MIN_VALUE);
  }

  @Test
  void deterministic() {
    VillageState start = village(3, 4, 2, 20, 100, 50.0);
    assertEquals(
        VillageSimulator.simulateDays(start, CALM, SEED, PARAMETERS, 300),
        VillageSimulator.simulateDays(start, CALM, SEED, PARAMETERS, 300));
  }

  @Test
  void professionsSurviveSimulatedDays() {
    VillageState start = village(2, 5, 0, 7, 80, 0.0);
    VillageState after =
        VillageSimulator.simulateDays(start, VillageEnvironment.calm(0.0), SEED, PARAMETERS, 64);
    assertEquals(5, after.villagePopulation().professionCount(LIBRARIAN));
    assertEquals(2, after.villagePopulation().professionCount(VillageSimulator.FARMER));
  }

  @Test
  void productionFollowsCropGrowthAndFieldSize() {
    VillageState oneFarmer = village(1, 0, 0, 1, 40, 0.0);
    double expected = 40 * PARAMETERS.harvestsPerFarmlandPerDay() * PARAMETERS.foodPerHarvest();
    assertEquals(
        expected, VillageSimulator.rates(oneFarmer, 0.0, 1.0, 0, PARAMETERS).production(), 1e-9);

    VillageState manyFarmers = village(10, 0, 0, 10, 40, 0.0);
    assertEquals(
        expected, VillageSimulator.rates(manyFarmers, 0.0, 1.0, 0, PARAMETERS).production(), 1e-9);
  }

  @Test
  void winterStopsFarming() {
    VillageState state = village(2, 0, 0, 2, 80, 0.0);
    long winterDay = 3L * GuestTime.DAYS_PER_SEASON;
    double spring = VillageSimulator.rates(state, 0.0, 1.0, 0, PARAMETERS).production();
    double winter = VillageSimulator.rates(state, 0.0, 1.0, winterDay, PARAMETERS).production();
    assertTrue(winter < spring * 0.1);
  }

  @Test
  void severeWeatherRaisesLossesAndCutsWork() {
    VillageState state = village(2, 8, 0, 10, 80, 0.0);
    VillageSimulator.Rates calm = VillageSimulator.rates(state, 0.0, 1.0, 0, PARAMETERS);
    VillageSimulator.Rates storm = VillageSimulator.rates(state, 1.0, 1.0, 0, PARAMETERS);
    assertTrue(storm.deaths() > calm.deaths());
    assertTrue(storm.production() < calm.production());
  }

  @Test
  void populationStaysWithinBeds() {
    VillageState state = village(4, 4, 0, 14, 200, 500.0);
    VillageEnvironment safe = VillageEnvironment.calm(0.0);
    for (int week = 0; week < 50; week++) {
      state = VillageSimulator.simulateDays(state, safe, SEED, PARAMETERS, 8);
      assertTrue(state.population() <= 14, "population " + state.population());
    }
    assertEquals(14, state.population());
  }

  @Test
  void hostileLossesMakeTheVillageFall() {
    VillageState state = village(3, 3, 2, 10, 50, 0.0);
    VillageState after =
        VillageSimulator.simulateDays(state, VillageEnvironment.calm(80.0), SEED, PARAMETERS, 400);
    assertEquals(0, after.population());
    assertTrue(after.fallen());
    assertTrue(after.infected() > 0);
    assertTrue(after.lastDeathDay() >= 0);
  }

  @Test
  void childrenGrowUpAsUnemployed() {
    VillageState state = village(0, 2, 4, 6, 0, 0.0);
    VillageState after =
        VillageSimulator.simulateDays(state, VillageEnvironment.calm(0.0), SEED, PARAMETERS, 8);
    assertEquals(0, after.children());
    assertEquals(4, after.villagePopulation().professionCount(VillageSimulator.NONE));
  }

  @Test
  void weekAlignedStepsDoNotDependOnPolling() {
    VillageState start = village(3, 4, 2, 20, 100, 50.0);
    VillageState once = VillageSimulator.simulateDays(start, CALM, SEED, PARAMETERS, 32);
    VillageState weekly = start;
    for (int i = 0; i < 4; i++) {
      weekly = VillageSimulator.simulateDays(weekly, CALM, SEED, PARAMETERS, 8);
    }
    assertEquals(once, weekly);
  }

  @Test
  void observedVillagesOnlyAdvanceTheEconomy() {
    VillageState start = village(2, 3, 1, 20, 80, 0.0);
    VillageState after =
        VillageSimulator.simulate(
            start, VillageEnvironment.calm(50.0), SEED, PARAMETERS, 10, false);
    assertEquals(start.villagePopulation(), after.villagePopulation());
    assertEquals(10, after.day());
    assertTrue(after.foodReserve() > 0.0);
  }

  @Test
  void centuryOfAbsenceIsCheap() {
    VillageState start = village(3, 4, 2, 20, 100, 50.0);
    long started = System.nanoTime();
    VillageState after =
        VillageSimulator.simulateDays(
            start, CALM, SEED, PARAMETERS, 100L * GuestTime.DAYS_PER_YEAR);
    assertTrue(System.nanoTime() - started < 500_000_000L);
    assertEquals(100L * GuestTime.DAYS_PER_YEAR, after.day());
  }

  @Test
  void caravansAreDeterministicAndStayHomeInStorms() {
    RoadEdge edge = RoadEdge.of(1L, 2L);
    Caravans.Parameters parameters = new Caravans.Parameters(0.5, 1000.0, 0.0, 1.0, 24.0);
    List<Caravans.Caravan> first =
        Caravans.departures(SEED, edge, 2500.0, 0, 200, parameters, d -> false);
    assertEquals(first, Caravans.departures(SEED, edge, 2500.0, 0, 200, parameters, d -> false));
    assertFalse(first.isEmpty());
    assertTrue(first.stream().allMatch(c -> c.arriveDay() - c.departDay() == 3 && !c.lost()));
    assertTrue(Caravans.departures(SEED, edge, 2500.0, 0, 200, parameters, d -> true).isEmpty());

    List<Caravans.Caravan> arrivals =
        Caravans.arrivals(SEED, edge, 2500.0, 3, 203, parameters, d -> false);
    assertEquals(first, arrivals);
  }

  @Test
  void caravanCargoFeedsTheVillage() {
    VillageState start = village(0, 2, 0, 2, 0, 0.0);
    VillageEnvironment trade =
        new VillageEnvironment(
            0.0,
            new VillageEnvironment.Timeline() {
              @Override
              public double severeFraction(long fromDay, long toDay) {
                return 0.0;
              }

              @Override
              public double importedFood(long fromDay, long toDay) {
                return fromDay <= 4 && 4 < toDay ? 24.0 : 0.0;
              }
            });
    VillageState after = VillageSimulator.simulate(start, trade, SEED, PARAMETERS, 8, false);
    assertEquals(24.0 - 8 * 2 * PARAMETERS.foodPerVillagerPerDay(), after.foodReserve(), 1e-9);
  }
}
