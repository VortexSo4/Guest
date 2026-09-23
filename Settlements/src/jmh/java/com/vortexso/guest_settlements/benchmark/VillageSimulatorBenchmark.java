package com.vortexso.guest_settlements.benchmark;

import com.vortexso.guest_settlements.village.VillageDayInput;
import com.vortexso.guest_settlements.village.VillagePopulation;
import com.vortexso.guest_settlements.village.VillageSimulationParameters;
import com.vortexso.guest_settlements.village.VillageSimulator;
import com.vortexso.guest_settlements.village.VillageState;
import java.util.Map;
import net.minecraft.core.BlockPos;
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

@BenchmarkMode(Mode.AverageTime)
@Fork(1)
@State(Scope.Benchmark)
public class VillageSimulatorBenchmark {

  private static final Identifier FARMER = Identifier.fromNamespaceAndPath("minecraft", "farmer");

  @Param({"10", "50", "100", "500", "1000", "5000"})
  public int population;

  @Param({"12", "60", "120"})
  public double fieldCapacity;

  private VillageState state;
  private VillageDayInput input;
  private VillageSimulationParameters parameters;

  @Setup(Level.Trial)
  public void setup() {
    parameters = VillageSimulationParameters.defaults();

    int children = population / 5;

    int adults = population - children;

    state =
        new VillageState(
            1L,
            BlockPos.ZERO,
            0L,
            new VillagePopulation(children, Map.of(FARMER, adults)),
            population + 20,
            population * 10.0);

    input = new VillageDayInput(fieldCapacity, 1.0, 1.0, 0, 0);
  }

  @Benchmark
  public VillageState simulateDay() {
    return VillageSimulator.simulateDay(state, input, 123456789L, parameters);
  }
}
