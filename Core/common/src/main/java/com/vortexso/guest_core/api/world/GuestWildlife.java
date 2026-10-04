package com.vortexso.guest_core.api.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

public final class GuestWildlife {
  public static final double BASELINE = 1.0;

  private static volatile PressureProvider hostilePressure = (level, pos, radius) -> BASELINE;

  private GuestWildlife() {}

  public static double hostilePressure(ServerLevel level, BlockPos pos, int radius) {
    return hostilePressure.get(level, pos, radius);
  }

  public static void registerHostilePressure(PressureProvider provider) {
    hostilePressure = provider;
  }

  @FunctionalInterface
  public interface PressureProvider {
    double get(ServerLevel level, BlockPos pos, int radius);
  }
}
