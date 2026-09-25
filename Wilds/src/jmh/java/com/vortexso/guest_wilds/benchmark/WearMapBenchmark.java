package com.vortexso.guest_wilds.benchmark;

import com.vortexso.guest_wilds.WildsParameters;
import com.vortexso.guest_wilds.path.WearMap;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

/** The per-step hot path: every walking entity adds wear every few ticks. */
@BenchmarkMode(Mode.AverageTime)
@Fork(1)
@State(Scope.Benchmark)
public class WearMapBenchmark {
  private WearMap map;
  private int cursor;
  private long time;

  @Setup(Level.Trial)
  public void setup() {
    map = new WearMap(WildsParameters.DEFAULT);
    for (int x = -128; x < 128; x += 3) {
      for (int z = -128; z < 128; z += 3) {
        map.add(x, z, 5.0, 0L);
      }
    }
  }

  @Benchmark
  public int stepOnColumn() {
    cursor = cursor * 1_103_515_245 + 12_345;
    int x = (cursor >> 8) % 256 - 128;
    int z = (cursor >> 16) % 256 - 128;
    time += 4;
    WearMap.Column column = map.add(x, z, 0.8, time);
    return map.targetStage(column, time);
  }
}
