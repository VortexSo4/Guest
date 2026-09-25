package com.vortexso.guest_core.debug;

import java.util.Set;
import java.util.SortedSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

/**
 * Named debug channels toggled with {@code /guest debug <channel>}. Debug renderers read the flag
 * and draw the live simulation state; they never run a separate simulation.
 *
 * <p>Static state is intentional: in singleplayer the integrated server and the client share this
 * JVM, which is also the only setup where renderers can read server-side state directly.
 */
public final class GuestDebug {
  private static final SortedSet<String> CHANNELS = new ConcurrentSkipListSet<>();
  private static final Set<String> ENABLED = ConcurrentHashMap.newKeySet();

  private GuestDebug() {}

  /** Makes a channel appear in command suggestions. Call once from the owning addon. */
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
