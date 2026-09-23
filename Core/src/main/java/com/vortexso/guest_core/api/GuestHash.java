package com.vortexso.guest_core.api;

public final class GuestHash {
  private static final long GAMMA = 0x9E3779B97F4A7C15L;

  private GuestHash() {}

  public static long hash(long seed, long value) {
    return mix(seed + GAMMA ^ value);
  }

  public static long hash(long seed, long first, long second) {
    long h = mix(seed + GAMMA ^ first);
    return mix(h + GAMMA ^ second);
  }

  public static long hash(long seed, long first, long second, long third) {
    long h = mix(seed + GAMMA ^ first);
    h = mix(h + GAMMA ^ second);
    return mix(h + GAMMA ^ third);
  }

  public static long hash(long seed, long first, long second, long third, long fourth) {
    long h = mix(seed + GAMMA ^ first);
    h = mix(h + GAMMA ^ second);
    h = mix(h + GAMMA ^ third);
    return mix(h + GAMMA ^ fourth);
  }

  public static double unit(long value) {
    return (value >>> 11) * 0x1.0p-53;
  }

  private static long mix(long value) {
    value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
    value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
    return value ^ (value >>> 31);
  }
}
