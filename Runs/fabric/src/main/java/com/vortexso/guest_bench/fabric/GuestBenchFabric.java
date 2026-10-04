package com.vortexso.guest_bench.fabric;

import com.vortexso.guest_bench.GuestBench;
import net.fabricmc.api.ModInitializer;

public final class GuestBenchFabric implements ModInitializer {
  @Override
  public void onInitialize() {
    GuestBench.init();
  }
}
