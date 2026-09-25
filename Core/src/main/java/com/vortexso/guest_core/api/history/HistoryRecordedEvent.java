package com.vortexso.guest_core.api.history;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.Event;

/** Posted on {@code NeoForge.EVENT_BUS} after a record is stored in {@link GuestHistory}. */
public final class HistoryRecordedEvent extends Event {
  private final ServerLevel level;
  private final HistoryRecord record;

  public HistoryRecordedEvent(ServerLevel level, HistoryRecord record) {
    this.level = level;
    this.record = record;
  }

  public ServerLevel level() {
    return level;
  }

  public HistoryRecord record() {
    return record;
  }
}
