package com.vortexso.guest_settlements.benchmark;

import com.vortexso.guest_settlements.village.VillageEnvironment;
import com.vortexso.guest_settlements.village.VillagePopulation;
import com.vortexso.guest_settlements.village.VillageSimulationParameters;
import com.vortexso.guest_settlements.village.VillageSimulator;
import com.vortexso.guest_settlements.village.VillageState;
import java.util.Map;
import net.minecraft.resources.Identifier;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/**
 * Catch-up cost when a village is loaded after an absence. Should grow with {@code days / 8}
 * (weekly steps), not with population.
 */
@BenchmarkMode(Mode.AverageTime)
@Fork(1)
@State(Scope.Benchmark)
public class VillageSimulatorBenchmark {
  private static final Identifier LIBRARIAN = Identifier.withDefaultNamespace("librarian");

  @Param({"10", "100", "5000"})
  public int population;

  /** One day, one year, a century. */
  @Param({"1", "128", "12800"})
  public long days;

  private VillageState state;
  private VillageEnvironment environment;
  private VillageSimulationParameters parameters;

  @Setup(Level.Trial)
  public void setup() {
    parameters = VillageSimulationParameters.defaults();
    int children = population / 5;
    int farmers = population / 5;
    state =
        new VillageState(
            1L,
            0L,
            new VillagePopulation(
                children,
                Map.of(
                    VillageSimulator.FARMER, farmers, LIBRARIAN, population - children - farmers)),
            population + 20,
            farmers * 40,
            population * 10.0,
            false,
            0,
            Long.MIN_VALUE);
    environment = VillageEnvironment.calm(1.0);
  }

  @Benchmark
  public VillageState catchUp() {
    return VillageSimulator.simulateDays(state, environment, 123456789L, parameters, days);
  }
}
