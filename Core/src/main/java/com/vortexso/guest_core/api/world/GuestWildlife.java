package com.vortexso.guest_core.api.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Read-only view of wild population pressure for consumers outside Wilds (e.g. settlements
 * estimating night losses). Without Guest Wilds the vanilla baseline of 1.0 is reported.
 */
public final class GuestWildlife {
  public static final double BASELINE = 1.0;

  private static volatile PressureProvider hostilePressure = (level, pos, radius) -> BASELINE;

  private GuestWildlife() {}

  /** Relative hostile pressure around a position; 1.0 = an ordinary vanilla night. */
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
