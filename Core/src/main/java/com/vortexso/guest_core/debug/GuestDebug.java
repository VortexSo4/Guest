package com.vortexso.guest_core.debug;

import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

public final class GuestDebug {
  private static final SortedSet<String> CHANNELS = new ConcurrentSkipListSet<>();
  private static final Set<String> ENABLED = ConcurrentHashMap.newKeySet();

  private GuestDebug() {}

  public static void register(String channel) {
    CHANNELS.add(channel);
  }

  public static boolean isEnabled(String channel) {
    return ENABLED.contains(channel);
  }

  public static boolean toggle(String channel) {
    if (ENABLED.remove(channel)) {
      return false;
    }
    ENABLED.add(channel);
    return true;
  }

  public static SortedSet<String> channels() {
    return CHANNELS;
  }
}
