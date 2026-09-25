package com.vortexso.guest_atmosphere.benchmark;

import com.vortexso.guest_atmosphere.weather.WeatherModel;
import com.vortexso.guest_atmosphere.weather.WeatherModel.Climate;
import com.vortexso.guest_atmosphere.weather.WeatherModel.ClimateClass;
import com.vortexso.guest_atmosphere.weather.WeatherParameters;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;

/**
 * The model answers every {@code GuestWeather.get} call and the regional precipitation mixin (per
 * entity rain checks), so its cost per query matters. Border positions evaluate four cells.
 */
@BenchmarkMode(Mode.AverageTime)
@Fork(1)
@State(Scope.Benchmark)
public class WeatherModelBenchmark {
  private static final Climate PLAINS =
      new Climate(ClimateClass.TEMPERATE, 0.8F, false, false, true);

  private long time = 1_000_000L;
  private int x;

  @Benchmark
  public WeatherModel.Sample cellInterior() {
    time += 37;
    return WeatherModel.sample(42L, time, 100, 100, PLAINS, WeatherParameters.DEFAULT);
  }

  @Benchmark
  public WeatherModel.Sample movingAcrossCells() {
    time += 37;
    x += 13;
    return WeatherModel.sample(42L, time, x, 384, PLAINS, WeatherParameters.DEFAULT);
  }
}
